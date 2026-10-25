package app.podara.desktop

import app.podara.player.MpvApi
import app.podara.player.MpvChapter
import app.podara.player.MpvNode
import app.podara.player.MpvNodeList
import app.podara.player.parseChapterList
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Parsing of mpv's `chapter-list` node, driven against node trees built by hand
 * with JNA Structures — the same byte layout mpv writes behind
 * `mpv_get_property("chapter-list", MPV_FORMAT_NODE)`.
 *
 * The parse function is pure, so no native library is needed: what these tests
 * buy is confidence in the *memory layout* (the union reinterpreted as scalars,
 * the list/keys pointer chasing) and in the degradation rules (missing title,
 * missing time, wrong format) that keep a malformed file from taking the
 * engine thread down.
 */
class MpvChapterParsingTest {

    /**
     * Builds `mpv_node` trees in native memory, keeping every allocation
     * referenced until the test is done — JNA frees its memory on GC, and a
     * collected string or list behind a live pointer is a use-after-free the
     * parser can do nothing about.
     */
    private class NodeBuilder {
        val keepAlive = mutableListOf<Any>()

        private fun cString(value: String): Pointer {
            val bytes = (value + "\u0000").toByteArray(Charsets.UTF_8)
            val mem = Memory(bytes.size.toLong())
            mem.write(0, bytes, 0, bytes.size)
            keepAlive += mem
            return mem
        }

        fun stringNode(value: String): MpvNode = MpvNode().also {
            it.format = MpvApi.MPV_FORMAT_STRING
            it.u = cString(value)
            it.write()
            keepAlive += it
        }

        fun doubleNode(value: Double): MpvNode = MpvNode().also {
            // The union's double member is stored raw, not pointed at: the 8
            // bytes ARE the value, so the node carries them as a Pointer whose
            // peer is the bit pattern.
            it.format = MpvApi.MPV_FORMAT_DOUBLE
            it.u = Pointer(java.lang.Double.doubleToRawLongBits(value))
            it.write()
            keepAlive += it
        }

        /** A NODE node whose list is a map of key → node, in order. */
        fun mapNode(entries: List<Pair<String, MpvNode>>): MpvNode {
            val list = MpvNodeList()
            keepAlive += list
            val keys = Memory((entries.size.toLong() * Native.POINTER_SIZE).coerceAtLeast(1))
            keepAlive += keys
            entries.forEachIndexed { index, (key, _) ->
                keys.setPointer((index * Native.POINTER_SIZE).toLong(), cString(key))
            }
            list.num = entries.size
            list.values = nodeArray(entries.map { it.second })
            list.keys = keys
            list.write()
            return nodePointingAt(list)
        }

        /** A NODE node whose list is an array of nodes, in order. */
        fun arrayNode(items: List<MpvNode>): MpvNode {
            val list = MpvNodeList()
            keepAlive += list
            list.num = items.size
            list.values = nodeArray(items)
            list.keys = null
            list.write()
            return nodePointingAt(list)
        }

        /** Writes [nodes] into one contiguous `mpv_node` array; returns its base. */
        private fun nodeArray(nodes: List<MpvNode>): Pointer? {
            if (nodes.isEmpty()) return null
            val size = MpvNode.SIZE.toInt()
            val block = Memory(nodes.size.toLong() * MpvNode.SIZE)
            keepAlive += block
            nodes.forEachIndexed { index, node ->
                node.write()
                block.write(index.toLong() * MpvNode.SIZE, node.pointer.getByteArray(0, size), 0, size)
            }
            return block
        }

        private fun nodePointingAt(list: MpvNodeList): MpvNode = MpvNode().also {
            it.format = MpvApi.MPV_FORMAT_NODE
            it.u = list.pointer
            it.write()
            keepAlive += it
        }

        /**
         * A `chapter-list` node: a NODE array of map entries, omitting the
         * title of a null entry and the time of an entry past [times].
         *
         * The builder must outlive [parseChapterList] — it is what keeps every
         * allocation behind the returned node's pointers alive.
         */
        fun chapterList(titles: List<String?>, times: List<Double>): MpvNode =
            arrayNode(titles.mapIndexed { index, title ->
                val members = buildList {
                    if (title != null) add("title" to stringNode(title))
                    if (index < times.size) add("time" to doubleNode(times[index]))
                }
                mapNode(members)
            })
    }

    @Test
    fun structLayoutMatchesMpvClientHeader() {
        // These must match client.h on this platform or every field read after
        // them is silently the wrong one. u is an 8-byte union, format an int
        // behind it (16 with padding); num an int ahead of two pointers (24).
        assertEquals(16, MpvNode().size(), "sizeof(mpv_node) drifted from client.h")
        assertEquals(24, MpvNodeList().size(), "sizeof(mpv_node_list) drifted from client.h")
    }

    @Test
    fun parsesTitlesAndStartTimesInListOrder() {
        val builder = NodeBuilder()
        val node = builder.chapterList(titles = listOf("Intro", "Deep dive"), times = listOf(0.0, 61.5))
        val chapters = parseChapterList(node)
        assertEquals(
            listOf(MpvChapter("Intro", 0L), MpvChapter("Deep dive", 61_500L)),
            chapters,
            "chapters are reported in list order with start times in ms"
        )
    }

    @Test
    fun entryWithoutTitleKeepsANullTitle() {
        // Files without chapter names only carry "time"; the parser must not
        // invent one — the UI substitutes its numbered fallback.
        val builder = NodeBuilder()
        val node = builder.chapterList(titles = listOf(null), times = listOf(125.0))
        val chapters = parseChapterList(node)
        assertEquals(1, chapters.size)
        assertEquals(null, chapters[0].title)
        assertEquals(125_000L, chapters[0].startMs)
    }

    @Test
    fun entryWithoutATimeIsDropped() {
        // A chapter without a start time cannot be seeked to; skipping it keeps
        // the rest of the list usable.
        val builder = NodeBuilder()
        val node = builder.arrayNode(listOf(
            builder.mapNode(listOf("title" to builder.stringNode("no time here"))),
            builder.mapNode(listOf(
                "title" to builder.stringNode("real one"),
                "time" to builder.doubleNode(30.0)
            ))
        ))
        val chapters = parseChapterList(node)
        assertEquals(listOf(MpvChapter("real one", 30_000L)), chapters)
    }

    @Test
    fun emptyListYieldsNoChapters() {
        val builder = NodeBuilder()
        val node = builder.chapterList(titles = emptyList(), times = emptyList())
        assertTrue(parseChapterList(node).isEmpty(), "an empty chapter-list is not an error")
    }

    @Test
    fun wrongTopLevelFormatYieldsNoChapters() {
        // A property read that came back as something other than a node (or a
        // node holding a scalar) must degrade, not throw — this runs on the
        // engine thread.
        val builder = NodeBuilder()
        assertTrue(parseChapterList(builder.stringNode("not a list")).isEmpty())
    }

    @Test
    fun unknownMapKeysAreIgnored() {
        val builder = NodeBuilder()
        val node = builder.arrayNode(listOf(
            builder.mapNode(listOf(
                "title" to builder.stringNode("Intro"),
                "time" to builder.doubleNode(10.0),
                "unknown" to builder.stringNode("ignored")
            ))
        ))
        assertEquals(listOf(MpvChapter("Intro", 10_000L)), parseChapterList(node))
    }
}
