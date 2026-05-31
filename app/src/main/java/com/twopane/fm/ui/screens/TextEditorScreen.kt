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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.twopane.fm.ui.components.EditorUndoStack
import com.twopane.fm.ui.components.UnifiedCodeEditor
import com.twopane.fm.ui.components.detectSyntaxMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.Charset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextEditorScreen(
    filePath: String,
    viewModel: FileExplorerViewModel,
    readOnly: Boolean = false,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val fileName = remember { filePath.substringAfterLast("/") }
    val displayPath = remember { filePath }
    val syntaxMode = remember { detectSyntaxMode(fileName) }

    var fileContent by remember { mutableStateOf("") }
    var editedContent by remember { mutableStateOf("") }
    var isModified by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    var statusText by remember { mutableStateOf("") }
    var isBusy by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val undoStack = remember { EditorUndoStack() }

    // Encoding state
    var selectedEncoding by remember { mutableStateOf("UTF-8") }
    var showEncodingMenu by remember { mutableStateOf(false) }
    val encodings = listOf("UTF-8", "UTF-16", "UTF-16LE", "UTF-16BE", "ISO-8859-1", "US-ASCII")

    // Word wrap state
    var wordWrap by remember { mutableStateOf(true) }

    // Go-to-line dialog
    var showGoToLine by remember { mutableStateOf(false) }
    var goToLineInput by remember { mutableStateOf("") }

    // Cursor position
    var cursorLine by remember { mutableIntStateOf(1) }
    var cursorCol by remember { mutableIntStateOf(1) }

    // File size warning
    var showSizeWarning by remember { mutableStateOf(false) }
    var fileSizeKB by remember { mutableLongStateOf(0L) }

    LaunchedEffect(filePath) {
        withContext(Dispatchers.IO) {
            try {
                val file = File(filePath)
                if (file.exists()) {
                    fileSizeKB = file.length() / 1024

                    // Auto-detect encoding
                    val detected = detectEncoding(file)
                    selectedEncoding = detected

                    val charset = Charset.forName(detected)
                    InputStreamReader(file.inputStream(), charset).use { reader ->
                        fileContent = reader.readText()
                    }

                    if (fileContent.length > 5_000_000) {
                        showSizeWarning = true
                    }

                    editedContent = fileContent
                    undoStack.push(fileContent)
                    loadError = null
                } else {
                    loadError = "File not found: $filePath"
                    fileContent = ""; editedContent = ""
                }
            } catch (e: Exception) {
                loadError = "Error reading: ${e.message}"
                fileContent = ""; editedContent = ""
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
                        Text(displayPath, style = MaterialTheme.typography.labelSmall,
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
                    // Word wrap toggle
                    IconButton(onClick = { wordWrap = !wordWrap }) {
                        Icon(
                            if (wordWrap) Icons.Default.WrapText else Icons.Default.TextSnippet,
                            "Word Wrap",
                            tint = if (wordWrap) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }

                    // Go to line
                    IconButton(onClick = { showGoToLine = true }) {
                        Icon(Icons.Default.FormatListNumbered, "Go to Line")
                    }

                    // Encoding selector
                    Box {
                        IconButton(onClick = { showEncodingMenu = true }) {
                            Icon(Icons.Default.TextFormat, "Encoding",
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                        DropdownMenu(expanded = showEncodingMenu,
                            onDismissRequest = { showEncodingMenu = false }) {
                            encodings.forEach { enc ->
                                DropdownMenuItem(
                                    text = {
                                        Text(enc, style = MaterialTheme.typography.bodyMedium,
                                            color = if (enc == selectedEncoding) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurface)
                                    },
                                    onClick = {
                                        showEncodingMenu = false
                                        // Reload with new encoding
                                        scope.launch {
                                            selectedEncoding = enc
                                            withContext(Dispatchers.IO) {
                                                try {
                                                    val charset = Charset.forName(enc)
                                                    InputStreamReader(File(filePath).inputStream(), charset).use { reader ->
                                                        fileContent = reader.readText()
                                                    }
                                                    editedContent = fileContent
                                                    undoStack.clear()
                                                    undoStack.push(fileContent)
                                                } catch (e: Exception) {
                                                    statusText = "Encoding error: ${e.message}"
                                                }
                                            }
                                        }
                                    },
                                    leadingIcon = if (enc == selectedEncoding) {
                                        { Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary) }
                                    } else null
                                )
                            }
                        }
                    }

                    if (readOnly) {
                        Surface(shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.padding(end = 8.dp)) {
                            Text("READ ONLY", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                        }
                    } else {
                        IconButton(onClick = {
                            scope.launch {
                                isBusy = true; statusText = "Saving..."
                                val result = withContext(Dispatchers.IO) {
                                    try {
                                        val charset = Charset.forName(selectedEncoding)
                                        File(filePath).outputStream().use { out ->
                                            InputStreamReader(fileContent.byteInputStream(), Charsets.UTF_8).use { reader ->
                                                // Write with selected encoding
                                                java.io.OutputStreamWriter(out, charset).use { writer ->
                                                    writer.write(editedContent)
                                                }
                                            }
                                        }
                                        fileContent = editedContent
                                        isModified = false; "Saved ($selectedEncoding)"
                                    } catch (e: Exception) { "Error: ${e.message}" }
                                }
                                statusText = result; isBusy = false
                            }
                        }, enabled = isModified) {
                            Icon(Icons.Default.Save, "Save",
                                tint = if (isModified) Color(0xFF4CAF50)
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
                        }
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

            when {
                isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                loadError != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.ErrorOutline, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(16.dp))
                        Text(loadError!!, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    }
                }
                else -> UnifiedCodeEditor(
                    content = editedContent,
                    onContentChange = if (readOnly) null else { newContent ->
                        editedContent = newContent
                        isModified = newContent != fileContent
                    },
                    syntaxMode = syntaxMode,
                    undoStack = if (readOnly) null else undoStack,
                    wordWrap = wordWrap,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Status bar with cursor position
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    val lineCount = editedContent.count { it == '\n' } + 1
                    val charCount = editedContent.length
                    Text("$lineCount lines, $charCount chars",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    Spacer(Modifier.weight(1f))
                    Text("Ln $cursorLine, Col $cursorCol",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    Spacer(Modifier.width(8.dp))
                    Text(selectedEncoding,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                    if (isModified) {
                        Spacer(Modifier.width(8.dp))
                        Text("(modified)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f))
                    }
                }
            }
        }
    }

    // Go-to-Line Dialog
    if (showGoToLine) {
        val lineCount = editedContent.count { it == '\n' } + 1
        AlertDialog(
            onDismissRequest = { showGoToLine = false },
            title = { Text("Go to Line") },
            text = {
                Column {
                    Text("Enter line number (1-$lineCount):",
                        style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = goToLineInput,
                        onValueChange = { goToLineInput = it.filter { c -> c.isDigit() } },
                        label = { Text("Line number") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val line = goToLineInput.toIntOrNull()
                    if (line != null && line in 1..lineCount) {
                        // Calculate cursor position for the target line
                        val lines = editedContent.split("\n")
                        var pos = 0
                        for (i in 0 until minOf(line - 1, lines.size)) {
                            pos += lines[i].length + 1 // +1 for newline
                        }
                        cursorLine = line
                        cursorCol = 1
                        statusText = "Jumped to line $line"
                    } else {
                        statusText = "Invalid line number"
                    }
                    showGoToLine = false
                    goToLineInput = ""
                }) { Text("Go") }
            },
            dismissButton = {
                TextButton(onClick = { showGoToLine = false; goToLineInput = "" }) { Text("Cancel") }
            }
        )
    }

    // File size warning dialog
    if (showSizeWarning) {
        AlertDialog(
            onDismissRequest = { showSizeWarning = false },
            title = { Text("Large File") },
            text = { Text("This file is ${fileSizeKB}KB. It may take a moment to load and edit.") },
            confirmButton = {
                TextButton(onClick = { showSizeWarning = false }) { Text("OK") }
            }
        )
    }
}

private fun detectEncoding(file: File): String {
    val bytes = file.readBytes()
    if (bytes.size < 2) return "UTF-8"

    // Check BOM
    if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return "UTF-16LE"
    if (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return "UTF-16BE"
    if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) return "UTF-8"

    // Try to detect UTF-16 by looking for null bytes in ASCII range
    var nullCount = 0
    val sampleSize = minOf(bytes.size, 1024)
    for (i in 0 until sampleSize step 2) {
        if (bytes[i] == 0.toByte() && i + 1 < sampleSize && bytes[i + 1] != 0.toByte()) nullCount++
    }
    if (nullCount > sampleSize / 8) return "UTF-16"

    return "UTF-8"
}
