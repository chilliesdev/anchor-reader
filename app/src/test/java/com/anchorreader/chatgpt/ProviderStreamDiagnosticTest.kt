package com.anchorreader.chatgpt

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException

/** Fixed failure labels from real synthetic parser inputs. No account or network access. */
class ProviderStreamDiagnosticTest {
    @Test fun emptyCommentAndNonSseBodiesHaveNoSseData() {
        for (body in listOf("", ": FAKE_SECRET_COMMENT\n\n", "{\"FAKE_SECRET_BODY\":true}", "<html>FAKE_SECRET_BODY</html>")) {
            assertReason("no_sse_data", body)
        }
    }

    @Test fun dataWithoutAuthoritativeCompletionIsMissingCompleted() {
        for (body in listOf(event("response.output_text.delta", JSONObject().put("delta", RESULT)),
            "data: {}\n", terminal().removeSuffix("\n"))) assertReason("missing_completed", body)
    }

    @Test fun earlyDoneIsNotCompletion() = assertReason("early_done", "data: [DONE]\n\n" + terminal())

    @Test fun eventJsonIsDistinctFromEventNameMismatch() {
        for (data in listOf("{FAKE_SECRET_JSON", "[]", "true")) assertReason("event_json", "data: $data\n\n" + terminal())
        assertReason("event_type", "event: FAKE_SECRET_EVENT\ndata: {\"type\":\"response.completed\"}\n\n" + terminal())
    }

    @Test fun malformedDeltaHasADeltaShapeReason() =
        assertReason("delta_shape", event("response.output_text.delta", JSONObject().put("delta", JSONObject())))

    @Test fun refusalAndIncompleteRemainSeparateFailures() {
        for (type in listOf("response.refusal.delta", "response.refusal.done")) {
            assertReason("refusal", event(type, JSONObject().put("delta", "FAKE_SECRET_REFUSAL")) + terminal())
        }
        assertReason("incomplete", event("response.incomplete", JSONObject()) + terminal())
    }

    @Test fun providerErrorsKeepTheirSafeKindAndHideCodesAndMessages() {
        for ((code, kind) in listOf("insufficient_quota" to ProviderFailureKind.LIMIT,
            "FAKE_SECRET_PROVIDER_CODE" to ProviderFailureKind.INVALID_RESPONSE)) {
            val error = JSONObject().put("code", code).put("message", "FAKE_SECRET_MESSAGE")
            assertReason("provider_error", event("response.failed", JSONObject().put("response", JSONObject().put("error", error))), kind)
            assertReason("provider_error", event("error", JSONObject().put("error", error)), kind)
        }
    }

    @Test fun completedMetadataIncludesMalformedResponseAndIncompleteState() {
        assertReason("completed_response", event("response.completed", JSONObject().put("response", "FAKE_SECRET_RESPONSE")))
        for ((reason, response) in listOf("completed_status" to completed().put("status", "incomplete"), "completed_error" to completed().put("error", JSONObject()),
            "completed_incomplete" to completed().put("incomplete_details", JSONObject()))) {
            assertReason(reason, event("response.completed", JSONObject().put("response", response)))
        }
    }

    @Test fun finalizedShapeHasIntegralUniqueBoundedIndexesAndObjectItems() {
        for (index in listOf<Any>(-1, "FAKE_SECRET_INDEX", 0.5, JSONObject.NULL)) {
            assertReason("finalized_index", finalized(index, message()))
        }
        assertReason("finalized_index", event("response.output_item.done", JSONObject().put("item", message())))
        assertReason("finalized_item", finalized(0, "FAKE_SECRET_ITEM"))
        assertReason("finalized_duplicate", finalized(2, reasoning()) + finalized(2, reasoning()))
        assertReason("finalized_count", buildString { repeat(33) { append(finalized(it, reasoning())) } })
    }

    @Test fun malformedAndEmptyTerminalOutputHaveOutputShapeReason() {
        for ((reason, output) in listOf("output_shape" to "FAKE_SECRET_OUTPUT", "output_empty" to JSONArray(), "output_item" to JSONArray().put("FAKE_SECRET_ITEM"))) {
            assertReason(reason, event("response.completed", JSONObject().put("response", completed().put("output", output))))
        }
        assertReason("output_missing", event("response.completed", JSONObject().put("response", completed().apply { remove("output") })))
        assertReason("output_count", terminal(JSONArray().apply { repeat(33) { put(reasoning()) } }))
    }

