package com.anchorreader.chatgpt

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.InputStream

class ProviderEmptyTerminalRecoveryTest {
    @Test fun measuredTextDoneItemDoneCompletedOrderRecoversFinalizedJsonOnly() {
        val markers = mutableListOf<ProviderCompletionRecord>()
        val body = event("response.output_text.delta", JSONObject().put("delta", "FAKE_SECRET_PARTIAL")) +
            event("response.output_text.done", JSONObject().put("text", "FAKE_SECRET_TEXT_DONE")) +
            event("response.content_part.done", JSONObject().put("part", JSONObject().put("text", "FAKE_SECRET_CONTENT_DONE"))) + done() + terminal()
        assertEquals(RESULT, parse(body, markers))
        assertEquals("completion_source=finalized_empty_terminal finalized_items=1 terminal_status=completed", markers.single().line())
    }

    @Test fun finalizedItemWithoutAnyDeltaRecoversAcrossAllLineEndingsAndIndexGaps() {
        for (ending in listOf("\n", "\r", "\r\n")) {
            val markers = mutableListOf<ProviderCompletionRecord>()
            assertEquals(RESULT, parse((done(index = 3_000_000_007L) + terminal()).replace("\n", ending), markers))
            assertEquals(1, markers.size)
        }
    }

    @Test fun deltaTextDoneContentDoneAndAddedItemsAloneCannotRecover() {
        for (prefix in listOf(event("response.output_text.delta", JSONObject().put("delta", RESULT)),
            event("response.output_text.done", JSONObject().put("text", RESULT)),
            event("response.content_part.done", JSONObject().put("part", JSONObject().put("text", RESULT))),
            event("response.output_item.added", JSONObject().put("output_index", 0).put("item", message())))) {
            reject(prefix + terminal())
        }
    }

    @Test fun recoveryRequiresExplicitCompletedFinalItemStatusButLegacyAbsentOutputStillWorks() {
        val item = message().apply { remove("status") }
        reject(done(item) + terminal(), ProviderStreamReason.MESSAGE_STATUS)
        for (output in listOf<Any?>(null, JSONObject.NULL)) {
            val response = completed().apply { remove("output"); if (output != null) put("output", output) }
            val markers = mutableListOf<ProviderCompletionRecord>()
            assertEquals(RESULT, parse(done(item) + terminal(response), markers))
            assertTrue(markers.isEmpty())
        }
        for (status in listOf<Any>("in_progress", "incomplete", JSONObject.NULL, true)) reject(done(message().put("status", status)) + terminal())
    }

    @Test fun wrongRolePhaseContentToolsAndMultipleFinalCandidatesRemainRejected() {
        for (item in listOf(message().put("role", "user"), message().put("phase", "FAKE_SECRET_PHASE"),
            message().put("content", JSONArray()), message().put("content", JSONArray().put(JSONObject().put("type", "refusal"))),
            JSONObject().put("type", "function_call"))) reject(done(item) + terminal())
        reject(done() + done(index = 1) + terminal(), ProviderStreamReason.RESULT_MULTIPLE)
    }

    @Test fun reasoningAndCommentaryMayAccompanyExactlyOneFinalMessageWithinTheExistingItemBound() {
        val markers = mutableListOf<ProviderCompletionRecord>()
        val reasoning = JSONObject().put("type", "reasoning")
        val commentary = message().put("phase", "commentary")
        assertEquals(RESULT, parse(done(reasoning, 3) + done(commentary, 6) + done(index = 9) + terminal(), markers))
        assertEquals("completion_source=finalized_empty_terminal finalized_items=3 terminal_status=completed", markers.single().line())
        reject(done(reasoning) + terminal(), ProviderStreamReason.RESULT_MISSING)
        reject(done(commentary) + terminal(), ProviderStreamReason.RESULT_MISSING)
        val bounded = buildString { repeat(31) { append(done(reasoning, it)) }; append(done(index = 32)); append(terminal()) }
        assertEquals(RESULT, parse(bounded))
        reject(buildString { repeat(32) { append(done(reasoning, it)) }; append(done(index = 32)); append(terminal()) }, ProviderStreamReason.FINALIZED_COUNT)
    }

