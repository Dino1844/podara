package app.podara.player

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.Structure.FieldOrder
import kotlin.math.roundToLong

/**
 * One chapter as mpv reports it in `chapter-list`.
 *
 * [title] is null when the file carries no chapter name; the UI substitutes a
 * numbered fallback so the list still reads. [startMs] is the chapter's start
 * converted to the engine's currency (milliseconds), so a click can feed it
 * straight to [AudioPlayerEngine.seek].
 */
data class MpvChapter(
    val title: String?,
    val startMs: Long
)

/**
 * `mpv_node_list` (client.h, 64-bit):
 *
 * ```
 * typedef struct mpv_node_list {
 *     int num;           // +0
 *     mpv_node *values;  // +8
 *     char **keys;       // +16
 * } mpv_node_list;
 * ```
 *
 * `keys` is only populated for NODE_MAP nodes; NODE_ARRAY nodes leave it null.
 */
@FieldOrder("num", "values", "keys")
open class MpvNodeList : Structure {
    constructor() : super()
    constructor(p: Pointer) : super(p)

    @JvmField var num: Int = 0
    @JvmField var values: Pointer? = null
    @JvmField var keys: Pointer? = null

    /** The `values` entry at [index], read out of the array mpv allocated. */
    internal fun nodeAt(index: Int): MpvNode? {
        val base = values ?: return null
        // JNA hands back either null or a zero-peer Pointer for a NULL, so
        // both must be refused before anything is read through the address.
        if (base == Pointer.NULL) return null
        if (index < 0 || index >= num) return null
        return MpvNode(base.share(index.toLong() * MpvNode.SIZE)).also { it.read() }
    }

    /** The map key at [index], or null for an array node / a NULL key. */
    internal fun keyAt(index: Int): String? {
        val base = keys ?: return null
        if (base == Pointer.NULL) return null
        if (index < 0 || index >= num) return null
        val entry = base.getPointer(index.toLong() * Native.POINTER_SIZE) ?: return null
        if (entry == Pointer.NULL) return null
        return entry.getString(0)
    }

    companion object {
        /** sizeof(mpv_node_list) as JNA computes it; pinned to 24 by a test. */
        internal val SIZE: Long = MpvNodeList().size().toLong()
    }
}

/**
 * `mpv_node` (client.h, 64-bit):
 *
 * ```
 * typedef struct mpv_node {
 *     union {
 *         char *string;
 *         mpv_node_list *list;
 *         int64_t int64;
 *         double double_;
 *         int flag;
 *     } u;                 // +0
 *     mpv_format format;   // +8
 * } mpv_node;
 * ```
 *
 * The union is held as a raw [Pointer] rather than a JNA `Union`: every member
 * is pointer-sized, so the same 8 bytes serve both the pointer members (used
 * as-is) and the scalar ones (reinterpreted via [Pointer.nativeValue]). This is
 * the same documented-raw-memory style the event loop in
 * [MpvAudioPlayerEngine] uses for its event structs.
 */
@FieldOrder("u", "format")
open class MpvNode : Structure {
    constructor() : super()
    constructor(p: Pointer) : super(p)

    /** Union storage; interpret per [format], never directly. */
    @JvmField var u: Pointer? = null
    @JvmField var format: Int = 0

    /** The `char *string` member, or null when this node is not a string. */
    internal fun stringMember(): String? {
        if (format != MpvApi.MPV_FORMAT_STRING) return null
        val ptr = u ?: return null
        if (ptr == Pointer.NULL) return null
        return ptr.getString(0)
    }

    /** The `double double_` member, or null when this node is not a double. */
    internal fun doubleMember(): Double? {
        if (format != MpvApi.MPV_FORMAT_DOUBLE) return null
        // Read the raw bytes, not the `u` field: a 0.0 scalar is 8 zero bytes,
        // the same bit pattern as NULL, and JNA collapses a zero-peer Pointer
        // field to null — which would silently drop a chapter starting at 0.
        return java.lang.Double.longBitsToDouble(unionRawBits())
    }

    /** The union's raw 8 bytes, read straight from the node's memory. */
    private fun unionRawBits(): Long = getPointer().getLong(0)

    /**
     * The `mpv_node_list *list` member as a read view, or null when this node
     * is neither a NODE_ARRAY nor a NODE_MAP.
     */
    internal fun listMember(): MpvNodeList? {
        if (format != MpvApi.MPV_FORMAT_NODE) return null
        val ptr = u ?: return null
        if (ptr == Pointer.NULL) return null
        return MpvNodeList(ptr).also { it.read() }
    }

    companion object {
        /** sizeof(mpv_node) as JNA computes it; pinned to 16 by a test. */
        internal val SIZE: Long = MpvNode().size().toLong()
    }
}

/**
 * Parses mpv's `chapter-list` node (a NODE_ARRAY of NODE_MAP with `title:
 * string` and `time: double` seconds per entry) into [MpvChapter]s.
 *
 * Pure: it reads only the memory [node] points at and touches no handle, which
 * is what makes it testable with hand-built structures. The caller must still
 * own the node's lifetime — mpv memory is parsed before
 * `mpv_free_node_contents` is called on it.
 *
 * Anything unexpected (wrong format, missing time, null pointers) degrades to
 * an empty or shortened list rather than an exception: this runs on the engine
 * thread, where a throw would take playback down with it. Entries without a
 * usable time are dropped; a missing title survives as null.
 */
internal fun parseChapterList(node: MpvNode): List<MpvChapter> {
    // The caller may hand over a freshly-filled structure whose JNA fields are
    // still stale; re-reading from memory makes both "just written by mpv" and
    // "just written by a test" work.
    node.read()
    if (node.format != MpvApi.MPV_FORMAT_NODE) return emptyList()
    val list = node.listMember() ?: return emptyList()

    val chapters = ArrayList<MpvChapter>(list.num.coerceAtLeast(0))
    for (index in 0 until list.num) {
        val entry = list.nodeAt(index) ?: continue
        // The array's members are also NODE-format nodes: MPV_FORMAT_NODE_ARRAY
        // and MPV_FORMAT_NODE_MAP describe the two shapes of `mpv_node_list`,
        // not separate mpv_node format values. What makes a member a map is its
        // keys pointer — a nested array has none, so every member lookup below
        // finds nothing and the entry is dropped.
        if (entry.format != MpvApi.MPV_FORMAT_NODE) continue
        val map = entry.listMember() ?: continue

        var title: String? = null
        var startSeconds: Double? = null
        for (member in 0 until map.num) {
            val key = map.keyAt(member) ?: continue
            val value = map.nodeAt(member) ?: continue
            when (key) {
                "title" -> title = value.stringMember()
                "time" -> startSeconds = value.doubleMember()
            }
        }
        val seconds = startSeconds ?: continue
        chapters.add(MpvChapter(title = title, startMs = (seconds * 1000.0).roundToLong()))
    }
    return chapters
}