    @Test fun missingMalformedAndUnknownItemTypesHaveOutputTypeReason() {
        for ((reason, item) in listOf("output_type" to JSONObject(), "output_type" to JSONObject().put("type", JSONObject()),
            "output_unsupported" to JSONObject().put("type", "FAKE_SECRET_TYPE"), "output_unsupported" to JSONObject().put("type", "function_call"))) {
            assertReason(reason, terminal(JSONArray().put(item)))
            assertReason(reason, finalized(0, item))
        }
    }

    @Test fun unsafeMessageShapeIsRejectedBeforePhaseSelection() {
        val cases = listOf("message_role" to message().put("role", "user"), "message_status" to message().put("status", "incomplete"),
            "message_status" to message().put("status", JSONObject()), "content_shape" to message().put("content", "FAKE_SECRET_CONTENT"),
            "content_empty" to message().put("content", JSONArray()), "text_part" to message().put("content", JSONArray().put(JSONObject())),
            "content_count" to message().put("content", JSONArray().put(JSONObject()).put(JSONObject())),
            "content_item" to message().put("content", JSONArray().put("FAKE_SECRET_PART")),
            "text_part" to message().put("content", JSONArray().put(JSONObject().put("type", "refusal"))),
            "text_shape" to message().put("content", JSONArray().put(JSONObject().put("type", "output_text"))),
            "text_shape" to message().put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", JSONObject()))))
        for ((reason, item) in cases) assertReason(reason, terminal(JSONArray().put(item.put("phase", "commentary"))))
    }

    @Test fun messagePhaseCannotExposeProviderStrings() {
        for ((reason, phase) in listOf("message_phase" to "FAKE_SECRET_PHASE", "message_phase_shape" to JSONObject(), "message_phase_shape" to true)) {
            assertReason(reason, terminal(JSONArray().put(message().put("phase", phase))))
            assertReason(reason, finalized(0, message().put("phase", phase)))
        }
    }

    @Test fun missingBlankAndMultipleResultsHaveResultCountReason() {
        for ((reason, output) in listOf("result_missing" to JSONArray().put(reasoning()), "result_blank" to JSONArray().put(message(" ")),
            "result_missing" to JSONArray().put(message().put("phase", "commentary")), "result_multiple" to JSONArray().put(message()).put(message()))) {
            assertReason(reason, terminal(output))
        }
        assertReason("result_multiple", finalized(0, message()) + finalized(3, message()))
    }

    @Test fun finalResultMustBeExactlyOneJsonObject() {
        for (text in listOf("{FAKE_SECRET_RESULT", "[]", "1", "{} {}", "{} FAKE_SECRET_TRAILING")) {
            assertReason("result_json", terminal(JSONArray().put(message(text))))
        }
    }

    @Test fun malformedAndTruncatedUtf8HaveInvalidUtf8Reason() {
        for (bytes in listOf(byteArrayOf(0xc3.toByte(), 0x28), byteArrayOf(0xe2.toByte(), 0x82.toByte()),
            byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()))) assertReason("invalid_utf8", bytes.inputStream())
    }

    @Test fun timeoutIoAndUnexpectedReadErrorsRemainSafeAndDistinct() {
        for ((reason, error) in listOf("read_timeout" to SocketTimeoutException("FAKE_SECRET_TIMEOUT"),
            "read_io" to IOException("FAKE_SECRET_IO"), "unexpected" to IllegalStateException("FAKE_SECRET_STATE"))) {
            assertReason(reason, object : InputStream() { override fun read(): Int = throw error })
        }
    }

    @Test fun lineBoundStillCountsBytesAndCharactersBeforeTerminalOutput() {
        assertEquals(RESULT, parse(":" + "é".repeat(32_767) + "x\n\n" + terminal()))
        assertReason("line_bound", ":" + "é".repeat(32_767) + "xx\n\n" + terminal())
        assertReason("line_bound", ":" + "x".repeat(64 * 1024) + "\n\n" + terminal())
    }

    @Test fun eventBoundStillIncludesAllDataFields() {
        assertEquals(RESULT, parse(paddedNotice(256 * 1024) + terminal()))
        assertReason("event_bound", paddedNotice(256 * 1024 + 1) + terminal())
    }

