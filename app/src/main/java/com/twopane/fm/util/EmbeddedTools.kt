package com.twopane.fm.util

import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.security.KeyStore

/**
 * Embedded APK tools — zero Termux dependency.
 * Uses jadx/smali/baksmali/apksig programmatic APIs directly.
 *
 * PERFORMANCE: JadxDecompiler is cached per APK path. The first call pays
 * the full load cost (~2-5s for a medium APK), subsequent calls on the
 * same APK are instant.
 */
object EmbeddedTools {

    // ── JADX Decompiler Cache ──
    // Cache the decompiler per input file path. Loading an APK into JADX
    // is the single most expensive operation in the app.
    private data class CachedJadx(
        val inputPath: String,
        val decompiler: jadx.api.JadxDecompiler
    )

    private var cachedJadx: CachedJadx? = null

    @Synchronized
    private fun getOrCreateJadx(inputPath: String, skipSources: Boolean = false): jadx.api.JadxDecompiler {
        cachedJadx?.let { cached ->
            if (cached.inputPath == inputPath) return cached.decompiler
            // Different APK — close old one
            try { cached.decompiler.close() } catch (_: Exception) {}
        }
        val inputFile = File(inputPath)
        val args = jadx.api.JadxArgs().apply {
            setInputFile(inputFile)
            setShowInconsistentCode(true)
            setDebugInfo(true)
            if (skipSources) setSkipSources(true)
            threadsCount = Runtime.getRuntime().availableProcessors()
        }
        val decompiler = jadx.api.JadxDecompiler(args)
        decompiler.load()
        cachedJadx = CachedJadx(inputPath, decompiler)
        return decompiler
    }

    @Synchronized
    fun clearJadxCache() {
        cachedJadx?.let { try { it.decompiler.close() } catch (_: Exception) {} }
        cachedJadx = null
    }

    // ── Baksmali: DEX → smali ──
    fun disassembleDex(dexPath: String, outputDir: String, apiLevel: Int = 35): Result<String> = runCatching {
        val dexFile = File(dexPath)
        val outDir = File(outputDir)
        outDir.mkdirs()

        val dex = org.jf.dexlib2.DexFileFactory.loadDexFile(dexFile, org.jf.dexlib2.Opcodes.forApi(apiLevel))
        val options = org.jf.baksmali.BaksmaliOptions().apply {
            this.apiLevel = apiLevel; deodex = false; debugInfo = true
            sequentialLabels = true; localsDirective = true
        }

        val success = org.jf.baksmali.Baksmali.disassembleDexFile(dex, outDir, options.apiLevel, options)
        if (!success) throw Exception("baksmali returned false")

        val count = outDir.walkTopDown().count { it.extension == "smali" }
        "Disassembled $count smali files → $outputDir"
    }

    // ── Smali: smali → DEX ──
    fun assembleSmali(smaliDir: String, outputDexPath: String, apiLevel: Int = 35): Result<String> = runCatching {
        val dir = File(smaliDir)
        val outFile = File(outputDexPath)
        outFile.parentFile?.mkdirs()

        val smaliFiles = dir.walkTopDown()
            .filter { it.extension == "smali" }
            .map { it.absolutePath }
            .toList()

        if (smaliFiles.isEmpty()) return@runCatching "No .smali files found"

        val options = org.jf.smali.SmaliOptions().apply {
            this.apiLevel = apiLevel
            outputDexFile = outFile.absolutePath
            jobs = Runtime.getRuntime().availableProcessors()
        }

        val success = org.jf.smali.Smali.assemble(options, smaliFiles)
        if (!success) throw Exception("smali returned false")

        "Assembled ${smaliFiles.size} smali files → ${outputDexPath} (${outFile.length() / 1024}KB)"
    }

