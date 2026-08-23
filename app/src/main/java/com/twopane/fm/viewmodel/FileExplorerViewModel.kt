package com.twopane.fm.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.twopane.fm.model.ClipboardEntry
import com.twopane.fm.model.ClipboardOperation
import com.twopane.fm.model.FileEntry
import com.twopane.fm.model.FilterType
import com.twopane.fm.model.ThemeMode
import com.twopane.fm.model.ViewMode
import com.twopane.fm.util.ArchiveSupport
import com.twopane.fm.util.AppPreferences
import com.twopane.fm.util.DirWatcher
import com.twopane.fm.util.FileType
import com.twopane.fm.util.FileUtils
import com.twopane.fm.model.SortOrder
import com.twopane.fm.util.TermuxIntegration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class PaneState(
    val currentPath: String = "/storage/emulated/0",
    val files: List<FileEntry> = emptyList(),
    val selectedFiles: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val history: List<String> = emptyList(),
    val forwardHistory: List<String> = emptyList(),
    val folderCount: Int = 0,
    val fileCount: Int = 0,
    val totalSize: Long = 0L,
    val rangeAnchor: String? = null
)

/** Progress state for long-running operations (copy/paste/delete/zip). */
data class BusyOp(
    val label: String,
    val doneBytes: Long = 0L,
    val totalBytes: Long = 0L
) {
    val fraction: Float get() = if (totalBytes > 0) doneBytes.toFloat() / totalBytes else 0f
}

class FileExplorerViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = AppPreferences(application)

    var leftPane by mutableStateOf(PaneState())
        private set

    var rightPane by mutableStateOf(PaneState())
        private set

    var clipboard by mutableStateOf<ClipboardEntry?>(null)
        private set

    var statusMessage by mutableStateOf<String?>(null)

    // Busy operation progress
    var busyOp by mutableStateOf<BusyOp?>(null)
        private set

    // ── Undo buffer ──
    // originalPath -> backupPath in .trash (deletes AND overwritten targets)
    private var trashBuffer: Map<String, String> = emptyMap()
    // rename records: oldPath -> newPath (renames & moves), reversed on undo
    private var renameBuffer: List<Pair<String, String>> = emptyList()
    var canUndo by mutableStateOf(false)
        private set
    var lastUndoMessage by mutableStateOf("")
        private set

    // Dialog states
    var showNewFolderDialog by mutableStateOf(false)
    var showNewFileDialog by mutableStateOf(false)
    var showRenameDialog by mutableStateOf(false)
    var renameTarget by mutableStateOf<FileEntry?>(null)
    var showDeleteConfirm by mutableStateOf(false)
    var deleteTargets by mutableStateOf<List<String>>(emptyList())
    var showRootPicker by mutableStateOf(false)
    var rootPickerTarget by mutableStateOf<PaneSide?>(null)
    var showGoToDialog by mutableStateOf(false)
    var goToTarget by mutableStateOf<PaneSide?>(null)
    var showPropertiesDialog by mutableStateOf(false)
    var propertiesTarget by mutableStateOf<FileEntry?>(null)
    var showContextMenu by mutableStateOf(false)
    var contextMenuTarget by mutableStateOf<FileEntry?>(null)
    var showBookmarks by mutableStateOf(false)
    var showShareDialog by mutableStateOf(false)
    var shareTargets by mutableStateOf<List<File>>(emptyList())
    var showManagePermission by mutableStateOf(false)
    var showSearchDialog by mutableStateOf(false)
    var searchQueryText by mutableStateOf("")

    // Batch rename
    var showBatchRenameDialog by mutableStateOf(false)
    var batchRenameTarget by mutableStateOf(PaneSide.LEFT)

    // Archive viewer
    var archivePath by mutableStateOf("")
        private set

    // Storage analyzer
    var analyzerPath by mutableStateOf("")
        private set

    // Text editor state
    var textEditorPath by mutableStateOf("")
        private set
    var textEditorReadOnly by mutableStateOf(false)
        private set
    var textEditorReturnTo by mutableStateOf(Screen.FILE_MANAGER)
        private set

    // Persisted settings
    private val _showHidden = mutableStateOf(prefs.showHidden)
    var showHidden: Boolean
        get() = _showHidden.value
        set(v) {
            _showHidden.value = v
            prefs.showHidden = v
            for (side in PaneSide.entries) loadDirectory(side, getPane(side).currentPath)
        }

    private val _sortOrder = mutableStateOf(prefs.sortOrder)
    var sortOrder: SortOrder
        get() = _sortOrder.value
        set(v) {
            _sortOrder.value = v
            prefs.sortOrder = v
            for (side in PaneSide.entries) loadDirectory(side, getPane(side).currentPath)
        }

    private val _sortAscending = mutableStateOf(true)
    var sortAscending: Boolean
        get() = _sortAscending.value
        set(v) {
            _sortAscending.value = v
            for (side in PaneSide.entries) loadDirectory(side, getPane(side).currentPath)
        }

    private val _themeMode = mutableStateOf(prefs.themeMode)
    var themeMode: ThemeMode
        get() = _themeMode.value
        set(v) { _themeMode.value = v; prefs.themeMode = v }

    private val _viewMode = mutableStateOf(prefs.viewMode)
    var viewMode: ViewMode
        get() = _viewMode.value
        set(v) { _viewMode.value = v; prefs.viewMode = v }

    private val _activeFilter = mutableStateOf(prefs.activeFilter)
    var activeFilter: FilterType
        get() = _activeFilter.value
        set(v) { _activeFilter.value = v; prefs.activeFilter = v }

    var bookmarks by mutableStateOf(prefs.getBookmarks())
        private set

    var isBookmarked by mutableStateOf(false)
        private set

    var linkedPanes: Boolean
        get() = prefs.linkedPanes
        set(v) { prefs.linkedPanes = v }

    var showThumbnails: Boolean
        get() = prefs.showThumbnails
        set(v) { prefs.showThumbnails = v }

    enum class PaneSide { LEFT, RIGHT }
    enum class Screen {
        FILE_MANAGER, ARCHIVE_BROWSER, TEXT_EDITOR, TEXT_DIFF, STORAGE_ANALYZER
    }

    var currentScreen by mutableStateOf(Screen.FILE_MANAGER)
        private set

    var diffPath1 by mutableStateOf("")
        private set
    var diffPath2 by mutableStateOf("")
        private set

    // ── Navigation between screens ──

    fun navigateToArchiveBrowser(path: String) {
        archivePath = path
        currentScreen = Screen.ARCHIVE_BROWSER
    }

    fun navigateToTextEditor(path: String, readOnly: Boolean = false, returnTo: Screen = Screen.FILE_MANAGER) {
        textEditorPath = path
        textEditorReadOnly = readOnly
        textEditorReturnTo = returnTo
        currentScreen = Screen.TEXT_EDITOR
    }

    fun navigateToTextDiff(path1: String, path2: String) {
        diffPath1 = path1
        diffPath2 = path2
        currentScreen = Screen.TEXT_DIFF
    }

    fun navigateToStorageAnalyzer(side: PaneSide) {
        analyzerPath = getPane(side).currentPath
        currentScreen = Screen.STORAGE_ANALYZER
    }

    fun navigateBackFromScreen() {
        currentScreen = when (currentScreen) {
            Screen.TEXT_EDITOR -> textEditorReturnTo
            else -> Screen.FILE_MANAGER
        }
        if (currentScreen == Screen.FILE_MANAGER) {
            textEditorPath = ""
            textEditorReadOnly = false
            archivePath = ""
        }
    }

    /** Unified back handler: sub-screens navigate in-app, file manager navigates directory history */
    fun handleBack(activePane: PaneSide) {
        if (currentScreen != Screen.FILE_MANAGER) {
            navigateBackFromScreen()
            return
        }
        val activeState = getPane(activePane)
        val otherPane = if (activePane == PaneSide.LEFT) PaneSide.RIGHT else PaneSide.LEFT
        val otherState = getPane(otherPane)
        if (activeState.history.isNotEmpty()) {
            navigateBack(activePane)
        } else if (otherState.history.isNotEmpty()) {
            navigateBack(otherPane)
        }
        // else: at root, back consumed — prevents accidental app exit
    }

    /**
     * Handle files received from external apps (intents).
     * Routes to the appropriate screen based on file type.
     */
    fun handleIncomingFile(path: String, mimeType: String?) {
        val file = File(path)
        if (!file.exists()) return

        val parentDir = file.parent ?: return
        leftPane = leftPane.copy(currentPath = parentDir)
        loadDirectory(PaneSide.LEFT, leftPane.currentPath)

        when {
            mimeType?.startsWith("text/") == true || isTextFile(file.name) ->
                navigateToTextEditor(path, readOnly = false)
            ArchiveSupport.isArchive(file.name) || mimeType == "application/zip" ->
                navigateToArchiveBrowser(path)
            else -> navigateToTextEditor(path, readOnly = true)
        }
    }

    // ── File opening from file manager ──

    fun openFile(side: PaneSide, entry: FileEntry) {
        if (entry.isDirectory) {
            navigateTo(side, entry.path)
            return
        }

        when {
            ArchiveSupport.isArchive(entry.name) -> navigateToArchiveBrowser(entry.path)
            isTextFile(entry.name) -> navigateToTextEditor(entry.path)
            else -> openWithExternalApp(entry)
        }
    }

    private fun isTextFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in setOf(
            "txt", "json", "xml", "html", "htm", "css", "js", "ts",
            "java", "kt", "kts", "smali", "py", "rb", "go", "rs",
            "c", "cpp", "h", "hpp", "cs", "swift", "dart", "lua",
            "php", "pl", "r", "sql", "sh", "bash", "zsh", "fish",
            "bat", "cmd", "ps1", "yaml", "yml", "toml", "ini", "cfg",
            "conf", "config", "env", "rc", "properties", "gradle",
            "pro", "gitignore", "dockerfile", "makefile", "mk",
            "md", "markdown", "rst", "csv", "tsv", "log"
        ) || name.lowercase() in setOf("makefile", "dockerfile", "license", "readme", "changelog")
    }

    private fun openWithExternalApp(entry: FileEntry) {
        val ctx = getApplication<Application>()
        try {
            val file = File(entry.path)
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            val mimeType = FileType.getMimeType(entry.name)

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }

            if (intent.resolveActivity(ctx.packageManager) != null) {
                ctx.startActivity(intent)
            } else {
                val fallback = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "*/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (fallback.resolveActivity(ctx.packageManager) != null) {
                    ctx.startActivity(fallback)
                } else {
                    statusMessage = "No app found for ${entry.name}, opening as text"
                    navigateToTextEditor(entry.path)
                }
            }
        } catch (e: Exception) {
            statusMessage = "Cannot open: ${e.message}"
        }
    }

    init {
        requestAllFilesAccess()
        loadDirectory(PaneSide.LEFT, getDefaultPath())
        loadDirectory(PaneSide.RIGHT, getDefaultPath())
    }

    override fun onCleared() {
        leftWatcher?.close()
        rightWatcher?.close()
        super.onCleared()
    }

    private fun requestAllFilesAccess() {
        val ctx = getApplication<Application>()
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            if (!Environment.isExternalStorageManager()) {
                showManagePermission = true
            }
        }
    }

    fun openSettings() {
        val ctx = getApplication<Application>()
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:${ctx.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
        showManagePermission = false
    }

    private fun getDefaultPath(): String {
        val paths = FileUtils.getRootPaths()
        return paths.firstOrNull() ?: Environment.getExternalStorageDirectory().absolutePath
    }

    // ── Live directory refresh (inotify via FileObserver) ──

    private var leftWatcher: DirWatcher? = null
    private var rightWatcher: DirWatcher? = null

    private fun updateWatcher(side: PaneSide, path: String) {
        val watcherRef = when (side) {
            PaneSide.LEFT -> ::leftWatcher
            PaneSide.RIGHT -> ::rightWatcher
        }
        watcherRef.get()?.close()
        watcherRef.set(DirWatcher(path) {
            viewModelScope.launch {
                if (getPane(side).currentPath == path) loadDirectory(side, path, showLoading = false)
            }
        })
    }

    // ── Directory loading & navigation ──

    fun loadDirectory(side: PaneSide, path: String, showLoading: Boolean = true) {
        viewModelScope.launch {
            if (showLoading) updatePane(side) { copy(isLoading = true) }

            val files = withContext(Dispatchers.IO) {
                FileUtils.listFiles(path, showHidden, sortOrder, sortAscending, activeFilter)
            }

            val state = getPane(side)
            val newHistory = if (state.currentPath != path && showLoading) {
                state.history + state.currentPath
            } else {
                state.history
            }

            updatePane(side) {
                val fc = files.count { it.isDirectory }
                val fic = files.count { !it.isDirectory }
                val ts = files.filter { !it.isDirectory }.sumOf { it.size }
                copy(
                    currentPath = path,
                    files = files,
                    selectedFiles = emptySet(),
                    isLoading = false,
                    history = newHistory,
                    forwardHistory = if (showLoading) emptyList() else forwardHistory,
                    folderCount = fc,
                    fileCount = fic,
                    totalSize = ts,
                    rangeAnchor = if (showLoading) null else rangeAnchor
                )
            }
            updateBookmarkState()
            updateWatcher(side, path)
        }
    }

    /**
     * Navigate [side] to [targetPath]; if linked-pane mode is enabled,
     * mirror the same relative step in the other pane when possible.
     */
    private fun navigateTo(side: PaneSide, targetPath: String) {
        val prev = getPane(side).currentPath
        loadDirectory(side, targetPath)
        if (!linkedPanes) return
        val other = side.other()
        val rel = relativeStep(prev, targetPath) ?: return
        val otherBase = getPane(other).currentPath
        val otherTarget = applyRelative(otherBase, rel)
        if (otherTarget != null && otherTarget != otherBase && File(otherTarget).isDirectory) {
            loadDirectory(other, otherTarget, showLoading = false)
        }
    }

    private fun relativeStep(prev: String, next: String): String? = when {
        prev == next -> null
        next.startsWith("$prev/") -> next.removePrefix("$prev/")
        prev.startsWith("$next/") -> "../".repeat(prev.removePrefix("$next/").count { it == '/' } + 1)
        else -> null
    }

    private fun applyRelative(base: String, rel: String): String? {
        var f = File(base)
        for (part in rel.split('/').filter { it.isNotBlank() }) {
            f = if (part == "..") (f.parent ?: return null)?.let(::File) ?: return null
            else File(f, part)
        }
        return if (f.exists() && f.isDirectory) f.absolutePath else null
    }

    fun refresh(side: PaneSide) {
        loadDirectory(side, getPane(side).currentPath, showLoading = false)
    }

    fun navigateUp(side: PaneSide) {
        val parent = FileUtils.getParentPath(getPane(side).currentPath)
        if (parent != getPane(side).currentPath) {
            navigateTo(side, parent)
        }
    }

    fun navigateBack(side: PaneSide) {
        val state = getPane(side)
        if (state.history.isNotEmpty()) {
            val prev = state.history.last()
            val newHistory = state.history.dropLast(1)
            updatePane(side) {
                copy(
                    currentPath = prev,
                    history = newHistory,
                    forwardHistory = forwardHistory + state.currentPath
                )
            }
            loadDirectory(side, prev)
        }
    }

    fun navigateForward(side: PaneSide) {
        val state = getPane(side)
        if (state.forwardHistory.isNotEmpty()) {
            val next = state.forwardHistory.last()
            val newForward = state.forwardHistory.dropLast(1)
            updatePane(side) {
                copy(
                    currentPath = next,
                    history = history + state.currentPath,
                    forwardHistory = newForward
                )
            }
            loadDirectory(side, next)
        }
    }

    // ── Selection ──

    fun toggleSelection(side: PaneSide, path: String) {
        val state = getPane(side)
        val newSelected = state.selectedFiles.toMutableSet()
        if (newSelected.contains(path)) {
            newSelected.remove(path)
        } else {
            newSelected.add(path)
        }
        updatePane(side) { copy(selectedFiles = newSelected) }
    }

    fun rangeSelect(side: PaneSide, path: String) {
        val state = getPane(side)
        val anchor = state.rangeAnchor ?: return

        val indices = state.files.map { it.path }
        val anchorIdx = indices.indexOf(anchor)
        val targetIdx = indices.indexOf(path)
        if (anchorIdx < 0 || targetIdx < 0) return

        val start = minOf(anchorIdx, targetIdx)
        val end = maxOf(anchorIdx, targetIdx)
        val range = indices.subList(start, end + 1).toSet()
        updatePane(side) { copy(selectedFiles = selectedFiles + range) }
    }

    fun selectAll(side: PaneSide) {
        val state = getPane(side)
        val allPaths = state.files.map { it.path }.toSet()
        updatePane(side) { copy(selectedFiles = allPaths) }
    }

    fun clearSelections(side: PaneSide) {
        updatePane(side) { copy(selectedFiles = emptySet()) }
    }

    fun invertSelection(side: PaneSide) {
        val state = getPane(side)
        val allPaths = state.files.map { it.path }.toSet()
        updatePane(side) { copy(selectedFiles = allPaths - state.selectedFiles) }
    }

    // ── Filter ──

    fun updateFilter(filter: FilterType) {
        activeFilter = filter
        refresh(PaneSide.LEFT)
        refresh(PaneSide.RIGHT)
    }

    // ── Breadcrumbs ──

    fun getBreadcrumbs(path: String): List<Breadcrumb> {
        val parts = path.split("/").filter { it.isNotBlank() }
        val crumbs = mutableListOf<Breadcrumb>()
        var accumulated = ""
        for (p in parts) {
            accumulated += "/$p"
            crumbs.add(Breadcrumb(p, accumulated))
        }
        return crumbs
    }

    data class Breadcrumb(val label: String, val path: String)

    // ── Bookmarks ──

    private fun updateBookmarkState() {
        val active = getPane(PaneSide.LEFT).currentPath
        isBookmarked = bookmarks.contains(active)
    }

    fun toggleBookmark() {
        val path = leftPane.currentPath
        if (bookmarks.contains(path)) {
            prefs.removeBookmark(path)
        } else {
            prefs.addBookmark(path)
        }
        bookmarks = prefs.getBookmarks()
        updateBookmarkState()
    }

    fun navigateToBookmark(path: String) {
        navigateTo(PaneSide.LEFT, path)
        showBookmarks = false
    }

    // ── Context Menu ──

    fun showContextMenuFor(entry: FileEntry) {
        contextMenuTarget = entry
        showContextMenu = true
    }

    // ── Termux terminal ──

    fun openTerminalHere(side: PaneSide) {
        val dir = getPane(side).currentPath
        statusMessage = TermuxIntegration.openTerminalHere(getApplication(), dir)
    }

    // ── Share ──

    fun shareSelected(side: PaneSide) {
        val state = getPane(side)
        if (state.selectedFiles.isEmpty()) return
        shareTargets = state.selectedFiles.map { File(it) }
        showShareDialog = true
    }

    fun doShare() {
        val uris = shareTargets.map { Uri.fromFile(it) }
        val ctx = getApplication<Application>()
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(Intent.createChooser(intent, "Share"))
        showShareDialog = false
        shareTargets = emptyList()
    }

    // ── Clipboard Ops ──

    fun copySelected(side: PaneSide) {
        val state = getPane(side)
        if (state.selectedFiles.isNotEmpty()) {
            clipboard = ClipboardEntry(state.selectedFiles.toList(), ClipboardOperation.COPY)
            statusMessage = "${state.selectedFiles.size} item(s) copied"
            clearSelections(side)
        }
    }

    fun cutSelected(side: PaneSide) {
        val state = getPane(side)
        if (state.selectedFiles.isNotEmpty()) {
            clipboard = ClipboardEntry(state.selectedFiles.toList(), ClipboardOperation.CUT)
            statusMessage = "${state.selectedFiles.size} item(s) cut"
            clearSelections(side)
        }
    }

    fun pasteTo(side: PaneSide) {
        val clip = clipboard ?: return
        val destDir = getPane(side).currentPath

        viewModelScope.launch {
            val sources = clip.sourcePaths
            busyOp = BusyOp("Preparing…")
            val totalBytes = withContext(Dispatchers.IO) { sources.sumOf { FileUtils.treeSize(it) } }
            var done = 0L
            var successCount = 0
            var failCount = 0
            val renames = mutableListOf<Pair<String, String>>()

            for (source in sources) {
                val srcFile = File(source)
                val destPath = FileUtils.resolvePath(destDir, srcFile.name)

                // Backup any existing target so overwrite is undoable
                val destFile = File(destPath)
                if (destFile.exists()) {
                    val backup = backupToTrash(destFile)
                    if (backup != null) renames.add(destPath to backup)
                }

                busyOp = BusyOp("Copying ${srcFile.name}", done, totalBytes)
                val result = withContext(Dispatchers.IO) {
                    when (clip.operation) {
                        ClipboardOperation.COPY -> FileUtils.copyWithProgress(source, destPath) { d ->
                            busyOp = BusyOp("Copying ${srcFile.name}", done + d, totalBytes)
                        }
                        ClipboardOperation.CUT -> {
                            val r = FileUtils.moveWithRenameRecord(source, destPath)
                            if (r.second != null) renames.add(r.second!!)
                            r.first
                        }
                    }
                }
                if (result.isSuccess) successCount++ else failCount++
                done += FileUtils.treeSize(source)
            }

            if (clip.operation == ClipboardOperation.CUT) clipboard = null
            recordUndo(trashMap = emptyMap(), renames = renames)
            busyOp = null
            statusMessage = if (failCount == 0) "Pasted $successCount item(s)"
                else "Pasted $successCount item(s), $failCount failed"
            loadDirectory(side, destDir, showLoading = false)
        }
    }

    // ── Delete (to .trash where possible, undoable) ──

    fun deleteSelected(side: PaneSide) {
        val state = getPane(side)
        if (state.selectedFiles.isNotEmpty()) {
            deleteTargets = state.selectedFiles.toList()
            showDeleteConfirm = true
        }
    }

    fun confirmDelete() {
        viewModelScope.launch {
            var trashed = 0
            var hardDeleted = 0
            var failCount = 0
            val paths = deleteTargets.toList()
            val affectedDirs = mutableSetOf<String>()
            val trashMap = mutableMapOf<String, String>()

            val totalBytes = withContext(Dispatchers.IO) {
                paths.sumOf { if (File(it).exists()) FileUtils.treeSize(it) else 0L }
            }
            var done = 0L

            for (path in paths) {
                val file = File(path)
                if (!file.exists()) continue
                val parent = file.parent ?: continue
                affectedDirs.add(parent)
                busyOp = BusyOp("Deleting ${file.name}", done, totalBytes)

                val trashPath = moveToTrash(file)
                if (trashPath != null) {
                    trashed++
                    trashMap[path] = trashPath
                    done += FileUtils.treeSize(trashPath)
                } else {
                    val result = withContext(Dispatchers.IO) {
                        FileUtils.deleteWithProgress(path) { d -> busyOp = BusyOp("Deleting ${file.name}", done + d, totalBytes) }
                    }
                    if (result.isSuccess) { hardDeleted++; done += FileUtils.treeSize(path) } else failCount++
                }
            }

            showDeleteConfirm = false
            deleteTargets = emptyList()
            busyOp = null

            for (dir in affectedDirs) {
                for (side in PaneSide.entries) {
                    if (getPane(side).currentPath == dir) {
                        loadDirectory(side, dir, showLoading = false)
                    }
                }
            }

            recordUndo(trashMap = trashMap, renames = emptyList())

            statusMessage = when {
                failCount > 0 -> "Deleted $trashed+$hardDeleted, $failCount failed"
                trashed > 0 -> "Moved to .trash — tap Undo"
                hardDeleted > 0 -> "Deleted $hardDeleted item(s)"
                else -> "Nothing deleted"
            }
        }
    }

    /** Rename [file] into its sibling .trash dir; returns trash path or null. */
    private fun moveToTrash(file: File): String? {
        val parent = file.parent ?: return null
        val trashDir = File(parent, ".trash")
        if (!trashDir.exists() && !trashDir.mkdirs()) return null
        val trashFile = if (File(trashDir, file.name).exists()) {
            File(trashDir, "${file.nameWithoutExtension}_${System.currentTimeMillis()}.${file.extension}")
        } else {
            File(trashDir, file.name)
        }
        return if (file.renameTo(trashFile)) trashFile.absolutePath else null
    }

    /** Backup an existing target before overwriting; returns backup path or null. */
    private fun backupToTrash(file: File): String? = moveToTrash(file)

    /** Store undo state (replaces previous). */
    private fun recordUndo(trashMap: Map<String, String>, renames: List<Pair<String, String>>) {
        trashBuffer = trashMap
        renameBuffer = renames
        canUndo = trashMap.isNotEmpty() || renames.isNotEmpty()
        lastUndoMessage = buildString {
            if (trashMap.isNotEmpty()) append("${trashMap.size} item(s) in trash")
            if (renames.isNotEmpty()) {
                if (isNotEmpty()) append(", ")
                append("${renames.size} moved/replaced")
            }
        }
    }

    /** Undo the last destructive operation: restore trash items and reverse renames/moves. */
    fun undoLast() {
        val trashSnapshot = trashBuffer.toMap()
        val renameSnapshot = renameBuffer.toList()
        trashBuffer = emptyMap()
        renameBuffer = emptyList()
        canUndo = false
        viewModelScope.launch {
            var restored = 0
            val affectedDirs = mutableSetOf<String>()

            withContext(Dispatchers.IO) {
                // Reverse most recent rename first
                for ((oldPath, newPath) in renameSnapshot.reversed()) {
                    val newFile = File(newPath)
                    val oldFile = File(oldPath)
                    if (newFile.exists()) {
                        if (!oldFile.exists() && newFile.renameTo(oldFile)) {
                            restored++
                            affectedDirs.add(oldFile.parent ?: continue)
                        }
                    }
                }
                for ((origPath, trashPath) in trashSnapshot) {
                    val trashFile = File(trashPath)
                    val origFile = File(origPath)
                    if (trashFile.exists() && !origFile.exists()) {
                        origFile.parentFile?.mkdirs()
                        if (trashFile.renameTo(origFile)) {
                            restored++
                            affectedDirs.add(origFile.parent ?: continue)
                        }
                    }
                }
            }

            for (dir in affectedDirs) {
                for (side in PaneSide.entries) {
                    if (getPane(side).currentPath == dir) {
                        loadDirectory(side, dir, showLoading = false)
                    }
                }
            }
            statusMessage = "Undid operation — restored $restored item(s)"
        }
    }

    fun cancelDelete() {
        showDeleteConfirm = false
        deleteTargets = emptyList()
    }

    // ── Create / rename ──

    fun showNewFolder(side: PaneSide) {
        showNewFolderDialog = true
        rootPickerTarget = side
    }

    fun createFolder(side: PaneSide, name: String) {
        val path = getPane(side).currentPath
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { FileUtils.mkdir(path, name) }
            if (result.isSuccess) {
                statusMessage = "Folder created"
                loadDirectory(side, path, showLoading = false)
            } else {
                statusMessage = "Failed: ${result.exceptionOrNull()?.message}"
            }
            showNewFolderDialog = false
        }
    }

    fun showNewFile(side: PaneSide) {
        showNewFileDialog = true
        rootPickerTarget = side
    }

    fun createFile(side: PaneSide, name: String) {
        val path = getPane(side).currentPath
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { FileUtils.createFile(path, name) }
            if (result.isSuccess) {
                statusMessage = "File created"
                loadDirectory(side, path, showLoading = false)
            } else {
                statusMessage = "Failed: ${result.exceptionOrNull()?.message}"
            }
            showNewFileDialog = false
        }
    }

    fun showRename(entry: FileEntry) {
        renameTarget = entry
        showRenameDialog = true
    }

    fun rename(name: String) {
        val target = renameTarget ?: return
        viewModelScope.launch {
            val oldPath = target.path
            val newPath = java.io.File(File(oldPath).parent, name).absolutePath
            val result = withContext(Dispatchers.IO) { FileUtils.rename(oldPath, name) }
            if (result.isSuccess) {
                recordUndo(trashMap = emptyMap(), renames = listOf(oldPath to newPath))
                statusMessage = "Renamed — tap Undo to revert"
                for (side in PaneSide.entries) {
                    if (getPane(side).currentPath == File(oldPath).parent) {
                        loadDirectory(side, getPane(side).currentPath, showLoading = false)
                    }
                }
            } else {
                statusMessage = "Rename failed: ${result.exceptionOrNull()?.message}"
            }
            showRenameDialog = false
            renameTarget = null
        }
    }

    fun showBatchRename(side: PaneSide) {
        batchRenameTarget = side
        showBatchRenameDialog = true
    }

    fun applyBatchRename(find: String, replace: String, useRegex: Boolean) {
        val state = getPane(batchRenameTarget)
        val files = state.selectedFiles.map { File(it) }
        if (files.isEmpty()) {
            statusMessage = "No files selected"
            showBatchRenameDialog = false
            return
        }
        viewModelScope.launch {
            var count = 0
            val renames = mutableListOf<Pair<String, String>>()
            withContext(Dispatchers.IO) {
                for (file in files) {
                    val newName = if (useRegex) {
                        file.name.replace(Regex(find), replace)
                    } else {
                        file.name.replace(find, replace)
                    }
                    if (newName.isNotBlank() && newName != file.name) {
                        val newPath = java.io.File(file.parent, newName)
                        if (FileUtils.rename(file.absolutePath, newPath.absolutePath).isSuccess) {
                            renames.add(file.absolutePath to newPath.absolutePath)
                            count++
                        }
                    }
                }
            }
            recordUndo(trashMap = emptyMap(), renames = renames)
            showBatchRenameDialog = false
            statusMessage = "Renamed $count file(s) — tap Undo to revert"
            loadDirectory(batchRenameTarget, getPane(batchRenameTarget).currentPath, showLoading = false)
        }
    }

    // ── Compress selection to zip ──

    fun compressSelected(side: PaneSide) {
        val state = getPane(side)
        if (state.selectedFiles.isEmpty()) return
        val sources = state.selectedFiles.toList()
        viewModelScope.launch {
            val defaultName = if (sources.size == 1)
                "${File(sources[0]).nameWithoutExtension}.zip"
            else "archive_${System.currentTimeMillis()}.zip"
            val outPath = FileUtils.resolvePath(state.currentPath, defaultName)
            busyOp = BusyOp("Creating $defaultName")
            val result = withContext(Dispatchers.IO) { ArchiveSupport.createZip(sources, outPath) }
            busyOp = null
            result.fold(
                onSuccess = {
                    recordUndo(trashMap = emptyMap(), renames = emptyList())
                    statusMessage = it
                    loadDirectory(side, state.currentPath, showLoading = false)
                },
                onFailure = { statusMessage = "Zip failed: ${it.message}" }
            )
        }
    }

    // ── Extract archive here ──

    fun extractArchiveHere(archiveFilePath: String) {
        viewModelScope.launch {
            val parent = File(archiveFilePath).parent ?: return@launch
            val outDir = FileUtils.resolvePath(parent, File(archiveFilePath).nameWithoutExtension)
            busyOp = BusyOp("Extracting ${File(archiveFilePath).name}")
            val result = withContext(Dispatchers.IO) { ArchiveSupport.extractAll(archiveFilePath, outDir) }
            busyOp = null
            statusMessage = result.getOrElse { "Extract failed: ${it.message}" }
            for (side in PaneSide.entries) {
                if (getPane(side).currentPath == parent) loadDirectory(side, parent, showLoading = false)
            }
        }
    }

    // ── Properties / search / goto ──

    fun showProperties(entry: FileEntry) {
        propertiesTarget = entry
        showPropertiesDialog = true
    }

    fun showSearch(side: PaneSide) {
        searchQueryText = ""
        showSearchDialog = true
        goToTarget = side
    }

    fun performSearch() {
        val side = goToTarget ?: PaneSide.LEFT
        val path = getPane(side).currentPath
        viewModelScope.launch {
            statusMessage = "Searching..."
            val results = withContext(Dispatchers.IO) {
                FileUtils.search(path, searchQueryText)
            }
            if (results.isEmpty()) {
                statusMessage = "No results for \"$searchQueryText\""
            } else {
                statusMessage = "Found ${results.size} result(s)\n${results.take(10).joinToString("\n")}"
            }
            showSearchDialog = false
        }
    }

    fun showRootPicker(side: PaneSide) {
        rootPickerTarget = side
        showRootPicker = true
    }

    fun navigateToRoot(side: PaneSide, path: String) {
        navigateTo(side, path)
        showRootPicker = false
    }

    fun showGoTo(side: PaneSide) {
        goToTarget = side
        showGoToDialog = true
    }

    fun goToPath(side: PaneSide, path: String) {
        val f = File(path)
        if (f.exists() && f.isDirectory) {
            navigateTo(side, path)
        } else {
            statusMessage = "Path not found: $path"
        }
        showGoToDialog = false
    }

    fun clearStatus() {
        statusMessage = null
    }

    fun getSelectedCount(side: PaneSide): Int = getPane(side).selectedFiles.size

    fun getClipboardInfo(): String? {
        return clipboard?.let {
            "${it.sourcePaths.size} item(s) ${if (it.operation == ClipboardOperation.COPY) "copied" else "cut"}"
        }
    }

    private fun PaneSide.other(): PaneSide = when (this) {
        PaneSide.LEFT -> PaneSide.RIGHT
        PaneSide.RIGHT -> PaneSide.LEFT
    }

    private fun getPane(side: PaneSide): PaneState = when (side) {
        PaneSide.LEFT -> leftPane
        PaneSide.RIGHT -> rightPane
    }

    private fun updatePane(side: PaneSide, transform: PaneState.() -> PaneState) {
        when (side) {
            PaneSide.LEFT -> leftPane = leftPane.transform()
            PaneSide.RIGHT -> rightPane = rightPane.transform()
        }
    }
}
