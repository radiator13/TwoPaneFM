package com.twopane.fm.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.twopane.fm.util.ArchiveSupport
import com.twopane.fm.util.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Browse any zip-based archive (.zip/.jar/.apk/.aab/.xapk...):
 * list entries, view text entries in-place, extract single entries or all.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ArchiveBrowserScreen(
    archivePath: String,
    onOpenText: (String) -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<ArchiveSupport.ArchiveEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("") }

    // Which folder inside the archive we're viewing ("" = root)
    var currentPrefix by remember { mutableStateOf("") }

    LaunchedEffect(archivePath) {
        isLoading = true
        error = null
        val result = withContext(Dispatchers.IO) { ArchiveSupport.listEntries(archivePath) }
        entries = result
        isLoading = false
    }

    fun extract(entryName: String?, isAll: Boolean) {
        scope.launch {
            status = "Extracting..."
            val downloads = android.os.Environment
                .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS).absolutePath
            val outDir = "$downloads/${archivePath.substringAfterLast('/').substringBeforeLast('.')}_extracted"
            val result = withContext(Dispatchers.IO) {
                if (isAll) ArchiveSupport.extractAll(archivePath, outDir)
                else ArchiveSupport.extractEntry(archivePath, entryName!!, outDir)
            }
            status = result.getOrElse { "Extract failed: ${it.message}" }
        }
    }

    fun viewEntry(entryName: String) {
        scope.launch {
            status = "Loading ${entryName.substringAfterLast('/')}..."
            val result = withContext(Dispatchers.IO) { ArchiveSupport.readTextEntry(archivePath, entryName) }
            result.fold(
                onSuccess = { content ->
                    val cacheFile = java.io.File(
                        android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS),
                        ".twopane_view_${entryName.substringAfterLast('/')}"
                    )
                    withContext(Dispatchers.IO) {
                        cacheFile.parentFile?.mkdirs()
                        cacheFile.writeText(content)
                    }
                    status = ""
                    onOpenText(cacheFile.absolutePath)
                },
                onFailure = { status = it.message ?: "Cannot open entry" }
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(archivePath.substringAfterLast('/'),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${entries.size} entries" + if (currentPrefix.isNotEmpty()) " · /$currentPrefix" else "",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (currentPrefix.isNotEmpty()) {
                            val parent = currentPrefix.trimEnd('/').substringBeforeLast('/', "")
                            currentPrefix = if (parent.isEmpty()) "" else "$parent/"
                        } else onBack()
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = { extract(null, isAll = true) }, enabled = !isLoading) {
                        Icon(Icons.Default.Download, "Extract all")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (status.isNotBlank()) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Text(status, style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(6.dp))
                }
            }
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                placeholder = { Text("Filter entries...", style = MaterialTheme.typography.bodySmall) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
            )
            when {
                isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Error: $error") }
                else -> {
                    val visible = entries.asSequence()
                        .filter { it.name.startsWith(currentPrefix) && it.name != currentPrefix }
                        .map { it.name.removePrefix(currentPrefix) }
                        .filter { it.isNotBlank() }
                        .filter { filter.isBlank() || it.contains(filter, ignoreCase = true) }
                        .map { rel ->
                            val isDir = rel.contains('/')
                            Triple(rel, isDir, entries.firstOrNull { e -> e.name == currentPrefix + (if (isDir) rel.substringBefore('/') + "/" else rel) })
                        }
                        .distinctBy { it.first }
                        .sortedWith(compareBy<Triple<String, Boolean, ArchiveSupport.ArchiveEntry?>> { !it.second }.thenBy { it.first })
                        .toList()

                    LazyColumn(Modifier.fillMaxSize()) {
                        items(visible, key = { it.first }) { (rel, isDir, meta) ->                            ListItem(
                                headlineContent = { Text(rel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = {
                                    if (!isDir && meta != null) {
                                        Text("${FileUtils.formatSize(meta.size)} (${FileUtils.formatSize(meta.compressedSize)} zipped)")
                                    }
                                },
                                leadingContent = {
                                    Icon(
                                        if (isDir) Icons.Default.FolderZip else Icons.Default.Description,
                                        null,
                                        tint = if (isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                },
                                modifier = Modifier.combinedClickable(
                                    onClick = {
                                        if (isDir) currentPrefix = currentPrefix + rel.substringBefore('/') + "/"
                                        else viewEntry(currentPrefix + rel)
                                    },
                                    onLongClick = { if (!isDir) extract(currentPrefix + rel, isAll = false) }
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}
