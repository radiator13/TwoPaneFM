package com.twopane.fm.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.twopane.fm.util.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Storage analyzer: sizes every child of [rootPath] (recursive du),
 * shows sorted bars, tap a folder to descend.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageAnalyzerScreen(
    rootPath: String,
    onBack: () -> Unit
) {
    var currentRoot by remember { mutableStateOf(rootPath) }
    var results by remember { mutableStateOf<List<Pair<String, Long>>>(emptyList()) } // name to bytes
    var total by remember { mutableLongStateOf(0L) }
    var isLoading by remember { mutableStateOf(true) }

    suspend fun analyze(path: String) {
        isLoading = true
        val computed = withContext(Dispatchers.IO) {
            File(path).listFiles()
                ?.orEmpty()
                .map { child ->
                    val size = if (child.isDirectory) FileUtils.diskUsage(child.absolutePath) else child.length()
                    child.name to size
                }
                ?.sortedByDescending { it.second }
                ?: emptyList()
        }
        results = computed
        total = computed.sumOf { it.second }
        isLoading = false
    }

    LaunchedEffect(currentRoot) { analyze(currentRoot) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Storage Analyzer", style = MaterialTheme.typography.titleSmall)
                        Text(currentRoot, style = MaterialTheme.typography.labelSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        val parent = FileUtils.getParentPath(currentRoot)
                        if (parent != currentRoot && File(parent).canRead()) currentRoot = parent else onBack()
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!isLoading && total > 0) {
                Text(
                    "Total: ${FileUtils.formatSize(total)} across ${results.size} items",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
            when {
                isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                results.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Empty directory", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(results, key = { it.first }) { (name, bytes) ->
                        val frac = if (total > 0) bytes.toFloat() / total else 0f
                        Column(Modifier.fillMaxWidth().clickable {
                            val child = File(currentRoot, name)
                            if (child.isDirectory) currentRoot = child.absolutePath
                        }.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Folder, null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(name, style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f))
                                Text(FileUtils.formatSize(bytes),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                if (frac > 0.01f) {
                                    Spacer(Modifier.width(6.dp))
                                    Text("${(frac * 100).toInt()}%",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Box(Modifier.fillMaxWidth().height(4.dp)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                                Box(Modifier.fillMaxWidth(frac.coerceIn(0.01f, 1f)).fillMaxHeight()
                                    .background(Color(0xFF7C4DFF)))
                            }
                        }
                    }
                }
            }
        }
    }
}
