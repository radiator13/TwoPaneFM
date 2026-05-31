package com.twopane.fm.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Syntax highlighting colors
private object SyntaxColors {
    val keyword = Color(0xFFC792EA)    // purple
    val string = Color(0xFFC3E88D)     // green
    val number = Color(0xFFF78C6C)     // orange
    val comment = Color(0xFF546E7A)    // gray
    val annotation = Color(0xFFFFCB6B) // yellow
    val type = Color(0xFF82AAFF)       // blue
    val operator = Color(0xFF89DDFF)   // cyan
    val smaliDirective = Color(0xFFC792EA)
    val smaliRegister = Color(0xFFF07178)
    val tag = Color(0xFFF07178)
    val attrName = Color(0xFFFFCB6B)
    val default = Color(0xFFE0E0E0)
    val func = Color(0xFF82AAFF)       // blue (for functions/builtins)
}

enum class SyntaxMode {
    NONE, SMALI, JAVA, XML, KOTLIN, JSON, SHELL, PYTHON
}

class EditorUndoStack {
    private val undoStack = ArrayDeque<String>(100)
    private val redoStack = ArrayDeque<String>(100)
    private var lastPush = ""

    fun push(state: String) {
        if (state == lastPush) return
        undoStack.addLast(state)
        if (undoStack.size > 100) undoStack.removeFirst()
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
        undoStack.clear()
        redoStack.clear()
        lastPush = ""
    }

    val canUndo: Boolean get() = undoStack.size > 1
    val canRedo: Boolean get() = redoStack.isNotEmpty()
}

fun highlightSyntax(code: String, mode: SyntaxMode): AnnotatedString {
    if (mode == SyntaxMode.NONE) return AnnotatedString(code)

    return buildAnnotatedString {
        val lines = code.split("\n")
        for ((lineIdx, line) in lines.withIndex()) {
            when (mode) {
                SyntaxMode.SMALI -> highlightSmaliLine(this, line)
                SyntaxMode.JAVA, SyntaxMode.KOTLIN -> highlightKotlinLine(this, line)
                SyntaxMode.XML -> highlightXmlLine(this, line)
                SyntaxMode.JSON -> highlightJsonLine(this, line)
                SyntaxMode.SHELL -> highlightShellLine(this, line)
                SyntaxMode.PYTHON -> highlightPythonLine(this, line)
                SyntaxMode.NONE -> append(line)
            }
            if (lineIdx < lines.lastIndex) append("\n")
        }
    }
}