    @Test fun badDuplicateIndexesAndDuplicateSuppliedItemIdentitiesAreRejected() {
        for (index in listOf<Any>(-1, 0.5, "0", JSONObject.NULL)) reject(done(index = index) + terminal(), ProviderStreamReason.FINALIZED_INDEX)
        reject(done() + done() + terminal(), ProviderStreamReason.FINALIZED_DUPLICATE)
        reject(done(JSONObject().put("type", "reasoning").put("id", "FAKE_SECRET_SAME_ID")) +
            done(message().put("id", "FAKE_SECRET_SAME_ID"), 1) + terminal(), ProviderStreamReason.RECOVERY_CONFLICT)
    }

    @Test fun consistentSuppliedResponseItemAndSequenceIdentityAllowsRecovery() {
        val markers = mutableListOf<ProviderCompletionRecord>()
        val body = event("response.created", JSONObject().put("sequence_number", 1).put("response", JSONObject().put("id", "FAKE_SECRET_RESPONSE"))) +
            event("response.output_item.added", JSONObject().put("sequence_number", 2).put("output_index", 0)
                .put("item", message().put("id", "FAKE_SECRET_ITEM").put("status", "in_progress"))) +
            event("response.output_text.delta", JSONObject().put("sequence_number", 3).put("output_index", 0).put("item_id", "FAKE_SECRET_ITEM")
                .put("response_id", "FAKE_SECRET_RESPONSE").put("delta", "FAKE_SECRET_PARTIAL")) +
            event("response.output_item.done", JSONObject().put("sequence_number", 4).put("output_index", 0).put("item", message().put("id", "FAKE_SECRET_ITEM"))) +
            event("response.completed", JSONObject().put("sequence_number", 5).put("response", completed().put("id", "FAKE_SECRET_RESPONSE")))
        assertEquals(RESULT, parse(body, markers))
        assertFalse(markers.single().line().contains("FAKE_SECRET"))
    }

    @Test fun suppliedResponseIdentityConflictsDoNotRecover() {
        val created = event("response.created", JSONObject().put("response", JSONObject().put("id", "FAKE_SECRET_ONE")))
        reject(created + done() + terminal(completed().put("id", "FAKE_SECRET_TWO")), ProviderStreamReason.RECOVERY_CONFLICT)
        reject(created + event("response.output_item.done", JSONObject().put("output_index", 0).put("response_id", "FAKE_SECRET_TWO")
            .put("item", message())) + terminal(), ProviderStreamReason.RECOVERY_CONFLICT)
    }

    @Test fun addedDoneOrTextItemIdentityConflictAndMalformedIdsDoNotRecover() {
        val added = event("response.output_item.added", JSONObject().put("output_index", 0).put("item", message().put("id", "FAKE_SECRET_ONE")))
        reject(added + done(message().put("id", "FAKE_SECRET_TWO")) + terminal(), ProviderStreamReason.RECOVERY_CONFLICT)
        reject(event("response.output_item.done", JSONObject().put("output_index", 0).put("item_id", "FAKE_SECRET_ONE")
            .put("item", message().put("id", "FAKE_SECRET_TWO"))) + terminal(), ProviderStreamReason.RECOVERY_CONFLICT)
        for (id in listOf<Any>(JSONObject.NULL, true, 123, "", "x".repeat(257))) {
            reject(done(message().put("id", id)) + terminal(), ProviderStreamReason.RECOVERY_CONFLICT)
        }
    }

    @Test fun suppliedSequenceNumbersMustBeNonnegativeIntegralAndStrictlyIncreasing() {
        for (number in listOf<Any>(-1, 0.5, "1", JSONObject.NULL, true)) {
            reject(event("response.created", JSONObject().put("sequence_number", number)) + done() + terminal(), ProviderStreamReason.RECOVERY_CONFLICT)
        }
        for (last in listOf(2, 1)) {
            reject(event("response.output_item.done", JSONObject().put("output_index", 0).put("sequence_number", 2).put("item", message())) +
                event("response.completed", JSONObject().put("sequence_number", last).put("response", completed())), ProviderStreamReason.RECOVERY_CONFLICT)
        }
    }

