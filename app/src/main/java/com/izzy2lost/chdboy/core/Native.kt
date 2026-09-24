package com.izzy2lost.chdboy.core

/**
 * The native converter.
 *
 * Nothing the app converts is reachable by name — SAF hands back URIs, and the
 * app holds no storage permission — so paths are made up on the native side:
 * open a session, bind a descriptor into it under the name the user sees, and
 * pass the path that comes back to [nativeConvert]. A `.cue` and its `.bin`
 * bound into the same session end up as siblings, which is what lets the
 * parser resolve `FILE "Game.bin"` with no special case.
 *
 * A session OWNS the descriptors bound into it: pass `detachFd()`, never a
 * live [android.os.ParcelFileDescriptor], and let [nativeCloseSession] close
 * them.
 */
internal object Native {

    /** Opens an empty binding session. Always pair with [nativeCloseSession]. */
    external fun nativeOpenSession(): Int

    /**
     * Binds [fd] into [session] under [name] and returns the path it is now
     * reachable at, or `""` on failure — in which case [fd] is still the
     * caller's to close.
     */
    external fun nativeBindFd(session: Int, fd: Int, name: String): String

    /** Closes every descriptor in [session] and forgets it. */
    external fun nativeCloseSession(session: Int)

    /**
     * Writes [destination] as a CHD holding [source], which may be a raw disc
     * image or a `.cue` / `.gdi` track sheet. Set [portable] to write Deflate
     * instead of zstd, for a file older tooling can read at the cost of being
     * slower and larger.
     *
     * [hunkBytes] is the compression block size, or 0 for the default. It must
     * be a multiple of 2048. Bigger compresses better; smaller costs a reader
     * less to serve one sector, since a hunk is decompressed whole. A track
     * sheet ignores it — the CD format fixes it at eight frames.
     *
     * BLOCKS for minutes on a full-size image, so this must not be called on
     * the main thread. Poll [nativeGetProgress] while it runs. Only one
     * conversion runs at a time; a second call returns false immediately.
     *
     * Returns false if it failed OR was cancelled, leaving a partial output
     * for the caller to delete; [nativeLastError] says which.
     */
    external fun nativeConvert(
        source: String,
        destination: String,
        portable: Boolean,
        hunkBytes: Int,
    ): Boolean

    /**
     * `"done\ntotal\nwritten"` in bytes, or `""` when nothing is converting.
     * Percentages and ratios are computed by the caller so the dialog and the
     * notification cannot disagree about them.
     */
    external fun nativeGetProgress(): String

    /** Stops the running conversion at its next batch boundary. */
    external fun nativeCancel()

    /** Why the last [nativeConvert] returned false. */
    external fun nativeLastError(): String

    init {
        System.loadLibrary("chdboy")
    }
}
