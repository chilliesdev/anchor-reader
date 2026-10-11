package com.anchorreader.chatgpt

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InputStream

/** Synthetic streams exercise the production parser. No account or network access. */
class ProviderTerminalCompatibilityTest {
    @Test fun completedLineEndingsCommentsAndChunkBoundariesKeepOneTerminalObject() {
        for (ending in listOf("\n", "\r\n", "\r")) {
            val stream = ": fixture comment$ending$ending" + terminal().replace("\n", ending)
            assertEquals(RESULT, parse(stream, chunks = intArrayOf(1)))
        }
        val mixed = ": ignored\r\nevent: response.completed\r" +
            "data: ${terminalBody()}\n\r\n"
        assertEquals(RESULT, parse(mixed, chunks = intArrayOf(1, 2, 7, 3)))
        assertEquals(RESULT, parse(terminal(), chunks = intArrayOf(7, 1, 2)))
    }

    @Test fun absentAndNullTerminalOutputUseFinalizedItemsSortedWithGaps() {
        for (nullOutput in listOf(false, true)) {
            val response = completedResponse().apply {
                remove("output")
                if (nullOutput) put("output", JSONObject.NULL)
            }
            val stream = finalized(3_000_000_007L, message()) + finalized(2, reasoning()) +
                event("response.completed", JSONObject().put("response", response))
            assertEquals(RESULT, parse(stream, chunks = intArrayOf(1, 3, 2, 5)))
        }
    }

    @Test fun commentaryIsIgnoredAndOneFinalOrLegacyMessageSuppliesResult() {
        val outputs = listOf(
            JSONArray().put(message("Fixture planning text.", "commentary")).put(message(RESULT, "final_answer")),
            JSONArray().put(reasoning()).put(message("{\"fixture_commentary\":true}", "commentary"))
                .put(message(RESULT, "final_answer")),
            JSONArray().put(message()),
            JSONArray().put(message().put("phase", JSONObject.NULL)),
            JSONArray().put(message("Fixture planning text.", "commentary")).put(message()),
        )
        outputs.forEach { assertEquals(RESULT, parse(terminal(output = it))) }
        val retained = finalized(8, message(RESULT, "final_answer")) +
            finalized(1, message("Fixture planning text.", "commentary")) + terminalWithoutOutput()
        assertEquals(RESULT, parse(retained))
    }

    @Test fun presentTerminalOutputIsAuthoritativeAndInvalidOutputDoesNotFallBack() {
        val retained = finalized(0, message("{\"fixture_retained\":true}"))
        assertEquals(RESULT, parse(retained + terminal()))
        assertEquals("{\"fixture_retained\":true}", parse(retained + terminal(output = JSONArray())))
        for (output in listOf("wrong shape", JSONObject(), 1, JSONArray().put(JSONObject.NULL))) {
            val response = completedResponse().put("output", output)
            assertRejected("present output $output", retained + event("response.completed", JSONObject().put("response", response)))
        }
    }

    @Test fun finalizedItemsRequireIntegralNonnegativeUniqueIndexesAndObjectItems() {
        val invalidIndexes = listOf<Any>(JSONObject.NULL, -1, -2L, 0.5, "1", true, JSONObject(), JSONArray())
        invalidIndexes.forEach { index ->
            assertRejected("invalid finalized index $index", finalized(index, message()) + terminalWithoutOutput())
        }
        val missingIndex = JSONObject().put("item", message())
        assertRejected("missing finalized index", event("response.output_item.done", missingIndex) + terminalWithoutOutput())
        for (item in listOf(JSONObject.NULL, "not an item", JSONArray(), JSONObject())) {
            assertRejected("invalid finalized item", finalized(0, item) + terminalWithoutOutput())
        }
        for (second in listOf(message(), reasoning())) {
            assertRejected("duplicate finalized index", finalized(2, message()) + finalized(2, second) + terminalWithoutOutput())
        }
    }

