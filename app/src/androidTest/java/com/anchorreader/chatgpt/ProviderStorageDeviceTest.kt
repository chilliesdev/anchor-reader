package com.anchorreader.chatgpt

import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.Base64
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ProviderStorageDeviceTest {
    @Test fun keystoreStorageEncryptsAndAuthenticatesTokensAndRetainsRegistrationIdentity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.noBackupFilesDir, "provider-storage-fixture-${UUID.randomUUID()}")
        val isolated = object : ContextWrapper(context) { override fun getNoBackupFilesDir(): File = root }
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "anchorreader.chatgpt.fixture.${UUID.randomUUID()}"
        val hadKey = keyStore.containsAlias(alias)
        try {
            val first = KeystoreCredentialStore(isolated, alias)
            val initial = first.load()
            val fake = Registration("issued-fixture-client", "fixture-subject", "https://auth.openai.com",
                accessToken = "fixture-access-token", refreshToken = "fixture-refresh-token", scopes = setOf("chatgpt.tokens.use.direct"))
            first.save(initial.replace(fake, select = true))
            val record = File(root, "chatgpt/connection.enc")
            val encrypted = record.readText()
            assertFalse(encrypted.contains("fixture-access-token"))
            assertFalse(encrypted.contains("fixture-refresh-token"))
            val reopened = KeystoreCredentialStore(isolated, alias)
            assertEquals(fake, reopened.load().selected())
            assertEquals(initial.hostId, reopened.load().hostId)
            reopened.save(reopened.load())
            assertNotEquals(JSONObject(encrypted).getString("iv"), JSONObject(record.readText()).getString("iv"))
            val validRecord = record.readBytes()
            val damaged = JSONObject(record.readText())
            val bytes = Base64.getDecoder().decode(damaged.getString("data"))
            bytes[0] = (bytes[0].toInt() xor 1).toByte()
            record.writeText(damaged.put("data", Base64.getEncoder().encodeToString(bytes)).toString())
            assertTrue(runCatching { reopened.load() }.isFailure)
            val publicData = reopened.recoveryData()
            assertEquals("issued-fixture-client", publicData.selectedClientId)
            assertNull(publicData.selected()?.subject)
            assertNull(publicData.selected()?.email)
            val registry = File(root, "chatgpt/registrations.json").readText()
            assertTrue(registry.contains("issued-fixture-client"))
            assertFalse(registry.contains("fixture-subject") || registry.contains("fixture-access-token"))
            record.writeBytes(validRecord)
            reopened.save(reopened.load().replace(fake.withoutTokens()))
            val disconnected = KeystoreCredentialStore(isolated, alias).load()
            assertEquals("issued-fixture-client", disconnected.selectedClientId)
            assertEquals(initial.hostId, disconnected.hostId)
            assertNull(disconnected.selected()?.accessToken)
            assertNull(disconnected.selected()?.refreshToken)
            assertEquals(fake.accountId, disconnected.selected()?.accountId)
            reopened.save(disconnected.replace(fake))
            keyStore.deleteEntry(alias)
            assertTrue(runCatching { reopened.load() }.isFailure)
            val repaired = reopened.resetForReconnect()
            assertEquals("issued-fixture-client", repaired.selectedClientId)
            assertEquals(initial.hostId, repaired.hostId)
            assertNull(repaired.selected()?.subject)
            assertNull(repaired.selected()?.accessToken)
            assertEquals(repaired, reopened.load())
            assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
        } finally {
            root.deleteRecursively()
            if (!hadKey) keyStore.deleteEntry(alias)
        }
    }
}
