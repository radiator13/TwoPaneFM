package com.twopane.fm.ui.screens

import android.content.Intent
import android.widget.ImageView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.twopane.fm.TwoPaneApp
import com.twopane.fm.ui.components.ApkAction
import com.twopane.fm.ui.components.ApkBrowserMenu
import com.twopane.fm.util.ApkUtils
import com.twopane.fm.util.EmbeddedTools
import com.twopane.fm.util.FileType
import com.twopane.fm.util.FileCategory
import com.twopane.fm.util.FileUtils
import com.twopane.fm.viewmodel.FileExplorerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ApkBrowserScreen(
    apkPath: String,
    viewModel: FileExplorerViewModel,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as TwoPaneApp
    val loader = app.toolLoader
    val scope = rememberCoroutineScope()

    val apkName = remember { apkPath.substringAfterLast("/") }
    val info = remember { ApkUtils.getApkInfo(apkPath) }

    var entries by remember { mutableStateOf(listOf<ApkEntry>()) }
    var isLoading by remember { mutableStateOf(true) }
    var statusText by remember { mutableStateOf("") }
    var isBusy by remember { mutableStateOf(false) }

    // Dialog states
    var showDexMenu by remember { mutableStateOf(false) }
    var selectedDex by remember { mutableStateOf("") }
    var showImageViewer by remember { mutableStateOf(false) }
    var imageViewerPath by remember { mutableStateOf("") }
    var showManifestDialog by remember { mutableStateOf(false) }
    var showPermissionDialog by remember { mutableStateOf(false) }
    var showExtractDialog by remember { mutableStateOf(false) }
    var extractTarget by remember { mutableStateOf<ApkEntry?>(null) }
    var showCloneDialog by remember { mutableStateOf(false) }
    var clonePackageName by remember { mutableStateOf("") }

    val extractCacheDir = remember { File(ctx.cacheDir, "apk_extract").apply { mkdirs() } }
    var decompiledDir by remember { mutableStateOf<String?>(null) }
    var rebuiltApkPath by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(apkPath) {
        withContext(Dispatchers.IO) {
            val list = mutableListOf<ApkEntry>()
            try {
                ZipFile(File(apkPath)).use { zip ->
                    zip.entries().asSequence().sortedBy { it.name }.forEach { e ->
                        val category = FileType.detect(e.name, isInsideApk = true)
                        list.add(ApkEntry(
                            name = e.name,
                            size = if (e.isDirectory) 0 else e.size,
                            isDirectory = e.isDirectory,
                            compressedSize = if (e.isDirectory) 0 else e.compressedSize,
                            category = category
                        ))
                    }
                }
            } catch (_: Exception) {}
            entries = list
            isLoading = false
        }
    }

    suspend fun extractFromApk(entryName: String): String? = withContext(Dispatchers.IO) {
        try {
            val outFile = File(extractCacheDir, entryName.replace("/", "_"))
            if (outFile.exists()) return@withContext outFile.absolutePath
            outFile.parentFile?.mkdirs()
            ZipFile(File(apkPath)).use { zip ->
                val zipEntry = zip.getEntry(entryName) ?: return@withContext null
                zip.getInputStream(zipEntry).use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
            outFile.absolutePath
        } catch (e: Exception) { null }
    }

    // ── Handle APK menu actions ──
    fun handleApkAction(action: ApkAction) {
        when (action) {
            ApkAction.APP_INFO -> {
                statusText = buildString {
                    info?.let {
                        appendLine("Package: ${it.packageName}")
                        appendLine("Version: ${it.versionName} (${it.versionCode})")
                        appendLine("SDK: ${it.minSdk} -> ${it.targetSdk}")
                        appendLine("Entries: ${it.entryCount}")
                        appendLine("DEX: ${it.dexCount} files (${FileUtils.formatSize(it.totalDexSize)})")
                    }
                }
            }
            ApkAction.MANIFEST -> showManifestDialog = true
            ApkAction.PERMISSIONS -> showPermissionDialog = true
            ApkAction.BROWSE_ENTRIES -> { /* already showing entries */ }

            ApkAction.DISASSEMBLE_SMALI -> {
                if (selectedDex.isEmpty()) {
                    // Find first DEX
                    val firstDex = entries.find { it.category == FileCategory.DEX }?.name
                    if (firstDex != null) {
                        selectedDex = firstDex
                        showDexMenu = true
                    } else {
                        statusText = "No DEX files found"
                    }
                } else {
                    showDexMenu = true
                }
            }
            ApkAction.DECOMPILE_JAVA, ApkAction.DECOMPILE_FULL -> {
                scope.launch {
                    isBusy = true
                    statusText = "Decompiling with JADX..."
                    val outDir = File(ctx.cacheDir, "jadx_${System.currentTimeMillis()}")
                    val result = withContext(Dispatchers.IO) {
                        if (action == ApkAction.DECOMPILE_FULL)
                            EmbeddedTools.decompileFullApk(apkPath, outDir.absolutePath)
                        else {
                            val dex = entries.find { it.category == FileCategory.DEX }
                            if (dex != null) {
                                val dexFile = File(extractCacheDir, "temp.dex")
                                ZipFile(File(apkPath)).use { zip ->
                                    zip.getEntry(dex.name)?.let { ze ->
                                        zip.getInputStream(ze).use { input ->
                                            dexFile.outputStream().use { output -> input.copyTo(output) }
                                        }
                                    }
                                }
                                EmbeddedTools.decompileWithJadx(dexFile.absolutePath, outDir.absolutePath)
                            } else Result.failure(Exception("No DEX found"))
                        }
                    }
                    result.onSuccess {
                        decompiledDir = outDir.absolutePath
                        statusText = it
                    }
                    result.onFailure { statusText = "Error: ${it.message}" }
                    isBusy = false
                }
            }

            ApkAction.SIGN_APK -> {
                scope.launch {
                    isBusy = true; statusText = "Signing..."
                    val outPath = apkPath.replace(".apk", "_signed.apk")
                    val result = withContext(Dispatchers.IO) {
                        EmbeddedTools.signApk(apkPath, outPath)
                    }
                    statusText = result.getOrDefault("Failed: ${result.exceptionOrNull()?.message}")
                    isBusy = false
                }
            }
            ApkAction.ALIGN_APK -> {
                scope.launch {
                    isBusy = true; statusText = "Aligning..."
                    val outPath = apkPath.replace(".apk", "_aligned.apk")
                    val result = withContext(Dispatchers.IO) {
                        if (loader.nativeZipalign != null)
                            loader.exec(loader.nativeZipalign!!, "-f", "-p", "4", apkPath, outPath)
                        else "zipalign not available"
                    }
                    statusText = result; isBusy = false
                }
            }
            ApkAction.REBUILD_SIGN -> {
                scope.launch {
                    isBusy = true; statusText = "Rebuilding..."
                    val outPath = apkPath.replace(".apk", "_patched.apk")
                    val result = withContext(Dispatchers.IO) {
                        EmbeddedTools.rebuildAndSign(apkPath, outPath, loader.nativeZipalign)
                    }
                    result.onSuccess {
                        rebuiltApkPath = outPath
                        statusText = "Rebuilt -> $outPath"
                    }
                    result.onFailure { statusText = "Failed: ${it.message}" }
                    isBusy = false
                }
            }
            ApkAction.DUMP_RESOURCES -> {
                scope.launch {
                    isBusy = true; statusText = "Dumping resources..."
                    val outFile = File(extractCacheDir, "resources_decoded.txt")
                    val result = withContext(Dispatchers.IO) {
                        EmbeddedTools.decodeResources(apkPath, outFile.absolutePath, loader.nativeAapt2)
                    }
                    result.onSuccess {
                        statusText = ""
                        viewModel.navigateToTextEditor(outFile.absolutePath, readOnly = true)
                    }
                    result.onFailure { statusText = "Error: ${it.message}" }
                    isBusy = false
                }
            }

            ApkAction.CLONE_APK -> {
                showCloneDialog = true
                clonePackageName = info?.packageName?.replace(".debug", "") ?: "com.clone.app"
            }

            ApkAction.REMOVE_VERIFY -> {
                scope.launch {
                    isBusy = true; statusText = "Removing signature verification..."
                    val outPath = apkPath.replace(".apk", "_noverify.apk")
                    val result = withContext(Dispatchers.IO) {
                        EmbeddedTools.removeSignatureVerification(apkPath, outPath, loader.nativeZipalign)
                    }
                    result.onSuccess {
                        rebuiltApkPath = outPath
                        statusText = "Verification removed → $outPath"
                    }
                    result.onFailure { statusText = "Failed: ${it.message}" }
                    isBusy = false
                }
            }

            ApkAction.INSTALL -> {
                scope.launch {
                    val target = rebuiltApkPath ?: apkPath
                    isBusy = true; statusText = "Installing..."
                    val result = withContext(Dispatchers.IO) {
                        try {
                            val file = File(target)
                            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, "application/vnd.android.package-archive")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            ctx.startActivity(intent)
                            "Install started"
                        } catch (e: Exception) { "Error: ${e.message}" }
                    }
                    statusText = result; isBusy = false
                }
            }
            ApkAction.SHARE -> {
                try {
                    val file = File(rebuiltApkPath ?: apkPath)
                    val uri = file.toURI()
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/vnd.android.package-archive"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    ctx.startActivity(Intent.createChooser(intent, "Share APK"))
                } catch (e: Exception) {
                    statusText = "Share failed: ${e.message}"
                }
            }
            ApkAction.EXTRACT -> {
                scope.launch {
                    isBusy = true; statusText = "Extracting to Downloads..."
                    val result = withContext(Dispatchers.IO) {
                        try {
                            val downloads = android.os.Environment.getExternalStoragePublicDirectory(
                                android.os.Environment.DIRECTORY_DOWNLOADS
                            )
                            val dest = File(downloads, apkName)
                            File(apkPath).copyTo(dest, overwrite = true)
                            dest.absolutePath
                        } catch (e: Exception) { null }
                    }
                    statusText = if (result != null) "Extracted to Downloads" else "Extraction failed"
                    isBusy = false
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(apkName, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium)
                        info?.let {
                            Text("${it.packageName} v${it.versionName}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    // Quick info chip
                    if (info != null) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.padding(end = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Info, null, modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "${info.dexCount} DEX, ${info.entryCount} entries",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                    // Overflow menu
                    ApkBrowserMenu(onAction = ::handleApkAction)
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Status bar
            if (statusText.isNotBlank()) {
                Surface(
                    color = if (isBusy) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isBusy) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(statusText, style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f))
                    }
                }
            }

            if (isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                // Entry list
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(entries, key = { it.name }) { entry ->
                        ApkEntryRow(
                            entry = entry,
                            onClick = {
                                if (!entry.isDirectory) {
                                    when (entry.category) {
                                        FileCategory.DEX -> {
                                            selectedDex = entry.name
                                            showDexMenu = true
                                        }
                                        FileCategory.IMAGE -> {
                                            scope.launch {
                                                isBusy = true; statusText = "Extracting..."
                                                val path = extractFromApk(entry.name)
                                                isBusy = false
                                                if (path != null) {
                                                    imageViewerPath = path; showImageViewer = true
                                                    statusText = ""
                                                } else statusText = "Failed"
                                            }
                                        }
                                        FileCategory.XML_BINARY -> {
                                            scope.launch {
                                                isBusy = true; statusText = "Decoding..."
                                                val text = withContext(Dispatchers.IO) {
                                                    ApkUtils.decodeBinaryXml(apkPath, entry.name)
                                                }
                                                isBusy = false
                                                if (text != null) {
                                                    val f = File(extractCacheDir, "${entry.name.replace("/", "_")}.xml")
                                                    f.writeText(text)
                                                    viewModel.navigateToTextEditor(f.absolutePath, readOnly = true)
                                                    statusText = ""
                                                } else statusText = "Failed to decode"
                                            }
                                        }
                                        FileCategory.XML_TEXT, FileCategory.TEXT, FileCategory.CERT -> {
                                            scope.launch {
                                                isBusy = true; statusText = "Extracting..."
                                                val path = extractFromApk(entry.name)
                                                isBusy = false
                                                if (path != null) {
                                                    statusText = ""
                                                    viewModel.navigateToTextEditor(path, readOnly = entry.category == FileCategory.CERT)
                                                } else statusText = "Failed"
                                            }
                                        }
                                        else -> {
                                            statusText = "${entry.name}\nSize: ${FileUtils.formatSize(entry.size)}"
                                        }
                                    }
                                }
                            },
                            onLongClick = {
                                if (!entry.isDirectory) {
                                    extractTarget = entry
                                    showExtractDialog = true
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    // ── Dialogs ──

    if (showDexMenu) {
        AlertDialog(
            onDismissRequest = { showDexMenu = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Code, null, tint = Color(0xFF4CAF50), modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(selectedDex.substringAfterLast("/"), style = MaterialTheme.typography.titleMedium)
                }
            },
            text = { Text("Choose action for this DEX file") },
            confirmButton = {
                Column {
                    TextButton(onClick = {
                        showDexMenu = false
                        scope.launch {
                            isBusy = true; statusText = "Disassembling $selectedDex..."
                            val dexDir = File(ctx.cacheDir, "dex_${System.currentTimeMillis()}")
                            dexDir.mkdirs()
                            val smaliDir = File(ctx.cacheDir, "smali_${System.currentTimeMillis()}")
                            val result = withContext(Dispatchers.IO) {
                                ZipFile(File(apkPath)).use { zip ->
                                    val ze = zip.getEntry(selectedDex) ?: return@withContext Result.failure(Exception("DEX not found"))
                                    val dexFile = File(dexDir, selectedDex)
                                    zip.getInputStream(ze).use { input -> dexFile.outputStream().use { output -> input.copyTo(output) } }
                                    EmbeddedTools.disassembleDex(dexFile.absolutePath, smaliDir.absolutePath)
                                }
                            }
                            result.onSuccess { statusText = it; viewModel.navigateToSmaliBrowser(smaliDir.absolutePath, selectedDex) }
                            result.onFailure { statusText = "Error: ${it.message}" }
                            isBusy = false
                        }
                    }) {
                        Icon(Icons.Default.Code, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Disassemble to Smali", color = MaterialTheme.colorScheme.primary)
                    }
                    TextButton(onClick = {
                        showDexMenu = false
                        scope.launch {
                            isBusy = true; statusText = "Decompiling $selectedDex with JADX..."
                            val dexDir = File(ctx.cacheDir, "dex_${System.currentTimeMillis()}")
                            dexDir.mkdirs()
                            val javaDir = File(ctx.cacheDir, "java_${System.currentTimeMillis()}")
                            val result = withContext(Dispatchers.IO) {
                                ZipFile(File(apkPath)).use { zip ->
                                    val ze = zip.getEntry(selectedDex) ?: return@withContext Result.failure(Exception("DEX not found"))
                                    val dexFile = File(dexDir, selectedDex)
                                    zip.getInputStream(ze).use { input -> dexFile.outputStream().use { output -> input.copyTo(output) } }
                                    EmbeddedTools.decompileWithJadx(dexFile.absolutePath, javaDir.absolutePath)
                                }
                            }
                            result.onSuccess {
                                decompiledDir = javaDir.absolutePath; statusText = it
                                viewModel.navigateToJavaBrowser(javaDir.absolutePath)
                            }
                            result.onFailure { statusText = "Error: ${it.message}" }
                            isBusy = false
                        }
                    }) {
                        Icon(Icons.Default.Coffee, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Decompile to Java (JADX)", color = MaterialTheme.colorScheme.secondary)
                    }
                    TextButton(onClick = { showDexMenu = false }) { Text("Cancel") }
                }
            }
        )
    }

    if (showManifestDialog) {
        var manifestText by remember { mutableStateOf("") }
        LaunchedEffect(showManifestDialog) {
            if (showManifestDialog && manifestText.isEmpty()) {
                withContext(Dispatchers.IO) {
                    manifestText = ApkUtils.getManifestText(apkPath) ?: "Failed to decode manifest"
                }
            }
        }
        AlertDialog(
            onDismissRequest = { showManifestDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Description, null, tint = Color(0xFF1565C0), modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp)); Text("AndroidManifest.xml")
                }
            },
            text = {
                Box(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                    Text(manifestText.ifEmpty { "Loading..." }, fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState())
                            .horizontalScroll(androidx.compose.foundation.rememberScrollState()))
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        showManifestDialog = false
                        val f = File(extractCacheDir, "AndroidManifest_decoded.xml")
                        f.writeText(manifestText)
                        viewModel.navigateToTextEditor(f.absolutePath, readOnly = true)
                    }) { Text("Edit", color = MaterialTheme.colorScheme.primary) }
                    TextButton(onClick = { showManifestDialog = false }) { Text("Close") }
                }
            }
        )
    }

    if (showPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Security, null, tint = Color(0xFF6A1B9A), modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp)); Text("Permissions")
                }
            },
            text = {
                Box(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                    val perms = info?.permissions ?: emptyList()
                    if (perms.isEmpty()) Text("No permissions declared", style = MaterialTheme.typography.bodyMedium)
                    else Column(modifier = Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                        Text("${perms.size} permissions:", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        Spacer(Modifier.height(8.dp))
                        perms.forEach { perm ->
                            Text(perm.substringAfterLast("."), style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 2.dp))
                            Text(perm, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                modifier = Modifier.padding(bottom = 4.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showPermissionDialog = false }) { Text("Close") } }
        )
    }

    if (showImageViewer) {
        AlertDialog(
            onDismissRequest = {
                showImageViewer = false
                try { File(imageViewerPath).delete() } catch (_: Exception) {}
                imageViewerPath = ""
            },
            title = { Text("Image Preview") },
            text = {
                Box(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp), contentAlignment = Alignment.Center) {
                    if (imageViewerPath.isNotEmpty()) {
                        AndroidView(
                            factory = { context ->
                                ImageView(context).apply {
                                    scaleType = ImageView.ScaleType.FIT_CENTER
                                    adjustViewBounds = true
                                    try {
                                        setImageBitmap(android.graphics.BitmapFactory.decodeFile(imageViewerPath))
                                    } catch (_: Exception) {}
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showImageViewer = false
                    try { File(imageViewerPath).delete() } catch (_: Exception) {}
                    imageViewerPath = ""
                }) { Text("Close") }
            }
        )
    }

    if (showExtractDialog && extractTarget != null) {
        val target = extractTarget!!
        AlertDialog(
            onDismissRequest = { showExtractDialog = false; extractTarget = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(FileType.getIcon(target.category), null, tint = FileType.getColor(target.category), modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(target.name.substringAfterLast("/"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            text = {
                Column {
                    Text("Category: ${FileType.getDescription(target.category)}")
                    Text("Size: ${FileUtils.formatSize(target.size)}")
                    Spacer(Modifier.height(16.dp)); Text("Choose action:")
                }
            },
            confirmButton = {
                Column {
                    TextButton(onClick = {
                        showExtractDialog = false; extractTarget = null
                        scope.launch {
                            isBusy = true; statusText = "Extracting to Downloads..."
                            val result = withContext(Dispatchers.IO) {
                                try {
                                    val dl = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                                    val out = File(dl, target.name.replace("/", "_"))
                                    ZipFile(File(apkPath)).use { zip ->
                                        zip.getEntry(target.name)?.let { ze ->
                                            zip.getInputStream(ze).use { input -> out.outputStream().use { output -> input.copyTo(output) } }
                                        }
                                    }
                                    out.absolutePath
                                } catch (e: Exception) { null }
                            }
                            isBusy = false
                            statusText = if (result != null) "Extracted to Downloads" else "Extraction failed"
                        }
                    }) {
                        Icon(Icons.Default.Download, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp)); Text("Extract to Downloads")
                    }
                    TextButton(onClick = { showExtractDialog = false; extractTarget = null }) { Text("Cancel") }
                }
            }
        )
    }

    // Clone APK Dialog
    if (showCloneDialog) {
        AlertDialog(
            onDismissRequest = { showCloneDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ContentCopy, null, tint = Color(0xFF00897B), modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Clone APK")
                }
            },
            text = {
                Column {
                    Text("Enter new package name:", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = clonePackageName,
                        onValueChange = { clonePackageName = it },
                        label = { Text("Package name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Current: ${info?.packageName ?: "unknown"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showCloneDialog = false
                    scope.launch {
                        isBusy = true; statusText = "Cloning APK..."
                        val outPath = apkPath.replace(".apk", "_clone.apk")
                        val result = withContext(Dispatchers.IO) {
                            EmbeddedTools.cloneApk(apkPath, outPath, clonePackageName, loader.nativeZipalign)
                        }
                        result.onSuccess {
                            rebuiltApkPath = outPath
                            statusText = "Cloned → $outPath"
                        }
                        result.onFailure { statusText = "Failed: ${it.message}" }
                        isBusy = false
                    }
                }, enabled = clonePackageName.isNotBlank()) {
                    Text("Clone", color = Color(0xFF00897B))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCloneDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ApkEntryRow(
    entry: ApkEntry,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (entry.isDirectory) {
            Icon(Icons.Default.Folder, null, tint = Color(0xFFFFA726), modifier = Modifier.size(22.dp))
        } else {
            Icon(FileType.getIcon(entry.category), null, tint = FileType.getColor(entry.category), modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(entry.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontFamily = if (entry.category == FileCategory.DEX || entry.category == FileCategory.RESOURCE || entry.category == FileCategory.NATIVE_LIB)
                    FontFamily.Monospace else FontFamily.Default)
            if (!entry.isDirectory) {
                Text(FileType.getDescription(entry.category), style = MaterialTheme.typography.labelSmall,
                    color = FileType.getColor(entry.category).copy(alpha = 0.7f), maxLines = 1)
            }
        }
        if (!entry.isDirectory && entry.size > 0) {
            Text(FileUtils.formatSize(entry.size), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
        }
    }
}

private data class ApkEntry(
    val name: String,
    val size: Long,
    val isDirectory: Boolean,
    val compressedSize: Long,
    val category: FileCategory
)