    // ── JADX: DEX/APK → Java decompilation (uses cache) ──
    fun decompileWithJadx(inputPath: String, outputDir: String): Result<String> = runCatching {
        val outDir = File(outputDir)
        outDir.mkdirs()

        // For DEX files, create a fresh decompiler (don't cache DEX-only decompiles)
        val args = jadx.api.JadxArgs().apply {
            setInputFile(File(inputPath))
            outDirSrc = File(outDir, "sources")
            outDirRes = File(outDir, "resources")
            setShowInconsistentCode(true)
            setDebugInfo(true)
            threadsCount = Runtime.getRuntime().availableProcessors()
        }

        jadx.api.JadxDecompiler(args).use { decompiler ->
            decompiler.load()
            decompiler.saveSources()
            decompiler.saveResources()

            val classCount = decompiler.classes.size
            val errCount = decompiler.errorsCount
            "Decompiled $classCount classes" +
                (if (errCount > 0) " ($errCount errors)" else "") +
                " → $outputDir"
        }
    }

    // ── JADX: Full APK decompile (uses cache) ──
    fun decompileFullApk(apkPath: String, outputDir: String, onProgress: ((String) -> Unit)? = null): Result<String> = runCatching {
        val outDir = File(outputDir)
        outDir.mkdirs()

        // Full decompile needs its own args with output dirs
        val args = jadx.api.JadxArgs().apply {
            setInputFile(File(apkPath))
            outDirSrc = File(outDir, "sources")
            outDirRes = File(outDir, "resources")
            setShowInconsistentCode(true)
            setDebugInfo(true)
            threadsCount = Runtime.getRuntime().availableProcessors()
        }

        jadx.api.JadxDecompiler(args).use { decompiler ->
            onProgress?.invoke("Loading APK classes...")
            decompiler.load()
            onProgress?.invoke("Saving sources...")
            decompiler.saveSources()
            onProgress?.invoke("Saving resources...")
            decompiler.saveResources()

            val classCount = decompiler.classes.size
            val resCount = decompiler.resources.size
            val errCount = decompiler.errorsCount
            buildString {
                append("Decompiled $classCount classes, $resCount resources")
                if (errCount > 0) append(" ($errCount errors)")
                append(" → $outputDir")
            }
        }
    }

    // ── JADX: Get Java source for a single class (FAST — uses cache) ──
    fun jadxGetJavaCode(inputPath: String, className: String): Result<String> = runCatching {
        val decompiler = getOrCreateJadx(inputPath)
        val cls = decompiler.classes.find {
            it.fullName == className || it.name == className ||
            it.fullName.endsWith("/$className") ||
            it.fullName.replace("/", ".") == className
        } ?: throw Exception("Class not found: $className")
        cls.code ?: "// No code generated"
    }

    // ── JADX: Get smali for a single class (FAST — uses cache) ──
    fun jadxGetSmali(inputPath: String, className: String): Result<String> = runCatching {
        val decompiler = getOrCreateJadx(inputPath)
        val cls = decompiler.classes.find {
            it.fullName == className || it.name == className ||
            it.fullName.endsWith("/$className") ||
            it.fullName.replace("/", ".") == className
        } ?: throw Exception("Class not found: $className")
        cls.smali ?: "// No smali generated"
    }

    // ── JADX: List all classes (FAST — uses cache) ──
    fun jadxListClasses(inputPath: String): Result<List<JadxClassInfo>> = runCatching {
        val decompiler = getOrCreateJadx(inputPath)
        decompiler.classes.map { cls ->
            JadxClassInfo(
                name = cls.name,
                fullName = cls.fullName,
                packageName = cls.getPackage(),
                isInner = cls.isInner
            )
        }
    }

    // ── Decode binary XML from APK (multi-strategy) ──
    fun decodeApkXml(apkPath: String, entryName: String): Result<String> = runCatching {
        // Strategy 1: JADX resource decoder (fast with cache)
        try {
            val decompiler = getOrCreateJadx(apkPath, skipSources = true)
            val res = decompiler.resources.find { it.originalName == entryName }
            if (res != null) {
                val container = res.loadContent()
                val text = container.text
                if (text != null) return@runCatching text.codeStr
            }
        } catch (_: Exception) {}

        // Strategy 2: Custom binary XML parser (zero disk I/O)
        ApkUtils.decodeBinaryXml(apkPath, entryName)
            ?: throw Exception("Cannot decode: $entryName")
    }

