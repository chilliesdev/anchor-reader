package com.anchorreader.chatgpt

import kotlinx.coroutines.CancellationException
import org.json.JSONException
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

class ProviderFailureDiagnosticsTest {
    @Test fun exceptionLabelsAndReasonsAreFixedAndNeverExposeExceptionData() {
        val secret = "FAKE_SECRET_EXCEPTION https://secret.test/path?access_token=FAKE_TOKEN"
        val cases = listOf(
            Triple(ProviderException(ProviderFailureKind.AUTH, secret), "ProviderException", "provider_failure"),
            Triple(JSONException(secret), "JSONException", "other_failure"),
            Triple(SSLHandshakeException(secret), "SSLHandshakeException", "tls_failure"),
            Triple(SSLPeerUnverifiedException(secret), "SSLPeerUnverifiedException", "tls_failure"),
            Triple(UnknownHostException(secret), "UnknownHostException", "dns_failure"),
            Triple(SocketTimeoutException(secret), "SocketTimeoutException", "timeout"),
            Triple(ConnectException(secret), "ConnectException", "connection_failure"),
            Triple(IOException(secret), "IOException", "io_failure"),
            Triple(IllegalArgumentException(secret), "IllegalArgumentException", "other_failure"),
            Triple(IllegalStateException(secret), "IllegalStateException", "other_failure"),
            Triple(object : Exception(secret) {
                override fun toString(): String = throw AssertionError("Exception text must not be read")
            }, "OtherException", "other_failure"),
        )
        cases.forEach { (error, label, reason) ->
            error.initCause(Exception("FAKE_SECRET_CAUSE"))
            error.addSuppressed(Exception("FAKE_SECRET_SUPPRESSED"))
            val record = ProviderFailureRecord.classify(ProviderRequestStage.RESPONSES_OPEN, error, null)!!
            assertEquals("stage=responses_open class=$label code=$reason" + if (error is ProviderException) " failure_kind=auth" else "", record.line())
            assertFalse(record.line().contains("FAKE_"))
            assertFalse(record.line().contains("https://"))
            assertTrue(record.line().length < 160)
        }
    }

    @Test fun catalogBoundReasonIsOnlyUsedAtTheReadBoundAndStatusIsBounded() {
        val error = IllegalArgumentException("FAKE_SECRET_BOUND")
        assertEquals("catalog_bound", ProviderFailureRecord.classify(ProviderRequestStage.MODELS_READ, error, 200)!!.reason.label)
        // Catalog parsing has other possible argument failures, so do not infer a bound there.
        assertEquals("other_failure", ProviderFailureRecord.classify(ProviderRequestStage.MODELS_PARSE, error, 200)!!.reason.label)
        assertEquals("catalog_shape", ProviderFailureRecord.classify(ProviderRequestStage.MODELS_PARSE, JSONException("FAKE_BODY"), 200)!!.reason.label)
        for (status in listOf(-1, 0, 99, 600, Int.MAX_VALUE)) {
            assertNull(ProviderFailureRecord.classify(ProviderRequestStage.MODELS_OPEN, error, status)!!.httpStatus)
        }
        for (status in listOf(100, 200, 401, 599)) {
            assertEquals(status, ProviderFailureRecord.classify(ProviderRequestStage.MODELS_READ, error, status)!!.httpStatus)
        }
    }

    @Test fun cancellationNeverCreatesAFailureRecord() {
        assertNull(ProviderFailureRecord.classify(ProviderRequestStage.MODELS_OPEN,
            CancellationException("FAKE_SECRET_CANCEL"), 200))
    }
}
