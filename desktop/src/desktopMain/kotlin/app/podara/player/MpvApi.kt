package app.podara.player

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

interface MpvApi : Library {

    fun mpv_create(): Long
    fun mpv_initialize(handle: Long): Int
    fun mpv_destroy(handle: Long)
    fun mpv_terminate_destroy(handle: Long)

    fun mpv_command(handle: Long, args: Array<String?>): Int
    fun mpv_command_string(handle: Long, args: String): Int

    fun mpv_set_property_string(handle: Long, name: String, data: String): Int
    fun mpv_get_property_string(handle: Long, name: String): Pointer
    fun mpv_set_option_string(handle: Long, name: String, data: String): Int

    /**
     * Reads [name] into `*out` as [format]. For MPV_FORMAT_NODE, `out` points at
     * an `mpv_node` the caller owns: whatever mpv hangs off it must be released
     * with [mpv_free_node_contents], on success and on failure alike — a failed
     * read leaves the node untouched, and freeing a zeroed node is a no-op.
     */
    fun mpv_get_property(handle: Long, name: String, format: Int, out: Pointer): Int

    /** Releases the tree mpv allocated behind an `mpv_node` filled by [mpv_get_property]. */
    fun mpv_free_node_contents(node: Pointer)

    /**
     * Requests that mpv push [name] on every change.
     *
     * [replyUserdata] comes back on each resulting event, which is how the event
     * loop tells one observed property from another without parsing names.
     * [format] is one of the `MPV_FORMAT_*` constants: asking for the property's
     * native type means mpv hands back a pointer to the value instead of a
     * heap-allocated string the caller has to free.
     */
    fun mpv_observe_property(handle: Long, replyUserdata: Long, name: String, format: Int): Int

    fun mpv_wait_event(handle: Long, timeout: Double): Pointer

    fun mpv_free(data: Pointer)

    companion object {
        val INSTANCE: MpvApi = Native.load("mpv-1", MpvApi::class.java)
            ?: throw UnsatisfiedLinkError("Failed to load mpv-1.dll")

        // mpv_format, from client.h. The numbering is not dense: values 2 and 3
        // are OSD_STRING and STRING, so FLAG/INT64/DOUBLE sit one higher than
        // "the obvious" 2/3/4. Declaring FLAG as 2 passed OSD_STRING, which
        // observe_property rejects with MPV_ERROR_PROPERTY_UNAVAILABLE (-9), and
        // DOUBLE's value pointer is only interpreted correctly by the reader when
        // the format it asked for is the one mpv actually stored.
        //
        //  NONE = 0, STRING = 1, OSD_STRING = 2, FLAG = 3, INT64 = 4,
        //  DOUBLE = 5, NODE = 6, NODE_ARRAY = 7, NODE_MAP = 8, BYTE_ARRAY = 9
        const val MPV_FORMAT_NONE = 0
        const val MPV_FORMAT_STRING = 1
        const val MPV_FORMAT_OSD_STRING = 2
        const val MPV_FORMAT_FLAG = 3
        const val MPV_FORMAT_INT64 = 4
        const val MPV_FORMAT_DOUBLE = 5
        const val MPV_FORMAT_NODE = 6
        const val MPV_FORMAT_NODE_ARRAY = 7
        const val MPV_FORMAT_NODE_MAP = 8
        const val MPV_FORMAT_BYTE_ARRAY = 9

        fun command(handle: Long, vararg args: String) {
            val arr = arrayOfNulls<String>(args.size + 1)
            args.copyInto(arr)
            arr[args.size] = null
            INSTANCE.mpv_command(handle, arr)
        }
    }
}
