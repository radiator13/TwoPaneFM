package com.twopane.fm.util

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

data class ApkInfo(
    val packageName: String = "Unknown",
    val versionName: String = "Unknown",
    val versionCode: Long = 0,
    val minSdk: Int = 0,
    val targetSdk: Int = 0,
    val permissions: List<String> = emptyList(),
    val dexCount: Int = 0,
    val totalDexSize: Long = 0L,
    val entryCount: Int = 0
)

data class AvailableTools(
    val embeddedJadx: Boolean = true,
    val embeddedBaksmali: Boolean = true,
    val embeddedSmali: Boolean = true,
    val embeddedApksigner: Boolean = true,
    val zipalign: Boolean = false,
    val aapt2: Boolean = false,
    val termuxJadx: Boolean = false,
    val termuxBaksmali: Boolean = false,
    val termuxSmali: Boolean = false,
    val termuxApksigner: Boolean = false
) {
    val any: Boolean get() = embeddedJadx || embeddedBaksmali || embeddedSmali || embeddedApksigner || zipalign || aapt2
}

object ApkUtils {

    private var cachedApkPath: String? = null
    private var cachedManifestText: String? = null
    private var cachedApkInfo: ApkInfo? = null

    // Precompiled regexes — avoids recompilation on every call
    private val RE_PACKAGE = Regex("""package="([^"]+)"""")
    private val RE_VERSION_NAME = Regex("""versionName="([^"]+)"""")
    private val RE_VERSION_CODE = Regex("""versionCode="(\d+)"""")
    private val RE_MIN_SDK = Regex("""minSdkVersion="(\d+)"""")
    private val RE_TARGET_SDK = Regex("""targetSdkVersion="(\d+)"""")
    private val RE_PERMISSION = Regex("""uses-permission.*?name="([^"]+)"""")

    fun getApkInfo(apkPath: String): ApkInfo? {
        // Fast path: return cached if same APK
        if (cachedApkPath == apkPath && cachedApkInfo != null) return cachedApkInfo
        return try {
            val file = File(apkPath)
            if (!file.exists()) return null
            ZipFile(file).use { zip ->
                val manifestEntry = zip.getEntry("AndroidManifest.xml") ?: return null
                val manifestBytes = zip.getInputStream(manifestEntry).readBytes()
                val manifestText = parseBinaryXml(manifestBytes)

                val pkg = RE_PACKAGE.find(manifestText)?.groupValues?.getOrNull(1) ?: ""
                val vName = RE_VERSION_NAME.find(manifestText)?.groupValues?.getOrNull(1) ?: ""
                val vCode = RE_VERSION_CODE.find(manifestText)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
                val minSdk = RE_MIN_SDK.find(manifestText)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                val targetSdk = RE_TARGET_SDK.find(manifestText)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0

                val perms = mutableListOf<String>()
                RE_PERMISSION.findAll(manifestText).forEach { perms.add(it.groupValues[1]) }

                var dexCount = 0
                var totalDexSize = 0L
                var entryCount = 0
                zip.entries().asSequence().forEach { e ->
                    entryCount++
                    if (e.name.startsWith("classes") && e.name.endsWith(".dex")) {
                        dexCount++
                        totalDexSize += e.size
                    }
                }

                cachedApkPath = apkPath
                cachedManifestText = manifestText
                val info = ApkInfo(
                    packageName = pkg, versionName = vName, versionCode = vCode,
                    minSdk = minSdk, targetSdk = targetSdk, permissions = perms,
                    dexCount = dexCount, totalDexSize = totalDexSize, entryCount = entryCount
                )
                cachedApkInfo = info
                info
            }
        } catch (e: Exception) { null }
    }

    fun getManifestText(apkPath: String): String? {
        if (cachedApkPath == apkPath && cachedManifestText != null) return cachedManifestText
        return try {
            ZipFile(File(apkPath)).use { zip ->
                val entry = zip.getEntry("AndroidManifest.xml") ?: return null
                cachedApkPath = apkPath
                parseBinaryXml(zip.getInputStream(entry).readBytes()).also { cachedManifestText = it }
            }
        } catch (e: Exception) { null }
    }

    fun decodeBinaryXml(apkPath: String, entryName: String): String? {
        return try {
            ZipFile(File(apkPath)).use { zip ->
                val entry = zip.getEntry(entryName) ?: return null
                val bytes = zip.getInputStream(entry).readBytes()
                if (bytes.size < 4) return String(bytes, Charsets.UTF_8)
                if (bytes[0] == '<'.code.toByte() || bytes[0] == 0xEF.toByte()) {
                    return String(bytes, Charsets.UTF_8)
                }
                parseBinaryXml(bytes)
            }
        } catch (e: Exception) { null }
    }

    fun listApkContents(apkPath: String): List<String> {
        return try {
            val entries = mutableListOf<String>()
            ZipFile(File(apkPath)).use { zip ->
                zip.entries().asSequence().sortedBy { it.name }.forEach { e ->
                    entries.add("${if (e.isDirectory) "D" else " "} ${"%8d".format(e.size)}  ${e.name}")
                }
            }
            entries
        } catch (e: Exception) { emptyList() }
    }

    fun rebuildAndSignApk(apkPath: String, outputPath: String, loader: ToolLoader): Result<String> {
        return EmbeddedTools.rebuildAndSign(apkPath, outputPath, loader.nativeZipalign)
    }

    // ── Binary XML Parser — ByteBuffer-based (zero temp files) ──

    private fun parseBinaryXml(bytes: ByteArray): String {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.remaining() < 8) return String(bytes, Charsets.UTF_8)
        val xmlMagic = buf.int
        if (xmlMagic != 0x00080003) return String(bytes, Charsets.UTF_8)
        buf.int // xmlSize
        val stringPool = readStringPool(buf)
        val sb = StringBuilder(4096)
        var depth = 0
        val indent = "  "
        while (buf.hasRemaining()) {
            if (buf.remaining() < 8) break
            val chunkType = buf.int
            if (chunkType == -1) break
            val chunkSize = buf.int
            if (chunkSize <= 0) break
            val chunkEnd = buf.position() - 8 + chunkSize
            when (chunkType) {
                0x00100100 -> {
                    buf.int; buf.int
                    val nameIdx = buf.int
                    val attrStart = buf.int
                    val attrCount = buf.int
                    buf.int; buf.int; buf.int
                    val tagName = stringPool.getOrNull(nameIdx) ?: "?$nameIdx"
                    sb.append("${indent.repeat(depth)}<$tagName"); depth++
                    // Skip to attrs
                    val skipBytes = attrStart - 20
                    if (skipBytes > 0) buf.position(buf.position() + skipBytes)
                    for (a in 0 until attrCount) {
                        val attrName = buf.int
                        val attrValue = buf.int
                        val attrType = buf.int
                        val attrData = buf.int
                        val name = stringPool.getOrNull(attrName) ?: "?$attrName"
                        val value = when {
                            attrType == 0x03000008 -> stringPool.getOrNull(attrData) ?: ""
                            attrType == 0x03000010 || attrType == 0x03000011 -> attrData.toString()
                            attrType == 0x0300001B || attrType == 0x0300001C -> "0x${attrData.toUInt().toString(16)}"
                            attrValue in 0 until stringPool.size -> stringPool[attrValue]
                            else -> ""
                        }
                        if (value.isNotEmpty()) sb.append("\n${indent.repeat(depth)}$name=\"$value\"")
                        else sb.append("\n${indent.repeat(depth)}$name")
                    }
                    sb.append(">")
                    // Skip remaining chunk bytes
                    if (buf.position() < chunkEnd && chunkEnd <= buf.limit()) {
                        buf.position(chunkEnd)
                    }
                }
                0x00100103 -> {
                    depth--
                    buf.int; buf.int
                    val nameIdx = buf.int
                    sb.append("</${stringPool.getOrNull(nameIdx) ?: "?"}>\n")
                    // Skip remaining
                    if (buf.position() < chunkEnd && chunkEnd <= buf.limit()) {
                        buf.position(chunkEnd)
                    }
                }
                0x00100104 -> {
                    buf.int; buf.int
                    sb.append(stringPool.getOrNull(buf.int) ?: "")
                    if (buf.position() < chunkEnd && chunkEnd <= buf.limit()) {
                        buf.position(chunkEnd)
                    }
                }
                else -> {
                    // Skip unknown chunk
                    if (chunkEnd <= buf.limit()) buf.position(chunkEnd)
                    else break
                }
            }
        }
        return sb.toString()
    }

    private fun readStringPool(buf: ByteBuffer): List<String> {
        val startPos = buf.position()
        buf.int; buf.int // chunk type, chunk size
        val stringCount = buf.int
        val styleCount = buf.int
        val flags = buf.int
        val stringsStart = buf.int
        buf.int // stylesStart
        val isUtf8 = (flags and 0x100) != 0
        val offsets = IntArray(stringCount) { buf.int }
        // Skip style offsets
        if (styleCount > 0) buf.position(buf.position() + styleCount * 4)
        return List(stringCount) { i ->
            try {
                val savedPos = buf.position()
                buf.position(startPos + stringsStart + offsets[i])
                val s = if (isUtf8) {
                    val l = readUleb128(buf); readUleb128(buf) // skip
                    val b = ByteArray(l); buf.get(b); String(b, Charsets.UTF_8)
                } else {
                    val l = buf.short.toInt() and 0xFFFF
                    val b = ByteArray(l * 2); buf.get(b); String(b, Charsets.UTF_16LE)
                }
                buf.position(savedPos)
                s
            } catch (_: Exception) { "" }
        }
    }

    private fun readUleb128(buf: ByteBuffer): Int {
        var r = 0; var s = 0
        while (buf.hasRemaining()) {
            val b = buf.get().toInt() and 0xFF
            r = r or ((b and 0x7F) shl s)
            if (b and 0x80 == 0) return r
            s += 7
        }
        return r
    }
}
