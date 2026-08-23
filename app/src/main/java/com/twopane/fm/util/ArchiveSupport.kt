package com.twopane.fm.util

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Generic zip-based archive support: list, read, extract, create.
 * Handles .zip, .jar, .apk, .aab, .apks, .xapk — anything ZipFile can open.
 */
object ArchiveSupport {

    val archiveExtensions = setOf("zip", "jar", "apk", "aab", "apks", "xapk", "war", "ear")

    fun isArchive(fileName: String): Boolean =
        fileName.substringAfterLast('.', "").lowercase() in archiveExtensions

    data class ArchiveEntry(
        val name: String,
        val isDirectory: Boolean,
        val size: Long,
        val compressedSize: Long,
        val lastModified: Long
    )

    fun listEntries(archivePath: String): List<ArchiveEntry> = runCatching {
        ZipFile(File(archivePath)).use { zip ->
            zip.entries().asSequence()
                .map { e ->
                    ArchiveEntry(
                        name = e.name,
                        isDirectory = e.isDirectory,
                        size = e.size,
                        compressedSize = e.compressedSize,
                        lastModified = e.time
                    )
                }
                .sortedWith(compareBy<ArchiveEntry> { it.name.substringBefore('/').lowercase() }
                    .thenBy { it.name })
                .toList()
        }
    }.getOrDefault(emptyList())

    /** Read a text entry (best-effort UTF-8). */
    fun readTextEntry(archivePath: String, entryName: String): Result<String> = runCatching {
        ZipFile(File(archivePath)).use { zip ->
            val entry = zip.getEntry(entryName) ?: throw Exception("Entry not found: $entryName")
            if (entry.size > 4 * 1024 * 1024) throw Exception("Entry too large to display (>4MB)")
            zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
    }

    /**
     * Extract a single entry (recursively for directories) into [outputDir].
     * Returns the path of the extracted root.
     */
    fun extractEntry(archivePath: String, entryName: String, outputDir: String): Result<String> = runCatching {
        val dest = File(outputDir)
        dest.mkdirs()
        var count = 0
        ZipFile(File(archivePath)).use { zip ->
            val entries = zip.entries().asSequence().toList()
            // Exact file match
            zip.getEntry(entryName)?.let { root ->
                if (root.isDirectory) {
                    val prefix = entryName
                    entries.filter { it.name.startsWith(prefix) }.forEach { writeEntry(zip, it, dest) { count++ } }
                } else {
                    writeEntry(zip, root, dest) { count++ }
                }
                return@runCatching "Extracted $count item(s) → $outputDir"
            }
            // Directory prefix match
            val prefix = if (entryName.endsWith("/")) entryName else "$entryName/"
            val matches = entries.filter { it.name.startsWith(prefix) }
            if (matches.isEmpty()) throw Exception("Entry not found: $entryName")
            matches.forEach { writeEntry(zip, it, dest) { count++ } }
            "Extracted $count item(s) → $outputDir"
        }
    }

    fun extractAll(archivePath: String, outputDir: String): Result<String> = runCatching {
        val dest = File(outputDir)
        dest.mkdirs()
        var count = 0
        ZipFile(File(archivePath)).use { zip ->
            zip.entries().asSequence().forEach { writeEntry(zip, it, dest) { count++ } }
        }
        "Extracted $count item(s) → $outputDir"
    }

    private inline fun writeEntry(zip: ZipFile, entry: ZipEntry, destRoot: File, onExtracted: () -> Unit) {
        val outFile = safeChildFile(destRoot, entry.name) ?: return
        if (entry.isDirectory) {
            outFile.mkdirs()
        } else {
            outFile.parentFile?.mkdirs()
            try {
                zip.getInputStream(entry).use { input ->
                    outFile.outputStream().buffered(65536).use { output ->
                        input.copyTo(output, bufferSize = 65536)
                    }
                }
                onExtracted()
            } catch (_: Exception) {}
        }
    }

    /** Zip-slip protection: refuse paths that escape [destRoot]. */
    private fun safeChildFile(destRoot: File, entryName: String): File? {
        val cleaned = entryName.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." && it != ".." }
        if (cleaned.isEmpty()) return null
        var f = destRoot
        for (part in cleaned) f = File(f, part)
        val canonicalRoot = destRoot.canonicalFile
        val canonical = f.canonicalFile
        return if (canonical.path.startsWith(canonicalRoot.path)) canonical else null
    }

    /** Create a zip at [archivePath] containing [sourcePaths] (files or dirs, recursive). */
    fun createZip(sourcePaths: List<String>, archivePath: String): Result<String> = runCatching {
        val out = File(archivePath)
        out.parentFile?.mkdirs()
        ZipOutputStream(out.outputStream().buffered(65536)).use { zos ->
            for (srcPath in sourcePaths) {
                val src = File(srcPath)
                if (!src.exists()) continue
                if (src.isFile) {
                    putFile(zos, src, src.name)
                } else {
                    // Directory: store relative paths under its own name
                    src.walkTopDown().filter { it.isFile }.forEach { f ->
                        val rel = src.parent?.let { p -> f.relativeTo(File(p)).path } ?: f.name
                        putFile(zos, f, rel)
                    }
                }
            }
        }
        "Created ${File(archivePath).name} (${FileUtils.formatSize(out.length())})"
    }

    private fun putFile(zos: ZipOutputStream, file: File, entryName: String) {
        val entry = ZipEntry(entryName).apply { time = file.lastModified() }
        zos.putNextEntry(entry)
        file.inputStream().buffered(65536).use { input -> input.copyTo(zos, bufferSize = 65536) }
        zos.closeEntry()
    }
}