private fun highlightSmaliLine(sb: AnnotatedString.Builder, line: String) {
    val trimmed = line.trimStart()
    when {
        trimmed.startsWith("#") -> sb.withStyle(SpanStyle(SyntaxColors.comment)) { sb.append(line) }
        trimmed.startsWith(".class") || trimmed.startsWith(".super") ||
        trimmed.startsWith(".source") || trimmed.startsWith(".implements") ||
        trimmed.startsWith(".field") || trimmed.startsWith(".method") ||
        trimmed.startsWith(".end") || trimmed.startsWith(".annotation") ||
        trimmed.startsWith(".parameter") || trimmed.startsWith(".local") ||
        trimmed.startsWith(".prologue") || trimmed.startsWith(".line") ||
        trimmed.startsWith(".catch") || trimmed.startsWith(".catchall") ||
        trimmed.startsWith(".packed-switch") || trimmed.startsWith(".sparse-switch") ||
        trimmed.startsWith(".array-data") -> {
            sb.withStyle(SpanStyle(SyntaxColors.smaliDirective, fontWeight = FontWeight.Bold)) { sb.append(line) }
        }
        else -> {
            // Highlight registers like v0, v1, p0, etc.
            var i = 0
            while (i < line.length) {
                when {
                    line.startsWith("//", i) -> {
                        sb.withStyle(SpanStyle(SyntaxColors.comment)) { sb.append(line.substring(i)) }
                        return
                    }
                    line[i] == '"' -> {
                        val end = line.indexOf('"', i + 1).let { if (it < 0) line.length else it + 1 }
                        sb.withStyle(SpanStyle(SyntaxColors.string)) { sb.append(line.substring(i, end)) }
                        i = end
                    }
                    line.startsWith("0x", i) -> {
                        val end = i + 2
                        while (end < line.length && line[end].isHexDigit()) { /* advance */ }
                        sb.withStyle(SpanStyle(SyntaxColors.number)) { sb.append(line.substring(i, end)) }
                        i = end
                    }
                    line[i].isDigit() -> {
                        var end = i
                        while (end < line.length && (line[end].isDigit() || line[end] == 'x' || line[end] == 'X' || line[end].isHexDigit())) end++
                        sb.withStyle(SpanStyle(SyntaxColors.number)) { sb.append(line.substring(i, end)) }
                        i = end
                    }
                    line.startsWith("invoke-", i) || line.startsWith("move", i) ||
                    line.startsWith("return", i) || line.startsWith("goto", i) ||
                    line.startsWith("if-", i) || line.startsWith("cond-", i) ||
                    line.startsWith("add", i) || line.startsWith("sub", i) ||
                    line.startsWith("mul", i) || line.startsWith("div", i) ||
                    line.startsWith("rem", i) && i + 3 < line.length && !line[i + 3].isLetter() ||
                    line.startsWith("and", i) || line.startsWith("or", i) ||
                    line.startsWith("xor", i) || line.startsWith("shl", i) ||
                    line.startsWith("shr", i) || line.startsWith("ushr", i) ||
                    line.startsWith("neg", i) || line.startsWith("not", i) ||
                    line.startsWith("cmp", i) || line.startsWith("new", i) ||
                    line.startsWith("check", i) || line.startsWith("throw", i) ||
                    line.startsWith("monitor", i) || line.startsWith("fill", i) ||
                    line.startsWith("sget", i) || line.startsWith("sput", i) ||
                    line.startsWith("iget", i) || line.startsWith("iput", i) ||
                    line.startsWith("aget", i) || line.startsWith("aput", i) ||
                    line.startsWith("const", i) || line.startsWith("monitor", i) -> {
                        sb.withStyle(SpanStyle(SyntaxColors.keyword)) { sb.append(line.substring(i, i + minOf(7, line.length - i))) }
                        i += minOf(7, line.length - i)
                    }
                    line[i] == 'v' || line[i] == 'p' -> {
                        var end = i + 1
                        while (end < line.length && line[end].isDigit()) end++
                        if (end > i + 1) {
                            sb.withStyle(SpanStyle(SyntaxColors.smaliRegister)) { sb.append(line.substring(i, end)) }
                            i = end
                        } else { sb.append(line[i]); i++ }
                    }
                    else -> { sb.append(line[i]); i++ }
                }
            }
        }
    }
}