    // ── Decode resources (aapt2 with JADX fallback) ──
    fun decodeResources(apkPath: String, outputPath: String, nativeAapt2: ToolLoader.ToolInfo?): Result<String> = runCatching {
        val outFile = File(outputPath)
        outFile.parentFile?.mkdirs()

        // Strategy 1: Try native aapt2 binary
        if (nativeAapt2 != null) {
            try {
                val proc = ProcessBuilder(
                    nativeAapt2.execPath, "dump", "resources", "--values", apkPath
                ).redirectErrorStream(true).start()

                val output = proc.inputStream.bufferedReader().use { it.readText() }
                val exitCode = proc.waitFor()

                if (exitCode == 0 && output.isNotBlank()) {
                    outFile.writeText(output)
                    return@runCatching "Decoded resources → $outputPath (${output.lines().size} lines)"
                }
                if (output.isNotBlank() && !output.startsWith("Error")) {
                    outFile.writeText(output)
                    return@runCatching "Decoded resources → $outputPath (${output.lines().size} lines, aapt2 exit=$exitCode)"
                }
            } catch (_: Exception) {}
        }

        // Strategy 2: JADX resource listing (uses cache)
        val decompiler = getOrCreateJadx(apkPath, skipSources = true)
        val sb = StringBuilder(8192)
        sb.appendLine("# Resources decoded by JADX (aapt2 not available)")
        sb.appendLine("# APK: $apkPath")
        sb.appendLine("# Resources: ${decompiler.resources.size}")
        sb.appendLine()

        val grouped = decompiler.resources.groupBy { res ->
            val name = res.originalName
            when {
                name.startsWith("res/") -> name.substringAfter("res/").substringBefore("/")
                name == "AndroidManifest.xml" -> "manifest"
                name.startsWith("assets/") -> "assets"
                else -> "other"
            }
        }

        for ((group, resources) in grouped.toSortedMap()) {
            sb.appendLine("[$group] (${resources.size} entries)")
            for (res in resources.sortedBy { it.originalName }) {
                sb.appendLine("  ${res.originalName}")
            }
            sb.appendLine()
        }

        outFile.writeText(sb.toString())
        "Decoded resources via JADX → $outputPath (${sb.lines().size} lines)"
    }

    // ── apksig: sign APK with debug key ──
    fun signApk(apkPath: String, outputPath: String): Result<String> = runCatching {
        val inFile = File(apkPath)
        val outFile = File(outputPath)
        outFile.parentFile?.mkdirs()

        val ksFile = File(inFile.parentFile ?: inFile.absoluteFile.parentFile, ".twopane_debug.keystore")
        if (!ksFile.exists()) {
            generateDebugKeystore(ksFile)
        }

        val ks = KeyStore.getInstance("PKCS12").apply {
            FileInputStream(ksFile).use { load(it, "android".toCharArray()) }
        }
        val alias = ks.aliases().nextElement()
        val privateKey = ks.getKey(alias, "android".toCharArray()) as java.security.PrivateKey
        val certChain = ks.getCertificateChain(alias).map { it as java.security.cert.X509Certificate }

        val signerConfig = com.android.apksig.ApkSigner.SignerConfig.Builder(
            "TwoPaneDebug", privateKey, certChain
        ).build()

        val signer = com.android.apksig.ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(inFile)
            .setOutputApk(outFile)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(false)
            .build()

        signer.sign()

        "Signed → ${outputPath} (${outFile.length() / 1024}KB)"
    }

    // ── Compile text XML to binary AXML ──
    fun compileXmlFile(textXmlPath: String, outputBinaryPath: String): Result<String> = runCatching {
        val text = File(textXmlPath).readText()
        val binaryBytes = AxmlCompiler.compile(text).getOrThrow()
        File(outputBinaryPath).writeBytes(binaryBytes)
        "Compiled → $outputBinaryPath (${binaryBytes.size} bytes)"
    }

