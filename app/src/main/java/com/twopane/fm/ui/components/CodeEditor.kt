package com.twopane.fm.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ── Syntax highlighting ──────────────────────────────────────────

private object SyntaxColors {
    val keyword = Color(0xFFC792EA)
    val string = Color(0xFFC3E88D)
    val number = Color(0xFFF78C6C)
    val comment = Color(0xFF546E7A)
    val annotation = Color(0xFFFFCB6B)
    val type = Color(0xFF82AAFF)
    val operator = Color(0xFF89DDFF)
    val smaliDirective = Color(0xFFC792EA)
    val smaliRegister = Color(0xFFF07178)
    val tag = Color(0xFFF07178)
    val attrName = Color(0xFFFFCB6B)
    val default = Color(0xFFE0E0E0)
    val func = Color(0xFF82AAFF)
}

enum class SyntaxMode {
    NONE, SMALI, JAVA, XML, KOTLIN, JSON, SHELL, PYTHON
}

// ── Undo/Redo stack ─────────────────────────────────────────────

class EditorUndoStack {
    private val undoStack = ArrayDeque<String>(200)
    private val redoStack = ArrayDeque<String>(200)
    private var lastPush = ""

    fun push(state: String) {
        if (state == lastPush) return
        undoStack.addLast(state)
        if (undoStack.size > 200) undoStack.removeFirst()
        redoStack.clear()
        lastPush = state
    }

    fun undo(current: String): Pair<String, Boolean> {
        if (undoStack.size <= 1) return current to false
        redoStack.addLast(current)
        undoStack.removeLast()
        val prev = undoStack.last()
        lastPush = prev
        return prev to true
    }

    fun redo(current: String): Pair<String, Boolean> {
        if (redoStack.isEmpty()) return current to false
        val next = redoStack.removeLast()
        undoStack.addLast(next)
        lastPush = next
        return next to true
    }

    fun clear() {
        undoStack.clear(); redoStack.clear(); lastPush = ""
    }

    val canUndo: Boolean get() = undoStack.size > 1
    val canRedo: Boolean get() = redoStack.isNotEmpty()
}

// ── Line-based syntax highlighting ──────────────────────────────

fun highlightLine(line: String, mode: SyntaxMode): AnnotatedString {
    if (mode == SyntaxMode.NONE) return AnnotatedString(line)
    return buildAnnotatedString {
        when (mode) {
            SyntaxMode.SMALI -> highlightSmaliLine(this, line)
            SyntaxMode.JAVA, SyntaxMode.KOTLIN -> highlightKotlinLine(this, line)
            SyntaxMode.XML -> highlightXmlLine(this, line)
            SyntaxMode.JSON -> highlightJsonLine(this, line)
            SyntaxMode.SHELL -> highlightShellLine(this, line)
            SyntaxMode.PYTHON -> highlightPythonLine(this, line)
            SyntaxMode.NONE -> append(line)
        }
    }
}

// ── Full syntax highlighter (for small files / search) ──────────

fun highlightSyntax(code: String, mode: SyntaxMode): AnnotatedString {
    if (mode == SyntaxMode.NONE) return AnnotatedString(code)
    return buildAnnotatedString {
        val lines = code.split("\n")
        for ((i, line) in lines.withIndex()) {
            when (mode) {
                SyntaxMode.SMALI -> highlightSmaliLine(this, line)
                SyntaxMode.JAVA, SyntaxMode.KOTLIN -> highlightKotlinLine(this, line)
                SyntaxMode.XML -> highlightXmlLine(this, line)
                SyntaxMode.JSON -> highlightJsonLine(this, line)
                SyntaxMode.SHELL -> highlightShellLine(this, line)
                SyntaxMode.PYTHON -> highlightPythonLine(this, line)
                SyntaxMode.NONE -> append(line)
            }
            if (i < lines.lastIndex) append("\n")
        }
    }
}

// ── Per-language highlighters ────────────────────────────────────

