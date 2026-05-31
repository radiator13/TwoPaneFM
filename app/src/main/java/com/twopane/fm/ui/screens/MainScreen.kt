package com.twopane.fm.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.viewmodel.compose.viewModel
import com.twopane.fm.BuildConfig
import com.twopane.fm.model.FilterType
import com.twopane.fm.model.ThemeMode
import com.twopane.fm.ui.components.ContextMenuDialog
import com.twopane.fm.ui.components.DeleteConfirmDialog
import com.twopane.fm.ui.components.FilePane
import com.twopane.fm.ui.components.GoToDialog
import com.twopane.fm.ui.components.ManageStorageDialog
import com.twopane.fm.ui.components.NewFileDialog
import com.twopane.fm.ui.components.NewFolderDialog
import com.twopane.fm.ui.components.PropertiesDialog
import com.twopane.fm.ui.components.RenameDialog
import com.twopane.fm.ui.components.RootPickerDialog
import com.twopane.fm.ui.components.SettingsDialog
import com.twopane.fm.ui.components.ShareDialog
import com.twopane.fm.ui.components.SearchDialog
import com.twopane.fm.ui.components.ApkBottomSheetMenu
import com.twopane.fm.ui.components.ApkAction
import com.twopane.fm.ui.components.BatchRenameDialog
import com.twopane.fm.ui.theme.TwoPaneFMTheme
import com.twopane.fm.util.ApkUtils
import com.twopane.fm.util.FileUtils
import com.twopane.fm.viewmodel.FileExplorerViewModel
import com.twopane.fm.viewmodel.FileExplorerViewModel.PaneSide
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: FileExplorerViewModel = viewModel()) {
    val isDarkTheme = when (viewModel.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }

    TwoPaneFMTheme(darkTheme = isDarkTheme) {
        var activePane by remember { mutableStateOf(PaneSide.LEFT) }

        // ── Unified back gesture ──
        // Sub-screens: navigate back in-app
        // File manager: navigate directory history (active pane first)
        BackHandler {
            viewModel.handleBack(activePane)
        }

        when (viewModel.currentScreen) {
            FileExplorerViewModel.Screen.APK_BROWSER -> {
                ApkBrowserScreen(
                    apkPath = viewModel.apkEditorPath,
                    viewModel = viewModel,
                    onBack = { viewModel.exitApkEditor() }
                )
            }
            FileExplorerViewModel.Screen.SMALI_BROWSER -> {
                SmaliBrowserScreen(
                    smaliDir = viewModel.apkDisassembledDir,
                    dexName = viewModel.currentDexName,
                    viewModel = viewModel,
                    onBack = { viewModel.navigateBackFromApkEditor() },
                    onOpenFile = { path ->
                        viewModel.navigateToSmaliEditor(path, viewModel.apkDisassembledDir)
                    }
                )
            }
            FileExplorerViewModel.Screen.SMALI_EDITOR -> {
                SmaliEditorScreen(
                    smaliFilePath = viewModel.currentSmaliFile,
                    workingDir = viewModel.editorWorkingDir,
                    viewModel = viewModel,
                    onBack = { viewModel.navigateBackFromApkEditor() }
                )
            }
            FileExplorerViewModel.Screen.TEXT_EDITOR -> {
                TextEditorScreen(
                    filePath = viewModel.textEditorPath,
                    viewModel = viewModel,
                    readOnly = viewModel.textEditorReadOnly,
                    onBack = { viewModel.navigateBackFromApkEditor() }
                )
            }
            FileExplorerViewModel.Screen.JAVA_BROWSER -> {
                JavaBrowserScreen(
                    javaDir = viewModel.javaBrowserDir,
                    viewModel = viewModel,
                    onBack = { viewModel.navigateBackFromApkEditor() },
                    onOpenFile = { path ->
                        viewModel.navigateToTextEditor(path, readOnly = false)
                    }
                )
            }
            FileExplorerViewModel.Screen.FILE_MANAGER -> {
        var showSettings by remember { mutableStateOf(false) }
        val snackbarHostState = remember { SnackbarHostState() }
        val drawerState = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        val clipboardManager = LocalClipboardManager.current

        LaunchedEffect(viewModel.statusMessage) {
            viewModel.statusMessage?.let { msg ->
                val result = snackbarHostState.showSnackbar(
                    message = msg,
                    actionLabel = if (viewModel.canUndo) "Undo" else null,
                    duration = androidx.compose.material3.SnackbarDuration.Short
                )
                if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                    viewModel.undoDelete()
                }
                viewModel.clearStatus()
            }
        }

        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = false,
            drawerContent = {
                ModalDrawerSheet(modifier = Modifier.width(280.dp)) {
                    Text("Bookmarks", style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(16.dp))
                    HorizontalDivider()
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        viewModel.bookmarks.forEach { path ->
                            TextButton(
                                onClick = { viewModel.navigateToBookmark(path) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(path, modifier = Modifier.weight(1f),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        ) {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHostState) },
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                val s = if (activePane == PaneSide.LEFT) viewModel.leftPane else viewModel.rightPane
                                val selCount = s.selectedFiles.size
                                if (selCount > 0) {
                                    Text(
                                        text = "$selCount selected",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        maxLines = 1
                                    )
                                } else {
                                    Text(
                                        text = s.currentPath,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text(
                                    text = "v${BuildConfig.VERSION_NAME} · ${s.folderCount} folders, ${s.fileCount} files · ${FileUtils.formatSize(s.totalSize)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                                )
                            }
                        },
                        actions = {
                            IconButton(onClick = { viewModel.toggleBookmark() }) {
                                Icon(
                                    if (viewModel.isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                    "Bookmark",
                                    tint = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                            if (viewModel.clipboard != null) {
                                Text(
                                    text = viewModel.getClipboardInfo() ?: "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                                )
                            }
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, "Bookmarks",
                                    tint = MaterialTheme.colorScheme.onPrimary)
                            }
                            IconButton(onClick = { showSettings = true }) {
                                Icon(Icons.Default.Settings, "Settings",
                                    tint = MaterialTheme.colorScheme.onPrimary)
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            titleContentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier.statusBarsPadding()
                    )
                },
                bottomBar = {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { viewModel.showRootPicker(activePane) }, modifier = Modifier.size(52.dp)) {
                                Icon(Icons.Default.Home, "Home", modifier = Modifier.size(28.dp))
                            }
                            IconButton(onClick = { viewModel.navigateBack(activePane) }, modifier = Modifier.size(52.dp)) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", modifier = Modifier.size(28.dp))
                            }
                            IconButton(onClick = { viewModel.showSearch(activePane) }, modifier = Modifier.size(52.dp)) {
                                Icon(Icons.Default.Search, "Search", modifier = Modifier.size(28.dp))
                            }
                            IconButton(onClick = { viewModel.showHidden = !viewModel.showHidden }, modifier = Modifier.size(52.dp)) {
                                Icon(
                                    if (viewModel.showHidden) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    "Hidden files",
                                    modifier = Modifier.size(28.dp),
                                    tint = if (viewModel.showHidden) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Spacer(Modifier.width(12.dp))

                            IconButton(onClick = { viewModel.showNewFolder(activePane) }, modifier = Modifier.size(52.dp)) {
                                Icon(Icons.Default.CreateNewFolder, "New Folder", modifier = Modifier.size(28.dp))
                            }
                            IconButton(onClick = { viewModel.showNewFile(activePane) }, modifier = Modifier.size(52.dp)) {
                                Icon(Icons.AutoMirrored.Filled.NoteAdd, "New File", modifier = Modifier.size(28.dp))
                            }
                        }
                    }
                }
            ) { padding ->
                Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                    // ── Two panes ──
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        FilePane(
                            paneState = viewModel.leftPane,
                            isActive = activePane == PaneSide.LEFT,
                            viewMode = viewModel.viewMode,
                            activeFilter = viewModel.activeFilter,
                            modifier = Modifier.weight(1f),
                            onActivate = { activePane = PaneSide.LEFT },
                            onItemClick = { entry ->
                                activePane = PaneSide.LEFT
                                viewModel.openFile(PaneSide.LEFT, entry)
                            },
                            onItemLongClick = { entry ->
                                activePane = PaneSide.LEFT
                                viewModel.toggleSelection(PaneSide.LEFT, entry.path)
                                viewModel.showContextMenuFor(entry)
                            },
                            onRangeSelect = { path ->
                                viewModel.rangeSelect(PaneSide.LEFT, path)
                            },
                            clipboardInfo = viewModel.getClipboardInfo(),
                            onCopy = { viewModel.copySelected(PaneSide.LEFT) },
                            onCut = { viewModel.cutSelected(PaneSide.LEFT) },
                            onPaste = { viewModel.pasteTo(PaneSide.LEFT) },
                            onDelete = { viewModel.deleteSelected(PaneSide.LEFT) },
                            onSelectAll = { viewModel.selectAll(PaneSide.LEFT) },
                            onClearSelection = { viewModel.clearSelections(PaneSide.LEFT) },
                            onInvertSelection = { viewModel.invertSelection(PaneSide.LEFT) },
                            onShare = { viewModel.shareSelected(PaneSide.LEFT) },
                            onCompare = if (viewModel.leftPane.selectedFiles.size == 2) {{
                                val paths = viewModel.leftPane.selectedFiles.toList()
                                viewModel.navigateToTextDiff(paths[0], paths[1])
                            }} else null,
                            onBatchRename = if (viewModel.leftPane.selectedFiles.size > 1) {{
                                viewModel.showBatchRename(PaneSide.LEFT)
                            }} else null,
                            onSortOrder = { viewModel.sortOrder = it },
                            onFilterChange = { viewModel.updateFilter(it) },
                            currentSortOrder = viewModel.sortOrder
                        )

                        Box(modifier = Modifier
                            .fillMaxHeight()
                            .width(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        )

                        FilePane(
                            paneState = viewModel.rightPane,
                            isActive = activePane == PaneSide.RIGHT,
                            viewMode = viewModel.viewMode,
                            activeFilter = viewModel.activeFilter,
                            modifier = Modifier.weight(1f),
                            onActivate = { activePane = PaneSide.RIGHT },
                            onItemClick = { entry ->
                                activePane = PaneSide.RIGHT
                                viewModel.openFile(PaneSide.RIGHT, entry)
                            },
                            onItemLongClick = { entry ->
                                activePane = PaneSide.RIGHT
                                viewModel.toggleSelection(PaneSide.RIGHT, entry.path)
                                viewModel.showContextMenuFor(entry)
                            },
                            onRangeSelect = { path ->
                                viewModel.rangeSelect(PaneSide.RIGHT, path)
                            },
                            clipboardInfo = viewModel.getClipboardInfo(),
                            onCopy = { viewModel.copySelected(PaneSide.RIGHT) },
                            onCut = { viewModel.cutSelected(PaneSide.RIGHT) },
                            onPaste = { viewModel.pasteTo(PaneSide.RIGHT) },
                            onDelete = { viewModel.deleteSelected(PaneSide.RIGHT) },
                            onSelectAll = { viewModel.selectAll(PaneSide.RIGHT) },
                            onClearSelection = { viewModel.clearSelections(PaneSide.RIGHT) },
                            onInvertSelection = { viewModel.invertSelection(PaneSide.RIGHT) },
                            onShare = { viewModel.shareSelected(PaneSide.RIGHT) },
                            onCompare = if (viewModel.rightPane.selectedFiles.size == 2) {{
                                val paths = viewModel.rightPane.selectedFiles.toList()
                                viewModel.navigateToTextDiff(paths[0], paths[1])
                            }} else null,
                            onBatchRename = if (viewModel.rightPane.selectedFiles.size > 1) {{
                                viewModel.showBatchRename(PaneSide.RIGHT)
                            }} else null,
                            onSortOrder = { viewModel.sortOrder = it },
                            onFilterChange = { viewModel.updateFilter(it) },
                            currentSortOrder = viewModel.sortOrder
                        )
                    }
                }
            }

            // ── Dialogs ──
            if (viewModel.showManagePermission) {
                ManageStorageDialog(
                    onOpenSettings = { viewModel.openSettings() },
                    onDismiss = { viewModel.showManagePermission = false }
                )
            }
            if (viewModel.showNewFolderDialog) {
                NewFolderDialog(onDismiss = { viewModel.showNewFolderDialog = false },
                    onConfirm = { viewModel.createFolder(viewModel.rootPickerTarget ?: PaneSide.LEFT, it) })
            }
            if (viewModel.showNewFileDialog) {
                NewFileDialog(onDismiss = { viewModel.showNewFileDialog = false },
                    onConfirm = { viewModel.createFile(viewModel.rootPickerTarget ?: PaneSide.LEFT, it) })
            }
            if (viewModel.showRenameDialog && viewModel.renameTarget != null) {
                RenameDialog(currentName = viewModel.renameTarget!!.name,
                    onDismiss = { viewModel.showRenameDialog = false; viewModel.renameTarget = null },
                    onConfirm = { viewModel.rename(it) })
            }
            if (viewModel.showDeleteConfirm) {
                DeleteConfirmDialog(count = viewModel.deleteTargets.size,
                    onDismiss = { viewModel.cancelDelete() }, onConfirm = { viewModel.confirmDelete() })
            }
            if (viewModel.showPropertiesDialog && viewModel.propertiesTarget != null) {
                PropertiesDialog(entry = viewModel.propertiesTarget!!,
                    onDismiss = { viewModel.showPropertiesDialog = false; viewModel.propertiesTarget = null })
            }
            if (viewModel.showGoToDialog && viewModel.goToTarget != null) {
                GoToDialog(onDismiss = { viewModel.showGoToDialog = false },
                    onConfirm = { viewModel.goToPath(viewModel.goToTarget ?: PaneSide.LEFT, it) })
            }
            if (viewModel.showRootPicker && viewModel.rootPickerTarget != null) {
                val roots = remember { FileUtils.getAvailableRoots() }
                RootPickerDialog(roots = roots, onDismiss = { viewModel.showRootPicker = false },
                    onSelect = { viewModel.navigateToRoot(viewModel.rootPickerTarget ?: PaneSide.LEFT, it) })
            }
            if (viewModel.showContextMenu && viewModel.contextMenuTarget != null) {
                val entry = viewModel.contextMenuTarget!!
                ContextMenuDialog(
                    entry = entry,
                    onDismiss = { viewModel.showContextMenu = false; viewModel.contextMenuTarget = null },
                    onRename = { viewModel.showRename(entry) },
                    onProperties = { viewModel.showProperties(entry) },
                    onCopyPath = {
                        clipboardManager.setText(AnnotatedString(entry.path))
                        viewModel.statusMessage = "Path copied"
                    },
                    onShare = { viewModel.shareSelected(activePane) },
                    onApkViewer = if (entry.name.endsWith(".apk", true)) {
                        { viewModel.showApkMenuFor(entry) }
                    } else null,
                    onOpenWith = {
                        viewModel.showContextMenu = false
                        viewModel.contextMenuTarget = null
                        viewModel.openFile(activePane, entry)
                    }
                )
            }
            if (viewModel.showApkMenu) {
                val apkInfo = remember(viewModel.apkMenuPath) {
                    ApkUtils.getApkInfo(viewModel.apkMenuPath)
                }
                ApkBottomSheetMenu(
                    apkName = viewModel.apkMenuPath.substringAfterLast("/"),
                    apkInfo = apkInfo,
                    onAction = { action -> viewModel.handleApkAction(action, viewModel.apkMenuPath) },
                    onDismiss = { viewModel.showApkMenu = false }
                )
            }
            if (viewModel.showSearchDialog) {
                SearchDialog(
                    query = viewModel.searchQueryText,
                    onQueryChange = { viewModel.searchQueryText = it },
                    onSearch = { viewModel.performSearch() },
                    onDismiss = { viewModel.showSearchDialog = false }
                )
            }
            if (viewModel.showShareDialog) {
                ShareDialog(
                    onConfirm = { viewModel.doShare() },
                    onDismiss = { viewModel.showShareDialog = false; viewModel.shareTargets = emptyList() }
                )
            }
            if (viewModel.showBatchRenameDialog) {
                val side = viewModel.batchRenameTarget
                val selCount = if (side == PaneSide.LEFT) viewModel.leftPane.selectedFiles.size else viewModel.rightPane.selectedFiles.size
                BatchRenameDialog(
                    fileCount = selCount,
                    onDismiss = { viewModel.showBatchRenameDialog = false },
                    onPreview = { _, _, _ -> },
                    onApply = { find, replace, useRegex -> viewModel.applyBatchRename(find, replace, useRegex) }
                )
            }
            if (showSettings) {
                SettingsDialog(
                    showHidden = viewModel.showHidden,
                    sortOrder = viewModel.sortOrder,
                    sortAscending = viewModel.sortAscending,
                    themeMode = viewModel.themeMode,
                    viewMode = viewModel.viewMode,
                    activeFilter = viewModel.activeFilter,
                    onToggleHidden = { viewModel.showHidden = it },
                    onSortOrder = { viewModel.sortOrder = it },
                    onSortAscending = { viewModel.sortAscending = it },
                    onThemeMode = { viewModel.themeMode = it },
                    onViewMode = { viewModel.viewMode = it },
                    onFilterChange = { viewModel.updateFilter(it) },
                    onDismiss = { showSettings = false })
            }
            }
        }
            FileExplorerViewModel.Screen.APK_INFO -> {
                ApkInfoScreen(
                    apkPath = viewModel.apkEditorPath,
                    onBack = { viewModel.navigateBackFromApkEditor() },
                    onPermissionsClick = { viewModel.navigateToPermissionList(viewModel.apkEditorPath) }
                )
            }
            FileExplorerViewModel.Screen.PERMISSION_LIST -> {
                PermissionListScreen(
                    apkPath = viewModel.apkEditorPath,
                    onBack = { viewModel.navigateToApkInfo(viewModel.apkEditorPath) }
                )
            }
            FileExplorerViewModel.Screen.APK_TOOL_RESULT -> {
                ApkToolResultScreen(
                    viewModel = viewModel,
                    onBack = { viewModel.navigateBackFromApkEditor() }
                )
            }
            FileExplorerViewModel.Screen.TEXT_DIFF -> {
                TextDiffScreen(
                    path1 = viewModel.diffPath1,
                    path2 = viewModel.diffPath2,
                    onBack = { viewModel.navigateBackFromApkEditor() }
                )
            }
        } // when
    } // TwoPaneFMTheme
}
