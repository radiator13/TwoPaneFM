package com.twopane.fm.util

/**
 * Native-backed text buffer for the virtualized text editor.
 * Uses Rust/JNI for fast file I/O, line indexing, and search.
 * Only materializes visible lines in Kotlin — handles 100MB+ files.
 */
class NativeTextBuffer private constructor(private val handle: Int) : AutoCloseable {

    val lineCount: Int
        get() = NativeFileOps.nativeTextLineCount(handle)

    val byteSize: Long
        get() = NativeFileOps.nativeTextSize(handle)

    /** Get a single line (0-indexed). Returns empty string if out of range. */
    fun getLine(lineNum: Int): String {
        return NativeFileOps.nativeTextGetLine(handle, lineNum) ?: ""
    }

    /** Get a range of lines. Returns list of (lineNum, text) pairs. */
    fun getLines(startLine: Int, count: Int): List<Pair<Int, String>> {
        val result = ArrayList<Pair<Int, String>>(count)
        val total = lineCount
        for (i in startLine until minOf(startLine + count, total)) {
            result.add(i to getLine(i))
        }
        return result
    }

    /** Search for text. Returns line number or -1. direction: 1=forward, -1=backward */
    fun search(query: String, fromLine: Int = 0, direction: Int = 1, caseSensitive: Boolean = false): Int {
        return NativeFileOps.nativeTextSearch(handle, query, fromLine, direction, caseSensitive)
    }

    /** Replace the entire buffer content (after editing). */
    fun setContent(content: String): Boolean {
        return NativeFileOps.nativeTextSetContent(handle, content)
    }

    /** Save to original path. */
    fun save(): Boolean {
        return NativeFileOps.nativeTextSave(handle)
    }

    /** Save to a different path. */
    fun saveAs(path: String): Boolean {
        return NativeFileOps.nativeTextSaveAs(handle, path)
    }

    override fun close() {
        NativeFileOps.nativeTextClose(handle)
    }

    companion object {
        /** Open a text file. Returns null on error. */
        fun open(path: String): NativeTextBuffer? {
            val handle = NativeFileOps.nativeTextOpen(path)
            return if (handle >= 0) NativeTextBuffer(handle) else null
        }
    }
}
