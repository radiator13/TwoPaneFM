package com.twopane.fm.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.twopane.fm.viewmodel.FileExplorerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Browser for JADX-decompiled Java source files.
 * Allows navigating the decompiled source tree and opening files in the text editor.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JavaBrowserScreen(
    javaDir: String,
    viewModel: FileExplorerViewModel,
    onBack: () -> Unit,
    onOpenFile: (String) -> Unit
) {
    // The sources directory from JADX output
    val sourcesDir = remember { File(javaDir, "sources") }
    val resourcesDir = remember { File(javaDir, "resources") }

    var currentDir by remember { mutableStateOf(sourcesDir) }
    var entries by remember { mutableStateOf(listOf<JavaEntry>()) }
    var showMode by remember { mutableStateOf(BrowseMode.SOURCES) } // sources or resources

    // Relative path for display
    val basePath = if (showMode == BrowseMode.SOURCES) sourcesDir.absolutePath else resourcesDir.absolutePath
    val relativePath = remember(currentDir, showMode) {
        currentDir.absolutePath.removePrefix(basePath).removePrefix("/").ifEmpty { "/" }
    }

    // Load entries when directory or mode changes
    LaunchedEffect(currentDir, showMode) {
        withContext(Dispatchers.IO) {
            val targetDir = if (showMode == BrowseMode.SOURCES) sourcesDir else resourcesDir
            if (!targetDir.exists()) {
                entries = emptyList()
                return@withContext
            }
            // If switching mode, reset currentDir
            if (!currentDir.absolutePath.startsWith(targetDir.absolutePath)) {
                currentDir = targetDir
                return@withContext
            }
            val list = mutableListOf<JavaEntry>()
            currentDir.listFiles()?.sortedWith(
                compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() }
            )?.forEach { f ->
                list.add(JavaEntry(
                    name = f.name,
                    path = f.absolutePath,
                    isDirectory = f.isDirectory,
                    size = if (f.isDirectory) 0 else f.length(),
                    extension = f.extension
                ))
            }
            entries = list
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            if (showMode == BrowseMode.SOURCES) "Java Sources" else "Resources",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            relativePath,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        val parent = currentDir.parentFile
                        if (parent != null && currentDir.absolutePath != basePath) {
                            currentDir = parent
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    // Toggle sources/resources
                    FilterChip(
                        selected = showMode == BrowseMode.SOURCES,
                        onClick = {
                            showMode = BrowseMode.SOURCES
                            currentDir = sourcesDir
                        },
                        label = { Text("Java", style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.height(28.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    FilterChip(
                        selected = showMode == BrowseMode.RESOURCES,
                        onClick = {
                            showMode = BrowseMode.RESOURCES
                            currentDir = resourcesDir
                        },
                        label = { Text("Res", style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.height(28.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Count info
            val fileCount = entries.count { !it.isDirectory }
            val dirCount = entries.count { it.isDirectory }
            Text(
                "$dirCount folders, $fileCount files",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )

            if (entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.FolderOff,
                            null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (!sourcesDir.exists()) "No decompiled sources found.\nDecompile the APK first."
                            else "Empty directory",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(entries, key = { it.path }) { entry ->
                        JavaEntryRow(
                            entry = entry,
                            onClick = {
                                if (entry.isDirectory) {
                                    currentDir = File(entry.path)
                                } else {
                                    onOpenFile(entry.path)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun JavaEntryRow(entry: JavaEntry, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = when {
                entry.isDirectory -> Icons.Default.Folder
                entry.extension == "java" -> Icons.Default.Code
                entry.extension == "xml" -> Icons.Default.Description
                entry.extension in setOf("png", "jpg", "webp", "gif") -> Icons.Default.Image
                else -> Icons.Default.InsertDriveFile
            },
            contentDescription = null,
            tint = when {
                entry.isDirectory -> Color(0xFFFFA726)
                entry.extension == "java" -> Color(0xFF4CAF50)
                entry.extension == "xml" -> Color(0xFF2196F3)
                else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            },
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontFamily = if (entry.extension == "java") FontFamily.Monospace else FontFamily.Default
            )
            if (!entry.isDirectory) {
                Text(
                    text = entry.extension.uppercase().ifEmpty { "FILE" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }
        }
        if (!entry.isDirectory && entry.size > 0) {
            Text(
                formatSize(entry.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
    else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
}

private data class JavaEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val extension: String
)

private enum class BrowseMode { SOURCES, RESOURCES }