private fun highlightSmaliLine(sb: AnnotatedString.Builder, line: String) {
    val trimmed = line.trimStart()
    when {
        trimmed.startsWith("#") -> sb.withStyle(SpanStyle(SyntaxColors.comment)) { sb.append(line) }
        trimmed.startsWith(".") -> sb.withStyle(SpanStyle(SyntaxColors.smaliDirective, fontWeight = FontWeight.Bold)) { sb.append(line) }
        else -> {
            var i = 0
            while (i < line.length) {
                when {
                    line.startsWith("//", i) -> { sb.withStyle(SpanStyle(SyntaxColors.comment)) { sb.append(line.substring(i)) }; return }
                    line[i] == '"' -> { val end = line.indexOf('"', i + 1).let { if (it < 0) line.length else it + 1 }; sb.withStyle(SpanStyle(SyntaxColors.string)) { sb.append(line.substring(i, end)) }; i = end }
                    line[i].isDigit() -> { var end = i; while (end < line.length && (line[end].isDigit() || line[end] in "xXabcdefABCDEF")) end++; sb.withStyle(SpanStyle(SyntaxColors.number)) { sb.append(line.substring(i, end)) }; i = end }
                    line[i] == 'v' || line[i] == 'p' -> { var end = i + 1; while (end < line.length && line[end].isDigit()) end++; if (end > i + 1) { sb.withStyle(SpanStyle(SyntaxColors.smaliRegister)) { sb.append(line.substring(i, end)) }; i = end } else { sb.append(line[i]); i++ } }
                    else -> { sb.append(line[i]); i++ }
                }
            }
        }
    }
}

private fun highlightKotlinLine(sb: AnnotatedString.Builder, line: String) {
    val keywords = setOf("abstract","actual","annotation","as","break","by","catch","class","companion","const","constructor","continue","crossinline","data","do","dynamic","else","enum","expect","external","final","finally","for","fun","get","if","import","in","init","inline","inner","interface","internal","is","it","lateinit","lazy","noinline","object","open","operator","out","override","package","private","protected","public","reified","return","sealed","set","super","suspend","tailrec","this","throw","try","typealias","val","var","vararg","when","where","while")
    val typeNames = setOf("String","Int","Long","Float","Double","Boolean","Char","Byte","Short","Unit","Any","Nothing","List","Map","Set","Array","MutableList","MutableMap","MutableSet","Result","Pair","Triple")
    var i = 0
    while (i < line.length) {
        when {
            line.startsWith("//", i) -> { sb.withStyle(SpanStyle(SyntaxColors.comment)) { sb.append(line.substring(i)) }; return }
            line.startsWith("/*", i) -> { val end = line.indexOf("*/", i + 2).let { if (it >= 0) it + 2 else line.length }; sb.withStyle(SpanStyle(SyntaxColors.comment)) { sb.append(line.substring(i, end)) }; i = end }
            line[i] == '"' -> { var j = i + 1; if (j < line.length && line[j] == '"') { j++; if (j < line.length && line[j] == '"') { j = line.indexOf("\"\"\"", j + 1).let { if (it >= 0) it + 3 else line.length } } else { j-- } } else { while (j < line.length && line[j] != '"') { if (line[j] == '\\') j++; j++ }; j = minOf(j + 1, line.length) }; sb.withStyle(SpanStyle(SyntaxColors.string)) { sb.append(line.substring(i, j)) }; i = j }
            line[i] == '\'' -> { var j = i + 1; if (j < line.length && line[j] == '\\') j++; j++; sb.withStyle(SpanStyle(SyntaxColors.string)) { sb.append(line.substring(i, minOf(j, line.length))) }; i = minOf(j, line.length) }
            line[i] == '@' -> { var end = i + 1; while (end < line.length && (line[end].isLetterOrDigit() || line[end] == '_')) end++; sb.withStyle(SpanStyle(SyntaxColors.annotation)) { sb.append(line.substring(i, end)) }; i = end }
            line[i].isDigit() -> { var end = i; while (end < line.length && (line[end].isDigit() || line[end] in "xXeE.")) end++; sb.withStyle(SpanStyle(SyntaxColors.number)) { sb.append(line.substring(i, end)) }; i = end }
            line[i].isLetter() || line[i] == '_' -> { var end = i; while (end < line.length && (line[end].isLetterOrDigit() || line[end] == '_')) end++; val word = line.substring(i, end); when { word in keywords -> sb.withStyle(SpanStyle(SyntaxColors.keyword, fontWeight = FontWeight.Bold)) { sb.append(word) }; word in typeNames -> sb.withStyle(SpanStyle(SyntaxColors.type)) { sb.append(word) }; end < line.length && line[end] == '(' -> sb.withStyle(SpanStyle(SyntaxColors.func)) { sb.append(word) }; else -> sb.append(word) }; i = end }
            line[i] in "!=<>" -> { var end = i + 1; while (end < line.length && line[end] in "=<>") end++; sb.withStyle(SpanStyle(SyntaxColors.operator)) { sb.append(line.substring(i, end)) }; i = end }
            else -> { sb.append(line[i]); i++ }
        }
    }
}

