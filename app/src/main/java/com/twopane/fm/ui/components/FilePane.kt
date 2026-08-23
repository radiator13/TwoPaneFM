package com.twopane.fm.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.twopane.fm.model.FileEntry
import com.twopane.fm.model.FilterType
import com.twopane.fm.model.SortOrder
import com.twopane.fm.model.ViewMode
import com.twopane.fm.util.FileUtils
import com.twopane.fm.viewmodel.PaneState

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FilePane(
    paneState: PaneState,
    isActive: Boolean,
    viewMode: ViewMode,
    activeFilter: FilterType,
    showThumbnails: Boolean = true,
    onActivate: () -> Unit,
    onItemClick: (FileEntry) -> Unit,
    onItemLongClick: (FileEntry) -> Unit,
    onRangeSelect: (String) -> Unit,
    clipboardInfo: String?,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onPaste: () -> Unit,
    onDelete: () -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onInvertSelection: () -> Unit,
    onShare: () -> Unit,
    onZip: (() -> Unit)? = null,
    onCompare: (() -> Unit)? = null,
    onBatchRename: (() -> Unit)? = null,
    onSortOrder: (SortOrder) -> Unit,
    onFilterChange: (FilterType) -> Unit,
    currentSortOrder: SortOrder,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clickable { onActivate() }
        ) {
            // ── Filter chips ──
            if (isActive) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FilterType.entries.forEach { ft ->
                        FilterChip(
                            selected = ft == activeFilter,
                            onClick = { onFilterChange(ft) },
                            label = { Text(ft.label, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.height(28.dp)
                        )
                    }
                }
            }

            // ── Column header sort indicators ──
            if (viewMode == ViewMode.LIST && paneState.files.isNotEmpty() && !paneState.isLoading) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Name", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.weight(1f).clickable { onSortOrder(SortOrder.NAME) })
                    Text(if (currentSortOrder == SortOrder.DATE) "Modified" else "Size",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.clickable { onSortOrder(SortOrder.DATE) }.width(60.dp))
                }
            }

            // ── Loading ──
            if (paneState.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (paneState.files.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Empty directory",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
            } else if (viewMode == ViewMode.GRID) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 100.dp),
                    contentPadding = PaddingValues(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(paneState.files, key = { it.path }) { entry ->
                        GridFileItem(
                            entry = entry,
                            isSelected = entry.path in paneState.selectedFiles,
                            showThumbnail = showThumbnails && isMediaFile(entry.name),
                            onClick = { onItemClick(entry) },
                            onLongClick = { onItemLongClick(entry) }
                        )
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(paneState.files, key = { it.path }) { entry ->
                        FileItemRow(
                            entry = entry,
                            isSelected = entry.path in paneState.selectedFiles,
                            showThumbnail = showThumbnails && isMediaFile(entry.name),
                            onClick = { onItemClick(entry) },
                            onLongClick = { onItemLongClick(entry) },
                            onShiftClick = { onRangeSelect(entry.path) }
                        )
                    }
                }
            }
        }

        // ── Active indicator overlay ──
        if (isActive) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.06f))
            )
        }

        // ── Floating selection bar ──
        AnimatedVisibility(
            visible = paneState.selectedFiles.isNotEmpty(),
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Surface(
                shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = 4.dp
            ) {
                Column {
                    Text(
                        "${paneState.selectedFiles.size} selected",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 12.dp, top = 4.dp)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        IconButton(onClick = onCopy, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.ContentCopy, "Copy", modifier = Modifier.size(20.dp))
                        }
                        IconButton(onClick = onCut, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.ContentCut, "Cut", modifier = Modifier.size(20.dp))
                        }
                        IconButton(onClick = onPaste, modifier = Modifier.size(36.dp), enabled = clipboardInfo != null) {
                            Icon(Icons.Default.ContentPaste, "Paste", modifier = Modifier.size(20.dp))
                        }
                        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.Delete, "Delete", modifier = Modifier.size(20.dp))
                        }
                        IconButton(onClick = onShare, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.Share, "Share", modifier = Modifier.size(20.dp))
                        }
                        if (onCompare != null) {
                            IconButton(onClick = onCompare, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.Compare, "Compare", modifier = Modifier.size(20.dp))
                            }
                        }
                        if (onZip != null) {
                            IconButton(onClick = onZip, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.FolderZip, "Compress", modifier = Modifier.size(20.dp))
                            }
                        }
                        if (onBatchRename != null) {
                            IconButton(onClick = onBatchRename, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.DriveFileRenameOutline, "Batch Rename", modifier = Modifier.size(20.dp))
                            }
                        }
                        IconButton(onClick = onSelectAll, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.SelectAll, "All", modifier = Modifier.size(20.dp))
                        }
                        IconButton(onClick = onInvertSelection, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.SwapVert, "Invert", modifier = Modifier.size(20.dp))
                        }
                        IconButton(onClick = onClearSelection, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.Deselect, "Deselect", modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileItemRow(
    entry: FileEntry,
    isSelected: Boolean,
    showThumbnail: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onShiftClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .then(
                if (isSelected) Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
                else Modifier
            )
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isSelected) {
            Icon(
                Icons.Default.CheckCircle, "Selected",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(8.dp))
        }

        if (showThumbnail && !entry.isDirectory) {
            Box(Modifier.width(40.dp)) {
                FileThumbnail(entry = entry, size = 40.dp)
            }
        } else {
            Icon(
                imageVector = iconForFile(entry),
                contentDescription = null,
                tint = if (entry.isDirectory) Color(0xFFFFA726) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.size(28.dp)
            )
        }
        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (entry.isHidden) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (entry.isEmptyDir) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "(empty)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!entry.isDirectory) {
                    Text(FileUtils.formatSize(entry.size), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                }
                Text(FileUtils.formatDate(entry.lastModified), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridFileItem(
    entry: FileEntry,
    isSelected: Boolean,
    showThumbnail: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .then(
                if (isSelected) Modifier.background(
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                    RoundedCornerShape(12.dp)
                ) else Modifier.background(
                    MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.3f),
                    RoundedCornerShape(12.dp)
                )
            )
            .padding(12.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (showThumbnail && !entry.isDirectory) {
                FileThumbnail(entry = entry, size = 48.dp,
                    modifier = Modifier.width(48.dp).height(48.dp))
            } else {
                Icon(
                    imageVector = iconForFile(entry),
                    contentDescription = null,
                    tint = if (entry.isDirectory) Color(0xFFFFA726) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.size(40.dp)
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (!entry.isDirectory) {
                Text(
                    text = FileUtils.formatSize(entry.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    maxLines = 1
                )
            }
        }
    }
}

private fun isMediaFile(name: String): Boolean {
    val ext = name.substringAfterLast('.', "").lowercase()
    return ext in setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "mp4", "mkv", "avi", "mov", "webm")
}

private fun iconForFile(entry: FileEntry): ImageVector {
    if (entry.isDirectory) return Icons.Default.Folder
    if (entry.name.endsWith(".apk", true)) return Icons.Default.PhoneAndroid
    return when {
        entry.name.endsWith(".jpg") || entry.name.endsWith(".jpeg") ||
                entry.name.endsWith(".png") || entry.name.endsWith(".gif") ||
                entry.name.endsWith(".bmp") || entry.name.endsWith(".webp") -> Icons.Default.Image
        entry.name.endsWith(".mp3") || entry.name.endsWith(".wav") ||
                entry.name.endsWith(".flac") || entry.name.endsWith(".aac") ||
                entry.name.endsWith(".ogg") -> Icons.Default.MusicNote
        entry.name.endsWith(".mp4") || entry.name.endsWith(".mkv") ||
                entry.name.endsWith(".avi") || entry.name.endsWith(".mov") -> Icons.Default.VideoFile
        entry.name.endsWith(".pdf") -> Icons.Default.PictureAsPdf
        entry.name.endsWith(".zip") || entry.name.endsWith(".rar") ||
                entry.name.endsWith(".7z") || entry.name.endsWith(".tar") ||
                entry.name.endsWith(".gz") -> Icons.Default.Archive
        else -> Icons.AutoMirrored.Filled.InsertDriveFile
    }
}