private fun highlightKotlinLine(sb: AnnotatedString.Builder, line: String) {
    val keywords = setOf(
        "abstract", "actual", "annotation", "as", "break", "by", "catch", "class",
        "companion", "const", "constructor", "continue", "crossinline", "data", "do",
        "dynamic", "else", "enum", "expect", "external", "final", "finally", "for",
        "fun", "get", "if", "import", "in", "init", "inline", "inner", "interface",
        "internal", "is", "it", "lateinit", "lazy", "noinline", "object", "open",
        "operator", "out", "override", "package", "private", "protected", "public",
        "reified", "return", "sealed", "set", "super", "suspend", "tailrec", "this",
        "throw", "try", "typealias", "val", "var", "vararg", "when", "where", "while"
    )
    val typeNames = setOf(
        "String", "Int", "Long", "Float", "Double", "Boolean", "Char", "Byte", "Short",
        "Unit", "Any", "Nothing", "List", "Map", "Set", "Array", "MutableList",
        "MutableMap", "MutableSet", "Result", "Pair", "Triple"
    )

    var i = 0
    while (i < line.length) {
        when {
            line.startsWith("//", i) -> {
                sb.withStyle(SpanStyle(SyntaxColors.comment)) { sb.append(line.substring(i)) }
                return
            }
            line.startsWith("/*", i) -> {
                val end = line.indexOf("*/", i + 2)
                if (end >= 0) {
                    sb.withStyle(SpanStyle(SyntaxColors.comment)) { sb.append(line.substring(i, end + 2)) }
                    i = end + 2
                } else {
                    sb.withStyle(SpanStyle(SyntaxColors.comment)) { sb.append(line.substring(i)) }
                    return
                }
            }
            line[i] == '"' -> {
                if (line.startsWith("\"\"\"", i)) {
                    val end = line.indexOf("\"\"\"", i + 3)
                    if (end >= 0) {
                        sb.withStyle(SpanStyle(SyntaxColors.string)) { sb.append(line.substring(i, end + 3)) }
                        i = end + 3
                    } else {
                        sb.withStyle(SpanStyle(SyntaxColors.string)) { sb.append(line.substring(i)) }
                        return
                    }
                } else {
                    var j = i + 1
                    while (j < line.length && line[j] != '"') { if (line[j] == '\\') j++; j++ }
                    val end = minOf(j + 1, line.length)
                    sb.withStyle(SpanStyle(SyntaxColors.string)) { sb.append(line.substring(i, end)) }
                    i = end
                }
            }
            line[i] == '\'' -> {
                var j = i + 1
                if (j < line.length && line[j] == '\\') j++
                j++
                val end = minOf(j + 1, line.length)
                sb.withStyle(SpanStyle(SyntaxColors.string)) { sb.append(line.substring(i, end)) }
                i = end
            }
            line[i] == '@' -> {
                var end = i + 1
                while (end < line.length && (line[end].isLetterOrDigit() || line[end] == '_')) end++
                sb.withStyle(SpanStyle(SyntaxColors.annotation)) { sb.append(line.substring(i, end)) }
                i = end
            }
            line[i].isDigit() -> {
                var end = i
                while (end < line.length && (line[end].isDigit() || line[end] in "xXeE.")) end++
                sb.withStyle(SpanStyle(SyntaxColors.number)) { sb.append(line.substring(i, end)) }
                i = end
            }
            line[i].isLetter() || line[i] == '_' -> {
                var end = i
                while (end < line.length && (line[end].isLetterOrDigit() || line[end] == '_')) end++
                val word = line.substring(i, end)
                when {
                    word in keywords -> sb.withStyle(SpanStyle(SyntaxColors.keyword, fontWeight = FontWeight.Bold)) { sb.append(word) }
                    word in typeNames -> sb.withStyle(SpanStyle(SyntaxColors.type)) { sb.append(word) }
                    end < line.length && line[end] == '(' -> sb.withStyle(SpanStyle(SyntaxColors.type)) { sb.append(word) }
                    else -> sb.append(word)
                }
                i = end
            }
            line[i] in "!=<>" -> {
                var end = i + 1
                while (end < line.length && line[end] in "=<>") end++
                sb.withStyle(SpanStyle(SyntaxColors.operator)) { sb.append(line.substring(i, end)) }
                i = end
            }
            else -> { sb.append(line[i]); i++ }
        }
    }
}

private fun highlightXmlLine(sb: AnnotatedString.Builder, line: String) {
    var i = 0
    while (i < line.length) {
        when {
            line.startsWith("<!--", i) -> {
                val end = line.indexOf("-->", i + 4)
                if (end >= 0) {
                    sb.withStyle(SpanStyle(SyntaxColors.comment)) { sb.append(line.substring(i, end + 3)) }
                    i = end + 3
                } else {
                    sb.withStyle(SpanStyle(SyntaxColors.comment)) { sb.append(line.substring(i)) }
                    return
                }
            }
            line[i] == '<' && (i + 1 < line.length && (line[i + 1].isLetter() || line[i + 1] == '/')) -> {
                var j = i + 1
                if (line[j] == '/') j++
                val nameStart = j
                while (j < line.length && (line[j].isLetterOrDigit() || line[j] in "_-:.")) j++
                sb.withStyle(SpanStyle(SyntaxColors.default)) { sb.append(line.substring(i, nameStart)) }
                sb.withStyle(SpanStyle(SyntaxColors.tag, fontWeight = FontWeight.Bold)) { sb.append(line.substring(nameStart, j)) }
                i = j
            }
            line[i] == '=' -> {
                sb.withStyle(SpanStyle(SyntaxColors.operator)) { sb.append("=") }
                i++
                if (i < line.length && line[i] == '"') {
                    var end = line.indexOf('"', i + 1)
                    if (end < 0) end = line.length - 1
                    sb.withStyle(SpanStyle(SyntaxColors.string)) { sb.append(line.substring(i, end + 1)) }
                    i = end + 1
                }
            }
            line[i].isLetter() -> {
                var j = i
                while (j < line.length && (line[j].isLetterOrDigit() || line[j] in "_-:.")) j++
                val word = line.substring(i, j)
                if (j < line.length && line[j] == '=') {
                    sb.withStyle(SpanStyle(SyntaxColors.attrName)) { sb.append(word) }
                } else {
                    sb.append(word)
                }
                i = j
            }
            line[i] == '"' -> {
                var j = i + 1
                while (j < line.length && line[j] != '"') j++
                val end = minOf(j + 1, line.length)
                sb.withStyle(SpanStyle(SyntaxColors.string)) { sb.append(line.substring(i, end)) }
                i = end
            }
            else -> { sb.append(line[i]); i++ }
        }
    }
}

