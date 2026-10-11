package com.anchorreader.chatgpt

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Credentials are encrypted. A separate public registry retains issued client IDs for explicit repair. */
internal class KeystoreCredentialStore(context: Context, private val keyAlias: String = KEY_ALIAS) : CredentialStore {
    private val directory = File(context.noBackupFilesDir, "chatgpt")
    private val record = AtomicFile(File(directory, "connection.enc"))
    private val hostFile = AtomicFile(File(directory, "host-id"))
    private val registry = AtomicFile(File(directory, "registrations.json"))
    private val aad = "AnchorReader ChatGPT credential record v1".toByteArray(Charsets.UTF_8)

    @Synchronized
    override fun load(): ConnectionData {
        val recoverable = publicRegistry()
        val host = recoverable.hostId
        if (!exists(record)) return recoverable
        val envelope = JSONObject(record.openRead().use { readBounded(it, 2 * 1024 * 1024) })
        require(envelope.getInt("version") == 1)
        val decoder = Base64.getDecoder()
        val iv = decoder.decode(envelope.getString("iv"))
        require(iv.size == 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        val plaintext = cipher.doFinal(decoder.decode(envelope.getString("data")))
        require(plaintext.size <= MAX_RECORD_BYTES)
        val json = JSONObject(plaintext.toString(Charsets.UTF_8))
        require(json.getString("hostId") == host) { "Connection host changed." }
        val array = json.getJSONArray("registrations")
        require(array.length() <= 32)
        val registrations = (0 until array.length()).map { Registration.read(array.getJSONObject(it)) }
        require(registrations.map { it.clientId }.distinct().size == registrations.size)
        val all = registrations + recoverable.registrations.filter { provisional -> registrations.none { it.clientId == provisional.clientId } }
        val loaded = ConnectionData(host, recoverable.selectedClientId ?: json.nullableString("selectedClientId"), all)
        writeRegistry(loaded)
        return loaded
    }

    @Synchronized
    override fun recoveryData(): ConnectionData = publicRegistry()

    @Synchronized
    override fun resetForReconnect(): ConnectionData {
        val retained = publicRegistry()
        record.delete()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(keyAlias) }
        save(retained)
        return retained
    }

    private fun publicRegistry(): ConnectionData {
        check(directory.exists() || directory.mkdirs()) { "Could not open connection storage." }
        val host = if (exists(hostFile)) {
            hostFile.openRead().use { readBounded(it, 100) }
        } else {
            "urn:uuid:${UUID.randomUUID()}".also { atomicWrite(hostFile, it.toByteArray(Charsets.UTF_8)) }
        }
        require(host.length in 20..100) { "Invalid connection host." }
        if (!exists(registry)) return ConnectionData(host)
        val json = JSONObject(registry.openRead().use { readBounded(it, 64 * 1024) })
        require(json.getInt("version") == 1)
        require(json.getString("hostId") == host) { "Connection host changed." }
        val array = json.getJSONArray("clientIds")
        require(array.length() <= 32)
        val clients = (0 until array.length()).map { array.getString(it) }
        require(clients.all { it.length in 1..512 && it != "dynamic_agent_client" } && clients.distinct().size == clients.size)
        val selected = json.nullableString("selectedClientId")
        require(selected == null || selected in clients)
        return ConnectionData(host, selected, clients.map { Registration(it) })
    }

    @Synchronized
    override fun save(data: ConnectionData) {
        check(directory.exists() || directory.mkdirs())
        writeRegistry(data)
        val json = JSONObject().put("hostId", data.hostId).put("selectedClientId", data.selectedClientId)
            .put("registrations", JSONArray(data.registrations.map { it.json() }))
        val plaintext = json.toString().toByteArray(Charsets.UTF_8)
        require(plaintext.size <= MAX_RECORD_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(aad)
        val encoder = Base64.getEncoder()
        val envelope = JSONObject().put("version", 1).put("iv", encoder.encodeToString(cipher.iv))
            .put("data", encoder.encodeToString(cipher.doFinal(plaintext)))
        atomicWrite(record, envelope.toString().toByteArray(Charsets.UTF_8))
    }

    private fun writeRegistry(data: ConnectionData) {
        require(data.registrations.size <= 32 && data.registrations.all { it.clientId.length in 1..512 })
        val metadata = JSONObject().put("version", 1).put("hostId", data.hostId)
            .put("selectedClientId", data.selectedClientId).put("clientIds", JSONArray(data.registrations.map { it.clientId }))
        atomicWrite(registry, metadata.toString().toByteArray(Charsets.UTF_8))
    }

    private fun exists(file: AtomicFile) = file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return generator.generateKey()
    }

    private fun atomicWrite(file: AtomicFile, bytes: ByteArray) {
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    companion object {
        private const val KEY_ALIAS = "anchorreader.chatgpt.credentials.v1"
        private const val MAX_RECORD_BYTES = 1024 * 1024L
    }
}
