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
import com.twopane.fm.ui.components.ApkAction
import com.twopane.fm.util.AppPreferences
import com.twopane.fm.util.FileType
import com.twopane.fm.util.FileUtils
import com.twopane.fm.model.SortOrder
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

class FileExplorerViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = AppPreferences(application)

    var leftPane by mutableStateOf(PaneState())
        private set

    var rightPane by mutableStateOf(PaneState())
        private set

    var clipboard by mutableStateOf<ClipboardEntry?>(null)
        private set

    var statusMessage by mutableStateOf<String?>(null)

    // Undo buffer
    private var trashBuffer: Map<String, String> = emptyMap()
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

    // APK viewer
    var showApkViewer by mutableStateOf(false)
    var apkViewerPath by mutableStateOf("")

    // APK menu (bottom sheet)
    var showApkMenu by mutableStateOf(false)
    var apkMenuPath by mutableStateOf("")
        private set

    // Smali editor
    var showSmaliEditor by mutableStateOf(false)
    var smaliEditorDir by mutableStateOf("")

    // Text editor state
    var textEditorPath by mutableStateOf("")
        private set
    var textEditorReadOnly by mutableStateOf(false)
        private set

    // Persisted settings
    private val _showHidden = mutableStateOf(prefs.showHidden)
    var showHidden: Boolean
        get() = _showHidden.value
        set(v) { _showHidden.value = v; prefs.showHidden = v }

    private val _sortOrder = mutableStateOf(prefs.sortOrder)
    var sortOrder: SortOrder
        get() = _sortOrder.value
        set(v) { _sortOrder.value = v; prefs.sortOrder = v }

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

    enum class PaneSide { LEFT, RIGHT }
    enum class Screen {
        FILE_MANAGER, APK_BROWSER, SMALI_BROWSER, SMALI_EDITOR,
        TEXT_EDITOR, JAVA_BROWSER, APK_INFO, PERMISSION_LIST, APK_TOOL_RESULT
    }

    enum class ToolOp { SIGN, ALIGN, REBUILD, REMOVE_VERIFY, CLONE }
    enum class ToolStatus { IDLE, RUNNING, SUCCESS, ERROR }

    var currentScreen by mutableStateOf(Screen.FILE_MANAGER)
        private set

    // APK editor state holders
    var apkEditorPath by mutableStateOf("")
        private set
    var apkDecompiledDir by mutableStateOf("")
        private set
    var apkDisassembledDir by mutableStateOf("")
        private set
    var currentSmaliFile by mutableStateOf("")
        private set
    var currentDexName by mutableStateOf("")
        private set
    var editorWorkingDir by mutableStateOf("")
        private set
    var javaBrowserDir by mutableStateOf("")
        private set

    // Tool operation state
    var toolOpType by mutableStateOf(ToolOp.SIGN)
        private set
    var toolInputApk by mutableStateOf("")
        private set
    var toolOutputApk by mutableStateOf<String?>(null)
        private set
    var toolStatus by mutableStateOf(ToolStatus.IDLE)
        private set
    var toolMessage by mutableStateOf("")
        private set
    var toolProgress by mutableStateOf("")
        private set
    var clonePackageName by mutableStateOf("")

    // ── Navigation ──

    fun navigateToApkBrowser(apkPath: String) {
        apkEditorPath = apkPath
        currentScreen = Screen.APK_BROWSER
    }

    fun navigateToSmaliBrowser(disassembledDir: String, dexName: String) {
        apkDisassembledDir = disassembledDir
        currentDexName = dexName
        currentScreen = Screen.SMALI_BROWSER
    }

    fun navigateToSmaliEditor(smaliFilePath: String, workingDir: String) {
        currentSmaliFile = smaliFilePath
        editorWorkingDir = workingDir
        currentScreen = Screen.SMALI_EDITOR
    }

    fun navigateToTextEditor(path: String, readOnly: Boolean = false) {
        textEditorPath = path
        textEditorReadOnly = readOnly
        currentScreen = Screen.TEXT_EDITOR
    }

    fun navigateToJavaBrowser(javaDir: String) {
        javaBrowserDir = javaDir
        currentScreen = Screen.JAVA_BROWSER
    }

    fun navigateToApkInfo(apkPath: String) {
        apkEditorPath = apkPath
        currentScreen = Screen.APK_INFO
    }

    fun navigateToPermissionList(apkPath: String) {
        apkEditorPath = apkPath
        currentScreen = Screen.PERMISSION_LIST
    }

    fun navigateToToolResult(apkPath: String, op: ToolOp, pkgName: String = "") {
        toolInputApk = apkPath
        toolOpType = op
        toolOutputApk = null
        toolStatus = ToolStatus.IDLE
        toolMessage = pkgName
        toolProgress = ""
        currentScreen = Screen.APK_TOOL_RESULT
    }

    fun runToolOperation() {
        val apkPath = toolInputApk
        val ctx = getApplication<Application>()
        val loader = (ctx as com.twopane.fm.TwoPaneApp).toolLoader
        viewModelScope.launch {
            toolStatus = ToolStatus.RUNNING
            toolProgress = "Starting..."
            val outPath = when (toolOpType) {
                ToolOp.SIGN -> apkPath.replace(".apk", "_signed.apk")
                ToolOp.ALIGN -> apkPath.replace(".apk", "_aligned.apk")
                ToolOp.REBUILD -> apkPath.replace(".apk", "_patched.apk")
                ToolOp.REMOVE_VERIFY -> apkPath.replace(".apk", "_noverify.apk")
                ToolOp.CLONE -> apkPath.replace(".apk", "_clone.apk")
            }
            val result = withContext(Dispatchers.IO) {
                when (toolOpType) {
                    ToolOp.SIGN -> com.twopane.fm.util.EmbeddedTools.signApk(apkPath, outPath)
                    ToolOp.ALIGN -> {
                        if (loader.nativeZipalign != null)
                            Result.success(loader.exec(loader.nativeZipalign!!, "-f", "-p", "4", apkPath, outPath))
                        else Result.failure(Exception("zipalign not available"))
                    }
                    ToolOp.REBUILD -> com.twopane.fm.util.EmbeddedTools.rebuildAndSign(apkPath, outPath, loader.nativeZipalign) {
                        toolProgress = it
                    }
                    ToolOp.REMOVE_VERIFY -> com.twopane.fm.util.EmbeddedTools.removeSignatureVerification(apkPath, outPath, loader.nativeZipalign) {
                        toolProgress = it
                    }
                    ToolOp.CLONE -> com.twopane.fm.util.EmbeddedTools.cloneApk(apkPath, outPath, toolMessage, loader.nativeZipalign) {
                        toolProgress = it
                    }
                }
            }
            result.onSuccess {
                toolOutputApk = outPath
                toolStatus = ToolStatus.SUCCESS
                toolMessage = it
            }
            result.onFailure {
                toolStatus = ToolStatus.ERROR
                toolMessage = "Failed: ${it.message}"
            }
        }
    }

    fun installFromToolResult() {
        val ctx = getApplication<Application>()
        val target = toolOutputApk ?: toolInputApk
        try {
            val file = File(target)
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
        } catch (e: Exception) {
            toolMessage = "Install failed: ${e.message}"
        }
    }

    fun shareFromToolResult() {
        val ctx = getApplication<Application>()
        val target = toolOutputApk ?: toolInputApk
        try {
            val uri = Uri.fromFile(File(target))
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.android.package-archive"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(Intent.createChooser(intent, "Share APK"))
        } catch (e: Exception) {
            toolMessage = "Share failed: ${e.message}"
        }
    }

    fun extractToolResult() {
        val target = toolOutputApk ?: toolInputApk
        viewModelScope.launch {
            statusMessage = "Extracting..."
            val result = withContext(Dispatchers.IO) {
                try {
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS
                    )
                    val dest = File(downloadsDir, File(target).name)
                    File(target).copyTo(dest, overwrite = true)
                    dest.absolutePath
                } catch (e: Exception) { null }
            }
            statusMessage = if (result != null) "Extracted to Downloads" else "Extract failed"
        }
    }

    fun navigateBackFromApkEditor() {
        when (currentScreen) {
            Screen.APK_TOOL_RESULT, Screen.APK_INFO, Screen.PERMISSION_LIST ->
                currentScreen = Screen.FILE_MANAGER
            Screen.TEXT_EDITOR -> {
                currentScreen = if (javaBrowserDir.isNotEmpty()) Screen.JAVA_BROWSER
                else if (apkEditorPath.isNotEmpty()) Screen.APK_BROWSER
                else Screen.FILE_MANAGER
            }
            Screen.JAVA_BROWSER -> currentScreen = Screen.APK_BROWSER
            Screen.SMALI_EDITOR -> currentScreen = Screen.SMALI_BROWSER
            Screen.SMALI_BROWSER -> currentScreen = Screen.APK_BROWSER
            Screen.APK_BROWSER -> {
                currentScreen = Screen.FILE_MANAGER
                clearApkState()
            }
            Screen.FILE_MANAGER -> {}
        }
    }

    fun exitApkEditor() {
        currentScreen = Screen.FILE_MANAGER
        clearApkState()
    }

    /**
     * Handle files received from external apps (intents).
     * Routes to the appropriate screen based on file type.
     */
    fun handleIncomingFile(path: String, mimeType: String?) {
        val file = File(path)
        if (!file.exists()) return

        // Navigate file manager to the file's parent directory
        val parentDir = file.parent ?: return
        leftPane = leftPane.copy(currentPath = parentDir)
        loadDirectory(PaneSide.LEFT, leftPane.currentPath)

        when {
            path.endsWith(".apk", true) || mimeType == "application/vnd.android.package-archive" -> {
                showApkMenu = true
                apkMenuPath = path
            }
            path.endsWith(".smali", true) -> {
                navigateToTextEditor(path, readOnly = false)
            }
            path.endsWith(".xml", true) || path.endsWith(".json", true) ||
                path.endsWith(".txt", true) || path.endsWith(".md", true) ||
                path.endsWith(".java", true) || path.endsWith(".kt", true) ||
                path.endsWith(".kt", true) || path.endsWith(".c", true) ||
                path.endsWith(".cpp", true) || path.endsWith(".h", true) ||
                path.endsWith(".py", true) || path.endsWith(".js", true) ||
                path.endsWith(".html", true) || path.endsWith(".css", true) ||
                path.endsWith(".gradle", true) || path.endsWith(".properties", true) ||
                mimeType?.startsWith("text/") == true -> {
                navigateToTextEditor(path, readOnly = false)
            }
            path.endsWith(".zip", true) || path.endsWith(".rar", true) ||
                path.endsWith(".7z", true) || mimeType == "application/zip" -> {
                // Show in file manager - user can tap to open
                statusMessage = "File: ${file.name}"
            }
            else -> {
                navigateToTextEditor(path, readOnly = true)
            }
        }
    }

    private fun clearApkState() {
        apkEditorPath = ""
        apkDecompiledDir = ""
        apkDisassembledDir = ""
        currentSmaliFile = ""
        currentDexName = ""
        editorWorkingDir = ""
        textEditorPath = ""
        textEditorReadOnly = false
        javaBrowserDir = ""
    }

    // ── File opening from file manager ──

    /**
     * Open a file from the file manager. Logic:
     * 1. Directories → enter directory
     * 2. .apk files → APK browser
     * 3. Text files → text editor
     * 4. Other files → try external app, fallback to text editor
     */
    fun openFile(side: PaneSide, entry: FileEntry) {
        if (entry.isDirectory) {
            enterDirectory(side, entry)
            return
        }

        when {
            entry.name.endsWith(".apk", true) -> showApkMenuFor(entry)
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

            // Check if any app can handle this intent
            if (intent.resolveActivity(ctx.packageManager) != null) {
                ctx.startActivity(intent)
            } else {
                // Try with wildcard mime type
                val fallback = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "*/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (fallback.resolveActivity(ctx.packageManager) != null) {
                    ctx.startActivity(fallback)
                } else {
                    // Last resort: open as text
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

    fun loadDirectory(side: PaneSide, path: String) {
        viewModelScope.launch {
            updatePane(side) { copy(isLoading = true) }

            val files = withContext(Dispatchers.IO) {
                FileUtils.listFiles(path, showHidden, sortOrder, activeFilter)
            }

            val state = getPane(side)
            val newHistory = if (state.currentPath != path) {
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
                    forwardHistory = emptyList(),
                    folderCount = fc,
                    fileCount = fic,
                    totalSize = ts,
                    rangeAnchor = null
                )
            }
            updateBookmarkState()
        }
    }

    fun refresh(side: PaneSide) {
        loadDirectory(side, getPane(side).currentPath)
    }

    fun navigateUp(side: PaneSide) {
        val parent = FileUtils.getParentPath(getPane(side).currentPath)
        if (parent != getPane(side).currentPath) {
            loadDirectory(side, parent)
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

    fun enterDirectory(side: PaneSide, entry: FileEntry) {
        if (entry.isDirectory) {
            loadDirectory(side, entry.path)
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
        loadDirectory(PaneSide.LEFT, path)
        showBookmarks = false
    }

    // ── Context Menu ──

    fun showContextMenuFor(entry: FileEntry) {
        contextMenuTarget = entry
        showContextMenu = true
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
            statusMessage = "Pasting..."
            var successCount = 0
            var failCount = 0

            for (source in clip.sourcePaths) {
                val srcFile = File(source)
                val destPath = FileUtils.resolvePath(destDir, srcFile.name)
                val result = when (clip.operation) {
                    ClipboardOperation.COPY -> FileUtils.copy(source, destPath)
                    ClipboardOperation.CUT -> FileUtils.move(source, destPath)
                }
                if (result.isSuccess) successCount++ else failCount++
            }

            clipboard = null
            val msg = if (failCount == 0) {
                "Pasted $successCount item(s)"
            } else {
                "Pasted $successCount item(s), $failCount failed"
            }
            statusMessage = msg
            loadDirectory(side, destDir)
        }
    }

    fun deleteSelected(side: PaneSide) {
        val state = getPane(side)
        if (state.selectedFiles.isNotEmpty()) {
            deleteTargets = state.selectedFiles.toList()
            showDeleteConfirm = true
        }
    }

    fun confirmDelete() {
        viewModelScope.launch {
            var successCount = 0
            var failCount = 0
            val paths = deleteTargets.toList()
            val affectedDirs = mutableSetOf<String>()
            val trashMap = mutableMapOf<String, String>()

            for (path in paths) {
                val file = File(path)
                val parent = file.parent ?: continue
                affectedDirs.add(parent)
                val trashName = ".twopane_tmp_${System.currentTimeMillis()}_${file.name}"
                val trashFile = File(parent, trashName)
                if (file.renameTo(trashFile)) {
                    successCount++
                    trashMap[path] = trashFile.absolutePath
                } else {
                    if (FileUtils.delete(path).isSuccess) successCount++ else failCount++
                }
            }

            showDeleteConfirm = false
            deleteTargets = emptyList()

            for (dir in affectedDirs) {
                for (side in PaneSide.entries) {
                    if (getPane(side).currentPath == dir) {
                        loadDirectory(side, dir)
                    }
                }
            }

            trashBuffer = trashMap
            canUndo = trashMap.isNotEmpty()
            lastUndoMessage = "Deleted ${trashMap.size} item(s)"

            if (trashMap.isNotEmpty()) {
                val snapshot = trashMap.toMap()
                launch {
                    kotlinx.coroutines.delay(30_000)
                    for ((_, trashPath) in snapshot) {
                        FileUtils.delete(trashPath)
                    }
                    if (trashBuffer.keys == snapshot.keys) {
                        trashBuffer = emptyMap()
                        canUndo = false
                    }
                }
            }

            statusMessage = if (failCount == 0 && successCount > 0) {
                "Deleted $successCount item(s) — tap Undo"
            } else if (failCount > 0) {
                "Deleted $successCount item(s), $failCount failed"
            } else {
                "Delete failed"
            }
        }
    }

    fun undoDelete() {
        val snapshot = trashBuffer.toMap()
        trashBuffer = emptyMap()
        canUndo = false
        viewModelScope.launch {
            var restored = 0
            val affectedDirs = mutableSetOf<String>()
            for ((origPath, trashPath) in snapshot) {
                val trashFile = File(trashPath)
                val origFile = File(origPath)
                if (trashFile.renameTo(origFile)) {
                    restored++
                    affectedDirs.add(origFile.parent ?: continue)
                }
            }
            for (dir in affectedDirs) {
                for (side in PaneSide.entries) {
                    if (getPane(side).currentPath == dir) {
                        loadDirectory(side, dir)
                    }
                }
            }
            statusMessage = "Undid deletion of $restored item(s)"
        }
    }

    fun cancelDelete() {
        showDeleteConfirm = false
        deleteTargets = emptyList()
    }

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
                loadDirectory(side, path)
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
                loadDirectory(side, path)
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
            val result = withContext(Dispatchers.IO) { FileUtils.rename(target.path, name) }
            if (result.isSuccess) {
                statusMessage = "Renamed"
                for (side in PaneSide.entries) {
                    if (getPane(side).currentPath == File(target.path).parent) {
                        loadDirectory(side, getPane(side).currentPath)
                    }
                }
            } else {
                statusMessage = "Rename failed: ${result.exceptionOrNull()?.message}"
            }
            showRenameDialog = false
            renameTarget = null
        }
    }

    fun showProperties(entry: FileEntry) {
        propertiesTarget = entry
        showPropertiesDialog = true
    }

    fun showApkViewer(entry: FileEntry) {
        navigateToApkBrowser(entry.path)
    }

    fun showApkMenuFor(entry: FileEntry) {
        apkMenuPath = entry.path
        showApkMenu = true
    }

    fun handleApkAction(action: ApkAction, apkPath: String) {
        when (action) {
            ApkAction.APP_INFO -> navigateToApkInfo(apkPath)
            ApkAction.MANIFEST -> {
                val manifest = com.twopane.fm.util.ApkUtils.getManifestText(apkPath)
                if (manifest != null) {
                    val f = File(getApplication<Application>().cacheDir, "apk_extract/AndroidManifest_decoded.xml")
                    f.parentFile?.mkdirs()
                    f.writeText(manifest)
                    navigateToTextEditor(f.absolutePath, readOnly = false)
                } else {
                    statusMessage = "Failed to decode manifest"
                }
            }
            ApkAction.PERMISSIONS -> navigateToPermissionList(apkPath)
            ApkAction.BROWSE_ENTRIES -> navigateToApkBrowser(apkPath)
            ApkAction.DISASSEMBLE_SMALI, ApkAction.DECOMPILE_JAVA, ApkAction.DECOMPILE_FULL ->
                navigateToApkBrowser(apkPath)
            ApkAction.SIGN_APK -> navigateToToolResult(apkPath, ToolOp.SIGN)
            ApkAction.ALIGN_APK -> navigateToToolResult(apkPath, ToolOp.ALIGN)
            ApkAction.REBUILD_SIGN -> navigateToToolResult(apkPath, ToolOp.REBUILD)
            ApkAction.DUMP_RESOURCES -> {
                val outFile = File(getApplication<Application>().cacheDir, "apk_extract/resources_decoded.txt")
                outFile.parentFile?.mkdirs()
                viewModelScope.launch {
                    val loader = (getApplication<Application>() as com.twopane.fm.TwoPaneApp).toolLoader
                    val result = withContext(Dispatchers.IO) {
                        com.twopane.fm.util.EmbeddedTools.decodeResources(apkPath, outFile.absolutePath, loader.nativeAapt2)
                    }
                    result.onSuccess { navigateToTextEditor(outFile.absolutePath, readOnly = true) }
                    result.onFailure { statusMessage = "Failed: ${it.message}" }
                }
            }
            ApkAction.CLONE_APK -> {
                val pkg = com.twopane.fm.util.ApkUtils.getApkInfo(apkPath)?.packageName?.replace(".debug", "") ?: "com.clone.app"
                navigateToToolResult(apkPath, ToolOp.CLONE, pkg)
            }
            ApkAction.REMOVE_VERIFY -> navigateToToolResult(apkPath, ToolOp.REMOVE_VERIFY)
            ApkAction.INSTALL -> installApk(apkPath)
            ApkAction.SHARE -> shareApk(apkPath)
            ApkAction.EXTRACT -> extractApk(apkPath)
        }
        showApkMenu = false
    }

    private fun installApk(apkPath: String) {
        val ctx = getApplication<Application>()
        try {
            val file = File(apkPath)
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
        } catch (e: Exception) {
            statusMessage = "Cannot install: ${e.message}"
        }
    }

    private fun shareApk(apkPath: String) {
        val ctx = getApplication<Application>()
        try {
            val file = File(apkPath)
            val uri = Uri.fromFile(file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.android.package-archive"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(Intent.createChooser(intent, "Share APK"))
        } catch (e: Exception) {
            statusMessage = "Cannot share: ${e.message}"
        }
    }

    private fun extractApk(apkPath: String) {
        viewModelScope.launch {
            statusMessage = "Extracting..."
            val result = withContext(Dispatchers.IO) {
                try {
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS
                    )
                    val dest = File(downloadsDir, File(apkPath).name)
                    File(apkPath).copyTo(dest, overwrite = true)
                    dest.absolutePath
                } catch (e: Exception) { null }
            }
            statusMessage = if (result != null) "Extracted to Downloads" else "Extract failed"
        }
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
        loadDirectory(side, path)
        showRootPicker = false
    }

    fun showGoTo(side: PaneSide) {
        goToTarget = side
        showGoToDialog = true
    }

    fun goToPath(side: PaneSide, path: String) {
        val f = File(path)
        if (f.exists() && f.isDirectory) {
            loadDirectory(side, path)
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