private fun highlightJsonLine(sb: AnnotatedString.Builder, line: String) {
    var i = 0
    while (i < line.length) {
        when {
            line[i] == '"' -> {
                var j = i + 1
                while (j < line.length && line[j] != '"') { if (line[j] == '\\') j++; j++ }
                val end = minOf(j + 1, line.length)
                val str = line.substring(i, end)
                // Check if it's a key (followed by :)
                var k = end
                while (k < line.length && line[k] == ' ') k++
                if (k < line.length && line[k] == ':') {
                    sb.withStyle(SpanStyle(SyntaxColors.attrName)) { sb.append(str) }
                } else {
                    sb.withStyle(SpanStyle(SyntaxColors.string)) { sb.append(str) }
                }
                i = end
            }
            line[i].isDigit() || (line[i] == '-' && i + 1 < line.length && line[i + 1].isDigit()) -> {
                var j = i
                if (line[j] == '-') j++
                while (j < line.length && (line[j].isDigit() || line[j] in ".eE+-")) j++
                sb.withStyle(SpanStyle(SyntaxColors.number)) { sb.append(line.substring(i, j)) }
                i = j
            }
            line.startsWith("true", i) || line.startsWith("false", i) || line.startsWith("null", i) -> {
                val word = when {
                    line.startsWith("true", i) -> "true"
                    line.startsWith("false", i) -> "false"
                    else -> "null"
                }
                sb.withStyle(SpanStyle(SyntaxColors.keyword)) { sb.append(word) }
                i += word.length
            }
            else -> { sb.append(line[i]); i++ }
        }
    }
}

private fun highlightShellLine(sb: AnnotatedString.Builder, line: String) {
    val trimmed = line.trimStart()

    // Comments
    if (trimmed.startsWith("#")) {
        sb.withStyle(SpanStyle(SyntaxColors.comment)) { sb.append(line) }
        return
    }

    val shellKeywords = setOf("if", "then", "else", "elif", "fi", "for", "while", "do",
        "done", "case", "esac", "function", "return", "exit", "export", "source",
        "alias", "unalias", "set", "unset", "local", "readonly", "declare")
    val shellBuiltins = setOf("echo", "printf", "read", "test", "[", "[[", "cd", "ls",
        "grep", "sed", "awk", "find", "chmod", "chown", "cp", "mv", "rm", "mkdir",
        "cat", "head", "tail", "sort", "uniq", "wc", "pipe", "tee", "xargs",
        "curl", "wget", "tar", "zip", "unzip", "ssh", "scp")

    var i = 0
    while (i < line.length) {
        when {
            // Double-quoted string
            line[i] == '"' -> {
                var j = i + 1
                while (j < line.length && line[j] != '"') { if (line[j] == '\\') j++; j++ }
                val end = minOf(j + 1, line.length)
                sb.withStyle(SpanStyle(SyntaxColors.string)) { sb.append(line.substring(i, end)) }
                i = end
            }
            // Single-quoted string
            line[i] == '\'' -> {
                var j = i + 1
                while (j < line.length && line[j] != '\'') j++
                val end = minOf(j + 1, line.length)
                sb.withStyle(SpanStyle(SyntaxColors.string)) { sb.append(line.substring(i, end)) }
                i = end
            }
            // Variable reference
            line[i] == '$' -> {
                var j = i + 1
                if (j < line.length && line[j] == '{') {
                    j++
                    while (j < line.length && line[j] != '}') j++
                    j = minOf(j + 1, line.length)
                } else {
                    while (j < line.length && (line[j].isLetterOrDigit() || line[j] == '_')) j++
                }
                sb.withStyle(SpanStyle(SyntaxColors.type)) { sb.append(line.substring(i, j)) }
                i = j
            }
            // Numbers
            line[i].isDigit() -> {
                var j = i
                while (j < line.length && (line[j].isDigit() || line[j] in "xXoObB")) j++
                sb.withStyle(SpanStyle(SyntaxColors.number)) { sb.append(line.substring(i, j)) }
                i = j
            }
            // Words (keywords, builtins, commands)
            line[i].isLetter() || line[i] == '_' -> {
                var j = i
                while (j < line.length && (line[j].isLetterOrDigit() || line[j] in "_./-")) j++
                val word = line.substring(i, j)
                when {
                    word in shellKeywords -> sb.withStyle(SpanStyle(SyntaxColors.keyword)) { sb.append(word) }
                    word in shellBuiltins -> sb.withStyle(SpanStyle(SyntaxColors.func)) { sb.append(word) }
                    j < line.length && line[j] == '(' -> sb.withStyle(SpanStyle(SyntaxColors.func)) { sb.append(word) }
                    else -> sb.append(word)
                }
                i = j
            }
            // Operators
            line[i] in "|&;><!{}()[]" -> {
                sb.withStyle(SpanStyle(SyntaxColors.operator)) { sb.append(line[i]) }
                i++
            }
            else -> { sb.append(line[i]); i++ }
        }
    }
}

