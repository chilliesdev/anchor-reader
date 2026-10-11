package com.anchorreader.chatgpt

import android.util.Log
import kotlinx.coroutines.CancellationException
import org.json.JSONException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

internal enum class ProviderRequestStage(val label: String) {
    SESSION_CHECK("session_check"), SESSION_REFRESH("session_refresh"),
    MODELS_OPEN("models_open"), MODELS_READ("models_read"), MODELS_PARSE("models_parse"),
    MODELS_CLOSE("models_close"), REQUEST_BUILD("request_build"),
    RESPONSES_OPEN("responses_open"), RESPONSES_READ("responses_read"),
    RESPONSES_CLOSE("responses_close"), FINISH("finish"),
}

internal enum class ProviderExceptionLabel(val label: String) {
    PROVIDER("ProviderException"), JSON("JSONException"), TLS_HANDSHAKE("SSLHandshakeException"),
    TLS_PEER("SSLPeerUnverifiedException"), DNS("UnknownHostException"), TIMEOUT("SocketTimeoutException"),
    CONNECTION("ConnectException"), IO("IOException"), ARGUMENT("IllegalArgumentException"),
    STATE("IllegalStateException"), OTHER("OtherException"),
}

internal enum class ProviderDiagnosticReason(val label: String) {
    CATALOG_SHAPE("catalog_shape"), CATALOG_BOUND("catalog_bound"), TLS("tls_failure"),
    DNS("dns_failure"), TIMEOUT("timeout"), CONNECTION("connection_failure"), IO("io_failure"),
    PROVIDER("provider_failure"), OTHER("other_failure"),
}

internal enum class ProviderStreamMedia(val label: String) {
    MISSING("missing"), EVENT_STREAM("event_stream"), JSON("json"), HTML("html"), OTHER("other"),
}

internal enum class ProviderStreamReason(val label: String) {
    TOTAL_BOUND("total_bound"), LINE_BOUND("line_bound"), EVENT_BOUND("event_bound"),
    DELTA_BOUND("delta_bound"), FINAL_BOUND("final_bound"), INVALID_UTF8("invalid_utf8"),
    READ_TIMEOUT("read_timeout"), READ_IO("read_io"), EVENT_JSON("event_json"),
    EVENT_TYPE("event_type"), DELTA_SHAPE("delta_shape"), EARLY_DONE("early_done"),
    NO_SSE_DATA("no_sse_data"), MISSING_COMPLETED("missing_completed"), REFUSAL("refusal"),
    INCOMPLETE("incomplete"), PROVIDER_ERROR("provider_error"), COMPLETED_RESPONSE("completed_response"),
    COMPLETED_STATUS("completed_status"), COMPLETED_ERROR("completed_error"),
    COMPLETED_INCOMPLETE("completed_incomplete"), FINALIZED_INDEX("finalized_index"),
    FINALIZED_DUPLICATE("finalized_duplicate"), FINALIZED_COUNT("finalized_count"),
    FINALIZED_ITEM("finalized_item"), OUTPUT_MISSING("output_missing"), OUTPUT_SHAPE("output_shape"),
    OUTPUT_EMPTY("output_empty"), OUTPUT_COUNT("output_count"), OUTPUT_ITEM("output_item"), OUTPUT_TYPE("output_type"),
    OUTPUT_UNSUPPORTED("output_unsupported"), MESSAGE_ROLE("message_role"), MESSAGE_STATUS("message_status"),
    CONTENT_SHAPE("content_shape"), CONTENT_EMPTY("content_empty"), CONTENT_COUNT("content_count"), CONTENT_ITEM("content_item"),
    TEXT_PART("text_part"), TEXT_SHAPE("text_shape"), MESSAGE_PHASE_SHAPE("message_phase_shape"),
    MESSAGE_PHASE("message_phase"), RESULT_MISSING("result_missing"), RESULT_BLANK("result_blank"),
    RESULT_MULTIPLE("result_multiple"), RESULT_JSON("result_json"), UNEXPECTED("unexpected"),
}

