package com.anchorreader.chatgpt

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProviderStreamEvidenceTest {
    @Test fun emptyCompletionWithoutEarlierTextHasZeroCountsAndCompletedEmptyShape() {
        val line = evidence(terminal()).line()
        for (field in listOf("events=1", "deltas=0", "item_done=0", "final_items=0", "completed=1", "prior_event=none",
            "terminal_response=object", "terminal_status=completed", "terminal_output=empty", "terminal_items=0", "terminal_usage=absent")) {
            assertField(line, field)
        }
    }

    @Test fun priorTextDeltaDoneContentAndFinalizedItemsStayDistinctFromEmptyTerminalOutput() {
        val body = event("response.output_text.delta", JSONObject().put("delta", "FAKE_SECRET_DELTA")) +
            event("response.output_text.done", JSONObject().put("text", "FAKE_SECRET_TEXT_DONE")) +
            event("response.content_part.done", JSONObject().put("part", JSONObject().put("text", "FAKE_SECRET_CONTENT_DONE"))) +
            event("response.output_item.done", JSONObject().put("output_index", 0).put("item", message().apply {
                getJSONArray("content").getJSONObject(0).put("text", "{FAKE_SECRET_INVALID_JSON")
            })) + terminal()
        val failure = failure(body)
        assertEquals(ProviderStreamReason.RESULT_JSON, failure.streamReason)
        val line = failure.streamEvidence!!.line()
        for (field in listOf("events=5", "deltas=1", "delta_text=1", "text_done=1", "text_done_text=1", "content_done=1",
            "content_done_text=1", "item_done=1", "item_done_text=1", "final_items=1", "prior_event=item_done", "terminal_output=empty")) {
            assertField(line, field)
        }
        assertSafe(line)
    }

    @Test fun commentaryAndReasoningAreNotAcceptedFinalMessages() {
        val body = event("response.output_item.done", JSONObject().put("output_index", 0).put("item", message().put("phase", "commentary"))) +
            event("response.output_item.done", JSONObject().put("output_index", 1).put("item", JSONObject().put("type", "reasoning"))) + terminal()
        val line = evidence(body).line()
        assertField(line, "item_done=2")
        assertField(line, "item_done_text=1")
        assertField(line, "final_items=0")
    }

    @Test fun absentNullEmptyAndWrongShapeTerminalOutputRemainSeparate() {
        for ((shape, response) in listOf("absent" to completed().apply { remove("output") },
            "null" to completed().put("output", JSONObject.NULL), "empty" to completed(),
            "other" to completed().put("output", "FAKE_SECRET_OUTPUT"),
            "array" to completed().put("output", JSONArray().put(JSONObject().put("type", "reasoning"))))) {
            assertField(evidence(event("response.completed", JSONObject().put("response", response))).line(), "terminal_output=$shape")
        }
    }

    @Test fun statusAndErrorMetadataCannotLeakUnsafeStrings() {
        for ((shape, value) in listOf("absent" to null, "null" to JSONObject.NULL, "incomplete" to "incomplete", "failed" to "failed",
            "other" to "FAKE_SECRET_STATUS", "invalid" to true)) {
            val response = completed().apply { remove("status"); if (value != null) put("status", value) }
                .put("error", "FAKE_SECRET_ERROR").put("incomplete_details", JSONObject().put("reason", "FAKE_SECRET_REASON"))
            val line = evidence(event("response.completed", JSONObject().put("response", response))).line()
            assertField(line, "terminal_status=$shape")
            assertField(line, "terminal_error=other")
            assertField(line, "terminal_incomplete=object")
            assertSafe(line)
        }
    }

    @Test fun malformedTerminalResponseHasNoInventedStatus() {
        val line = evidence(event("response.completed", JSONObject().put("response", "FAKE_SECRET_RESPONSE"))).line()
        assertField(line, "terminal_response=other")
        assertField(line, "terminal_status=unseen")
    }

    @Test fun terminalUsageReadsOnlyNonnegativeIntegralBoundedCounts() {
        val usage = JSONObject().put("input_tokens", 12).put("output_tokens", 0).put("total_tokens", 12)
            .put("FAKE_SECRET_USAGE_KEY", "FAKE_SECRET_USAGE_VALUE")
        val line = evidence(terminal(completed().put("usage", usage))).line()
        assertField(line, "terminal_usage=object")
        assertField(line, "input_tokens=12")
        assertField(line, "output_tokens=0")
        assertField(line, "total_tokens=12")
        assertSafe(line)
    }

    @Test fun absentNullWrongShapeAndMalformedUsageAreNotZeroTokens() {
        for ((shape, response) in listOf("absent" to completed(), "null" to completed().put("usage", JSONObject.NULL),
            "other" to completed().put("usage", "FAKE_SECRET_USAGE"))) {
            val line = evidence(terminal(response)).line()
            assertField(line, "terminal_usage=$shape")
            assertField(line, "output_tokens=absent")
        }
        for ((value, expected) in listOf(-1 to "invalid", 0.5 to "invalid", "FAKE_SECRET_COUNT" to "invalid",
            true to "invalid", JSONObject.NULL to "null", Long.MAX_VALUE to "capped", 1_000_000_000L to "1000000000")) {
            val line = evidence(terminal(completed().put("usage", JSONObject().put("output_tokens", value)))).line()
            assertField(line, "output_tokens=$expected")
            assertSafe(line)
        }
    }

    @Test fun unknownEventNamesMapToOneCategoryAndCountersSaturate() {
        val observer = ProviderStreamObserver()
        repeat(ProviderStreamEvidence.COUNT_CAP + 10) { observer.observe("FAKE_SECRET_EVENT_$it", JSONObject()) }
        observer.observe("response.completed", JSONObject().put("response", completed()))
        val line = observer.snapshot().line()
        assertField(line, "events=4096")
        assertField(line, "other_events=4096")
        assertField(line, "prior_event=other")
        assertSafe(line)
    }

    @Test fun outputCountSaturatesBeforeParserRejectsTheExistingOutputBound() {
        val output = JSONArray().apply { repeat(ProviderStreamEvidence.COUNT_CAP + 10) { put(JSONObject().put("type", "reasoning")) } }
        val observer = ProviderStreamObserver()
        observer.observe("response.completed", JSONObject().put("response", completed().put("output", output)))
        assertField(observer.snapshot().line(), "terminal_items=4096")
    }

    @Test fun readAndFramingFailuresKeepObservedStateWithoutClaimingATerminalEvent() {
        val line = evidence(event("response.output_text.delta", JSONObject().put("delta", "FAKE_SECRET_DELTA"))).line()
        assertField(line, "deltas=1")
        assertField(line, "completed=0")
        assertField(line, "terminal_output=unseen")
        val incompleteEvent = terminal().removeSuffix("\n")
        assertField(evidence(incompleteEvent).line(), "completed=0")
    }

    @Test fun successfulTerminalOutputRemainsAuthoritativeOverEarlierText() {
        val body = event("response.output_text.delta", JSONObject().put("delta", "FAKE_SECRET_PARTIAL")) +
            terminal(completed().put("output", JSONArray().put(message())) )
        assertEquals(RESULT, readCompletedResponse(body.byteInputStream()))
    }

    private fun evidence(body: String) = failure(body).streamEvidence!!
    private fun failure(body: String): ProviderException {
        val failure = runCatching { readCompletedResponse(body.byteInputStream()) }.exceptionOrNull()
        assertTrue(failure is ProviderException)
        return failure as ProviderException
    }
    private fun assertField(line: String, field: String) { assertTrue("Missing $field in $line", field in line.split(' ')) }
    private fun assertSafe(line: String) { assertFalse(line.contains("FAKE_SECRET")); assertFalse(line.contains("https://")); assertTrue(line.length < 1024) }
    private fun completed() = JSONObject().put("status", "completed").put("output", JSONArray())
    private fun terminal(response: JSONObject = completed()) = event("response.completed", JSONObject().put("response", response))
    private fun message() = JSONObject().put("type", "message").put("role", "assistant").put("status", "completed")
        .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", RESULT)))
    private fun event(type: String, value: JSONObject) = "event: $type\ndata: ${value.put("type", type)}\n\n"
    private companion object { const val RESULT = "{\"questions\":[]}" }
}
