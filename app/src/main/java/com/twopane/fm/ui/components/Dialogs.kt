package com.twopane.fm.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.twopane.fm.model.FileEntry
import com.twopane.fm.model.FilterType
import com.twopane.fm.model.SortOrder
import com.twopane.fm.model.ThemeMode
import com.twopane.fm.model.ViewMode
import com.twopane.fm.util.FileUtils

@Composable
fun NewFolderDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("New Folder") }, text = {
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Folder name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    }, confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text("Create") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
fun NewFileDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("New File") }, text = {
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("File name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    }, confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text("Create") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
fun RenameDialog(currentName: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(currentName) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Rename") }, text = {
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("New name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    }, confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank() && name != currentName) { Text("Rename") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
fun BatchRenameDialog(
    fileCount: Int,
    onDismiss: () -> Unit,
    onPreview: (find: String, replace: String, useRegex: Boolean) -> Unit,
    onApply: (find: String, replace: String, useRegex: Boolean) -> Unit
) {
    var findText by remember { mutableStateOf("") }
    var replaceText by remember { mutableStateOf("") }
    var useRegex by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Batch Rename ($fileCount files)") },
        text = {
            Column {
                OutlinedTextField(
                    value = findText, onValueChange = { findText = it },
                    label = { Text("Find") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = replaceText, onValueChange = { replaceText = it },
                    label = { Text("Replace with") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Regex", modifier = Modifier.weight(1f))
                    Switch(checked = useRegex, onCheckedChange = { useRegex = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onApply(findText, replaceText, useRegex) },
                enabled = findText.isNotBlank()
            ) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun DeleteConfirmDialog(count: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Delete") }, text = { Text("Delete $count item(s)?") }, confirmButton = { TextButton(onClick = onConfirm) { Text("Delete", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
fun PropertiesDialog(entry: FileEntry, onDismiss: () -> Unit) {
    var checksums by remember { mutableStateOf<Pair<String, String>?>(null) }
    LaunchedEffect(entry.path) {
        if (!entry.isDirectory) {
            kotlinx.coroutines.Dispatchers.IO.let { dispatch ->
                kotlinx.coroutines.withContext(dispatch) {
                    try {
                        val file = java.io.File(entry.path)
                        val md5 = java.security.MessageDigest.getInstance("MD5")
                        val sha256 = java.security.MessageDigest.getInstance("SHA-256")
                        file.inputStream().buffered(65536).use { input ->
                            val buffer = ByteArray(65536)
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                md5.update(buffer, 0, read)
                                sha256.update(buffer, 0, read)
                            }
                        }
                        checksums = md5.digest().joinToString("") { "%02x".format(it) } to
                                sha256.digest().joinToString("") { "%02x".format(it) }
                    } catch (_: Exception) {}
                }
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(entry.name) },
        text = {
            Column {
                Text("Path: ${entry.path}"); Spacer(Modifier.height(4.dp))
                Text("Type: ${if (entry.isDirectory) "Directory" else "File"}"); Spacer(Modifier.height(4.dp))
                if (!entry.isDirectory) { Text("Size: ${FileUtils.formatSize(entry.size)} (${String.format("%,d", entry.size)} bytes)"); Spacer(Modifier.height(4.dp)) }
                Text("Modified: ${FileUtils.formatDate(entry.lastModified)}"); Spacer(Modifier.height(4.dp))
                Text("Permissions: ${entry.permissions}")
                if (!entry.isDirectory && checksums != null) {
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    Text("MD5:", style = MaterialTheme.typography.labelMedium)
                    Text(checksums!!.first, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    Spacer(Modifier.height(4.dp))
                    Text("SHA-256:", style = MaterialTheme.typography.labelMedium)
                    Text(checksums!!.second, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
fun GoToDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var path by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Go to Path") }, text = {
        OutlinedTextField(value = path, onValueChange = { path = it }, label = { Text("Path") }, singleLine = true, modifier = Modifier.fillMaxWidth(), placeholder = { Text("/storage/emulated/0") })
    }, confirmButton = { TextButton(onClick = { onConfirm(path) }, enabled = path.isNotBlank()) { Text("Go") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
fun RootPickerDialog(roots: List<FileEntry>, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Select Root") }, text = {
        Column { roots.forEach { root -> TextButton(onClick = { onSelect(root.path) }, modifier = Modifier.fillMaxWidth()) { Text(root.path, modifier = Modifier.fillMaxWidth()) } } }
    }, confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
fun ShareDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Share") }, text = { Text("Share selected files via Android share sheet?") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Share") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
fun ManageStorageDialog(onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Storage Permission Required") },
        text = { Text("TwoPane FM needs 'All files access' to browse external storage on Android 11+. Please grant it in Settings.") },
        confirmButton = { TextButton(onClick = onOpenSettings) { Text("Open Settings") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Later") } })
}

@Composable
fun ContextMenuDialog(
    entry: FileEntry,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onProperties: () -> Unit,
    onCopyPath: () -> Unit,
    onShare: () -> Unit,
    onApkViewer: (() -> Unit)? = null,
    onOpenWith: (() -> Unit)? = null
) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(entry.name) }, text = {
        Column {
            if (onApkViewer != null) {
                TextButton(onClick = { onApkViewer(); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                    Text("APK Viewer", color = MaterialTheme.colorScheme.primary)
                }
            }
            if (onOpenWith != null) {
                TextButton(onClick = { onOpenWith(); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Open", color = MaterialTheme.colorScheme.primary)
                }
            }
            TextButton(onClick = { onRename(); onDismiss() }, modifier = Modifier.fillMaxWidth()) { Text("Rename") }
            TextButton(onClick = { onProperties(); onDismiss() }, modifier = Modifier.fillMaxWidth()) { Text("Properties") }
            TextButton(onClick = { onCopyPath(); onDismiss() }, modifier = Modifier.fillMaxWidth()) { Text("Copy path") }
            TextButton(onClick = { onShare(); onDismiss() }, modifier = Modifier.fillMaxWidth()) { Text("Share") }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
fun SearchDialog(query: String, onQueryChange: (String) -> Unit, onSearch: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Search") }, text = {
        OutlinedTextField(value = query, onValueChange = onQueryChange, label = { Text("Search files") }, singleLine = true, modifier = Modifier.fillMaxWidth(), placeholder = { Text("file name...") })
    }, confirmButton = { TextButton(onClick = onSearch, enabled = query.isNotBlank()) { Text("Search") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
fun SettingsDialog(
    showHidden: Boolean,
    sortOrder: SortOrder,
    sortAscending: Boolean,
    themeMode: ThemeMode,
    viewMode: ViewMode,
    activeFilter: FilterType,
    onToggleHidden: (Boolean) -> Unit,
    onSortOrder: (SortOrder) -> Unit,
    onSortAscending: (Boolean) -> Unit,
    onThemeMode: (ThemeMode) -> Unit,
    onViewMode: (ViewMode) -> Unit,
    onFilterChange: (FilterType) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Settings") }, text = {
        Column {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("Show hidden files", modifier = Modifier.weight(1f)); Switch(checked = showHidden, onCheckedChange = onToggleHidden) }
            Spacer(Modifier.height(8.dp))
            Text("Theme", style = MaterialTheme.typography.labelMedium)
            ThemeMode.entries.forEach { mode ->
                TextButton(onClick = { onThemeMode(mode) }, modifier = Modifier.fillMaxWidth()) {
                    Text(text = when (mode) { ThemeMode.SYSTEM -> "System default"; ThemeMode.LIGHT -> "Light"; ThemeMode.DARK -> "Dark" },
                        color = if (mode == themeMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("View", style = MaterialTheme.typography.labelMedium)
            ViewMode.entries.forEach { vm ->
                TextButton(onClick = { onViewMode(vm) }, modifier = Modifier.fillMaxWidth()) {
                    Text(text = when (vm) { ViewMode.LIST -> "List view"; ViewMode.GRID -> "Grid view" },
                        color = if (vm == viewMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Sort by", style = MaterialTheme.typography.labelMedium)
            SortOrder.entries.forEach { order ->
                TextButton(onClick = { onSortOrder(order) }, modifier = Modifier.fillMaxWidth()) {
                    Text(text = order.label,
                        color = if (order == sortOrder) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Ascending order", modifier = Modifier.weight(1f))
                Switch(checked = sortAscending, onCheckedChange = onSortAscending)
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}
