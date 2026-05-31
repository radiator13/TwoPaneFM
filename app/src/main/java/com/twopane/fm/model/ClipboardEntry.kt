package com.twopane.fm.model

data class ClipboardEntry(
    val sourcePaths: List<String>,
    val operation: ClipboardOperation
)

enum class ClipboardOperation { COPY, CUT }
