package com.twopane.fm.data

import com.twopane.fm.model.FileEntry
import com.twopane.fm.model.FilterType
import com.twopane.fm.model.SortOrder
import com.twopane.fm.util.ArchiveSupport
import com.twopane.fm.util.FileUtils
import com.twopane.fm.util.NativeFileOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Repository layer for file operations.
 * Abstracts data sources and provides a clean API for ViewModels.
 */
class FileRepository {

    // ── File System Operations ──

    suspend fun listFiles(
        path: String,
        showHidden: Boolean = false,
        sortOrder: SortOrder = SortOrder.NAME,
        sortAscending: Boolean = true,
        filter: FilterType = FilterType.ALL
    ): List<FileEntry> = withContext(Dispatchers.IO) {
        FileUtils.listFiles(path, showHidden, sortOrder, sortAscending, filter)
    }

    suspend fun copyFile(source: String, destination: String): Result<Unit> = withContext(Dispatchers.IO) {
        FileUtils.copy(source, destination)
    }

    suspend fun moveFile(source: String, destination: String): Result<Unit> = withContext(Dispatchers.IO) {
        FileUtils.move(source, destination)
    }

    suspend fun deleteFile(path: String): Result<Unit> = withContext(Dispatchers.IO) {
        FileUtils.delete(path)
    }

    suspend fun renameFile(path: String, newName: String): Result<Unit> = withContext(Dispatchers.IO) {
        FileUtils.rename(path, newName)
    }

    suspend fun createDirectory(path: String, name: String): Result<Unit> = withContext(Dispatchers.IO) {
        FileUtils.mkdir(path, name)
    }

    suspend fun createFile(path: String, name: String): Result<Unit> = withContext(Dispatchers.IO) {
        FileUtils.createFile(path, name)
    }

    suspend fun searchFiles(path: String, query: String): List<String> = withContext(Dispatchers.IO) {
        FileUtils.search(path, query)
    }

    suspend fun getDiskUsage(path: String): Long = withContext(Dispatchers.IO) {
        FileUtils.diskUsage(path)
    }

    fun getRootPaths(): List<String> = FileUtils.getRootPaths()
    fun getAvailableRoots(): List<FileEntry> = FileUtils.getAvailableRoots()
    fun formatSize(bytes: Long): String = FileUtils.formatSize(bytes)
    fun formatDate(timestamp: Long): String = FileUtils.formatDate(timestamp)
    fun getParentPath(path: String): String = FileUtils.getParentPath(path)
    fun resolvePath(base: String, name: String): String = FileUtils.resolvePath(base, name)

    // ── Archive Operations ──

    suspend fun listArchiveEntries(archivePath: String): List<ArchiveSupport.ArchiveEntry> =
        withContext(Dispatchers.IO) { ArchiveSupport.listEntries(archivePath) }

    suspend fun readArchiveTextEntry(archivePath: String, entryName: String): Result<String> =
        withContext(Dispatchers.IO) { ArchiveSupport.readTextEntry(archivePath, entryName) }

    suspend fun extractArchiveEntry(archivePath: String, entryName: String, outputDir: String): Result<String> =
        withContext(Dispatchers.IO) { ArchiveSupport.extractEntry(archivePath, entryName, outputDir) }

    suspend fun extractArchiveAll(archivePath: String, outputDir: String): Result<String> =
        withContext(Dispatchers.IO) { ArchiveSupport.extractAll(archivePath, outputDir) }

    suspend fun createArchive(sourcePaths: List<String>, archivePath: String): Result<String> =
        withContext(Dispatchers.IO) { ArchiveSupport.createZip(sourcePaths, archivePath) }

    // ── File I/O ──

    suspend fun readFile(path: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching { File(path).readText() }
    }

    suspend fun writeFile(path: String, content: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { File(path).writeText(content) }
    }
}
