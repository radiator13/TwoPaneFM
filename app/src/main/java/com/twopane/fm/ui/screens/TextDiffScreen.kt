package com.twopane.fm.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

enum class DiffType { SAME, ADDED, REMOVED }

data class DiffLine(val lineNum1: Int?, val lineNum2: Int?, val text: String, val type: DiffType)

fun computeDiff(lines1: List<String>, lines2: List<String>): List<DiffLine> {
    // Simple LCS-based diff
    val m = lines1.size
    val n = lines2.size
    val dp = Array(m + 1) { IntArray(n + 1) }
    for (i in 1..m) for (j in 1..n) {
        dp[i][j] = if (lines1[i - 1] == lines2[j - 1]) dp[i - 1][j - 1] + 1
        else maxOf(dp[i - 1][j], dp[i][j - 1])
    }
    val result = mutableListOf<DiffLine>()
    var i = m; var j = n
    val temp = mutableListOf<DiffLine>()
    while (i > 0 || j > 0) {
        when {
            i > 0 && j > 0 && lines1[i - 1] == lines2[j - 1] -> {
                temp.add(DiffLine(i, j, lines1[i - 1], DiffType.SAME))
                i--; j--
            }
            j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j]) -> {
                temp.add(DiffLine(null, j, lines2[j - 1], DiffType.ADDED))
                j--
            }
            else -> {
                temp.add(DiffLine(i, null, lines1[i - 1], DiffType.REMOVED))
                i--
            }
        }
    }
    result.addAll(temp.reversed())
    return result
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextDiffScreen(path1: String, path2: String, onBack: () -> Unit) {
    var diffLines by remember { mutableStateOf<List<DiffLine>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(path1, path2) {
        isLoading = true
        try {
            kotlinx.coroutines.Dispatchers.IO.let { dispatch ->
                kotlinx.coroutines.withContext(dispatch) {
                    val lines1 = File(path1).readLines()
                    val lines2 = File(path2).readLines()
                    diffLines = computeDiff(lines1, lines2)
                }
            }
        } catch (e: Exception) {
            error = e.message
        }
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Text Diff", style = MaterialTheme.typography.titleSmall, maxLines = 1)
                        Text(
                            "${path1.substringAfterLast("/")} vs ${path2.substringAfterLast("/")}",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        when {
            isLoading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            error != null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Error: $error", color = MaterialTheme.colorScheme.error)
            }
            else -> {
                val scrollState = rememberScrollState()
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding)
                ) {
                    items(diffLines) { line ->
                        val bg = when (line.type) {
                            DiffType.ADDED -> Color(0xFF1B5E20).copy(alpha = 0.3f)
                            DiffType.REMOVED -> Color(0xFFB71C1C).copy(alpha = 0.3f)
                            DiffType.SAME -> Color.Transparent
                        }
                        val textColor = when (line.type) {
                            DiffType.ADDED -> Color(0xFF66BB6A)
                            DiffType.REMOVED -> Color(0xFFEF5350)
                            DiffType.SAME -> MaterialTheme.colorScheme.onSurface
                        }
                        val prefix = when (line.type) {
                            DiffType.ADDED -> "+ "
                            DiffType.REMOVED -> "- "
                            DiffType.SAME -> "  "
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().background(bg).padding(horizontal = 4.dp, vertical = 1.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${(line.lineNum1 ?: line.lineNum2 ?: 0).toString().padStart(4)} ",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                modifier = Modifier.width(40.dp)
                            )
                            Text(
                                text = "$prefix${line.text}",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                                color = textColor,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.horizontalScroll(scrollState)
                            )
                        }
                    }
                }
            }
        }
    }
}