/** Contains only constants and a bounded HTTP status. Never retains an exception or provider data. */
internal class ProviderFailureRecord private constructor(
    val stage: ProviderRequestStage,
    val exceptionLabel: ProviderExceptionLabel,
    val reason: ProviderDiagnosticReason,
    val httpStatus: Int?,
    val failureKind: ProviderFailureKind?,
    val streamReason: ProviderStreamReason?,
    val media: ProviderStreamMedia?,
) {
    fun line(): String = "stage=${stage.label} class=${exceptionLabel.label} code=${reason.label}" +
        (httpStatus?.let { " status=$it" } ?: "") + (failureKind?.let { " failure_kind=${it.diagnosticLabel()}" } ?: "") +
        (streamReason?.let { " stream=${it.label}" } ?: "") + (media?.let { " media=${it.label}" } ?: "")

    companion object {
        fun classify(stage: ProviderRequestStage, error: Exception, httpStatus: Int?,
            media: ProviderStreamMedia? = null): ProviderFailureRecord? {
            if (error is CancellationException) return null
            val exceptionLabel = when (error) {
                is ProviderException -> ProviderExceptionLabel.PROVIDER
                is JSONException -> ProviderExceptionLabel.JSON
                is SSLHandshakeException -> ProviderExceptionLabel.TLS_HANDSHAKE
                is SSLPeerUnverifiedException -> ProviderExceptionLabel.TLS_PEER
                is UnknownHostException -> ProviderExceptionLabel.DNS
                is SocketTimeoutException -> ProviderExceptionLabel.TIMEOUT
                is ConnectException -> ProviderExceptionLabel.CONNECTION
                is IOException -> ProviderExceptionLabel.IO
                is IllegalArgumentException -> ProviderExceptionLabel.ARGUMENT
                is IllegalStateException -> ProviderExceptionLabel.STATE
                else -> ProviderExceptionLabel.OTHER
            }
            val reason = when (exceptionLabel) {
                ProviderExceptionLabel.PROVIDER -> ProviderDiagnosticReason.PROVIDER
                ProviderExceptionLabel.JSON -> if (stage == ProviderRequestStage.MODELS_PARSE)
                    ProviderDiagnosticReason.CATALOG_SHAPE else ProviderDiagnosticReason.OTHER
                ProviderExceptionLabel.TLS_HANDSHAKE, ProviderExceptionLabel.TLS_PEER -> ProviderDiagnosticReason.TLS
                ProviderExceptionLabel.DNS -> ProviderDiagnosticReason.DNS
                ProviderExceptionLabel.TIMEOUT -> ProviderDiagnosticReason.TIMEOUT
                ProviderExceptionLabel.CONNECTION -> ProviderDiagnosticReason.CONNECTION
                ProviderExceptionLabel.IO -> ProviderDiagnosticReason.IO
                ProviderExceptionLabel.ARGUMENT -> if (stage == ProviderRequestStage.MODELS_READ)
                    ProviderDiagnosticReason.CATALOG_BOUND else ProviderDiagnosticReason.OTHER
                ProviderExceptionLabel.STATE, ProviderExceptionLabel.OTHER -> ProviderDiagnosticReason.OTHER
            }
            return ProviderFailureRecord(stage, exceptionLabel, reason, httpStatus?.takeIf { it in 100..599 },
                (error as? ProviderException)?.kind, (error as? ProviderException)?.streamReason, media)
        }
    }
}

private fun ProviderFailureKind.diagnosticLabel(): String = when (this) {
    ProviderFailureKind.AUTH -> "auth"
    ProviderFailureKind.NO_PLAN -> "no_plan"
    ProviderFailureKind.LIMIT -> "limit"
    ProviderFailureKind.NETWORK -> "network"
    ProviderFailureKind.INVALID_RESPONSE -> "invalid_response"
}

internal fun interface ProviderFailureSink { fun record(failure: ProviderFailureRecord) }

internal object AndroidProviderFailureSink : ProviderFailureSink {
    const val TAG = "AnchorReaderChatGpt"
    override fun record(failure: ProviderFailureRecord) { Log.w(TAG, failure.line()) }
}