private fun highlightPythonLine(sb: AnnotatedString.Builder, line: String) {
    val trimmed = line.trimStart()

    // Comments
    if (trimmed.startsWith("#")) {
        sb.withStyle(SpanStyle(SyntaxColors.comment)) { sb.append(line) }
        return
    }

    // Triple-quoted strings
    if (trimmed.startsWith("\"\"\"") || trimmed.startsWith("'''")) {
        val quote = trimmed.substring(0, 3)
        val endQuote = line.indexOf(quote, 3)
        val end = if (endQuote >= 0) minOf(endQuote + 3, line.length) else line.length
        sb.withStyle(SpanStyle(SyntaxColors.string)) { sb.append(line.substring(0, end)) }
        if (end < line.length) {
            sb.append(line.substring(end))
        }
        return
    }

    val pyKeywords = setOf("def", "class", "if", "elif", "else", "for", "while", "return",
        "import", "from", "as", "try", "except", "finally", "raise", "with", "yield",
        "lambda", "pass", "break", "continue", "and", "or", "not", "in", "is",
        "True", "False", "None", "global", "nonlocal", "assert", "del", "print")
    val pyBuiltins = setOf("len", "range", "str", "int", "float", "list", "dict", "set",
        "tuple", "type", "isinstance", "super", "self", "enumerate", "zip", "map",
        "filter", "sorted", "reversed", "open", "input", "abs", "max", "min", "sum",
        "any", "all", "bool", "bytes", "callable", "dir", "getattr", "hasattr", "id",
        "hex", "oct", "ord", "chr", "hash", "repr", "round")

    var i = 0
    while (i < line.length) {
        when {
            // Double-quoted string
            line[i] == '"' -> {
                var j = i + 1
                while (j < line.length && line[j] != '"') { if (line[j] == '\\') j++; j++ }
                val end = minOf(j + 1, line.length)
                val str = line.substring(i, end)
                if (str.startsWith("f\"") || str.startsWith("F\"")) {
                    sb.withStyle(SpanStyle(SyntaxColors.annotation)) { sb.append(str) }
                } else {
                    sb.withStyle(SpanStyle(SyntaxColors.string)) { sb.append(str) }
                }
                i = end
            }
            // Single-quoted string
            line[i] == '\'' -> {
                var j = i + 1
                while (j < line.length && line[j] != '\'') { if (line[j] == '\\') j++; j++ }
                val end = minOf(j + 1, line.length)
                sb.withStyle(SpanStyle(SyntaxColors.string)) { sb.append(line.substring(i, end)) }
                i = end
            }
            // Decorator
            line[i] == '@' -> {
                var j = i + 1
                while (j < line.length && (line[j].isLetterOrDigit() || line[j] == '_' || line[j] == '.')) j++
                sb.withStyle(SpanStyle(SyntaxColors.annotation)) { sb.append(line.substring(i, j)) }
                i = j
            }
            // Numbers
            line[i].isDigit() || (line[i] == '-' && i + 1 < line.length && line[i + 1].isDigit()) -> {
                var j = i
                if (line[j] == '-') j++
                while (j < line.length && (line[j].isDigit() || line[j] in ".xXoOeE_jJ")) j++
                sb.withStyle(SpanStyle(SyntaxColors.number)) { sb.append(line.substring(i, j)) }
                i = j
            }
            // Words (keywords, builtins)
            line[i].isLetter() || line[i] == '_' -> {
                var j = i
                while (j < line.length && (line[j].isLetterOrDigit() || line[j] == '_')) j++
                val word = line.substring(i, j)
                when {
                    word in pyKeywords -> sb.withStyle(SpanStyle(SyntaxColors.keyword)) { sb.append(word) }
                    word in pyBuiltins -> sb.withStyle(SpanStyle(SyntaxColors.func)) { sb.append(word) }
                    j < line.length && line[j] == '(' -> sb.withStyle(SpanStyle(SyntaxColors.func)) { sb.append(word) }
                    else -> sb.append(word)
                }
                i = j
            }
            // Operators
            line[i] in "+-*/%=<>!&|^~@" -> {
                sb.withStyle(SpanStyle(SyntaxColors.operator)) { sb.append(line[i]) }
                i++
            }
            else -> { sb.append(line[i]); i++ }
        }
    }
}

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