private fun highlightXmlLine(sb: AnnotatedString.Builder, line: String) {
    var i = 0
    while (i < line.length) {
        when {
            line.startsWith("<!--", i) -> { val end = line.indexOf("-->", i + 4).let { if (it >= 0) it + 3 else line.length }; sb.withStyle(SpanStyle(SyntaxColors.comment)) { sb.append(line.substring(i, end)) }; i = end }
            line[i] == '<' && (i + 1 < line.length && (line[i + 1].isLetter() || line[i + 1] == '/')) -> { var j = i + 1; if (line[j] == '/') j++; val ns = j; while (j < line.length && (line[j].isLetterOrDigit() || line[j] in "_-:.")) j++; sb.withStyle(SpanStyle(SyntaxColors.default)) { sb.append(line.substring(i, ns)) }; sb.withStyle(SpanStyle(SyntaxColors.tag, fontWeight = FontWeight.Bold)) { sb.append(line.substring(ns, j)) }; i = j }
            line[i] == '=' -> { sb.withStyle(SpanStyle(SyntaxColors.operator)) { sb.append("=") }; i++; if (i < line.length && line[i] == '"') { var end = line.indexOf('"', i + 1).let { if (it < 0) line.length - 1 else it }; sb.withStyle(SpanStyle(SyntaxColors.string)) { sb.append(line.substring(i, end + 1)) }; i = end + 1 } }
            line[i].isLetter() -> { var j = i; while (j < line.length && (line[j].isLetterOrDigit() || line[j] in "_-:.")) j++; if (j < line.length && line[j] == '=') sb.withStyle(SpanStyle(SyntaxColors.attrName)) { sb.append(line.substring(i, j)) } else sb.append(line.substring(i, j)); i = j }
            else -> { sb.append(line[i]); i++ }
        }
    }
}

private fun highlightJsonLine(sb: AnnotatedString.Builder, line: String) {
    var i = 0
    while (i < line.length) {
        when {
            line[i] == '"' -> { var j = i + 1; while (j < line.length && line[j] != '"') { if (line[j] == '\\') j++; j++ }; val end = minOf(j + 1, line.length); val str = line.substring(i, end); var k = end; while (k < line.length && line[k] == ' ') k++; if (k < line.length && line[k] == ':') sb.withStyle(SpanStyle(SyntaxColors.attrName)) { sb.append(str) } else sb.withStyle(SpanStyle(SyntaxColors.string)) { sb.append(str) }; i = end }
            line[i].isDigit() || (line[i] == '-' && i + 1 < line.length && line[i + 1].isDigit()) -> { var j = i; if (line[j] == '-') j++; while (j < line.length && (line[j].isDigit() || line[j] in ".eE+-")) j++; sb.withStyle(SpanStyle(SyntaxColors.number)) { sb.append(line.substring(i, j)) }; i = j }
            line.startsWith("true", i) || line.startsWith("false", i) || line.startsWith("null", i) -> { val w = when { line.startsWith("true", i) -> "true"; line.startsWith("false", i) -> "false"; else -> "null" }; sb.withStyle(SpanStyle(SyntaxColors.keyword)) { sb.append(w) }; i += w.length }
            else -> { sb.append(line[i]); i++ }
        }
    }
}