    @Test fun retainedItemCountAccepts32AndRejects33WithIndexGaps() {
        val accepted = buildString {
            repeat(31) { append(finalized(it * 3, reasoning())) }
            append(finalized(3_000_000_200L, message()))
            append(terminalWithoutOutput())
        }
        assertEquals(RESULT, parse(accepted))
        val rejected = buildString {
            repeat(32) { append(finalized(it * 3, reasoning())) }
            append(finalized(3_000_000_200L, message()))
            append(terminalWithoutOutput())
        }
        assertRejected("33 retained items", rejected)
    }

    @Test fun finalizedItemsWithoutACompletedEventNeverBecomeResults() {
        for (suffix in listOf("", ": stream ended\n\n", "data: [DONE]\n\n")) {
            assertRejected("no terminal event", finalized(0, message()) + suffix)
        }
        assertRejected("terminal without finalized items", terminalWithoutOutput())
        assertRejected("null output without finalized items", terminalWithoutOutput(nullOutput = true))
        assertRejected("reasoning alone", finalized(0, reasoning()) + terminalWithoutOutput())
    }

    @Test fun deltasAddedItemsAndTextDoneCannotSupplyMissingTerminalOutput() {
        val partials = listOf(
            event("response.output_text.delta", JSONObject().put("delta", RESULT)),
            event("response.output_item.added", JSONObject().put("output_index", 0).put("item", message())),
            event("response.output_text.done", JSONObject().put("output_index", 0).put("text", RESULT)),
        )
        partials.forEach { partial ->
            assertRejected("partial output only", partial)
            assertRejected("partial output plus empty terminal", partial + terminalWithoutOutput())
        }
    }

    @Test fun doneFailureIncompleteAndRefusalEventsCannotBeOverriddenBySuccess() {
        val failures = listOf(
            "data: [DONE]\n\n",
            event("response.incomplete", JSONObject()),
            event("response.refusal.delta", JSONObject().put("delta", "Fixture refusal.")),
            event("response.refusal.done", JSONObject().put("refusal", "Fixture refusal.")),
            event("response.failed", JSONObject().put("response", JSONObject().put("error", JSONObject().put("code", "fixture_failure")))),
            event("error", JSONObject().put("error", JSONObject().put("code", "fixture_failure"))),
        )
        failures.forEach { assertProviderFailure("failed event before terminal", finalized(0, message()) + it + terminal()) }
    }

    @Test fun terminalStatusErrorAndIncompleteMetadataAreRequiredEvenForFallback() {
        val responses = listOf(
            completedResponse().put("status", "incomplete"),
            completedResponse().put("status", "in_progress"),
            completedResponse().put("status", JSONObject.NULL),
            completedResponse().put("error", JSONObject().put("code", "fixture_error")),
            completedResponse().put("incomplete_details", JSONObject().put("reason", "fixture_limit")),
        )
        responses.forEach { response ->
            assertRejected("invalid terminal metadata", event("response.completed", JSONObject().put("response", response)))
            response.remove("output")
            assertRejected("invalid fallback metadata", finalized(0, message()) + event("response.completed", JSONObject().put("response", response)))
        }
    }

    @Test fun commentaryOnlyOutputFailsEvenWhenCommentaryContainsJson() {
        for (text in listOf("Fixture planning text.", RESULT, "{\"fixture_commentary\":true}")) {
            val commentary = message(text, "commentary")
            assertRejected("commentary-only terminal", terminal(output = JSONArray().put(commentary)))
            assertRejected("commentary-only fallback", finalized(4, commentary) + terminalWithoutOutput())
        }
    }

    @Test fun unknownOrMalformedMessagePhaseFails() {
        for (phase in listOf<Any>("", "analysis", "final", 1, false, JSONArray(), JSONObject())) {
            val item = message().put("phase", phase)
            assertRejected("invalid terminal phase", terminal(output = JSONArray().put(item)))
            assertRejected("invalid finalized phase", finalized(0, item) + terminalWithoutOutput())
            assertRejected("invalid finalized phase before present terminal", finalized(0, item) + terminal())
        }
    }

