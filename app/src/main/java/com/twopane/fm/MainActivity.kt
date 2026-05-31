package com.twopane.fm

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.twopane.fm.ui.screens.MainScreen
import com.twopane.fm.viewmodel.FileExplorerViewModel
import java.io.File

class MainActivity : ComponentActivity() {

    private var sharedViewModel: FileExplorerViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: FileExplorerViewModel = viewModel()
            sharedViewModel = vm
            MainScreen(viewModel = vm)
        }

        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val vm = sharedViewModel ?: return
        val action = intent?.action ?: return

        when (action) {
            Intent.ACTION_VIEW -> {
                val uri = intent.data ?: return
                val path = resolveUriToPath(uri) ?: return
                vm.handleIncomingFile(path, intent.type)
            }
            Intent.ACTION_SEND -> {
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: return
                val path = resolveUriToPath(uri) ?: return
                vm.handleIncomingFile(path, intent.type)
            }
        }
    }

    private fun resolveUriToPath(uri: Uri): String? {
        // content:// URIs: query ContentResolver for the file path
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        val name = it.getString(idx)
                        // Try to get the real file path from _data column
                        val dataIdx = it.getColumnIndex("_data")
                        if (dataIdx >= 0) {
                            return it.getString(dataIdx)
                        }
                        // Fallback: copy to cache
                        val cacheFile = File(cacheDir, "intent_${System.currentTimeMillis()}_$name")
                        contentResolver.openInputStream(uri)?.use { input ->
                            cacheFile.outputStream().use { output -> input.copyTo(output) }
                        }
                        return cacheFile.absolutePath
                    }
                }
            }
            // Fallback: copy to cache
            val ext = uri.lastPathSegment?.substringAfterLast('.', "tmp") ?: "tmp"
            val cacheFile = File(cacheDir, "intent_${System.currentTimeMillis()}.$ext")
            contentResolver.openInputStream(uri)?.use { input ->
                cacheFile.outputStream().use { output -> input.copyTo(output) }
            }
            return cacheFile.absolutePath
        }

        // file:// URIs
        if (uri.scheme == "file") {
            return uri.path
        }

        // Fallback: try toString as path
        return uri.toString().let { if (it.startsWith("/")) it else null }
    }
}
