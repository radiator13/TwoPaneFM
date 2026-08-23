package com.twopane.fm.util

import android.os.Environment
import com.twopane.fm.model.FileEntry
import com.twopane.fm.model.FilterType
import com.twopane.fm.model.SortOrder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FileUtils {

    /**
     * List files. All readdir/stat in native C — one JNI call.
     */
    fun listFiles(
        path: String,
        showHidden: Boolean = false,
        sortOrder: SortOrder = SortOrder.NAME,
        sortAscending: Boolean = true,
        filter: FilterType = FilterType.ALL
    ): List<FileEntry> {
        val dir = File(path)
        if (!dir.exists() || !dir.isDirectory) return emptyList()

        val parentEntry = dir.parent?.let { parent ->
            listOf(FileEntry(
                name = "..", path = File(parent).absolutePath,
                isDirectory = true, lastModified = File(parent).lastModified(),
                permissions = "drwx", isHidden = false, isEmptyDir = false
            ))
        } ?: emptyList()

        var entries = NativeFileOps.listDir(path, showHidden)

        if (filter != FilterType.ALL) {
            entries = entries.filter { it.isDirectory || matchesFilter(it.name, filter) }
        }

        val sorted = entries.sortedWith(
            compareBy<FileEntry> { !it.isDirectory }.then(sortComparator(sortOrder, sortAscending))
        )

        return parentEntry + sorted
    }

    fun isEmptyDir(path: String): Boolean = NativeFileOps.nativeIsEmptyDir(path)

    fun isExternalStorageAccessible(): Boolean =
        Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED

    @Suppress("DEPRECATION")
    fun getRootPaths(): List<String> = listOf(
        Environment.getExternalStorageDirectory().absolutePath,
        "/storage/emulated/0", "/sdcard",
        "/data/data/com.termux/files/home"
    ).filter { File(it).exists() }.distinct()

    fun getAvailableRoots(): List<FileEntry> = getRootPaths().map { path ->
        FileEntry(name = File(path).name.ifBlank { path.substringAfterLast("/") },
            path = path, isDirectory = true, permissions = "drwx")
    }

    // ── All file ops go through native C ──

    fun copy(source: String, destination: String): Result<Unit> = runCatching {
        if (!NativeFileOps.copy(source, destination))
            throw Exception("copy failed: $source → $destination")
    }

    /** Total byte size of a file or directory tree. */
    fun treeSize(path: String): Long = try {
        val f = File(path)
        if (f.isFile) f.length()
        else f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    } catch (_: Exception) { 0L }

    /** Copy with byte-level progress reporting (pure Kotlin for progress visibility). */
    fun copyWithProgress(
        source: String,
        destination: String,
        onProgress: (bytesDone: Long) -> Unit
    ): Result<Unit> = runCatching {
        val src = File(source)
        val dst = File(destination)
        var done = 0L

        fun doCopyFile(from: File, to: File) {
            to.parentFile?.mkdirs()
            from.inputStream().buffered(65536).use { input ->
                to.outputStream().buffered(65536).use { output ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        done += read
                        onProgress(done)
                    }
                }
            }
            // Preserve permissions and mtime
            to.setLastModified(from.lastModified())
        }

        if (src.isDirectory) {
            src.walkTopDown().forEach { f ->
                val rel = f.relativeTo(src)
                val target = File(dst, rel.path)
                if (f.isDirectory) target.mkdirs() else doCopyFile(f, target)
            }
        } else {
            doCopyFile(src, dst)
        }
    }

    /** Delete with byte-level progress reporting. */
    fun deleteWithProgress(
        path: String,
        onProgress: (bytesDeleted: Long) -> Unit
    ): Result<Unit> = runCatching {
        val root = File(path)
        var done = 0L
        root.walkBottomUp().forEach { f ->
            val size = if (f.isFile) f.length() else 0L
            if (!f.delete()) throw Exception("delete failed: ${f.absolutePath}")
            done += size
            onProgress(done)
        }
    }

    /**
     * Move via rename(); returns (result, renameRecord) where renameRecord is
     * non-null when an atomic rename happened (oldPath to newPath), enabling undo.
     */
    fun moveWithRenameRecord(source: String, destination: String): Pair<Result<Unit>, Pair<String, String>?> {
        return try {
            if (NativeFileOps.rename(source, destination)) {
                Result.success(Unit) to (source to destination)
            } else {
                // Cross-filesystem fallback
                copy(source, destination).getOrThrow()
                delete(source).getOrThrow()
                Result.success(Unit) to null
            }
        } catch (e: Exception) {
            Result.failure(e) to null
        }
    }


    fun move(source: String, destination: String): Result<Unit> = runCatching {
        // rename() is atomic on same FS, falls back to copy+delete
        if (!NativeFileOps.rename(source, destination)) {
            // Cross-filesystem: copy then delete
            copy(source, destination).getOrThrow()
            delete(source).getOrThrow()
        }
    }

    fun delete(path: String): Result<Unit> = runCatching {
        if (!NativeFileOps.delete(path))
            throw Exception("delete failed: $path")
    }

    fun rename(path: String, newName: String): Result<Unit> = runCatching {
        val newPath = File(File(path).parent, newName).absolutePath
        if (!NativeFileOps.rename(path, newPath))
            throw Exception("rename failed: $path → $newPath")
    }

    fun mkdir(path: String, name: String): Result<Unit> = runCatching {
        val target = "$path/$name"
        if (!NativeFileOps.mkdirs(target))
            throw Exception("mkdir failed: $target")
    }

    fun createFile(path: String, name: String): Result<Unit> = runCatching {
        val target = File(path, name)
        if (!target.createNewFile())
            throw Exception("createFile failed: ${target.absolutePath}")
    }

    // ── Permission / ownership / symlink operations ──

    fun chmod(path: String, mode: Int): Result<Unit> = runCatching {
        if (!NativeFileOps.chmod(path, mode))
            throw Exception("chmod failed: $path mode=${Integer.toOctalString(mode)}")
    }

    fun chown(path: String, uid: Int, gid: Int): Result<Unit> = runCatching {
        if (!NativeFileOps.chown(path, uid, gid))
            throw Exception("chown failed: $path uid=$uid gid=$gid")
    }

    fun symlink(target: String, linkPath: String): Result<Unit> = runCatching {
        if (!NativeFileOps.symlink(target, linkPath))
            throw Exception("symlink failed: $target -> $linkPath")
    }

    fun setModTime(path: String, millis: Long): Result<Unit> = runCatching {
        if (!NativeFileOps.setModTime(path, millis))
            throw Exception("setModTime failed: $path")
    }

    fun readlink(path: String): String? = NativeFileOps.readlink(path)

    fun isSymlink(path: String): Boolean {
        val mode = NativeFileOps.statMode(path)
        if (mode == -1) return false
        // S_IFLNK = 0120000
        return (mode and 0xF000.toInt()) == 0xA000.toInt()
    }

    // ── Batch rename ──

    fun batchRename(
        files: List<File>,
        pattern: String,
        replacement: String,
        useRegex: Boolean
    ): Result<List<Pair<String, String>>> = runCatching {
        files.map { file ->
            val newName = if (useRegex) {
                file.name.replace(Regex(pattern), replacement)
            } else {
                file.name.replace(pattern, replacement)
            }
            file.name to newName
        }
    }

    fun batchRenameExecute(
        files: List<File>,
        newNames: List<String>
    ): Result<Int> = runCatching {
        var count = 0
        files.zip(newNames).forEach { (file, newName) ->
            if (newName.isNotBlank() && newName != file.name) {
                val newPath = File(file.parent, newName)
                if (!NativeFileOps.rename(file.absolutePath, newPath.absolutePath)) {
                    throw Exception("rename failed: ${file.name} -> $newName")
                }
                count++
            }
        }
        count
    }

    // ── Archive extraction ──

    fun extractZip(zipPath: String, destDir: String): Result<String> = runCatching {
        val dest = File(destDir)
        dest.mkdirs()
        var count = 0
        java.util.zip.ZipFile(File(zipPath)).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                count++
                val outFile = File(dest, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        outFile.outputStream().buffered(65536).use { output ->
                            input.copyTo(output, bufferSize = 65536)
                        }
                    }
                }
            }
        }
        "Extracted $count entries -> $destDir"
    }

    // ── Search via native C (recursive readdir + strstr) ──

    fun search(path: String, query: String): List<String> =
        NativeFileOps.search(path, query)

    // ── Disk usage via native C ──

    fun diskUsage(path: String): Long = NativeFileOps.diskUsage(path)

    // ── Formatting (pure Kotlin, no I/O) ──

    fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }

    fun formatDate(timestamp: Long): String {
        val diff = System.currentTimeMillis() - timestamp
        return when {
            diff < 60_000 -> "Just now"
            diff < 3_600_000 -> "${diff / 60_000}m ago"
            diff < 86_400_000 -> "${diff / 3_600_000}h ago"
            diff < 172_800_000 -> "Yesterday"
            diff < 604_800_000 -> "${diff / 86_400_000}d ago"
            else -> SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timestamp))
        }
    }

    fun getParentPath(path: String): String = File(path).parent ?: path
    fun resolvePath(base: String, name: String): String = File(base, name).absolutePath

    private fun matchesFilter(name: String, filter: FilterType): Boolean = when (filter) {
        FilterType.IMAGES -> name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) ||
                name.endsWith(".png", true) || name.endsWith(".gif", true) ||
                name.endsWith(".bmp", true) || name.endsWith(".webp", true)
        FilterType.AUDIO -> name.endsWith(".mp3", true) || name.endsWith(".wav", true) ||
                name.endsWith(".flac", true) || name.endsWith(".aac", true) || name.endsWith(".ogg", true)
        FilterType.ARCHIVES -> name.endsWith(".zip", true) || name.endsWith(".rar", true) ||
                name.endsWith(".7z", true) || name.endsWith(".tar", true) || name.endsWith(".gz", true)
        FilterType.APK -> name.endsWith(".apk", true)
        FilterType.ALL -> true
    }

    private fun sortComparator(order: SortOrder, ascending: Boolean): Comparator<FileEntry> {
        val base = when (order) {
            SortOrder.NAME -> compareBy<FileEntry> { it.name.lowercase() }
            SortOrder.TYPE -> compareBy<FileEntry> {
                it.name.substringAfterLast('.', "").lowercase()
            }.thenBy { it.name.lowercase() }
            SortOrder.SIZE -> compareBy { it.size }
            SortOrder.DATE -> compareBy { it.lastModified }
        }
        return if (ascending) base else base.reversed()
    }
}