private fun highlightShellLine(sb: AnnotatedString.Builder, line: String) {
    val trimmed = line.trimStart()
    if (trimmed.startsWith("#")) { sb.withStyle(SpanStyle(SyntaxColors.comment)) { sb.append(line) }; return }
    val kws = setOf("if","then","else","elif","fi","for","while","do","done","case","esac","function","return","exit","export","source","alias","set","unset","local","readonly","declare")
    val builtins = setOf("echo","printf","read","test","cd","ls","grep","sed","awk","find","chmod","chown","cp","mv","rm","mkdir","cat","head","tail","sort","uniq","wc","tee","xargs","curl","wget","tar","zip","unzip","ssh","scp")
    var i = 0
    while (i < line.length) {
        when {
            line[i] == '"' -> { var j = i + 1; while (j < line.length && line[j] != '"') { if (line[j] == '\\') j++; j++ }; sb.withStyle(SpanStyle(SyntaxColors.string)) { sb.append(line.substring(i, minOf(j + 1, line.length))) }; i = minOf(j + 1, line.length) }
            line[i] == '\'' -> { var j = i + 1; while (j < line.length && line[j] != '\'') j++; sb.withStyle(SpanStyle(SyntaxColors.string)) { sb.append(line.substring(i, minOf(j + 1, line.length))) }; i = minOf(j + 1, line.length) }
            line[i] == '$' -> { var j = i + 1; if (j < line.length && line[j] == '{') { j++; while (j < line.length && line[j] != '}') j++; j = minOf(j + 1, line.length) } else { while (j < line.length && (line[j].isLetterOrDigit() || line[j] == '_')) j++ }; sb.withStyle(SpanStyle(SyntaxColors.type)) { sb.append(line.substring(i, j)) }; i = j }
            line[i].isDigit() -> { var j = i; while (j < line.length && (line[j].isDigit() || line[j] in "xXoObB")) j++; sb.withStyle(SpanStyle(SyntaxColors.number)) { sb.append(line.substring(i, j)) }; i = j }
            line[i].isLetter() || line[i] == '_' -> { var j = i; while (j < line.length && (line[j].isLetterOrDigit() || line[j] in "_./-")) j++; val word = line.substring(i, j); when { word in kws -> sb.withStyle(SpanStyle(SyntaxColors.keyword)) { sb.append(word) }; word in builtins -> sb.withStyle(SpanStyle(SyntaxColors.func)) { sb.append(word) }; j < line.length && line[j] == '(' -> sb.withStyle(SpanStyle(SyntaxColors.func)) { sb.append(word) }; else -> sb.append(word) }; i = j }
            line[i] in "|&;><!{}()[]" -> { sb.withStyle(SpanStyle(SyntaxColors.operator)) { sb.append(line[i]) }; i++ }
            else -> { sb.append(line[i]); i++ }
        }
    }
}

private fun highlightPythonLine(sb: AnnotatedString.Builder, line: String) {
    val trimmed = line.trimStart()
    if (trimmed.startsWith("#")) { sb.withStyle(SpanStyle(SyntaxColors.comment)) { sb.append(line) }; return }
    if (trimmed.startsWith("\"\"\"") || trimmed.startsWith("'''")) { sb.withStyle(SpanStyle(SyntaxColors.string)) { sb.append(line) }; return }
    val kws = setOf("def","class","if","elif","else","for","while","return","import","from","as","try","except","finally","raise","with","yield","lambda","pass","break","continue","and","or","not","in","is","True","False","None","global","nonlocal","assert","del","print")
    val builtins = setOf("len","range","str","int","float","list","dict","set","tuple","type","isinstance","super","self","enumerate","zip","map","filter","sorted","reversed","open","input","abs","max","min","sum","any","all","bool","bytes","callable","dir","getattr","hasattr","id","hex","oct","ord","chr","hash","repr","round")
    var i = 0
    while (i < line.length) {
        when {
            line[i] == '"' -> { var j = i + 1; while (j < line.length && line[j] != '"') { if (line[j] == '\\') j++; j++ }; sb.withStyle(SpanStyle(SyntaxColors.string)) { sb.append(line.substring(i, minOf(j + 1, line.length))) }; i = minOf(j + 1, line.length) }
            line[i] == '\'' -> { var j = i + 1; while (j < line.length && line[j] != '\'') { if (line[j] == '\\') j++; j++ }; sb.withStyle(SpanStyle(SyntaxColors.string)) { sb.append(line.substring(i, minOf(j + 1, line.length))) }; i = minOf(j + 1, line.length) }
            line[i] == '@' -> { var j = i + 1; while (j < line.length && (line[j].isLetterOrDigit() || line[j] == '_' || line[j] == '.')) j++; sb.withStyle(SpanStyle(SyntaxColors.annotation)) { sb.append(line.substring(i, j)) }; i = j }
            line[i].isDigit() || (line[i] == '-' && i + 1 < line.length && line[i + 1].isDigit()) -> { var j = i; if (line[j] == '-') j++; while (j < line.length && (line[j].isDigit() || line[j] in ".xXoOeE_jJ")) j++; sb.withStyle(SpanStyle(SyntaxColors.number)) { sb.append(line.substring(i, j)) }; i = j }
            line[i].isLetter() || line[i] == '_' -> { var j = i; while (j < line.length && (line[j].isLetterOrDigit() || line[j] == '_')) j++; val word = line.substring(i, j); when { word in kws -> sb.withStyle(SpanStyle(SyntaxColors.keyword)) { sb.append(word) }; word in builtins -> sb.withStyle(SpanStyle(SyntaxColors.func)) { sb.append(word) }; j < line.length && line[j] == '(' -> sb.withStyle(SpanStyle(SyntaxColors.func)) { sb.append(word) }; else -> sb.append(word) }; i = j }
            line[i] in "+-*/%=<>!&|^~@" -> { sb.withStyle(SpanStyle(SyntaxColors.operator)) { sb.append(line[i]) }; i++ }
            else -> { sb.append(line[i]); i++ }
        }
    }
}

