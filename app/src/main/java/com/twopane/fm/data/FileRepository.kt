package com.twopane.fm.data

import com.twopane.fm.model.FileEntry
import com.twopane.fm.model.FilterType
import com.twopane.fm.model.SortOrder
import com.twopane.fm.util.ApkInfo
import com.twopane.fm.util.ApkUtils
import com.twopane.fm.util.EmbeddedTools
import com.twopane.fm.util.FileUtils
import com.twopane.fm.util.NativeFileOps
import com.twopane.fm.util.ToolLoader
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

    // ── APK Operations ──

    suspend fun getApkInfo(apkPath: String): ApkInfo? = withContext(Dispatchers.IO) {
        ApkUtils.getApkInfo(apkPath)
    }

    suspend fun getManifestText(apkPath: String): String? = withContext(Dispatchers.IO) {
        ApkUtils.getManifestText(apkPath)
    }

    suspend fun decodeBinaryXml(apkPath: String, entryName: String): String? = withContext(Dispatchers.IO) {
        ApkUtils.decodeBinaryXml(apkPath, entryName)
    }

    suspend fun listApkContents(apkPath: String): List<String> = withContext(Dispatchers.IO) {
        ApkUtils.listApkContents(apkPath)
    }

    // ── Embedded Tools ──

    suspend fun disassembleDex(dexPath: String, outputDir: String, apiLevel: Int = 35): Result<String> = withContext(Dispatchers.IO) {
        EmbeddedTools.disassembleDex(dexPath, outputDir, apiLevel)
    }

    suspend fun assembleSmali(smaliDir: String, outputDexPath: String, apiLevel: Int = 35): Result<String> = withContext(Dispatchers.IO) {
        EmbeddedTools.assembleSmali(smaliDir, outputDexPath, apiLevel)
    }

    suspend fun decompileFullApk(
        apkPath: String,
        outputDir: String,
        onProgress: ((String) -> Unit)? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        EmbeddedTools.decompileFullApk(apkPath, outputDir, onProgress)
    }

    suspend fun signApk(apkPath: String, outputPath: String): Result<String> = withContext(Dispatchers.IO) {
        EmbeddedTools.signApk(apkPath, outputPath)
    }

    suspend fun rebuildAndSign(
        apkPath: String,
        outputPath: String,
        nativeZipalign: ToolLoader.ToolInfo?,
        onProgress: ((String) -> Unit)? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        EmbeddedTools.rebuildAndSign(apkPath, outputPath, nativeZipalign, onProgress)
    }

    suspend fun decodeResources(
        apkPath: String,
        outputPath: String,
        nativeAapt2: ToolLoader.ToolInfo?
    ): Result<String> = withContext(Dispatchers.IO) {
        EmbeddedTools.decodeResources(apkPath, outputPath, nativeAapt2)
    }

    suspend fun jadxGetJavaCode(inputPath: String, className: String): Result<String> = withContext(Dispatchers.IO) {
        EmbeddedTools.jadxGetJavaCode(inputPath, className)
    }

    fun listSmaliFiles(smaliDir: String) = EmbeddedTools.listSmaliFiles(smaliDir)
    fun readSmaliFile(path: String) = EmbeddedTools.readSmaliFile(path)
    fun writeSmaliFile(path: String, content: String) = EmbeddedTools.writeSmaliFile(path, content)

    // ── File I/O ──

    suspend fun readFile(path: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching { File(path).readText() }
    }

    suspend fun writeFile(path: String, content: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { File(path).writeText(content) }
    }

    suspend fun extractFromApk(apkPath: String, entryName: String, outputDir: File): String? = withContext(Dispatchers.IO) {
        try {
            val outFile = File(outputDir, entryName.replace("/", "_"))
            if (outFile.exists()) return@withContext outFile.absolutePath
            outFile.parentFile?.mkdirs()
            java.util.zip.ZipFile(File(apkPath)).use { zip ->
                val zipEntry = zip.getEntry(entryName) ?: return@withContext null
                zip.getInputStream(zipEntry).use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
            outFile.absolutePath
        } catch (e: Exception) { null }
    }
}