@Composable
fun UnifiedCodeEditor(
    content: String,
    onContentChange: ((String) -> Unit)?,
    syntaxMode: SyntaxMode = SyntaxMode.NONE,
    undoStack: EditorUndoStack? = null,
    wordWrap: Boolean = true,
    modifier: Modifier = Modifier
) {
    var showFindBar by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var replaceQuery by remember { mutableStateOf("") }
    var showReplace by remember { mutableStateOf(false) }
    var matchCount by remember { mutableIntStateOf(0) }
    var currentMatch by remember { mutableIntStateOf(0) }

    Column(modifier = modifier) {
        // Find/Replace bar
        if (showFindBar) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = findQuery,
                            onValueChange = { findQuery = it },
                            modifier = Modifier.weight(1f).height(40.dp),
                            placeholder = { Text("Find", style = MaterialTheme.typography.labelSmall) },
                            textStyle = MaterialTheme.typography.labelSmall,
                            singleLine = true
                        )
                        if (findQuery.isNotEmpty()) {
                            Text(
                                "${if (matchCount > 0) currentMatch else 0}/$matchCount",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                        }
                        IconButton(onClick = {
                            showReplace = !showReplace
                        }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.FindReplace, "Replace",
                                modifier = Modifier.size(16.dp))
                        }
                        IconButton(onClick = {
                            showFindBar = false
                            findQuery = ""
                            replaceQuery = ""
                        }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Close, "Close",
                                modifier = Modifier.size(16.dp))
                        }
                    }
                    if (showReplace && onContentChange != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = replaceQuery,
                                onValueChange = { replaceQuery = it },
                                modifier = Modifier.weight(1f).height(40.dp),
                                placeholder = { Text("Replace", style = MaterialTheme.typography.labelSmall) },
                                textStyle = MaterialTheme.typography.labelSmall,
                                singleLine = true
                            )
                            TextButton(onClick = {
                                if (findQuery.isNotEmpty()) {
                                    val new = content.replace(findQuery, replaceQuery)
                                    onContentChange(new)
                                }
                            }, modifier = Modifier.height(40.dp)) {
                                Text("All", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }

        // Editor row: line numbers + code
        Row(modifier = Modifier.weight(1f)) {
            // Line numbers
            val lineCount = content.count { it == '\n' } + 1
            val lineNumWidth = when {
                lineCount >= 10000 -> 56.dp
                lineCount >= 1000 -> 48.dp
                lineCount >= 100 -> 40.dp
                else -> 32.dp
            }
            val lineScrollState = rememberScrollState()
            val codeScrollState = rememberScrollState()

            Column(
                modifier = Modifier
                    .width(lineNumWidth)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f))
                    .verticalScroll(lineScrollState)
                    .padding(end = 4.dp)
            ) {
                for (i in 1..lineCount) {
                    Text(
                        "$i",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                        modifier = Modifier.padding(end = 4.dp)
                    )
                }
            }

            // Code area
            if (onContentChange != null) {
                val highlighted = if (syntaxMode != SyntaxMode.NONE) {
                    highlightSyntax(content, syntaxMode)
                } else null

                SelectionContainer {
                    OutlinedTextField(
                        value = content,
                        onValueChange = { newContent ->
                            undoStack?.push(newContent)
                            onContentChange(newContent)
                        },
                        modifier = Modifier.fillMaxSize()
                            .let { mod ->
                                if (wordWrap) mod.verticalScroll(codeScrollState)
                                else mod.verticalScroll(codeScrollState).horizontalScroll(rememberScrollState())
                            },
                        textStyle = LocalTextStyle.current.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 15.sp,
                            color = if (syntaxMode != SyntaxMode.NONE) Color.Transparent
                            else MaterialTheme.colorScheme.onSurface
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent
                        )
                    )
                }
                // Syntax-highlighted overlay (read-only visual layer)
                if (syntaxMode != SyntaxMode.NONE) {
                    Text(
                        text = highlighted!!,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 15.sp,
                        modifier = Modifier.fillMaxSize()
                            .verticalScroll(codeScrollState)
                            .padding(horizontal = 16.dp, vertical = 0.dp)
                    )
                }
            } else {
                // Read-only view with syntax highlighting
                val highlighted = if (syntaxMode != SyntaxMode.NONE) {
                    highlightSyntax(content, syntaxMode)
                } else null
                SelectionContainer {
                    Text(
                        text = highlighted ?: AnnotatedString(content.ifEmpty { "// Empty file" }),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 15.sp,
                        modifier = Modifier.fillMaxSize()
                            .verticalScroll(codeScrollState)
                            .horizontalScroll(rememberScrollState())
                            .padding(8.dp)
                    )
                }
            }
        }

        // Bottom toolbar
        if (undoStack != null || onContentChange != null) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (undoStack != null) {
                        IconButton(
                            onClick = { undoStack.undo(content).let { if (it.second) onContentChange?.invoke(it.first) } },
                            enabled = undoStack.canUndo,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.Undo, "Undo", modifier = Modifier.size(16.dp))
                        }
                        IconButton(
                            onClick = { undoStack.redo(content).let { if (it.second) onContentChange?.invoke(it.first) } },
                            enabled = undoStack.canRedo,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.Redo, "Redo", modifier = Modifier.size(16.dp))
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(
                        onClick = { showFindBar = !showFindBar },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Default.Search, "Find", modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

fun detectSyntaxMode(fileName: String): SyntaxMode {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "smali" -> SyntaxMode.SMALI
        "java", "kt", "kts" -> SyntaxMode.KOTLIN
        "xml" -> SyntaxMode.XML
        "json" -> SyntaxMode.JSON
        "sh", "bash", "zsh", "fish", "ash", "ksh" -> SyntaxMode.SHELL
        "py", "pyw" -> SyntaxMode.PYTHON
        "c", "cpp", "h", "hpp", "cc", "cxx" -> SyntaxMode.JAVA
        "rs" -> SyntaxMode.JAVA
        "go" -> SyntaxMode.JAVA
        "rb" -> SyntaxMode.JAVA
        "cs" -> SyntaxMode.JAVA
        "swift" -> SyntaxMode.JAVA
        "dart" -> SyntaxMode.JAVA
        "js", "ts", "jsx", "tsx" -> SyntaxMode.JAVA
        "html", "htm", "css", "scss", "less" -> SyntaxMode.XML
        "gradle", "kts" -> SyntaxMode.KOTLIN
        "properties", "conf", "cfg", "ini" -> SyntaxMode.SHELL
        "yaml", "yml" -> SyntaxMode.SHELL
        else -> SyntaxMode.NONE
    }
    }
