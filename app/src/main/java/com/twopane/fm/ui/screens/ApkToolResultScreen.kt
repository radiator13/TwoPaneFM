package com.twopane.fm.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.twopane.fm.viewmodel.FileExplorerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApkToolResultScreen(
    viewModel: FileExplorerViewModel,
    onBack: () -> Unit
) {
    val opType = viewModel.toolOpType
    val inputApk = viewModel.toolInputApk
    val outputApk = viewModel.toolOutputApk
    val status = viewModel.toolStatus
    val message = viewModel.toolMessage
    val progress = viewModel.toolProgress
    val isClone = opType == FileExplorerViewModel.ToolOp.CLONE

    val opName = when (opType) {
        FileExplorerViewModel.ToolOp.SIGN -> "Sign APK"
        FileExplorerViewModel.ToolOp.ALIGN -> "Zipalign"
        FileExplorerViewModel.ToolOp.REBUILD -> "Rebuild + Sign"
        FileExplorerViewModel.ToolOp.REMOVE_VERIFY -> "Remove Verification"
        FileExplorerViewModel.ToolOp.CLONE -> "Clone APK"
    }

    val opDescription = when (opType) {
        FileExplorerViewModel.ToolOp.SIGN -> "Apply debug signature to the APK"
        FileExplorerViewModel.ToolOp.ALIGN -> "Optimize shared library alignment to 4-byte boundaries"
        FileExplorerViewModel.ToolOp.REBUILD -> "Extract, re-zip (skip META-INF), align, and re-sign"
        FileExplorerViewModel.ToolOp.REMOVE_VERIFY -> "Patch smali code to bypass signature verification checks"
        FileExplorerViewModel.ToolOp.CLONE -> "Duplicate APK with a new package name"
    }

    val opIcon = when (opType) {
        FileExplorerViewModel.ToolOp.SIGN -> Icons.Default.Key
        FileExplorerViewModel.ToolOp.ALIGN -> Icons.Default.Tune
        FileExplorerViewModel.ToolOp.REBUILD -> Icons.Default.Build
        FileExplorerViewModel.ToolOp.REMOVE_VERIFY -> Icons.Default.VerifiedUser
        FileExplorerViewModel.ToolOp.CLONE -> Icons.Default.ContentCopy
    }

    val opColor = when (opType) {
        FileExplorerViewModel.ToolOp.SIGN -> Color(0xFF6A1B9A)
        FileExplorerViewModel.ToolOp.ALIGN -> Color(0xFFE65100)
        FileExplorerViewModel.ToolOp.REBUILD -> Color(0xFFB71C1C)
        FileExplorerViewModel.ToolOp.REMOVE_VERIFY -> Color(0xFFD32F2F)
        FileExplorerViewModel.ToolOp.CLONE -> Color(0xFF00897B)
    }

    LaunchedEffect(status) {
        if (status == FileExplorerViewModel.ToolStatus.IDLE && !isClone) {
            viewModel.runToolOperation()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(opName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Operation header card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = opColor.copy(alpha = 0.08f))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(opIcon, null, tint = opColor, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(opName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(opDescription, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                }
            }

            // Input APK card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Input", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(4.dp))
                    Text(inputApk.substringAfterLast("/"), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(inputApk, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                }
            }

            // Clone package name input
            if (isClone && status == FileExplorerViewModel.ToolStatus.IDLE) {
                OutlinedTextField(
                    value = viewModel.clonePackageName,
                    onValueChange = { viewModel.clonePackageName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("New package name") },
                    singleLine = true
                )
            }

            // Status card
            when (status) {
                FileExplorerViewModel.ToolStatus.RUNNING -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("Running...", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                if (progress.isNotBlank()) {
                                    Text(progress, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                                }
                            }
                        }
                    }
                }
                FileExplorerViewModel.ToolStatus.SUCCESS -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1B5E20).copy(alpha = 0.08f))
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF2E7D32))
                                Spacer(Modifier.width(8.dp))
                                Text("Success", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
                            }
                            if (outputApk != null) {
                                Spacer(Modifier.height(8.dp))
                                Text("Output:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                                Text(outputApk.substringAfterLast("/"), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
                FileExplorerViewModel.ToolStatus.ERROR -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFB71C1C).copy(alpha = 0.08f))
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Error, null, tint = Color(0xFFC62828))
                            Spacer(Modifier.width(8.dp))
                            Text(message, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFC62828))
                        }
                    }
                }
                else -> {}
            }

            // Action buttons
            when (status) {
                FileExplorerViewModel.ToolStatus.IDLE -> {
                    if (isClone) {
                        Button(
                            onClick = { viewModel.runToolOperation() },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = viewModel.clonePackageName.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = opColor)
                        ) {
                            Icon(opIcon, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Clone")
                        }
                    }
                }
                FileExplorerViewModel.ToolStatus.SUCCESS -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.installFromToolResult() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.SystemUpdate, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Install")
                        }
                        OutlinedButton(
                            onClick = { viewModel.shareFromToolResult() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Share, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Share")
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.extractToolResult() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Download, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Extract")
                        }
                        OutlinedButton(
                            onClick = {
                                val newApk = outputApk ?: inputApk
                                viewModel.navigateToToolResult(newApk, FileExplorerViewModel.ToolOp.SIGN)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Key, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Re-sign")
                        }
                    }
                }
                else -> {}
            }
        }
    }
}
