package com.twopane.fm.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.twopane.fm.util.ApkInfo
import com.twopane.fm.util.FileUtils

// ── Action definitions ──

enum class ApkActionCategory(val label: String, val icon: ImageVector) {
    VIEW("View", Icons.Default.Visibility),
    DECOMPILE("Decompile", Icons.Default.Code),
    TOOLS("Tools", Icons.Default.Build),
    SHARE("Share & Install", Icons.Default.Share)
}

enum class ApkAction(
    val label: String,
    val subtitle: String,
    val icon: ImageVector,
    val color: Color,
    val category: ApkActionCategory
) {
    // View
    APP_INFO("App Info", "Package, version, SDK", Icons.Default.Info, Color(0xFF37474F), ApkActionCategory.VIEW),
    MANIFEST("Manifest", "View AndroidManifest.xml", Icons.Default.Description, Color(0xFF1565C0), ApkActionCategory.VIEW),
    PERMISSIONS("Permissions", "All declared permissions", Icons.Default.Security, Color(0xFF6A1B9A), ApkActionCategory.VIEW),
    BROWSE_ENTRIES("Browse Entries", "All files inside APK", Icons.Default.FolderOpen, Color(0xFF455A64), ApkActionCategory.VIEW),

    // Decompile
    DISASSEMBLE_SMALI("Disassemble to Smali", "Edit bytecode directly", Icons.Default.Code, Color(0xFF2E7D32), ApkActionCategory.DECOMPILE),
    DECOMPILE_JAVA("Decompile to Java", "Read source code via JADX", Icons.Default.Coffee, Color(0xFFFF9800), ApkActionCategory.DECOMPILE),
    DECOMPILE_FULL("Full APK Decompile", "Sources + resources", Icons.Default.Source, Color(0xFFFF5722), ApkActionCategory.DECOMPILE),

    // Tools
    SIGN_APK("Sign APK", "Apply debug signature", Icons.Default.Key, Color(0xFF6A1B9A), ApkActionCategory.TOOLS),
    ALIGN_APK("Zipalign", "Optimize APK alignment", Icons.Default.Tune, Color(0xFFE65100), ApkActionCategory.TOOLS),
    REBUILD_SIGN("Rebuild + Sign", "Full rebuild pipeline", Icons.Default.Build, Color(0xFFB71C1C), ApkActionCategory.TOOLS),
    DUMP_RESOURCES("Dump Resources", "Decode resources.arsc", Icons.Default.Palette, Color(0xFF9C27B0), ApkActionCategory.TOOLS),
    CLONE_APK("Clone APK", "Duplicate with new package name", Icons.Default.ContentCopy, Color(0xFF00897B), ApkActionCategory.TOOLS),
    REMOVE_VERIFY("Remove Verification", "Bypass signature checks", Icons.Default.VerifiedUser, Color(0xFFD32F2F), ApkActionCategory.TOOLS),

    // Share & Install
    INSTALL("Install", "Install APK on device", Icons.Default.SystemUpdate, Color(0xFF4CAF50), ApkActionCategory.SHARE),
    SHARE("Share", "Send via share sheet", Icons.Default.Share, Color(0xFF1565C0), ApkActionCategory.SHARE),
    EXTRACT("Extract", "Save to Downloads", Icons.Default.Download, Color(0xFF795548), ApkActionCategory.SHARE)
}

// ── Bottom Sheet APK Menu ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApkBottomSheetMenu(
    apkName: String,
    apkInfo: ApkInfo?,
    onAction: (ApkAction) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp)
        ) {
            // ── Header with APK info ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Android,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        apkName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    apkInfo?.let { info ->
                        Text(
                            "${info.packageName}  v${info.versionName}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // ── Grouped sections ──
            ApkActionCategory.entries.forEach { category ->
                val actions = ApkAction.entries.filter { it.category == category }
                if (actions.isNotEmpty()) {
                    // Section header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            category.icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            category.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    // Action items
                    actions.forEach { action ->
                        ApkActionItem(action) { onAction(action) }
                    }

                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun ApkActionItem(action: ApkAction, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = action.color.copy(alpha = 0.12f),
            modifier = Modifier.size(36.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    action.icon,
                    contentDescription = null,
                    tint = action.color,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                action.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                action.subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
            modifier = Modifier.size(20.dp)
        )
    }
}

// ── APK Browser overflow menu (for the top bar) ──

@Composable
fun ApkBrowserMenu(
    onAction: (ApkAction) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.MoreVert, "Menu")
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            ApkActionCategory.entries.forEach { category ->
                val actions = ApkAction.entries.filter { it.category == category }
                if (actions.isNotEmpty()) {
                    Text(
                        category.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                    actions.forEach { action ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(action.label, style = MaterialTheme.typography.bodyMedium)
                                    Text(action.subtitle, style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                                }
                            },
                            leadingIcon = {
                                Icon(action.icon, null, tint = action.color, modifier = Modifier.size(20.dp))
                            },
                            onClick = { expanded = false; onAction(action) }
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                }
            }
        }
    }
}
