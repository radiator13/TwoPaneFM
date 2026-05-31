package com.twopane.fm.util

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class ToolLoader(private val ctx: Context) {

    private val toolsDir: File by lazy {
        val dir = File(ctx.filesDir, "tools")
        dir.mkdirs()
        dir
    }

    var nativeZipalign: ToolInfo? = null; private set
    var nativeAapt2: ToolInfo? = null; private set
    var nativeAapt2WorkDir: File? = null; private set
    var termuxJadx: ToolInfo? = null; private set
    var termuxBaksmali: ToolInfo? = null; private set
    var termuxSmali: ToolInfo? = null; private set
    var termuxApksigner: ToolInfo? = null; private set

    data class ToolInfo(val name: String, val execPath: String, val native: Boolean = true)

    fun init(): AvailableTools {
        extractNativeBinaries()
        detectTermuxJavaTools()
        return AvailableTools(
            embeddedJadx = true,
            embeddedBaksmali = true,
            embeddedSmali = true,
            embeddedApksigner = true,
            zipalign = nativeZipalign != null,
            aapt2 = nativeAapt2 != null,
            termuxJadx = termuxJadx != null,
            termuxBaksmali = termuxBaksmali != null,
            termuxSmali = termuxSmali != null,
            termuxApksigner = termuxApksigner != null
        )
    }

    /**
     * Extract native binaries and find a working execution directory.
     * Android 14+ (API 34+) enforces SELinux restrictions on executing binaries
     * from app_data_file contexts. We try multiple locations:
     *   1. nativeLibraryDir (usually allows exec)
     *   2. codeCacheDir (may allow exec)
     *   3. cacheDir (last resort)
     */
    private fun extractNativeBinaries() {
        // Candidate directories for executable extraction, ordered by likelihood of working
        val candidateDirs = mutableListOf<File>()

        // 1. nativeLibraryDir — best bet on Android 14+
        try {
            val nld = File(ctx.applicationInfo.nativeLibraryDir)
            if (nld.exists() && nld.canWrite()) {
                candidateDirs.add(nld)
            }
        } catch (_: Exception) {}

        // 2. codeCacheDir — sometimes has exec perms
        try {
            val ccd = ctx.codeCacheDir
            ccd.mkdirs()
            if (ccd.canWrite()) candidateDirs.add(ccd)
        } catch (_: Exception) {}

        // 3. filesDir/tools — original location
        toolsDir.mkdirs()
        candidateDirs.add(toolsDir)

        // 4. cacheDir — last resort
        try {
            ctx.cacheDir.mkdirs()
            if (ctx.cacheDir.canWrite()) candidateDirs.add(ctx.cacheDir)
        } catch (_: Exception) {}

        for (name in listOf("zipalign", "aapt2")) {
            var extracted = false
            for (dir in candidateDirs) {
                if (extracted) break
                try {
                    val target = File(dir, "twopane_$name")
                    // Extract from assets
                    ctx.assets.open("tools/$name").use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    target.setExecutable(true, false)
                    target.setReadable(true, false)

                    // Verify the binary is actually runnable
                    val testResult = tryExecBinary(target)
                    if (testResult) {
                        val info = ToolInfo(name, target.absolutePath)
                        when (name) {
                            "zipalign" -> nativeZipalign = info
                            "aapt2" -> {
                                nativeAapt2 = info
                                nativeAapt2WorkDir = dir
                            }
                        }
                        extracted = true
                    }
                } catch (_: Exception) {
                    // Try next directory
                }
            }
        }
    }

    /**
     * Test if a binary can actually be executed.
     * Returns true if execution succeeded (even with non-zero exit, as long as it ran).
     */
    private fun tryExecBinary(binary: File): Boolean {
        return try {
            val proc = ProcessBuilder(binary.absolutePath, "--version")
                .redirectErrorStream(true)
                .start()
            // Must read output to prevent pipe deadlock
            val output = proc.inputStream.bufferedReader().use { it.readText() }
            val exitCode = proc.waitFor()
            // aapt2 --version returns 0, zipalign --version may return 1
            // We just care that it actually ran (not killed by SIGKILL/SELinux)
            true
        } catch (e: Exception) {
            // "EACCES" = permission denied, "IOException" = can't exec
            false
        }
    }

    private fun detectTermuxJavaTools() {
        val javaBin = File("/data/data/com.termux/files/usr/bin/java")
        if (!javaBin.exists()) return

        val termuxPrefix = "/data/data/com.termux/files/usr"

        val jadxPath = "$termuxPrefix/bin/jadx"
        if (File(jadxPath).exists()) termuxJadx = ToolInfo("jadx", jadxPath, false)

        val baksmaliPath = "$termuxPrefix/opt/dex2jar/d2j-baksmali.sh"
        if (File(baksmaliPath).exists()) termuxBaksmali = ToolInfo("d2j-baksmali", baksmaliPath, false)

        val smaliPath = "$termuxPrefix/opt/dex2jar/d2j-smali.sh"
        if (File(smaliPath).exists()) termuxSmali = ToolInfo("d2j-smali", smaliPath, false)

        val apksignerPath = "$termuxPrefix/bin/apksigner"
        if (File(apksignerPath).exists()) termuxApksigner = ToolInfo("apksigner", apksignerPath, false)
    }

    fun exec(tool: ToolInfo, vararg args: String): String = try {
        if (tool.native) {
            val cmd = ArrayList<String>(args.size + 1).apply {
                add(tool.execPath)
                addAll(args)
            }
            val pb = ProcessBuilder(cmd)
                .redirectErrorStream(true)
            // Set working directory — some tools need it
            pb.directory(nativeAapt2WorkDir ?: toolsDir)
            val proc = pb.start()
            val output = BufferedReader(InputStreamReader(proc.inputStream)).readText()
            proc.waitFor()
            output
        } else {
            val cmd = buildString {
                append(tool.execPath)
                args.forEach { append(" '${it.replace("'", "'\\''")}'") }
            }
            val proc = ProcessBuilder("/data/data/com.termux/files/usr/bin/sh", "-c", cmd)
                .redirectErrorStream(true)
                .start()
            val output = BufferedReader(InputStreamReader(proc.inputStream)).readText()
            proc.waitFor()
            output
        }
    } catch (e: Exception) { "Error: ${e.message}" }

    /**
     * Execute a native binary and return (output, exitCode).
     * More detailed than exec() for tools that need exit code checking.
     */
    fun execWithCode(tool: ToolInfo, vararg args: String): Pair<String, Int> = try {
        val cmd = ArrayList<String>(args.size + 1).apply {
            add(tool.execPath)
            addAll(args)
        }
        val pb = ProcessBuilder(cmd)
            .redirectErrorStream(true)
        pb.directory(nativeAapt2WorkDir ?: toolsDir)
        val proc = pb.start()
        val output = BufferedReader(InputStreamReader(proc.inputStream)).readText()
        val exitCode = proc.waitFor()
        Pair(output, exitCode)
    } catch (e: Exception) { Pair("Error: ${e.message}", -1) }
}
