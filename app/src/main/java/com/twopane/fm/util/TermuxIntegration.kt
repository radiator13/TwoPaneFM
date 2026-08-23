package com.twopane.fm.util

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * Integration with Termux-family terminal apps via RUN_COMMAND intent.
 * Requires Termux with allow-external-apps=true in ~/.termux/termux.properties.
 */
object TermuxIntegration {

    private val candidates = listOf(
        "com.termux",
        "com.termux.x11"
    )

    /** Try to open [dir] in an installed Termux-family app. Returns a user-facing message. */
    fun openTerminalHere(ctx: Context, dir: String): String {
        for (pkg in candidates) {
            val intent = Intent("com.termux.RUN_COMMAND").apply {
                `package` = pkg
                component = ComponentName(pkg, "com.termux.app.RunCommandService")
                putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/$pkg/files/usr/bin/bash")
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", "cd '${dir.replace("'", "'\\''")}' && exec bash"))
                putExtra("com.termux.RUN_COMMAND_WORKDIR", dir)
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                ctx.startService(intent)
                return "Opened in $pkg"
            } catch (_: SecurityException) {
                return "Termux found but 'allow-external-apps' is not enabled"
            } catch (_: ActivityNotFoundException) {
                // service not exported / app missing — try next candidate
            } catch (_: Exception) {
                // try next candidate
            }
        }
        // Fall back to launching the terminal app itself
        for (pkg in candidates) {
            val launch = ctx.packageManager.getLaunchIntentForPackage(pkg)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    ctx.startActivity(launch)
                    return "Opened $pkg (cd manually — RUN_COMMAND unavailable)"
                } catch (_: Exception) {}
            }
        }
        return "No terminal app found (install Termux)"
    }
}