// ── Detect syntax mode from filename ────────────────────────────

fun detectSyntaxMode(fileName: String): SyntaxMode {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "smali" -> SyntaxMode.SMALI
        "java","kt","kts" -> SyntaxMode.KOTLIN
        "xml" -> SyntaxMode.XML
        "json" -> SyntaxMode.JSON
        "sh","bash","zsh","fish","ash","ksh" -> SyntaxMode.SHELL
        "py","pyw" -> SyntaxMode.PYTHON
        "c","cpp","h","hpp","cc","cxx","rs","go","rb","cs","swift","dart","js","ts","jsx","tsx" -> SyntaxMode.JAVA
        "html","htm","css","scss","less" -> SyntaxMode.XML
        "gradle" -> SyntaxMode.KOTLIN
        "properties","conf","cfg","ini","yaml","yml","toml" -> SyntaxMode.SHELL
        else -> SyntaxMode.NONE
    }
}

// ── Legacy compatibility wrapper ─────────────────────────────────
// Bridges old `content: String` API to new `lines: List<String>` API.

@Composable
fun UnifiedCodeEditor(
    content: String,
    onContentChange: ((String) -> Unit)?,
    syntaxMode: SyntaxMode = SyntaxMode.NONE,
    undoStack: EditorUndoStack? = null,
    wordWrap: Boolean = true,
    modifier: Modifier = Modifier
) {
    val lines = remember(content) { content.split("\n") }

    VirtualizedCodeEditor(
        lines = lines,
        onLineChange = if (onContentChange != null) { idx, newLine ->
            val newLines = lines.toMutableList().apply { set(idx, newLine) }
            onContentChange(newLines.joinToString("\n"))
        } else null,
        onContentChange = onContentChange,
        syntaxMode = syntaxMode,
        undoStack = undoStack,
        wordWrap = wordWrap,
        modifier = modifier
    )
}

// ── Virtualized Code Editor ─────────────────────────────────────
// Uses LazyColumn for O(visible) rendering instead of O(total).

private const val BASE_FONT_SIZE = 12f
private const val MIN_FONT_SIZE = 8f
private const val MAX_FONT_SIZE = 32f
private const val BASE_LINE_HEIGHT = 15f

