package com.twopane.fm.util

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

enum class FileCategory {
    DEX,           // .dex files
    XML_TEXT,      // .xml files (text XML in res/)
    XML_BINARY,    // AndroidManifest.xml or binary XML at root
    RESOURCE,      // resources.arsc
    NATIVE_LIB,    // .so files
    IMAGE,         // .png, .jpg, .webp, .gif
    TEXT,          // .txt, .json, .properties, .cfg, .ini, .log, .md, .smali, .java, .kt, .py, .sh, .html, .css, .js
    ARCHIVE,       // .zip, .jar, .apk, .aab
    CERT,          // .RSA, .DSA, .SF, .MF in META-INF
    UNKNOWN
}

object FileType {

    private val textExtensions = setOf(
        "txt", "json", "properties", "cfg", "ini", "log", "md",
        "smali", "java", "kt", "py", "sh", "html", "css", "js",
        "xml", "yaml", "yml", "toml", "gradle", "pro", "gitignore",
        "csv", "tsv", "sql", "rb", "go", "rs", "c", "cpp", "h", "hpp",
        "bat", "cmd", "ps1", "lua", "php", "pl", "r", "swift", "dart",
        "tf", "conf", "config", "env", "rc", "makefile", "mk"
    )

    private val imageExtensions = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "svg", "ico")

    private val archiveExtensions = setOf("zip", "jar", "apk", "aab", "apks", "xapk", "war", "ear")

    private val certExtensions = setOf("rsa", "dsa", "sf", "mf")

    fun detect(fileName: String, isInsideApk: Boolean = false): FileCategory {
        val name = fileName.lowercase()
        val ext = name.substringAfterLast('.', "").lowercase()
        val path = name // for path-based checks

        return when {
            // DEX files
            ext == "dex" -> FileCategory.DEX

            // resources.arsc
            name == "resources.arsc" -> FileCategory.RESOURCE

            // Native libraries
            ext == "so" -> FileCategory.NATIVE_LIB

            // Images
            ext in imageExtensions -> FileCategory.IMAGE

            // Archives
            ext in archiveExtensions -> FileCategory.ARCHIVE

            // META-INF certificates
            (path.contains("meta-inf/") || path.startsWith("meta-inf/")) && ext in certExtensions -> FileCategory.CERT

            // XML files
            ext == "xml" -> {
                if (isInsideApk) {
                    // Inside APK, XML files in res/ are usually text XML after extraction
                    // but AndroidManifest.xml and other root XMLs are binary
                    if (path.startsWith("res/") || path.startsWith("r/")) {
                        FileCategory.XML_TEXT
                    } else {
                        FileCategory.XML_BINARY
                    }
                } else {
                    FileCategory.XML_TEXT
                }
            }

            // Text files
            ext in textExtensions -> FileCategory.TEXT

            // Files with no extension but known names
            name in setOf("makefile", "dockerfile", "license", "readme", "changelog") -> FileCategory.TEXT

            // Default
            else -> FileCategory.UNKNOWN
        }
    }

    fun getIcon(category: FileCategory): ImageVector = when (category) {
        FileCategory.DEX -> Icons.Default.Code
        FileCategory.XML_TEXT -> Icons.Default.Description
        FileCategory.XML_BINARY -> Icons.Default.DataObject
        FileCategory.RESOURCE -> Icons.Default.Palette
        FileCategory.NATIVE_LIB -> Icons.Default.Memory
        FileCategory.IMAGE -> Icons.Default.Image
        FileCategory.TEXT -> Icons.AutoMirrored.Filled.TextSnippet
        FileCategory.ARCHIVE -> Icons.Default.FolderZip
        FileCategory.CERT -> Icons.Default.Security
        FileCategory.UNKNOWN -> Icons.AutoMirrored.Filled.InsertDriveFile
    }

    fun getColor(category: FileCategory): Color = when (category) {
        FileCategory.DEX -> Color(0xFF4CAF50)
        FileCategory.XML_TEXT -> Color(0xFF2196F3)
        FileCategory.XML_BINARY -> Color(0xFF1565C0)
        FileCategory.RESOURCE -> Color(0xFFFF5722)
        FileCategory.NATIVE_LIB -> Color(0xFF9C27B0)
        FileCategory.IMAGE -> Color(0xFFE91E63)
        FileCategory.TEXT -> Color(0xFF795548)
        FileCategory.ARCHIVE -> Color(0xFFFF9800)
        FileCategory.CERT -> Color(0xFF607D8B)
        FileCategory.UNKNOWN -> Color(0xFF9E9E9E)
    }

    fun getDescription(category: FileCategory): String = when (category) {
        FileCategory.DEX -> "DEX Executable"
        FileCategory.XML_TEXT -> "XML Resource"
        FileCategory.XML_BINARY -> "Binary XML"
        FileCategory.RESOURCE -> "Compiled Resources"
        FileCategory.NATIVE_LIB -> "Native Library"
        FileCategory.IMAGE -> "Image"
        FileCategory.TEXT -> "Text File"
        FileCategory.ARCHIVE -> "Archive"
        FileCategory.CERT -> "Certificate/Signature"
        FileCategory.UNKNOWN -> "File"
    }

    fun getMimeType(fileName: String): String {
        val ext = fileName.lowercase().substringAfterLast('.', "").lowercase()
        return when (ext) {
            "txt" -> "text/plain"
            "json" -> "application/json"
            "xml" -> "text/xml"
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "js" -> "application/javascript"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "jar" -> "application/java-archive"
            "apk" -> "application/vnd.android.package-archive"
            "dex" -> "application/octet-stream"
            "so" -> "application/octet-stream"
            "properties" -> "text/plain"
            "smali" -> "text/plain"
            "java" -> "text/x-java-source"
            "kt" -> "text/x-kotlin"
            "py" -> "text/x-python"
            "sh" -> "text/x-shellscript"
            "md" -> "text/markdown"
            "log" -> "text/plain"
            "ini", "cfg", "conf" -> "text/plain"
            else -> "*/*"
        }
    }
}