    @Test fun duplicateAddedItemsAndItemEventsAfterDoneCannotRecover() {
        val added = event("response.output_item.added", JSONObject().put("output_index", 0).put("item", message()))
        reject(added + added + done() + terminal(), ProviderStreamReason.RECOVERY_CONFLICT)
        for (event in listOf(added, event("response.output_text.delta", JSONObject().put("output_index", 0).put("delta", "late")),
            event("response.output_text.done", JSONObject().put("output_index", 0).put("text", RESULT)),
            event("response.content_part.done", JSONObject().put("output_index", 0)))) {
            reject(done() + event + terminal(), ProviderStreamReason.RECOVERY_CONFLICT)
        }
    }

    @Test fun exactKnownItemIdOnlyLateDeltaCannotEnableEmptyTerminalRecovery() {
        val prefix = done(message().put("id", "fixture-item")) +
            event("response.output_text.delta", JSONObject().put("item_id", "fixture-item").put("delta", "late"))
        reject(prefix + terminal(), ProviderStreamReason.RECOVERY_CONFLICT)
    }

    @Test fun knownItemIdOnlyLateTextContentAndNestedAddedEventsCannotRecover() {
        val prefix = done(message().put("id", "FAKE_SECRET_ITEM"))
        val events = listOf(
            event("response.output_text.done", JSONObject().put("item_id", "FAKE_SECRET_ITEM").put("text", RESULT)),
            event("response.content_part.added", JSONObject().put("item_id", "FAKE_SECRET_ITEM")),
            event("response.content_part.done", JSONObject().put("item_id", "FAKE_SECRET_ITEM")),
            event("response.output_item.added", JSONObject().put("item", message().put("id", "FAKE_SECRET_ITEM"))),
        )
        events.forEach { reject(prefix + it + terminal(), ProviderStreamReason.RECOVERY_CONFLICT) }
    }

    @Test fun knownUnfinishedItemIdCorrelatesLegalPreDoneEventsWithoutAnOutputIndex() {
        val added = event("response.output_item.added", JSONObject().put("output_index", 7)
            .put("item", message().put("id", "FAKE_SECRET_ITEM").put("status", "in_progress")))
        val events = event("response.output_text.delta", JSONObject().put("item_id", "FAKE_SECRET_ITEM").put("delta", "partial")) +
            event("response.output_text.done", JSONObject().put("item_id", "FAKE_SECRET_ITEM").put("text", RESULT)) +
            event("response.content_part.done", JSONObject().put("item_id", "FAKE_SECRET_ITEM"))
        val markers = mutableListOf<ProviderCompletionRecord>()
        assertEquals(RESULT, parse(added + events + done(message().put("id", "FAKE_SECRET_ITEM"), 7) + terminal(), markers))
        assertEquals(1, markers.size)
        assertEquals(RESULT, parse(event("response.output_text.delta", JSONObject().put("delta", "partial")) + done() + terminal()))
    }

    @Test fun suppliedIndexCannotConflictWithTheKnownIndexOfAnUnfinishedItem() {
        val added = event("response.output_item.added", JSONObject().put("output_index", 7)
            .put("item", message().put("id", "FAKE_SECRET_ITEM").put("status", "in_progress")))
        val conflicting = event("response.output_text.delta", JSONObject().put("output_index", 8)
            .put("item_id", "FAKE_SECRET_ITEM").put("delta", "partial"))
        reject(added + conflicting + done(message().put("id", "FAKE_SECRET_ITEM"), 7) + terminal(), ProviderStreamReason.RECOVERY_CONFLICT)
    }

    @Test fun knownItemIdOnlyLateEventDoesNotChangeNonemptyAbsentOrNullTerminalAuthority() {
        val prefix = done(message().put("id", "FAKE_SECRET_ITEM")) +
            event("response.output_text.delta", JSONObject().put("item_id", "FAKE_SECRET_ITEM").put("delta", "late"))
        val markers = mutableListOf<ProviderCompletionRecord>()
        val authoritative = "{\"authoritative\":true}"
        assertEquals(authoritative, parse(prefix + terminal(completed(JSONArray().put(message(authoritative)))), markers))
        assertEquals(RESULT, parse(prefix + terminal(completed().apply { remove("output") }), markers))
        assertEquals(RESULT, parse(prefix + terminal(completed().put("output", JSONObject.NULL)), markers))
        assertTrue(markers.isEmpty())
    }

