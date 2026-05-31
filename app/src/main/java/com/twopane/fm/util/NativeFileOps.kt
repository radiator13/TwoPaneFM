package com.twopane.fm.util

import com.twopane.fm.model.FileEntry

/**
 * JNI bridge to native file operations in fileops.c.
 * All filesystem I/O happens in C — single JNI call per bulk operation.
 */
object NativeFileOps {

    init {
        System.loadLibrary("fileops")
    }

    // ── Directory scan ──
    private external fun nativeScanDir(path: String, showHidden: Boolean): Int
    private external fun nativeGetCount(slot: Int): Int
    private external fun nativeGetSizes(slot: Int): LongArray?
    private external fun nativeGetMtimes(slot: Int): LongArray?
    private external fun nativeGetFlags(slot: Int): IntArray?
    private external fun nativeGetNames(slot: Int): ByteArray?
    private external fun nativeGetNameOffsets(slot: Int): IntArray?
    private external fun nativeGetNameLens(slot: Int): IntArray?
    private external fun nativeFreeSlot(slot: Int)

    // ── File operations ──
    external fun nativeIsEmptyDir(path: String): Boolean
    external fun nativeExists(path: String): Boolean
    external fun nativeIsDir(path: String): Boolean
    external fun nativeCopy(src: String, dst: String): Int
    external fun nativeDelete(path: String): Int
    external fun nativeRename(oldPath: String, newPath: String): Int
    external fun nativeMkdir(path: String, mode: Int): Int
    external fun nativeMkdirs(path: String, mode: Int): Int
    external fun nativeDiskUsage(path: String): Long
    external fun nativeTouch(path: String): Int

    // ── Permission / ownership / symlink operations ──
    external fun nativeChmod(path: String, mode: Int): Int
    external fun nativeChown(path: String, uid: Int, gid: Int): Int
    external fun nativeSymlink(target: String, linkPath: String): Int
    external fun nativeSetModTime(path: String, millis: Long): Int
    external fun nativeReadlink(path: String): String?
    external fun nativeStatMode(path: String): Int

    // ── Search ──
    external fun nativeSearch(path: String, query: String): Int
    private external fun nativeGetSearchResult(idx: Int): String?

    // ── Text engine (native-backed) ──
    external fun nativeTextOpen(path: String): Int
    external fun nativeTextLineCount(handle: Int): Int
    external fun nativeTextGetLine(handle: Int, lineNum: Int): String?
    external fun nativeTextLineByteLen(handle: Int, lineNum: Int): Int
    external fun nativeTextSearch(handle: Int, query: String, fromLine: Int, direction: Int, caseSensitive: Boolean): Int
    external fun nativeTextSetContent(handle: Int, content: String): Boolean
    external fun nativeTextSave(handle: Int): Boolean
    external fun nativeTextSaveAs(handle: Int, path: String): Boolean
    external fun nativeTextClose(handle: Int)
    external fun nativeTextSize(handle: Int): Long

    /**
     * Scan a directory and return FileEntry list.
     * All stat() calls happen in native code — single JNI round-trip.
     */
    fun listDir(path: String, showHidden: Boolean): List<FileEntry> {
        val slot = nativeScanDir(path, showHidden)
        if (slot < 0) return emptyList()

        try {
            val count = nativeGetCount(slot)
            if (count == 0) return emptyList()

            val sizes = nativeGetSizes(slot) ?: return emptyList()
            val mtimes = nativeGetMtimes(slot) ?: return emptyList()
            val flags = nativeGetFlags(slot) ?: return emptyList()
            val namesBuf = nativeGetNames(slot) ?: return emptyList()
            val nameOffsets = nativeGetNameOffsets(slot) ?: return emptyList()
            val nameLens = nativeGetNameLens(slot) ?: return emptyList()

            val entries = ArrayList<FileEntry>(count)
            for (i in 0 until count) {
                val name = String(namesBuf, nameOffsets[i], nameLens[i], Charsets.UTF_8)
                val f = flags[i]
                entries.add(FileEntry(
                    name = name,
                    path = if (path.endsWith("/")) "$path$name" else "$path/$name",
                    isDirectory = (f and 1) != 0,
                    size = sizes[i],
                    lastModified = mtimes[i],
                    isHidden = (f and 2) != 0,
                    permissions = buildPermString(f),
                    isEmptyDir = false
                ))
            }
            return entries
        } finally {
            nativeFreeSlot(slot)
        }
    }

    /**
     * Copy file or directory. Uses sendfile() for files, recursive for dirs.
     * Returns 0 on success, -1 on failure.
     */
    fun copy(src: String, dst: String): Boolean = nativeCopy(src, dst) == 0

    /**
     * Delete file or directory recursively. Uses unlink()/rmdir() in C.
     */
    fun delete(path: String): Boolean = nativeDelete(path) == 0

    /**
     * Rename/move file. Uses rename() syscall.
     */
    fun rename(oldPath: String, newPath: String): Boolean = nativeRename(oldPath, newPath) == 0

    /**
     * Create directory. mode=0755 typical.
     */
    fun mkdir(path: String): Boolean = nativeMkdir(path, 493) == 0 || nativeExists(path)

    /**
     * Create directory path (mkdir -p).
     */
    fun mkdirs(path: String): Boolean = nativeMkdirs(path, 493) == 0 || nativeExists(path)

    /**
     * Recursive disk usage in bytes (actual blocks allocated).
     */
    fun diskUsage(path: String): Long = nativeDiskUsage(path)

    /**
     * Search for files matching query (case-insensitive substring).
     * Returns up to 200 absolute paths.
     */
    fun search(path: String, query: String): List<String> {
        val count = nativeSearch(path, query)
        if (count <= 0) return emptyList()
        return (0 until count).mapNotNull { nativeGetSearchResult(it) }
    }

    /**
     * Touch file (update mtime to now).
     */
    fun touch(path: String): Boolean = nativeTouch(path) == 0

    /**
     * Change file permissions (chmod). mode is octal, e.g. 0755 = 493.
     */
    fun chmod(path: String, mode: Int): Boolean = nativeChmod(path, mode) == 0

    /**
     * Change file owner/group. uid=-1 and gid=-1 to leave unchanged.
     */
    fun chown(path: String, uid: Int, gid: Int): Boolean = nativeChown(path, uid, gid) == 0

    /**
     * Create a symbolic link.
     */
    fun symlink(target: String, linkPath: String): Boolean = nativeSymlink(target, linkPath) == 0

    /**
     * Set file modification time in milliseconds since epoch.
     */
    fun setModTime(path: String, millis: Long): Boolean = nativeSetModTime(path, millis) == 0

    /**
     * Read symlink target path. Returns null if not a symlink.
     */
    fun readlink(path: String): String? = nativeReadlink(path)

    /**
     * Get raw stat mode (includes file type bits).
     */
    fun statMode(path: String): Int = nativeStatMode(path)

    /**
     * Check if path is a symbolic link.
     */
    fun isSymlink(path: String): Boolean {
        val mode = nativeStatMode(path)
        if (mode == -1) return false
        // S_IFLNK = 0120000 (012 << 12 = 4096 = 0x1200)
        return (mode and 0xF000) == 0xA000
    }

    private fun buildPermString(flags: Int): String {
        val d = if ((flags and 1) != 0) 'd' else '-'
        val r = if ((flags and 4) != 0) 'r' else '-'
        val w = if ((flags and 8) != 0) 'w' else '-'
        val x = if ((flags and 16) != 0) 'x' else '-'
        return "$d$r$w$x"
    }
}