@Composable
fun VirtualizedCodeEditor(
    lines: List<String>,
    onLineChange: ((Int, String) -> Unit)?,
    onContentChange: ((String) -> Unit)?,
    syntaxMode: SyntaxMode = SyntaxMode.NONE,
    undoStack: EditorUndoStack? = null,
    wordWrap: Boolean = true,
    highlightLineNum: Int = -1,
    scrollToLine: Int = -1,
    onVisibleLineChange: ((Int) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var fontSize by remember { mutableFloatStateOf(BASE_FONT_SIZE) }
    val lineHeightSp = remember(fontSize) { (fontSize * 1.25f).sp }

    // Find/Replace state
    var showFindBar by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var replaceQuery by remember { mutableStateOf("") }
    var showReplace by remember { mutableStateOf(false) }
    var caseSensitive by remember { mutableStateOf(false) }

    // Matched lines for find navigation
    val matchedLines = remember(findQuery, lines, caseSensitive) {
        if (findQuery.isEmpty()) emptyList()
        else {
            val q = if (caseSensitive) findQuery else findQuery.lowercase()
            lines.indices.filter { idx ->
                val line = lines[idx]
                (if (caseSensitive) line else line.lowercase()).contains(q)
            }
        }
    }
    var matchPos by remember { mutableStateOf(0) }
    LaunchedEffect(matchedLines) { matchPos = 0 }
    val scope = rememberCoroutineScope()

    // Active highlighted line: explicit jump wins, else current find match
    val activeHighlight = remember(highlightLineNum, matchedLines, matchPos) {
        if (highlightLineNum >= 0) highlightLineNum
        else matchedLines.getOrNull(matchPos) ?: -1
    }

    val listState = rememberLazyListState()

    // Scroll to specific line when requested
    LaunchedEffect(scrollToLine) {
        if (scrollToLine in lines.indices) {
            listState.scrollToItem(scrollToLine.coerceAtMost(max(0, lines.size - 3)), 0)
        }
    }

    // Report first visible line (for Ln/Col status)
    val firstVisible by remember { derivedStateOf { listState.firstVisibleItemIndex } }
    LaunchedEffect(firstVisible) {
        onVisibleLineChange?.invoke(firstVisible + 1)
    }

    // Compute line number column width
    val lineNumWidth = remember(lines.size) {
        when {
            lines.size >= 10000 -> 56.dp
            lines.size >= 1000 -> 48.dp
            lines.size >= 100 -> 40.dp
            else -> 32.dp
        }
    }

    Column(modifier = modifier) {
        // Find/Replace bar
        if (showFindBar) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(4.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = findQuery,
                            onValueChange = { findQuery = it },
                            modifier = Modifier.weight(1f).height(40.dp),
                            placeholder = { Text("Find", style = MaterialTheme.typography.labelSmall) },
                            textStyle = MaterialTheme.typography.labelSmall,
                            singleLine = true
                        )
                        if (findQuery.isNotEmpty()) {
                            Text("${if (matchedLines.isEmpty()) 0 else matchPos + 1}/${matchedLines.size}",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 4.dp))
                        }
                        IconButton(
                            onClick = {
                                if (matchedLines.isNotEmpty()) {
                                    matchPos = if (matchPos - 1 < 0) matchedLines.size - 1 else matchPos - 1
                                    val target = matchedLines[matchPos].coerceAtMost(max(0, lines.size - 3))
                                    scope.launch { listState.scrollToItem(target, 0) }
                                }
                            },
                            enabled = matchedLines.isNotEmpty(),
                            modifier = Modifier.size(32.dp)
                        ) { Icon(Icons.Default.KeyboardArrowUp, "Previous match", modifier = Modifier.size(16.dp)) }
                        IconButton(
                            onClick = {
                                if (matchedLines.isNotEmpty()) {
                                    matchPos = (matchPos + 1) % matchedLines.size
                                    val target = matchedLines[matchPos].coerceAtMost(max(0, lines.size - 3))
                                    scope.launch { listState.scrollToItem(target, 0) }
                                }
                            },
                            enabled = matchedLines.isNotEmpty(),
                            modifier = Modifier.size(32.dp)
                        ) { Icon(Icons.Default.KeyboardArrowDown, "Next match", modifier = Modifier.size(16.dp)) }
                        IconButton(onClick = { caseSensitive = !caseSensitive }, modifier = Modifier.size(32.dp)) {
                            Text("Aa", style = MaterialTheme.typography.labelSmall,
                                color = if (caseSensitive) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        }
                        IconButton(onClick = { showReplace = !showReplace }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.FindReplace, "Replace", modifier = Modifier.size(16.dp))
                        }
                        IconButton(onClick = { showFindBar = false; findQuery = ""; replaceQuery = "" }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Close, "Close", modifier = Modifier.size(16.dp))
                        }
                    }
                    if (showReplace && onContentChange != null) {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = replaceQuery,
                                onValueChange = { replaceQuery = it },
                                modifier = Modifier.weight(1f).height(40.dp),
                                placeholder = { Text("Replace", style = MaterialTheme.typography.labelSmall) },
                                textStyle = MaterialTheme.typography.labelSmall,
                                singleLine = true
                            )
                            TextButton(onClick = {
                                // Replace one occurrence on the current match line
                                val idx = matchedLines.getOrNull(matchPos) ?: return@TextButton
                                val line = lines.getOrNull(idx) ?: return@TextButton
                                val replaced = if (caseSensitive) line.replaceFirst(findQuery, replaceQuery)
                                else line.replaceFirst(findQuery, replaceQuery, ignoreCase = true)
                                onLineChange?.invoke(idx, replaced)
                            }, enabled = matchedLines.isNotEmpty(), modifier = Modifier.height(40.dp)) {
                                Text("One", style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(onClick = {
                                if (findQuery.isNotEmpty()) {
                                    val newLines = lines.map {
                                        if (caseSensitive) it.replace(findQuery, replaceQuery)
                                        else it.replace(findQuery, replaceQuery, ignoreCase = true)
                                    }
                                    onContentChange(newLines.joinToString("\n"))
                                }
                            }, enabled = matchedLines.isNotEmpty(), modifier = Modifier.height(40.dp)) {
                                Text("All", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }

        // Editor area with pinch-to-zoom
        Box(modifier = Modifier.weight(1f)
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ ->
                    fontSize = (fontSize * zoom).coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
                }
            }
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                // Line numbers gutter
                LazyColumn(
                    state = listState,
                    modifier = Modifier.width(lineNumWidth)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f))
                ) {
                    items(lines.size) { idx ->
                        Text(
                            "${idx + 1}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = fontSize.sp,
                            lineHeight = lineHeightSp,
                            color = if (idx == activeHighlight) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    }
                }

                // Code area
                SelectionContainer(modifier = Modifier.weight(1f)) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize()
                            .let { mod -> if (!wordWrap) mod.horizontalScroll(rememberScrollState()) else mod }
                    ) {
                        items(lines.size) { idx ->
                            val line = lines[idx]
                            val bg = if (idx == activeHighlight)
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                            else Color.Transparent

                            Box(modifier = Modifier.fillMaxWidth().background(bg)) {
                                if (onContentChange != null) {
                                    // Editable line
                                    val highlighted = if (syntaxMode != SyntaxMode.NONE) highlightLine(line, syntaxMode) else null
                                    // When syntax mode is on: transparent text in field, highlighted overlay
                                    // When syntax mode is off: normal text in field, no overlay
                                    BasicTextField(
                                        value = line,
                                        onValueChange = { newLine ->
                                            onLineChange?.invoke(idx, newLine)
                                        },
                                        textStyle = TextStyle(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = fontSize.sp,
                                            lineHeight = lineHeightSp,
                                            color = if (syntaxMode != SyntaxMode.NONE) Color.Transparent
                                            else MaterialTheme.colorScheme.onSurface
                                        ),
                                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                                    )
                                    // Syntax overlay (only when syntax mode active)
                                    if (highlighted != null) {
                                        Text(
                                            text = highlighted,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = fontSize.sp,
                                            lineHeight = lineHeightSp,
                                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                                                .pointerInput(Unit) {} // pass-through to BasicTextField
                                        )
                                    }
                                } else {
                                    // Read-only line
                                    val highlighted = if (syntaxMode != SyntaxMode.NONE) highlightLine(line, syntaxMode) else AnnotatedString(line.ifEmpty { " " })
                                    Text(
                                        text = highlighted,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = fontSize.sp,
                                        lineHeight = lineHeightSp,
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Bottom toolbar
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                if (undoStack != null && onContentChange != null) {
                    IconButton(onClick = {
                        val full = lines.joinToString("\n")
                        undoStack.undo(full).let { (new, ok) -> if (ok) onContentChange(new) }
                    }, enabled = undoStack.canUndo, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Undo, "Undo", modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = {
                        val full = lines.joinToString("\n")
                        undoStack.redo(full).let { (new, ok) -> if (ok) onContentChange(new) }
                    }, enabled = undoStack.canRedo, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Redo, "Redo", modifier = Modifier.size(16.dp))
                    }
                }
                // Zoom controls
                IconButton(onClick = { fontSize = (fontSize - 2f).coerceAtLeast(MIN_FONT_SIZE) }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Remove, "Zoom Out", modifier = Modifier.size(16.dp))
                }
                Text("${fontSize.roundToInt()}sp", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                IconButton(onClick = { fontSize = (fontSize + 2f).coerceAtMost(MAX_FONT_SIZE) }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Add, "Zoom In", modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { showFindBar = !showFindBar }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Search, "Find", modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}
