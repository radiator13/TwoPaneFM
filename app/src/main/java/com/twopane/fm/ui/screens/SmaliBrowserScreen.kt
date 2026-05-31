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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.twopane.fm.TwoPaneApp
import com.twopane.fm.util.EmbeddedTools
import com.twopane.fm.viewmodel.FileExplorerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmaliBrowserScreen(
    smaliDir: String,
    dexName: String,
    viewModel: FileExplorerViewModel,
    onBack: () -> Unit,
    onOpenFile: (String) -> Unit
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val app = ctx.applicationContext as TwoPaneApp
    val loader = app.toolLoader
    val scope = rememberCoroutineScope()

    // The APK path from the editor state
    val apkPath = viewModel.apkEditorPath

    var currentDir by remember { mutableStateOf(smaliDir) }
    var entries by remember { mutableStateOf(listOf<SmaliEntry>()) }
    var statusText by remember { mutableStateOf("") }
    var isBusy by remember { mutableStateOf(false) }
    var showRecompileDialog by remember { mutableStateOf(false) }

    // Build relative path for display
    val relativePath = remember(currentDir) {
        currentDir.removePrefix(smaliDir).removePrefix("/").ifEmpty { "/" }
    }

    LaunchedEffect(currentDir) {
        withContext(Dispatchers.IO) {
            val dir = File(currentDir)
            val list = mutableListOf<SmaliEntry>()
            dir.listFiles()?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name })?.forEach { f ->
                list.add(SmaliEntry(
                    name = f.name,
                    path = f.absolutePath,
                    isDirectory = f.isDirectory,
                    size = if (f.isDirectory) 0 else f.length()
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
                        Text("Smali Browser", style = MaterialTheme.typography.titleMedium)
                        Text(relativePath, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        // Navigate up within smali tree, or back to APK browser
                        val parent = File(currentDir).parent
                        if (parent != null && currentDir != smaliDir) {
                            currentDir = parent
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showRecompileDialog = true }) {
                        Icon(Icons.Default.PlayArrow, "Recompile", tint = Color(0xFF4CAF50))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (statusText.isNotBlank()) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (isBusy) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(statusText, style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.weight(1f))
                    }
                }
            }

            // File count
            val fileCount = entries.count { !it.isDirectory }
            val dirCount = entries.count { it.isDirectory }
            Text(
                "$dirCount folders, $fileCount files",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(entries, key = { it.path }) { entry ->
                    SmaliEntryRow(
                        entry = entry,
                        onClick = {
                            if (entry.isDirectory) {
                                currentDir = entry.path
                            } else if (entry.name.endsWith(".smali")) {
                                onOpenFile(entry.path)
                            }
                        }
                    )
                }
            }
        }
    }

    // Recompile dialog
    if (showRecompileDialog) {
        AlertDialog(
            onDismissRequest = { showRecompileDialog = false },
            title = { Text("Recompile & Rebuild") },
            text = { Text("Assemble all smali files back to DEX, replace in APK, and sign?") },
            confirmButton = {
                TextButton(onClick = {
                    showRecompileDialog = false
                    scope.launch {
                        isBusy = true
                        statusText = "Assembling smali → DEX..."
                        val dexOut = File(ctx.cacheDir, "recompiled_${System.currentTimeMillis()}.dex")

                        val assembleResult = withContext(Dispatchers.IO) {
                            EmbeddedTools.assembleSmali(smaliDir, dexOut.absolutePath)
                        }
                        assembleResult.onFailure {
                            statusText = "Assembly failed: ${it.message}"
                            isBusy = false
                            return@launch
                        }

                        statusText = "Injecting DEX into APK..."
                        val outApk = apkPath.replace(".apk", "_recompiled.apk")

                        // Determine which DEX entry to replace
                        val dexEntryName = withContext(Dispatchers.IO) {
                            java.util.zip.ZipFile(File(apkPath)).use { zip ->
                                zip.entries().asSequence()
                                    .filter { it.name.endsWith(".dex") && it.name.contains(dexName.substringAfterLast("/").substringBeforeLast(".")) }
                                    .firstOrNull()?.name
                                    ?: zip.entries().asSequence().filter { it.name.endsWith(".dex") }.firstOrNull()?.name
                                    ?: "classes.dex"
                            }
                        }

                        val rebuildResult = withContext(Dispatchers.IO) {
                            EmbeddedTools.rebuildWithModifiedDex(
                                apkPath = apkPath,
                                newDexPath = dexOut.absolutePath,
                                dexEntryName = dexEntryName,
                                outputPath = outApk,
                                nativeZipalign = loader.nativeZipalign
                            )
                        }

                        rebuildResult.onSuccess {
                            statusText = "✅ $it"
                        }
                        rebuildResult.onFailure {
                            statusText = "Rebuild failed: ${it.message}"
                        }
                        isBusy = false
                    }
                }) {
                    Text("Recompile", color = Color(0xFF4CAF50))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRecompileDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun SmaliEntryRow(entry: SmaliEntry, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = when {
                entry.isDirectory -> Icons.Default.Folder
                entry.name.endsWith(".smali") -> Icons.Default.Code
                else -> Icons.Default.InsertDriveFile
            },
            contentDescription = null,
            tint = when {
                entry.isDirectory -> Color(0xFFFFA726)
                entry.name.endsWith(".smali") -> Color(0xFF4CAF50)
                else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            },
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = entry.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (!entry.isDirectory && entry.size > 0) {
            Text(
                "${entry.size / 1024}KB",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
    }
}

private data class SmaliEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long
)
