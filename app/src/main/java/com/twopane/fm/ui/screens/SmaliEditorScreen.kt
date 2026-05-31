package com.twopane.fm.ui.screens

import com.twopane.fm.viewmodel.FileExplorerViewModel
import androidx.compose.foundation.layout.*
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
import com.twopane.fm.ui.components.EditorUndoStack
import com.twopane.fm.ui.components.SyntaxMode
import com.twopane.fm.ui.components.UnifiedCodeEditor
import com.twopane.fm.util.EmbeddedTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmaliEditorScreen(
    smaliFilePath: String,
    workingDir: String,
    viewModel: FileExplorerViewModel,
    onBack: () -> Unit
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val app = ctx.applicationContext as TwoPaneApp
    val scope = rememberCoroutineScope()

    val apkPath = viewModel.apkEditorPath
    val smaliRootDir = workingDir
    val fileName = remember { smaliFilePath.substringAfterLast("/") }
    val relativePath = remember { smaliFilePath.removePrefix(smaliRootDir).removePrefix("/") }

    var fileContent by remember { mutableStateOf("") }
    var editedContent by remember { mutableStateOf("") }
    var isModified by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    var statusText by remember { mutableStateOf("") }
    var isBusy by remember { mutableStateOf(false) }
    val undoStack = remember { EditorUndoStack() }

    var showJava by remember { mutableStateOf(false) }
    var javaContent by remember { mutableStateOf("") }
    var javaLoading by remember { mutableStateOf(false) }

    LaunchedEffect(smaliFilePath) {
        withContext(Dispatchers.IO) {
            try {
                fileContent = File(smaliFilePath).readText()
                editedContent = fileContent
                undoStack.push(fileContent)
            } catch (e: Exception) {
                fileContent = "// Error: ${e.message}"
                editedContent = fileContent
            }
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(fileName, style = MaterialTheme.typography.titleMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(relativePath, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        showJava = !showJava
                        if (showJava && javaContent.isEmpty()) {
                            scope.launch {
                                javaLoading = true
                                javaContent = withContext(Dispatchers.IO) {
                                    decompileSmaliToJava(smaliFilePath, smaliRootDir, apkPath)
                                }
                                javaLoading = false
                            }
                        }
                    }) {
                        Icon(Icons.Default.Coffee, "View Java",
                            tint = if (showJava) Color(0xFFFF9800) else MaterialTheme.colorScheme.onSurface)
                    }
                    IconButton(onClick = {
                        scope.launch {
                            isBusy = true; statusText = "Saving..."
                            val result = withContext(Dispatchers.IO) {
                                try {
                                    File(smaliFilePath).writeText(editedContent)
                                    fileContent = editedContent
                                    isModified = false; "Saved"
                                } catch (e: Exception) { "Error: ${e.message}" }
                            }
                            statusText = result; isBusy = false
                        }
                    }, enabled = isModified) {
                        Icon(Icons.Default.Save, "Save",
                            tint = if (isModified) Color(0xFF4CAF50)
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer)
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (statusText.isNotBlank()) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (isBusy) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(statusText, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            if (isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else if (showJava) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Surface(color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.weight(1f).fillMaxWidth()) {
                        Column {
                            Text("Smali (editable)", style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF4CAF50),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                            UnifiedCodeEditor(
                                content = editedContent,
                                onContentChange = { editedContent = it; isModified = true },
                                syntaxMode = SyntaxMode.SMALI,
                                undoStack = undoStack,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    HorizontalDivider(thickness = 2.dp, color = Color(0xFFFF9800))

                    Surface(color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.weight(1f).fillMaxWidth()) {
                        Column {
                            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text("Java (read-only, JADX)", style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFFFF9800), modifier = Modifier.weight(1f))
                                if (javaLoading) {
                                    CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
                                }
                            }
                            if (javaLoading) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("Decompiling...", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                                }
                            } else {
                                UnifiedCodeEditor(
                                    content = javaContent.ifEmpty { "// Java decompilation will appear here" },
                                    onContentChange = null,
                                    syntaxMode = SyntaxMode.JAVA,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                }
            } else {
                UnifiedCodeEditor(
                    content = editedContent,
                    onContentChange = { editedContent = it; isModified = true },
                    syntaxMode = SyntaxMode.SMALI,
                    undoStack = undoStack,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

private suspend fun decompileSmaliToJava(
    smaliFilePath: String,
    smaliRootDir: String,
    apkPath: String
): String = withContext(Dispatchers.IO) {
    try {
        val smaliContent = File(smaliFilePath).readText()
        val classMatch = Regex("""\.class\s+(.+)""").find(smaliContent)
        val classDesc = classMatch?.groupValues?.get(1)?.trim()
            ?: return@withContext "// Could not find .class directive"
        val className = classDesc.removePrefix("L").removeSuffix(";")
        val result = EmbeddedTools.jadxGetJavaCode(apkPath, className)
        result.getOrDefault("// JADX decompilation failed for $className")
    } catch (e: Exception) { "// Error: ${e.message}" }
}
