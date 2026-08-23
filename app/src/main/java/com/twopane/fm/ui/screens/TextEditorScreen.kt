package com.twopane.fm.ui.screens

import androidx.activity.compose.BackHandler
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
import com.twopane.fm.ui.components.EditorUndoStack
import com.twopane.fm.ui.components.VirtualizedCodeEditor
import com.twopane.fm.ui.components.detectSyntaxMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    val syntaxMode = remember { detectSyntaxMode(fileName) }

    var lines by remember { mutableStateOf(listOf<String>()) }
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
    var jumpToLine by remember { mutableIntStateOf(-1) }

    // Save As dialog
    var showSaveAs by remember { mutableStateOf(false) }
    var saveAsPath by remember { mutableStateOf(filePath) }

    // Dirty-state guard
    var showDiscardConfirm by remember { mutableStateOf(false) }

    // Cursor position (line-based)
    var cursorLine by remember { mutableIntStateOf(1) }
    var cursorCol by remember { mutableIntStateOf(1) }

    // File size
    var fileSizeKB by remember { mutableLongStateOf(0L) }
    var lineCount by remember { mutableIntStateOf(0) }
    var charCount by remember { mutableLongStateOf(0L) }

    // Track original content for dirty detection
    var originalContent by remember { mutableStateOf("") }

    fun loadContent(path: String, encoding: String, announce: String?) {
        scope.launch {
            isBusy = true
            if (announce != null) statusText = announce
            withContext(Dispatchers.IO) {
                try {
                    val file = File(path)
                    if (!file.exists()) {
                        loadError = "File not found: $path"
                        lines = listOf()
                    } else {
                        fileSizeKB = file.length() / 1024
                        val charset = Charset.forName(encoding)
                        val content = InputStreamReader(file.inputStream(), charset).use { it.readText() }
                        originalContent = content
                        lines = content.split("\n")
                        lineCount = lines.size
                        charCount = content.length.toLong()
                        jumpToLine = -1
                        undoStack.clear()
                        undoStack.push(content)
                        loadError = null
                    }
                } catch (e: Exception) {
                    loadError = "Error reading: ${e.message}"
                    lines = listOf()
                }
            }
            isModified = false
            isBusy = false
        }
    }

    // Load file
    LaunchedEffect(filePath) {
        withContext(Dispatchers.IO) {
            try {
                val file = File(filePath)
                if (!file.exists()) {
                    loadError = "File not found: $filePath"
                    isLoading = false
                    return@withContext
                }

                fileSizeKB = file.length() / 1024
                selectedEncoding = detectEncoding(file)

                val charset = Charset.forName(selectedEncoding)
                val content = InputStreamReader(file.inputStream(), charset).use { it.readText() }
                originalContent = content
                lines = content.split("\n")
                lineCount = lines.size
                charCount = content.length.toLong()

                undoStack.push(content)
                loadError = null
            } catch (e: Exception) {
                loadError = "Error reading: ${e.message}"
                lines = listOf()
            }
            isLoading = false
        }
    }

    // Derive full content from lines
    val fullContent = remember(lines) { lines.joinToString("\n") }

    // Track modifications + push undo states (debounced)
    LaunchedEffect(fullContent) {
        isModified = fullContent != originalContent
        delay(350)
        undoStack.push(fullContent)
    }

    fun requestBack() {
        if (isModified && !readOnly) showDiscardConfirm = true else onBack()
    }

    BackHandler(enabled = !showDiscardConfirm) { requestBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            fileName + if (isModified) " •" else "",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Text(filePath, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { requestBack() }) {
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
                                        loadContent(filePath, enc, null)
                                    },
                                    leadingIcon = if (enc == selectedEncoding) {
                                        { Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary) }
                                    } else null
                                )
                            }
                        }
                    }

                    // More options menu
                    var showMoreMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { showMoreMenu = true }) {
                            Icon(Icons.Default.MoreVert, "More")
                        }
                        DropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Save As...") },
                                onClick = { showMoreMenu = false; showSaveAs = true },
                                leadingIcon = { Icon(Icons.Default.SaveAs, null) }
                            )
                            DropdownMenuItem(
                                text = { Text("Reload") },
                                onClick = {
                                    showMoreMenu = false
                                    loadContent(filePath, selectedEncoding, "Reloading...")
                                },
                                leadingIcon = { Icon(Icons.Default.Refresh, null) }
                            )
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
                        // Save button
                        IconButton(onClick = {
                            scope.launch {
                                isBusy = true; statusText = "Saving..."
                                val result = withContext(Dispatchers.IO) {
                                    try {
                                        val charset = Charset.forName(selectedEncoding)
                                        File(filePath).outputStream().use { out ->
                                            java.io.OutputStreamWriter(out, charset).use { writer ->
                                                writer.write(fullContent)
                                            }
                                        }
                                        originalContent = fullContent
                                        isModified = false
                                        "Saved ($selectedEncoding)"
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
            // Status bar
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
                else -> VirtualizedCodeEditor(
                    lines = lines,
                    onLineChange = if (readOnly) null else { idx, newLine ->
                        // Multi-line safe: Enter/paste may embed '\n' inside one row
                        lines = if (newLine.contains('\n')) {
                            val parts = newLine.split('\n')
                            lines.toMutableList().apply {
                                removeAt(idx)
                                addAll(idx, parts)
                            }
                        } else {
                            lines.toMutableList().apply { set(idx, newLine) }
                        }
                        lineCount = lines.size
                        charCount = lines.sumOf { it.length + 1 }.toLong()
                    },
                    onContentChange = if (readOnly) null else { newContent ->
                        lines = newContent.split("\n")
                        lineCount = lines.size
                        charCount = newContent.length.toLong()
                    },
                    syntaxMode = syntaxMode,
                    undoStack = if (readOnly) null else undoStack,
                    wordWrap = wordWrap,
                    highlightLineNum = jumpToLine - 1, // 1-based UI → 0-based index
                    scrollToLine = jumpToLine - 1,
                    onVisibleLineChange = { firstVisible ->
                        if (jumpToLine < 0) cursorLine = firstVisible
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Bottom status bar
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("$lineCount lines, $charCount chars",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    if (fileSizeKB > 0) {
                        Spacer(Modifier.width(8.dp))
                        Text("${fileSizeKB}KB",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                    }
                    Spacer(Modifier.weight(1f))
                    Text(if (cursorCol > 1 || cursorLine > 1) "Ln $cursorLine" else "",
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
                        jumpToLine = line
                        cursorLine = line
                        cursorCol = 1
                        statusText = "Jumped to line $line"
                    } else {
                        statusText = "Invalid line number"
                    }
                    showGoToLine = false; goToLineInput = ""
                }) { Text("Go") }
            },
            dismissButton = {
                TextButton(onClick = { showGoToLine = false; goToLineInput = "" }) { Text("Cancel") }
            }
        )
    }

    // Save As Dialog
    if (showSaveAs) {
        AlertDialog(
            onDismissRequest = { showSaveAs = false },
            title = { Text("Save As") },
            text = {
                Column {
                    Text("Enter file path:", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = saveAsPath,
                        onValueChange = { saveAsPath = it },
                        label = { Text("File path") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        isBusy = true; statusText = "Saving..."
                        val result = withContext(Dispatchers.IO) {
                            try {
                                val charset = Charset.forName(selectedEncoding)
                                File(saveAsPath).parentFile?.mkdirs()
                                File(saveAsPath).outputStream().use { out ->
                                    java.io.OutputStreamWriter(out, charset).use { writer ->
                                        writer.write(fullContent)
                                    }
                                }
                                originalContent = fullContent
                                isModified = false
                                "Saved to $saveAsPath"
                            } catch (e: Exception) { "Error: ${e.message}" }
                        }
                        statusText = result; isBusy = false
                    }
                    showSaveAs = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showSaveAs = false }) { Text("Cancel") }
            }
        )
    }

    // Discard-changes confirmation
    if (showDiscardConfirm) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = { Text("Unsaved changes") },
            text = { Text("Save changes to $fileName before leaving?") },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardConfirm = false
                    scope.launch {
                        isBusy = true
                        val ok = withContext(Dispatchers.IO) {
                            try {
                                val charset = Charset.forName(selectedEncoding)
                                File(filePath).outputStream().use { out ->
                                    java.io.OutputStreamWriter(out, charset).use { w -> w.write(fullContent) }
                                }
                                true
                            } catch (_: Exception) { false }
                        }
                        isModified = !ok
                        isBusy = false
                        if (ok) onBack() else statusText = "Save failed — changes kept"
                    }
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDiscardConfirm = false
                    isModified = false
                    onBack()
                }) { Text("Discard") }
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