    // ── Full rebuild: extract → rezip → align → sign ──
    fun rebuildAndSign(
        apkPath: String,
        outputPath: String,
        nativeZipalign: ToolLoader.ToolInfo?,
        onProgress: ((String) -> Unit)? = null
    ): Result<String> = runCatching {
        val inFile = File(apkPath)
        val parentDir = inFile.absoluteFile.parentFile ?: File(apkPath).absoluteFile.parentFile
        val workDir = File(parentDir, ".twopane_rebuild_${System.currentTimeMillis()}")
        workDir.mkdirs()

        try {
            // Extract (skip META-INF)
            onProgress?.invoke("Extracting APK entries...")
            java.util.zip.ZipFile(inFile).use { zip ->
                zip.entries().asSequence()
                    .filter { !it.name.startsWith("META-INF/") }
                    .forEach { entry ->
                        val outFile = File(workDir, entry.name)
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            zip.getInputStream(entry).use { input ->
                                outFile.outputStream().buffered(65536).use { output ->
                                    input.copyTo(output, bufferSize = 65536)
                                }
                            }
                        }
                    }
            }

            val unsigned = File(workDir, "unsigned.apk")
            onProgress?.invoke("Creating ZIP...")
            createZipFromDir(workDir, unsigned, excludePrefix = "unsigned.apk")

            val aligned = File(workDir, "aligned.apk")
            if (nativeZipalign != null) {
                onProgress?.invoke("Aligning APK...")
                val proc = ProcessBuilder(
                    nativeZipalign.execPath, "-f", "-p", "4",
                    unsigned.absolutePath, aligned.absolutePath
                ).redirectErrorStream(true).start()
                proc.inputStream.bufferedReader().use { it.readText() }
                proc.waitFor()
                if (!aligned.exists()) throw Exception("zipalign failed")
            } else {
                unsigned.copyTo(aligned, overwrite = true)
            }

            onProgress?.invoke("Signing APK...")
            val result = signApk(aligned.absolutePath, outputPath)
            if (result.isFailure) throw result.exceptionOrNull()!!
            result.getOrThrow()
        } finally {
            workDir.deleteRecursively()
        }
    }

    // ── Replace a single entry in a ZIP file (for DEX injection) ──
    fun replaceEntryInZip(
        zipPath: String,
        entryName: String,
        newEntryPath: String,
        outputPath: String
    ): Result<String> = runCatching {
        val outFile = File(outputPath)
        outFile.parentFile?.mkdirs()

        java.util.zip.ZipFile(File(zipPath)).use { zip ->
            java.util.zip.ZipOutputStream(outFile.outputStream().buffered(65536)).use { zos ->
                val buffer = ByteArray(65536)
                zip.entries().asSequence().forEach { entry ->
                    if (entry.name == entryName) {
                        // Replace with new content
                        zos.putNextEntry(java.util.zip.ZipEntry(entry.name))
                        File(newEntryPath).inputStream().buffered(65536).use { input ->
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                zos.write(buffer, 0, read)
                            }
                        }
                        zos.closeEntry()
                    } else {
                        // Copy original entry
                        zos.putNextEntry(java.util.zip.ZipEntry(entry.name))
                        zip.getInputStream(entry).use { input ->
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                zos.write(buffer, 0, read)
                            }
                        }
                        zos.closeEntry()
                    }
                }
            }
        }

        "Replaced $entryName → $outputPath (${outFile.length() / 1024}KB)"
    }

    // ── Rebuild APK with a modified DEX file ──
    fun rebuildWithModifiedDex(
        apkPath: String,
        newDexPath: String,
        dexEntryName: String,
        outputPath: String,
        nativeZipalign: ToolLoader.ToolInfo?,
        onProgress: ((String) -> Unit)? = null
    ): Result<String> = runCatching {
        val workDir = File(File(outputPath).absoluteFile.parentFile ?: File("."), ".twopane_dex_${System.currentTimeMillis()}")
        workDir.mkdirs()

        try {
            // Step 1: Replace DEX in original APK ZIP (skip META-INF)
            onProgress?.invoke("Injecting modified DEX...")
            val injected = File(workDir, "injected.apk")
            replaceEntryInZip(apkPath, dexEntryName, newDexPath, injected.absolutePath).getOrThrow()

            // Step 2: Align if zipalign available
            val aligned = File(workDir, "aligned.apk")
            if (nativeZipalign != null) {
                onProgress?.invoke("Aligning APK...")
                val proc = ProcessBuilder(
                    nativeZipalign.execPath, "-f", "-p", "4",
                    injected.absolutePath, aligned.absolutePath
                ).redirectErrorStream(true).start()
                proc.inputStream.bufferedReader().use { it.readText() }
                proc.waitFor()
                if (!aligned.exists()) {
                    injected.copyTo(aligned, overwrite = true)
                }
            } else {
                injected.copyTo(aligned, overwrite = true)
            }

            // Step 3: Sign
            onProgress?.invoke("Signing APK...")
            val signResult = signApk(aligned.absolutePath, outputPath)
            if (signResult.isFailure) throw signResult.exceptionOrNull()!!
            signResult.getOrThrow()
        } finally {
            workDir.deleteRecursively()
        }
    }

    // ── Smali file listing ──
    fun listSmaliFiles(smaliDir: String): List<SmaliFileEntry> {
        val dir = File(smaliDir)
        if (!dir.isDirectory) return emptyList()
        return dir.walkTopDown()
            .filter { it.extension == "smali" }
            .map { f ->
                SmaliFileEntry(
                    path = f.absolutePath,
                    relativePath = f.relativeTo(dir).path,
                    size = f.length(),
                    className = f.nameWithoutExtension
                )
            }
            .sortedBy { it.relativePath }
            .toList()
    }

    fun readSmaliFile(path: String): Result<String> = runCatching { File(path).readText() }

    fun writeSmaliFile(path: String, content: String): Result<String> = runCatching {
        File(path).writeText(content)
        "Saved ${content.length} chars"
    }

    // ── Internal helpers ──

    private fun createZipFromDir(sourceDir: File, zipFile: File, excludePrefix: String) {
        java.util.zip.ZipOutputStream(zipFile.outputStream().buffered(65536)).use { zos ->
            val buffer = ByteArray(65536)
            sourceDir.walkTopDown()
                .filter { it.isFile && !it.name.startsWith(excludePrefix) }
                .forEach { file ->
                    val relPath = file.relativeTo(sourceDir).path.replace('\\', '/')
                    zos.putNextEntry(java.util.zip.ZipEntry(relPath))
                    file.inputStream().buffered(65536).use { input ->
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            zos.write(buffer, 0, read)
                        }
                    }
                    zos.closeEntry()
                }
        }
    }

    private fun generateDebugKeystore(ksFile: File) {
        val keyPair = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val now = java.util.Date()
        val cal = java.util.Calendar.getInstance().apply { add(java.util.Calendar.YEAR, 30) }
        val expiry = cal.time

        val dn = javax.security.auth.x500.X500Principal("CN=TwoPane Debug, O=TwoPaneFM")
        val cert = generateSelfSignedCert(keyPair, dn, now, expiry)

        ksFile.parentFile?.mkdirs()
        val ks = java.security.KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("debug", keyPair.private, "android".toCharArray(), arrayOf(cert))
        }
        ksFile.outputStream().use { ks.store(it, "android".toCharArray()) }
    }

    private fun generateSelfSignedCert(
        keyPair: java.security.KeyPair,
        dn: javax.security.auth.x500.X500Principal,
        notBefore: java.util.Date,
        notAfter: java.util.Date
    ): java.security.cert.X509Certificate {
        java.security.Security.addProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())

        val certBuilder = org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
            dn, java.math.BigInteger.ONE, notBefore, notAfter, dn, keyPair.public
        )
        val signer = org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA")
            .build(keyPair.private)
        val certHolder = certBuilder.build(signer)
        return org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
            .setProvider("BC")
            .getCertificate(certHolder)
    }

    // ── Clone APK with new package name ──
    fun cloneApk(
        apkPath: String,
        outputPath: String,
        newPackageName: String,
        nativeZipalign: ToolLoader.ToolInfo?,
        onProgress: ((String) -> Unit)? = null
    ): Result<String> = runCatching {
        val inFile = File(apkPath)
        val workDir = File(inFile.absoluteFile.parentFile ?: File("."), ".twopane_clone_${System.currentTimeMillis()}")
        workDir.mkdirs()

        try {
            // Step 1: Extract APK
            onProgress?.invoke("Extracting APK...")
            java.util.zip.ZipFile(inFile).use { zip ->
                zip.entries().asSequence()
                    .filter { !it.name.startsWith("META-INF/") }
                    .forEach { entry ->
                        val outFile = File(workDir, entry.name)
                        if (entry.isDirectory) outFile.mkdirs()
                        else {
                            outFile.parentFile?.mkdirs()
                            zip.getInputStream(entry).use { input ->
                                outFile.outputStream().buffered(65536).use { output ->
                                    input.copyTo(output, bufferSize = 65536)
                                }
                            }
                        }
                    }
            }

            // Step 2: Modify AndroidManifest.xml package attribute
            onProgress?.invoke("Modifying package name...")
            val manifestFile = File(workDir, "AndroidManifest.xml")
            if (manifestFile.exists()) {
                // Try to modify binary manifest by replacing package name string
                // This is a simplified approach - for full support we'd need binary XML editing
                val manifestBytes = manifestFile.readBytes()
                val originalPkg = extractPackageName(manifestBytes)
                if (originalPkg != null) {
                    val newManifest = replacePackageName(manifestBytes, originalPkg, newPackageName)
                    manifestFile.writeBytes(newManifest)
                }
            }

            // Step 3: Create ZIP
            onProgress?.invoke("Creating ZIP...")
            val unsigned = File(workDir, "unsigned.apk")
            createZipFromDir(workDir, unsigned, excludePrefix = "unsigned.apk")

            // Step 4: Align
            val aligned = File(workDir, "aligned.apk")
            if (nativeZipalign != null) {
                onProgress?.invoke("Aligning...")
                val proc = ProcessBuilder(
                    nativeZipalign.execPath, "-f", "-p", "4",
                    unsigned.absolutePath, aligned.absolutePath
                ).redirectErrorStream(true).start()
                proc.inputStream.bufferedReader().use { it.readText() }
                proc.waitFor()
                if (!aligned.exists()) unsigned.copyTo(aligned, overwrite = true)
            } else {
                unsigned.copyTo(aligned, overwrite = true)
            }

            // Step 5: Sign with new key
            onProgress?.invoke("Signing cloned APK...")
            val signResult = signApk(aligned.absolutePath, outputPath)
            if (signResult.isFailure) throw signResult.exceptionOrNull()!!
            signResult.getOrThrow()
        } finally {
            workDir.deleteRecursively()
        }
    }

    // ── Remove signature verification from APK ──
    fun removeSignatureVerification(
        apkPath: String,
        outputPath: String,
        nativeZipalign: ToolLoader.ToolInfo?,
        onProgress: ((String) -> Unit)? = null
    ): Result<String> = runCatching {
        val inFile = File(apkPath)
        val workDir = File(inFile.absoluteFile.parentFile ?: File("."), ".twopane_noverify_${System.currentTimeMillis()}")
        workDir.mkdirs()

        try {
            // Step 1: Extract APK
            onProgress?.invoke("Extracting APK...")
            java.util.zip.ZipFile(inFile).use { zip ->
                zip.entries().asSequence()
                    .filter { !it.name.startsWith("META-INF/") }
                    .forEach { entry ->
                        val outFile = File(workDir, entry.name)
                        if (entry.isDirectory) outFile.mkdirs()
                        else {
                            outFile.parentFile?.mkdirs()
                            zip.getInputStream(entry).use { input ->
                                outFile.outputStream().buffered(65536).use { output ->
                                    input.copyTo(output, bufferSize = 65536)
                                }
                            }
                        }
                    }
            }

            // Step 2: Patch smali files to bypass signature verification
            onProgress?.invoke("Patching signature verification...")
            val smaliDir = File(workDir, "smali")
            if (smaliDir.exists()) {
                patchSignatureVerification(smaliDir)
            }

            // Step 3: Create ZIP
            onProgress?.invoke("Creating ZIP...")
            val unsigned = File(workDir, "unsigned.apk")
            createZipFromDir(workDir, unsigned, excludePrefix = "unsigned.apk")

            // Step 4: Align
            val aligned = File(workDir, "aligned.apk")
            if (nativeZipalign != null) {
                onProgress?.invoke("Aligning...")
                val proc = ProcessBuilder(
                    nativeZipalign.execPath, "-f", "-p", "4",
                    unsigned.absolutePath, aligned.absolutePath
                ).redirectErrorStream(true).start()
                proc.inputStream.bufferedReader().use { it.readText() }
                proc.waitFor()
                if (!aligned.exists()) unsigned.copyTo(aligned, overwrite = true)
            } else {
                unsigned.copyTo(aligned, overwrite = true)
            }

            // Step 5: Sign
            onProgress?.invoke("Signing APK...")
            val signResult = signApk(aligned.absolutePath, outputPath)
            if (signResult.isFailure) throw signResult.exceptionOrNull()!!
            signResult.getOrThrow()
        } finally {
            workDir.deleteRecursively()
        }
    }

    private fun extractPackageName(manifestBytes: ByteArray): String? {
        // Look for package name pattern in binary XML
        // Simplified: search for common package name patterns
        val text = String(manifestBytes, Charsets.ISO_8859_1)
        val match = Regex("package=\"([^\"]+)\"").find(text)
        return match?.groupValues?.get(1)
    }

    private fun replacePackageName(manifestBytes: ByteArray, oldPackage: String, newPackage: String): ByteArray {
        // Replace package name in binary XML
        // This is a simplified string replacement approach
        val text = String(manifestBytes, Charsets.ISO_8859_1)
        val replaced = text.replace(oldPackage, newPackage)
        return replaced.toByteArray(Charsets.ISO_8859_1)
    }

    private fun patchSignatureVerification(smaliDir: File) {
        // Patch common signature verification patterns in smali code
        val patterns = listOf(
            // Pattern: getPackageInfo with GET_SIGNATURES flag
            Regex("""invoke-virtual.*getPackageInfo.*GET_SIGNATURES"""),
            // Pattern: Signature check
            Regex("""iget-object.*PackageInfo.*signatures"""),
            // Pattern: CertificateFactory.getInstance
            Regex("""invoke-static.*CertificateFactory.*getInstance""")
        )

        smaliDir.walkTopDown()
            .filter { it.extension == "smali" }
            .forEach { smaliFile ->
                try {
                    var content = smaliFile.readText()
                    var modified = false

                    // Add .method to return true for signature check methods
                    // This is a simplified patching approach
                    if (content.contains("checkSignature") || content.contains("verifySignature")) {
                        // Find methods that do signature verification and make them return true
                        content = content.replace(
                            Regex("""(\.method.*checkSignature.*\n(?:.*\n)*?\.end method)"""),
                            """
                            |# Patched: always return true
                            |    .registers 2
                            |    const/4 v0, 0x1
                            |    return v0
                            |.end method
                            """.trimMargin()
                        )
                        modified = true
                    }

                    if (modified) {
                        smaliFile.writeText(content)
                    }
                } catch (_: Exception) {}
            }
    }
}

data class SmaliFileEntry(
    val path: String,
    val relativePath: String,
    val size: Long,
    val className: String
)

data class JadxClassInfo(
    val name: String,
    val fullName: String,
    val packageName: String,
    val isInner: Boolean
)