    @Test fun duplicateAndMixedResultCandidatesFailEvenWhenTheirTextMatches() {
        val pairs = listOf(
            listOf(message(RESULT, "final_answer"), message(RESULT, "final_answer")),
            listOf(message(), message()),
            listOf(message(), message(RESULT, "final_answer")),
            listOf(message(RESULT, "final_answer"), message().put("phase", JSONObject.NULL)),
        )
        pairs.forEach { pair ->
            assertRejected("multiple terminal results", terminal(output = JSONArray(pair)))
            assertRejected("multiple finalized results", finalized(0, pair[0]) + finalized(3, pair[1]) + terminalWithoutOutput())
            assertRejected("multiple finalized results before present terminal", finalized(0, pair[0]) + finalized(3, pair[1]) + terminal())
        }
    }

    @Test fun unsafeMessageRoleStatusAndContentFailBeforePhaseSelection() {
        val invalidMessages = listOf(
            message().put("role", "user"),
            message().put("status", "incomplete"),
            message().put("status", "in_progress"),
            message().put("content", JSONArray()),
            message().put("content", JSONArray().put(textPart()).put(textPart())),
            message().put("content", JSONArray().put(JSONObject().put("type", "refusal").put("refusal", "Fixture refusal."))),
            message().put("content", JSONArray().put(JSONObject().put("type", "output_text"))),
            message().put("content", "wrong shape"),
        )
        invalidMessages.forEach { invalid ->
            assertRejected("unsafe result message", terminal(output = JSONArray().put(invalid)))
            assertRejected("unsafe finalized message", finalized(0, invalid) + terminalWithoutOutput())
            val commentary = JSONObject(invalid.toString()).put("phase", "commentary")
            assertRejected("unsafe commentary before valid result", terminal(output = JSONArray().put(commentary).put(message())))
        }
    }

    @Test fun toolAndUnknownOutputItemsFailAlongsideOtherwiseValidMessages() {
        for (type in listOf("function_call", "function_call_output", "web_search_call", "fixture_unknown")) {
            val item = JSONObject().put("type", type).put("status", "completed")
            assertRejected("tool in terminal output", terminal(output = JSONArray().put(item).put(message())))
            assertRejected("tool in finalized output", finalized(1, item) + finalized(2, message()) + terminalWithoutOutput())
            assertRejected("tool in finalized output before present terminal", finalized(1, item) + terminal())
        }
    }

    @Test fun resultMustBeOneNonblankJsonObjectWithNoTrailingValue() {
        for (text in listOf("", " \t\n", "not json", "{broken", "[]", "1", "null", "true", "{} {}", "{} trailing-data")) {
            assertRejected("invalid result JSON", terminal(output = JSONArray().put(message(text))))
            assertRejected("invalid finalized result JSON", finalized(0, message(text)) + terminalWithoutOutput())
        }
    }

    @Test fun malformedEventJsonAndConflictingEventNamesFail() {
        val invalid = listOf(
            "event: response.completed\ndata: {broken\n\n",
            "data: []\n\n",
            "event: response.completed\ndata: {\"type\":\"response.output_text.delta\",\"delta\":\"{}\"}\n\n",
            event("response.completed", JSONObject().put("response", "wrong shape")),
        )
        invalid.forEach { assertRejected("invalid event JSON", it + terminal()) }
    }

