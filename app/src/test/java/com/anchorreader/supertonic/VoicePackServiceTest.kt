package com.anchorreader.supertonic

import com.anchorreader.chatgpt.LocalHttpFixture
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

class VoicePackServiceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var rootDir: File
    private lateinit var service: VoicePackService
    private var fixture: LocalHttpFixture? = null

    @Before
    fun setup() {
        rootDir = tempFolder.newFolder("app_files")
        service = VoicePackService(rootDir)
    }

    @After
    fun tearDown() {
        fixture?.stop()
    }

    private fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    private fun createArchive(entries: Map<String, ByteArray>): Pair<ByteArray, String> {
        val bytesOut = ByteArrayOutputStream()
        BZip2CompressorOutputStream(bytesOut).use { bzOut ->
            TarArchiveOutputStream(bzOut).use { tarOut ->
                for ((name, data) in entries) {
                    val entry = TarArchiveEntry(name).apply { size = data.size.toLong() }
                    tarOut.putArchiveEntry(entry)
                    tarOut.write(data)
                    tarOut.closeArchiveEntry()
                }
            }
        }
        val data = bytesOut.toByteArray()
        return data to sha256(data)
    }

    private val sampleFiles = mapOf(
        "duration_predictor.int8.onnx" to "dummy_dp".toByteArray(),
        "text_encoder.int8.onnx" to "dummy_te".toByteArray(),
        "vector_estimator.int8.onnx" to "dummy_ve".toByteArray(),
        "vocoder.int8.onnx" to "dummy_voc".toByteArray(),
        "tts.json" to "{}".toByteArray(),
        "unicode_indexer.bin" to "dummy_idx".toByteArray(),
        "voice.bin" to "dummy_voice".toByteArray(),
    )

    @Test
    fun testSuccessfulDownloadAndExtraction() = runBlocking {
        val (archiveBytes, archiveHash) = createArchive(sampleFiles)
        val fileHashes = sampleFiles.mapValues { sha256(it.value) }

        fixture = LocalHttpFixture().apply {
            createContext("/voice.tar.bz2") { exchange ->
                exchange.sendResponseHeaders(200, archiveBytes.size.toLong())
                exchange.responseBody.write(archiveBytes)
                exchange.responseBody.flush()
            }
            start()
        }

        val url = "http://${fixture!!.address.hostString}:${fixture!!.address.port}/voice.tar.bz2"
        service.download(
            downloadUrl = url,
            expectedArchiveHash = archiveHash,
            expectedFileHashes = fileHashes,
            totalBytesExpected = archiveBytes.size.toLong(),
        )

        var deadline = System.currentTimeMillis() + 5000
        while (service.state.value !is VoicePackState.Installed && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }

        assertTrue("Expected Installed state, got ${service.state.value}", service.state.value is VoicePackState.Installed)
        assertTrue(service.isInstalled())
        assertTrue(service.installedBytes() > 0)

        // Verify .verified marker exists
        val verifiedFile = File(service.getVoiceDir(), SupertonicManifest.VERIFIED_MARKER)
        assertTrue(verifiedFile.exists())
        assertTrue(verifiedFile.readText().contains(archiveHash))

        // Verify all 7 files exist
        for (required in SupertonicManifest.REQUIRED_FILES) {
            val file = File(service.getVoiceDir(), required)
            assertTrue("Expected file $required to exist", file.exists())
            assertEquals(sha256(file.readBytes()), fileHashes[required])
        }

        // Test delete
        service.delete()
        assertFalse(service.isInstalled())
        assertEquals(VoicePackState.NotInstalled, service.state.value)
        assertFalse(service.getVoiceDir().exists())
    }

    @Test
    fun testArchiveHashMismatchFailsAndCleansStaging() = runBlocking {
        val (archiveBytes, _) = createArchive(sampleFiles)
        val wrongHash = "0000000000000000000000000000000000000000000000000000000000000000"

        fixture = LocalHttpFixture().apply {
            createContext("/voice.tar.bz2") { exchange ->
                exchange.sendResponseHeaders(200, archiveBytes.size.toLong())
                exchange.responseBody.write(archiveBytes)
                exchange.responseBody.flush()
            }
            start()
        }

        val url = "http://${fixture!!.address.hostString}:${fixture!!.address.port}/voice.tar.bz2"
        service.download(
            downloadUrl = url,
            expectedArchiveHash = wrongHash,
            expectedFileHashes = emptyMap(),
            totalBytesExpected = archiveBytes.size.toLong(),
        )

        var deadline = System.currentTimeMillis() + 5000
        while (service.state.value !is VoicePackState.Failed && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }

        assertTrue("Expected Failed state, got ${service.state.value}", service.state.value is VoicePackState.Failed)
        assertFalse(service.isInstalled())
        assertFalse(File(rootDir, "supertonic_staging").exists())
    }

    @Test
    fun testInnerFileHashMismatchFailsAndLeavesStagingClean() = runBlocking {
        val (archiveBytes, archiveHash) = createArchive(sampleFiles)
        val wrongFileHashes = sampleFiles.mapValues { "bad_hash_for_${it.key}" }

        fixture = LocalHttpFixture().apply {
            createContext("/voice.tar.bz2") { exchange ->
                exchange.sendResponseHeaders(200, archiveBytes.size.toLong())
                exchange.responseBody.write(archiveBytes)
                exchange.responseBody.flush()
            }
            start()
        }

        val url = "http://${fixture!!.address.hostString}:${fixture!!.address.port}/voice.tar.bz2"
        service.download(
            downloadUrl = url,
            expectedArchiveHash = archiveHash,
            expectedFileHashes = wrongFileHashes,
            totalBytesExpected = archiveBytes.size.toLong(),
        )

        var deadline = System.currentTimeMillis() + 5000
        while (service.state.value !is VoicePackState.Failed && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }

        assertTrue("Expected Failed state, got ${service.state.value}", service.state.value is VoicePackState.Failed)
        assertFalse(service.isInstalled())
        assertFalse(File(rootDir, "supertonic_staging").exists())
    }

    @Test
    fun testPathTraversalZipSlipRejection() = runBlocking {
        val maliciousEntries = sampleFiles + ("../evil.txt" to "evil_content".toByteArray())
        val (archiveBytes, archiveHash) = createArchive(maliciousEntries)

        fixture = LocalHttpFixture().apply {
            createContext("/voice.tar.bz2") { exchange ->
                exchange.sendResponseHeaders(200, archiveBytes.size.toLong())
                exchange.responseBody.write(archiveBytes)
                exchange.responseBody.flush()
            }
            start()
        }

        val url = "http://${fixture!!.address.hostString}:${fixture!!.address.port}/voice.tar.bz2"
        service.download(
            downloadUrl = url,
            expectedArchiveHash = archiveHash,
            expectedFileHashes = emptyMap(),
            totalBytesExpected = archiveBytes.size.toLong(),
        )

        var deadline = System.currentTimeMillis() + 5000
        while (service.state.value !is VoicePackState.Failed && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }

        assertTrue("Expected Failed state due to zip-slip, got ${service.state.value}", service.state.value is VoicePackState.Failed)
        assertFalse(service.isInstalled())
        assertFalse(File(rootDir.parentFile, "evil.txt").exists())
        assertFalse(File(rootDir, "supertonic_staging").exists())
    }

    @Test
    fun testTruncatedDownloadRecoversCleanly() = runBlocking {
        val (archiveBytes, archiveHash) = createArchive(sampleFiles)
        val truncatedBytes = archiveBytes.copyOf(archiveBytes.size / 2)

        fixture = LocalHttpFixture().apply {
            createContext("/voice.tar.bz2") { exchange ->
                exchange.sendResponseHeaders(200, archiveBytes.size.toLong())
                exchange.responseBody.write(truncatedBytes)
                exchange.responseBody.flush()
                exchange.close()
            }
            start()
        }

        val url = "http://${fixture!!.address.hostString}:${fixture!!.address.port}/voice.tar.bz2"
        service.download(
            downloadUrl = url,
            expectedArchiveHash = archiveHash,
            expectedFileHashes = emptyMap(),
            totalBytesExpected = archiveBytes.size.toLong(),
        )

        var deadline = System.currentTimeMillis() + 5000
        while (service.state.value !is VoicePackState.Failed && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }

        assertTrue("Expected Failed state, got ${service.state.value}", service.state.value is VoicePackState.Failed)
        assertFalse(service.isInstalled())
        assertFalse(File(rootDir, "supertonic_staging").exists())
    }
}