    @Test fun newIdentityGuardCannotReplaceNonemptyTerminalAuthorityOrChangeAbsentNullFallback() {
        val prefix = event("response.created", JSONObject().put("sequence_number", -1)) + done()
        val markers = mutableListOf<ProviderCompletionRecord>()
        val authoritative = "{\"authoritative\":true}"
        assertEquals(authoritative, parse(prefix + terminal(completed(JSONArray().put(message(authoritative)))), markers))
        assertEquals(RESULT, parse(prefix + terminal(completed().apply { remove("output") }), markers))
        assertEquals(RESULT, parse(prefix + terminal(completed().put("output", JSONObject.NULL)), markers))
        assertTrue(markers.isEmpty())
        for (value in listOf<Any>("wrong shape", JSONObject(), JSONArray().put(JSONObject.NULL), JSONArray().put(message("invalid")))) {
            reject(done() + terminal(completed().put("output", value)))
        }
    }

    @Test fun terminalErrorIncompleteStatusOrRefusalCannotRecover() {
        for (response in listOf(completed().put("status", "incomplete"), completed().put("error", JSONObject()), completed().put("incomplete_details", JSONObject()))) {
            reject(done() + terminal(response))
        }
        for (type in listOf("response.refusal.delta", "response.refusal.done", "response.incomplete", "response.failed", "error")) {
            reject(done() + event(type, JSONObject()) + terminal())
        }
    }

    @Test fun missingDelimiterEofAndReadInterruptionCannotRecover() {
        reject(done())
        reject(done() + terminal().removeSuffix("\n"), ProviderStreamReason.MISSING_COMPLETED)
        reject(done() + "data: [DONE]\n\n", ProviderStreamReason.EARLY_DONE)
        val prefix = done().toByteArray()
        val input = object : InputStream() {
            var offset = 0
            override fun read() = if (offset < prefix.size) prefix[offset++].toInt() and 255 else throw IOException("FAKE_SECRET_IO")
        }
        val error = runCatching { readCompletedResponse(input) }.exceptionOrNull() as ProviderException
        assertEquals(ProviderStreamReason.READ_IO, error.streamReason)
    }

    @Test fun blankOversizedAndNonObjectOrMalformedFinalizedJsonNeverEmitRecovery() {
        for (text in listOf(" ", "{invalid", "[]", "{} {}", "{} FAKE_SECRET_TRAILING", "x".repeat(128 * 1024 + 1))) {
            reject(done(message(text)) + terminal())
        }
    }

    @Test fun throwingRecoverySinkCannotChangeTheParsedResult() {
        assertEquals(RESULT, readCompletedResponse((done() + terminal()).byteInputStream()) { throw IllegalStateException("FAKE_SECRET_SINK") })
    }

    private fun parse(body: String, records: MutableList<ProviderCompletionRecord> = mutableListOf()) =
        readCompletedResponse(body.byteInputStream()) { records += it }
    private fun reject(body: String, reason: ProviderStreamReason? = null) {
        val records = mutableListOf<ProviderCompletionRecord>()
        val error = runCatching { parse(body, records) }.exceptionOrNull()
        assertTrue("Expected rejected synthetic stream", error is ProviderException)
        if (reason != null) assertEquals(reason, (error as ProviderException).streamReason)
        assertTrue(records.isEmpty())
        val line = ProviderFailureRecord.classify(ProviderRequestStage.RESPONSES_READ, error as ProviderException, 200)!!.line()
        assertFalse(line.contains("FAKE_SECRET"))
    }
    private fun message(text: String = RESULT) = JSONObject().put("type", "message").put("role", "assistant").put("status", "completed")
        .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", text)))
    private fun completed(output: JSONArray = JSONArray()) = JSONObject().put("status", "completed").put("error", JSONObject.NULL)
        .put("incomplete_details", JSONObject.NULL).put("output", output)
    private fun done(item: JSONObject = message(), index: Any = 0) = event("response.output_item.done", JSONObject().put("output_index", index).put("item", item))
    private fun terminal(response: JSONObject = completed()) = event("response.completed", JSONObject().put("response", response))
    private fun event(type: String, body: JSONObject) = "event: $type\ndata: ${body.put("type", type)}\n\n"
    private companion object { const val RESULT = "{\"questions\":[]}" }
}