    @Test fun totalBoundStillIncludesCommentsAndReadAhead() {
        assertEquals(RESULT, parse(paddedStream(1024 * 1024)))
        assertReason("total_bound", paddedStream(1024 * 1024 + 1))
    }

    @Test fun deltaBoundStillCountsAccumulatedUtf8Bytes() {
        val prefix = buildString { repeat(8) { append(event("response.output_text.delta", JSONObject().put("delta", "é".repeat(8192)))) } }
        assertEquals(RESULT, parse(prefix + terminal()))
        assertReason("delta_bound", prefix + event("response.output_text.delta", JSONObject().put("delta", "x")) + terminal())
    }

    @Test fun oversizedFinalTextRemainsRejectedByTheEarlierLineGuard() {
        val item = message(JSONObject().put("padding", "é".repeat(64 * 1024)).toString())
        assertReason("line_bound", terminal(JSONArray().put(item)))
        assertReason("line_bound", finalized(0, item))
    }

    @Test fun authoritativeAndFinalizedResultsKeepReasoningCommentaryAndFramingRules() {
        val retained = finalized(9, message().put("phase", "final_answer")) + finalized(2, reasoning()) +
            finalized(5, message("FAKE_SECRET_COMMENTARY").put("phase", "commentary"))
        for (ending in listOf("\n", "\r", "\r\n")) {
            assertEquals(RESULT, parse((retained + event("response.completed", JSONObject().put("response", completed().apply { remove("output") }))).replace("\n", ending)))
            assertEquals(RESULT, parse(retained + terminal()))
        }
        assertReason("output_empty", retained + terminal(JSONArray()))
    }

    private fun parse(body: String) = readCompletedResponse(body.byteInputStream())
    private fun assertReason(reason: String, body: String, kind: ProviderFailureKind = ProviderFailureKind.INVALID_RESPONSE) =
        assertReason(reason, body.byteInputStream(), kind)

    private fun assertReason(reason: String, input: InputStream, kind: ProviderFailureKind = ProviderFailureKind.INVALID_RESPONSE) {
        val error = runCatching { readCompletedResponse(input) }.exceptionOrNull()
        assertTrue(error is ProviderException)
        error as ProviderException
        assertEquals(kind, error.kind)
        if (kind == ProviderFailureKind.INVALID_RESPONSE && reason != "provider_error") assertEquals("ChatGPT did not return complete questions. Try again.", error.message)
        assertNull(error.cause)
        assertTrue(error.suppressed.isEmpty())
        val line = ProviderFailureRecord.classify(ProviderRequestStage.RESPONSES_READ, error, 200)!!.line()
        assertEquals("stage=responses_read class=ProviderException code=provider_failure status=200 failure_kind=${kind.name.lowercase()} stream=$reason", line)
        assertFalse(line.contains("FAKE_SECRET"))
        assertFalse(line.contains("https://"))
        assertTrue(line.length < 200)
    }

    private fun message(text: String = RESULT) = JSONObject().put("type", "message").put("role", "assistant")
        .put("status", "completed").put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", text)))
    private fun reasoning() = JSONObject().put("type", "reasoning")
    private fun completed(output: JSONArray = JSONArray().put(message())) = JSONObject().put("status", "completed").put("output", output)
    private fun terminal(output: JSONArray = JSONArray().put(message())) = event("response.completed", JSONObject().put("response", completed(output)))
    private fun finalized(index: Any, item: Any) = event("response.output_item.done", JSONObject().put("output_index", index).put("item", item))
    private fun event(type: String, data: JSONObject) = "event: $type\ndata: ${data.put("type", type)}\n\n"
    private fun paddedNotice(bytes: Int) = buildString {
        val first = "{\"type\":\"fixture.notice\"}"
        append("data: $first\n")
        var remaining = bytes - first.toByteArray(Charsets.UTF_8).size - 1
        while (remaining > 0) {
            val size = minOf(remaining, 16 * 1024)
            append("data: ").append(" ".repeat(size - 1)).append('\n')
            remaining -= size
        }
        append('\n')
    }
    private fun paddedStream(bytes: Int) = buildString {
        var remaining = bytes - terminal().toByteArray(Charsets.UTF_8).size
        while (remaining > 0) {
            val size = minOf(remaining, 8192)
            if (size == 1) append('\n') else append(':').append("x".repeat(size - 2)).append('\n')
            remaining -= size
        }
        append(terminal())
    }
    private companion object { const val RESULT = "{\"questions\":[]}" }
}
