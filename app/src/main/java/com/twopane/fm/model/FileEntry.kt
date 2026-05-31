package com.twopane.fm.model

data class FileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val lastModified: Long = 0,
    val isHidden: Boolean = false,
    val permissions: String = "",
    val isEmptyDir: Boolean = false
)