    @Test fun malformedAndTruncatedUtf8FailInsteadOfReplacingBytes() {
        for (bytes in listOf(byteArrayOf(0xc3.toByte(), 0x28), byteArrayOf(0xe2.toByte(), 0x82.toByte()),
            byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()))) {
            val stream = ":".toByteArray() + bytes + "\n\n".toByteArray() + terminal().toByteArray(Charsets.UTF_8)
            assertRejectedBytes("invalid UTF-8", stream, chunks = intArrayOf(1, 2))
        }
    }

    @Test fun lineLimitCountsUtf8BytesIncludingComments() {
        val accepted = ":" + "é".repeat(32_767) + "x"
        assertEquals(64 * 1024, accepted.toByteArray(Charsets.UTF_8).size)
        assertEquals(RESULT, parse(accepted + "\n\n" + terminal()))
        assertRejected("line byte limit plus one", accepted + "x\n\n" + terminal())
        assertRejected("ASCII line bound", ":" + "x".repeat(64 * 1024) + "\r\r" + terminal())
    }

    @Test fun eventLimitCountsAllDataLinesAtTheBoundary() {
        val accepted = paddedNoticeEvent(256 * 1024)
        assertEquals(RESULT, parse(accepted + terminal()))
        assertRejected("event byte limit plus one", paddedNoticeEvent(256 * 1024 + 1) + terminal())
    }

    @Test fun totalStreamLimitCountsCommentsAndUtf8AcrossChunks() {
        val atLimit = paddedStream(1024 * 1024)
        assertEquals(1024 * 1024, atLimit.toByteArray(Charsets.UTF_8).size)
        assertEquals(RESULT, parse(atLimit, chunks = intArrayOf(127, 3, 1024)))
        assertRejected("total stream byte limit plus one", paddedStream(1024 * 1024 + 1))
    }

    @Test fun accumulatedDeltaTextLimitCountsUtf8BytesBeforeCompletion() {
        val chunk = "é".repeat(8192)
        val prefix = buildString { repeat(8) { append(event("response.output_text.delta", JSONObject().put("delta", chunk))) } }
        assertEquals(128 * 1024, chunk.toByteArray(Charsets.UTF_8).size * 8)
        assertEquals(RESULT, parse(prefix + terminal()))
        assertRejected("delta UTF-8 byte limit plus one", prefix + event("response.output_text.delta", JSONObject().put("delta", "x")) + terminal())
    }

    @Test fun oversizedFinalTextIsRejectedForTerminalAndFallbackMessages() {
        val text = JSONObject().put("fixture_padding", "é".repeat(64 * 1024)).toString()
        assertTrue(text.toByteArray(Charsets.UTF_8).size > 128 * 1024)
        assertRejected("oversized terminal text", terminal(output = JSONArray().put(message(text))))
        assertRejected("oversized finalized text", finalized(0, message(text)) + terminalWithoutOutput())
    }

    @Test fun transportReadFailureCannotReturnRetainedOrPartialOutput() {
        val prefix = finalized(0, message()) + event("response.output_text.delta", JSONObject().put("delta", RESULT))
        val bytes = (prefix + terminal()).toByteArray(Charsets.UTF_8)
        val input = object : InputStream() {
            private var position = 0
            override fun read(): Int {
                if (position >= prefix.toByteArray(Charsets.UTF_8).size) throw IOException("Synthetic read interruption")
                return bytes[position++].toInt() and 0xff
            }
        }
        val failure = runCatching { readCompletedResponse(input) }.exceptionOrNull()
        assertTrue(failure is ProviderException)
        assertEquals(ProviderFailureKind.INVALID_RESPONSE, (failure as ProviderException).kind)
    }

    @Test fun eofDoesNotDispatchAnUndelimitedTerminalEventForAnyLineEnding() {
        for (ending in listOf("\n", "\r\n", "\r")) {
            val stream = terminal().replace("\n", ending)
            assertRejected("no terminal blank line", stream.removeSuffix(ending))
            assertRejected("no final line delimiter", stream.removeSuffix(ending + ending))
        }
    }

    private fun parse(stream: String, chunks: IntArray = intArrayOf(4096)): String =
        readCompletedResponse(ChunkedInput(stream.toByteArray(Charsets.UTF_8), chunks))

    private fun assertRejected(label: String, stream: String) =
        assertRejectedBytes(label, stream.toByteArray(Charsets.UTF_8))

    private fun assertRejectedBytes(label: String, bytes: ByteArray, chunks: IntArray = intArrayOf(4096)) {
        val failure = runCatching { readCompletedResponse(ChunkedInput(bytes, chunks)) }.exceptionOrNull()
        assertTrue("$label: expected ProviderException, got $failure", failure is ProviderException)
        assertEquals(label, ProviderFailureKind.INVALID_RESPONSE, (failure as ProviderException).kind)
    }

    private fun assertProviderFailure(label: String, stream: String) {
        val failure = runCatching { parse(stream) }.exceptionOrNull()
        assertTrue("$label: expected ProviderException, got $failure", failure is ProviderException)
    }

    private fun textPart(text: String = RESULT) = JSONObject().put("type", "output_text").put("text", text)

    private fun message(text: String = RESULT, phase: String? = null) = JSONObject().put("type", "message")
        .put("role", "assistant").put("status", "completed").put("content", JSONArray().put(textPart(text)))
        .apply { if (phase != null) put("phase", phase) }

    private fun reasoning() = JSONObject().put("type", "reasoning").put("summary", JSONArray())

    private fun completedResponse(output: JSONArray = JSONArray().put(message())) =
        JSONObject().put("status", "completed").put("error", JSONObject.NULL)
            .put("incomplete_details", JSONObject.NULL).put("output", output)

    private fun terminalBody(output: JSONArray = JSONArray().put(message())) =
        JSONObject().put("type", "response.completed").put("response", completedResponse(output))

    private fun terminal(output: JSONArray = JSONArray().put(message())) =
        event("response.completed", JSONObject().put("response", completedResponse(output)))

    private fun terminalWithoutOutput(nullOutput: Boolean = false): String {
        val response = completedResponse().apply {
            remove("output")
            if (nullOutput) put("output", JSONObject.NULL)
        }
        return event("response.completed", JSONObject().put("response", response))
    }

    private fun finalized(index: Any, item: Any) =
        event("response.output_item.done", JSONObject().put("output_index", index).put("item", item))

    private fun event(type: String, payload: JSONObject): String =
        "event: $type\ndata: ${JSONObject(payload.toString()).put("type", type)}\n\n"

    private fun paddedNoticeEvent(dataBytes: Int): String = buildString {
        append("event: fixture.notice\n")
        val first = "{\"type\":\"fixture.notice\"}"
        append("data: $first\n")
        var remaining = dataBytes - first.toByteArray(Charsets.UTF_8).size - 1
        while (remaining > 0) {
            val lineBytes = minOf(remaining, 16 * 1024)
            append("data: ").append(" ".repeat(lineBytes - 1)).append('\n')
            remaining -= lineBytes
        }
        append('\n')
    }

    private fun paddedStream(totalBytes: Int): String = buildString {
        val final = terminal()
        var remaining = totalBytes - final.toByteArray(Charsets.UTF_8).size
        while (remaining > 0) {
            val lineBytes = minOf(remaining, 8 * 1024)
            if (lineBytes == 1) append('\n')
            else append(':').append("x".repeat(lineBytes - 2)).append('\n')
            remaining -= lineBytes
        }
        append(final)
    }

    private class ChunkedInput(private val bytes: ByteArray, private val chunks: IntArray) : InputStream() {
        private var position = 0
        private var chunkIndex = 0
        override fun read(): Int = if (position == bytes.size) -1 else bytes[position++].toInt() and 0xff
        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (position == bytes.size) return -1
            val size = minOf(length, chunks[chunkIndex++ % chunks.size], bytes.size - position)
            bytes.copyInto(target, offset, position, position + size)
            position += size
            return size
        }
    }

    private companion object { const val RESULT = "{\"questions\":[]}" }
}
