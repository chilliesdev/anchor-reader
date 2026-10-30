package com.anchorreader.supertonic

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class VoicePackService(
    private val filesDir: File,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(context: Context, dispatcher: CoroutineDispatcher = Dispatchers.IO) : this(context.filesDir, dispatcher)

    private val voiceDir = File(filesDir, "supertonic_voice")
    private val stagingDir = File(filesDir, "supertonic_staging")

    private val mutableState = MutableStateFlow<VoicePackState>(checkInitialState())
    val state: StateFlow<VoicePackState> = mutableState.asStateFlow()

    private var downloadJob: Job? = null
    @Volatile
    private var isCancelled = false

    fun getVoiceDir(): File = voiceDir

    fun checkInitialState(): VoicePackState {
        return if (isInstalled()) {
            VoicePackState.Installed(installedBytes())
        } else {
            VoicePackState.NotInstalled
        }
    }

    fun isInstalled(): Boolean {
        if (!voiceDir.exists() || !voiceDir.isDirectory) return false
        val marker = File(voiceDir, SupertonicManifest.VERIFIED_MARKER)
        if (!marker.exists()) return false
        for (required in SupertonicManifest.REQUIRED_FILES) {
            val file = File(voiceDir, required)
            if (!file.exists() || file.length() == 0L) return false
        }
        return true
    }

    fun installedBytes(): Long {
        if (!voiceDir.exists()) return 0L
        return voiceDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    fun download(
        downloadUrl: String = SupertonicManifest.DOWNLOAD_URL,
        expectedArchiveHash: String = SupertonicManifest.ARCHIVE_SHA256,
        expectedFileHashes: Map<String, String> = SupertonicManifest.EXPECTED_FILE_HASHES,
        totalBytesExpected: Long = SupertonicManifest.ARCHIVE_SIZE_BYTES,
    ) {
        if (state.value is VoicePackState.Downloading) return
        isCancelled = false
        val scope = CoroutineScope(dispatcher + SupervisorJob())
        downloadJob = scope.launch {
            mutableState.value = VoicePackState.Downloading(0L, totalBytesExpected)
            try {
                // Ensure clean staging dir
                if (stagingDir.exists()) {
                    stagingDir.deleteRecursively()
                }
                stagingDir.mkdirs()

                streamDownloadAndExtract(
                    url = downloadUrl,
                    expectedArchiveHash = expectedArchiveHash,
                    expectedFileHashes = expectedFileHashes,
                    totalBytesExpected = totalBytesExpected,
                )

                if (isCancelled) {
                    stagingDir.deleteRecursively()
                    mutableState.value = VoicePackState.NotInstalled
                    return@launch
                }

                // Write verified marker
                File(stagingDir, SupertonicManifest.VERIFIED_MARKER).writeText("verified\n$expectedArchiveHash\n")

                // Atomic rename to voiceDir
                if (voiceDir.exists()) {
                    voiceDir.deleteRecursively()
                }
                val moved = stagingDir.renameTo(voiceDir)
                if (!moved) {
                    voiceDir.mkdirs()
                    stagingDir.listFiles()?.forEach { file ->
                        file.renameTo(File(voiceDir, file.name))
                    }
                    stagingDir.deleteRecursively()
                }

                mutableState.value = VoicePackState.Installed(installedBytes())
            } catch (c: CancellationException) {
                stagingDir.deleteRecursively()
                mutableState.value = VoicePackState.NotInstalled
            } catch (e: Exception) {
                stagingDir.deleteRecursively()
                val message = e.message ?: "Voice pack download failed. Tap retry to try again."
                mutableState.value = VoicePackState.Failed(message)
            }
        }
    }

    fun cancel() {
        isCancelled = true
        downloadJob?.cancel()
        downloadJob = null
        if (stagingDir.exists()) {
            stagingDir.deleteRecursively()
        }
        mutableState.value = checkInitialState()
    }

    fun delete() {
        cancel()
        if (voiceDir.exists()) {
            voiceDir.deleteRecursively()
        }
        mutableState.value = VoicePackState.NotInstalled
    }

    private suspend fun streamDownloadAndExtract(
        url: String,
        expectedArchiveHash: String,
        expectedFileHashes: Map<String, String>,
        totalBytesExpected: Long,
    ) = withContext(dispatcher) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "AnchorReader")
        }

        val responseCode = connection.responseCode
        if (responseCode !in 200..299) {
            throw IllegalStateException("HTTP download failed with code $responseCode")
        }

        val totalLength = connection.contentLengthLong.takeIf { it > 0 } ?: totalBytesExpected
        val rawInput = connection.inputStream

        val outerDigest = MessageDigest.getInstance("SHA-256")
        var bytesReadTotal = 0L

        val countingStream = object : InputStream() {
            private var lastProgressReport = 0L

            override fun read(): Int {
                if (isCancelled) throw CancellationException("Cancelled")
                val b = rawInput.read()
                if (b != -1) {
                    outerDigest.update(b.toByte())
                    bytesReadTotal++
                    reportProgress()
                }
                return b
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (isCancelled) throw CancellationException("Cancelled")
                val count = rawInput.read(b, off, len)
                if (count > 0) {
                    outerDigest.update(b, off, count)
                    bytesReadTotal += count
                    reportProgress()
                }
                return count
            }

            private fun reportProgress() {
                val now = System.currentTimeMillis()
                if (now - lastProgressReport > 200 || bytesReadTotal == totalLength) {
                    lastProgressReport = now
                    mutableState.value = VoicePackState.Downloading(bytesReadTotal, totalLength)
                }
            }

            override fun close() {
                rawInput.close()
            }
        }

        val extractedHashes = mutableMapOf<String, String>()

        countingStream.use { countStream ->
            BZip2CompressorInputStream(countStream).use { bz2In ->
                TarArchiveInputStream(bz2In).use { tarIn ->
                    val canonicalStaging = stagingDir.canonicalFile
                    while (true) {
                        if (isCancelled) throw CancellationException("Cancelled")
                        val entry = (tarIn.nextEntry as? TarArchiveEntry) ?: break
                        if (entry.isDirectory) continue

                        // Zip-slip / tar-slip check on raw archive entry path
                        val rawTarget = File(stagingDir, entry.name).canonicalFile
                        if (!rawTarget.toPath().startsWith(canonicalStaging.toPath())) {
                            throw SecurityException("Illegal archive entry path: ${entry.name}")
                        }

                        val simpleName = File(entry.name).name
                        val targetFile = File(stagingDir, simpleName).canonicalFile

                        val fileDigest = MessageDigest.getInstance("SHA-256")
                        FileOutputStream(targetFile).use { out ->
                            val buffer = ByteArray(8192)
                            var count: Int
                            while (tarIn.read(buffer).also { count = it } != -1) {
                                if (isCancelled) throw CancellationException("Cancelled")
                                out.write(buffer, 0, count)
                                fileDigest.update(buffer, 0, count)
                            }
                        }
                        val hash = fileDigest.digest().joinToString("") { "%02x".format(it) }
                        extractedHashes[simpleName] = hash
                    }
                }
            }
        }

        // Verify outer archive hash if expectedArchiveHash is provided
        val actualArchiveHash = outerDigest.digest().joinToString("") { "%02x".format(it) }
        if (expectedArchiveHash.isNotEmpty() && !actualArchiveHash.equals(expectedArchiveHash, ignoreCase = true)) {
            throw IllegalStateException("Archive SHA-256 mismatch: expected $expectedArchiveHash, got $actualArchiveHash")
        }

        // Verify all 7 files exist
        for (required in SupertonicManifest.REQUIRED_FILES) {
            val file = File(stagingDir, required)
            if (!file.exists() || file.length() == 0L) {
                throw IllegalStateException("Required voice file missing or empty: $required")
            }
            val expectedHash = expectedFileHashes[required]
            if (expectedHash != null) {
                val actual = extractedHashes[required]
                if (!expectedHash.equals(actual, ignoreCase = true)) {
                    throw IllegalStateException("File SHA-256 mismatch for $required: expected $expectedHash, got $actual")
                }
            }
        }
    }
}
