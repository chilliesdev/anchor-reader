package com.anchorreader.chatgpt

import com.anchorreader.data.Book
import com.anchorreader.data.Concept
import com.anchorreader.data.QuestionSource
import com.anchorreader.data.QuestionValidator
import com.anchorreader.data.SourceSpan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ProviderStreamMediaTest {
    @Test fun completedEmptyTerminalRecoversOneFinalizedResultAndEmitsOnlyTheFixedBranchMarker() = runBlocking {
        val fixture = MediaFixture(emptyMap(), diagnosticResponsesCallLimit = 1)
        fixture.body = emptyTerminalRecoveryMediaEvent(fixture.output)
        assertEquals(fixture.output, fixture.response())
        assertEquals("completion_source=finalized_empty_terminal finalized_items=1 terminal_status=completed", fixture.completions.single().line())
        assertTrue(fixture.records.isEmpty())
        assertEquals("Generation stopped. Your book and ready questions are saved.", fixture.failure().message)
        fixture.assertTransportCleanup(2, 1)
        assertEquals(1, fixture.completions.size)
    }

    @Test fun throwingRecoverySinkCannotChangeTheResultCleanupOrSpentCap() = runBlocking {
        val fixture = MediaFixture(emptyMap(), diagnosticResponsesCallLimit = 1)
        fixture.body = emptyTerminalRecoveryMediaEvent(fixture.output)
        fixture.completionSinkFailure = IllegalStateException("FAKE_SECRET_COMPLETION_SINK")
        assertEquals(fixture.output, fixture.response())
        fixture.assertTransportCleanup(1, 1)
        assertEquals(1, fixture.completions.size)
        assertTrue(fixture.records.isEmpty())
        fixture.failure()
        fixture.assertTransportCleanup(2, 1)
    }

    @Test fun boundedStreamEvidenceAndRequestConfigurationReachTheFailureSinkWithoutSpendingAnotherCall() = runBlocking {
        val fixture = MediaFixture(emptyMap(), diagnosticResponsesCallLimit = 1)
        fixture.body = mediaEvent("response.output_text.delta", JSONObject().put("delta", "FAKE_SECRET_DELTA")) +
            mediaEvent("response.completed", JSONObject().put("response", JSONObject().put("status", "completed").put("output", JSONArray())
                .put("usage", JSONObject().put("input_tokens", 20).put("output_tokens", 0).put("total_tokens", 20))))
        fixture.sinkFailure = IllegalStateException("FAKE_SECRET_SINK")
        assertEquals(ProviderStreamReason.OUTPUT_EMPTY, fixture.failure().streamReason)
        val record = fixture.records.single()
        val line = record.evidenceLine()!!
        assertTrue(line.startsWith("evidence=responses request=json_schema_stream_no_store model_support=unknown "))
        for (field in listOf("deltas=1", "delta_text=1", "terminal_output=empty", "input_tokens=20", "output_tokens=0")) {
            assertTrue(field in line.split(' '))
        }
        assertFalse(line.contains("FAKE_SECRET"))
        assertFalse(line.contains(fixture.account))
        assertTrue(line.length < 1024)
        assertEquals("Generation stopped. Your book and ready questions are saved.", fixture.failure().message)
        fixture.assertTransportCleanup(2, 1)
        assertNull(fixture.records.last().streamEvidence)
    }

    @Test fun asciiSseCaseParametersAndHeaderNamesReachCompletedOutput() = runBlocking {
        val headers = listOf(
            "Content-Type" to "text/event-stream",
            "content-type" to "TEXT/EVENT-STREAM",
            "CoNtEnT-TyPe" to "TeXt/EvEnT-StReAm",
            "Content-Type" to "text/event-stream; charset=UTF-8",
            "content-type" to "TEXT/EVENT-STREAM; charset=\"UTF-8\"",
            "CoNtEnT-TyPe" to " \tTeXt/EvEnT-StReAm \t; charset=FAKE_SECRET_PARAMETER",
        )
        for ((name, value) in headers) {
            val fixture = MediaFixture(mapOf(name to listOf(value)))
            assertEquals(fixture.output, fixture.response())
            fixture.assertRequestsAndCleanup(readsExpected = true)
            assertTrue(fixture.records.isEmpty())
        }
    }

    @Test fun invalidMediaRejectsEvenCompletedSseWithoutReadingTheBodyAndLogsOnlyFixedCategories() = runBlocking {
        val cases = listOf(
            "application/json" to "json", "APPLICATION/JSON; FAKE_SECRET_PARAMETER" to "json",
            "text/html" to "html", " \tTeXt/HtMl ; FAKE_SECRET_PARAMETER" to "html",
            "text/plain" to "other", "application/FAKE_SECRET_UNKNOWN" to "other",
            "text/event-stream+FAKE_SECRET_SUFFIX" to "other",
            "text/event-stream, application/json" to "other",
            "text/event-\u017Ftream" to "other", "text/event-stream\nFAKE_SECRET_MALFORMED" to "other",
            "application/json" + "FAKE_SECRET_OVERSIZED".repeat(4096) to "other",
        )
        for ((value, category) in cases) {
            val fixture = MediaFixture(mapOf("cOnTeNt-TyPe" to listOf(value)))
            fixture.assertMediaFailure(category)
            fixture.assertRequestsAndCleanup(readsExpected = false)
            val line = fixture.records.single().line()
            assertFalse(line.contains("FAKE_SECRET"))
            assertFalse(line.contains("https://"))
            assertFalse(line.contains(fixture.account))
            assertFalse(line.contains(value))
            assertTrue(line.length < 160)
        }
    }

    @Test fun normalizedMissingMediaUsesOnlyTheCompletedSseParser() = runBlocking {
        val cases = listOf(
            emptyMap(), mapOf("Content-Type" to emptyList()), mapOf("content-type" to listOf("")),
            mapOf("Content-Type" to listOf(" \t ")), mapOf("Content-Type" to listOf("; FAKE_SECRET_PARAMETER")),
            mapOf("Content-Type" to listOf("", "application/json")),
            linkedMapOf("content-type" to emptyList(), "CONTENT-TYPE" to listOf("text/html")),
        )
        for (headers in cases) {
            val fixture = MediaFixture(headers)
            assertEquals(fixture.output, fixture.response())
            fixture.assertRequestsAndCleanup(readsExpected = true)
            assertTrue(fixture.records.isEmpty())
        }
    }

    @Test fun missingMediaCannotTurnNonSseIncompleteFailedOrOversizedBodiesIntoTerminalOutput() = runBlocking {
        val cases = listOf(
            Triple("{\"FAKE_SECRET_JSON\":true}", ProviderFailureKind.INVALID_RESPONSE, "no_sse_data"),
            Triple("<html>FAKE_SECRET_HTML</html>", ProviderFailureKind.INVALID_RESPONSE, "no_sse_data"),
            Triple("", ProviderFailureKind.INVALID_RESPONSE, "no_sse_data"),
            Triple("event: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\",\"delta\":\"FAKE_SECRET_BODY\"}\n\n", ProviderFailureKind.INVALID_RESPONSE, "missing_completed"),
            Triple("data: [DONE]\n\n", ProviderFailureKind.INVALID_RESPONSE, "early_done"),
            Triple("event: response.completed\ndata: {FAKE_SECRET_MALFORMED\n\n", ProviderFailureKind.INVALID_RESPONSE, "event_json"),
            Triple("event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{\"status\":\"incomplete\"}}\n\n", ProviderFailureKind.INVALID_RESPONSE, "completed_status"),
            Triple("event: response.failed\ndata: {\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"insufficient_quota\",\"message\":\"FAKE_SECRET_ERROR\"}}}\n\n", ProviderFailureKind.LIMIT, "provider_error"),
            Triple(":" + "FAKE_SECRET_OVERSIZED".repeat(4000) + "\n\n", ProviderFailureKind.INVALID_RESPONSE, "line_bound"),
        )
        for ((body, kind, reason) in cases) {
            val fixture = MediaFixture(emptyMap())
            fixture.body = body
            assertEquals(kind, fixture.failure().kind)
            fixture.assertRequestsAndCleanup(readsExpected = true)
            assertEquals("stage=responses_read class=ProviderException code=provider_failure status=200 failure_kind=${kind.name.lowercase()} stream=$reason media=missing", fixture.records.single().line())
            assertEquals(ProviderStreamMedia.MISSING, fixture.records.single().media)
        }
    }

    @Test fun missingMediaTerminalOutputMustStillPassTheActualLocalQuestionValidator() = runBlocking {
        val text = "Saved books can be read without a network connection."
        val unit = Concept("Offline", listOf(text), 2, listOf(SourceSpan(2, text, 0, text.length)))
        val book = Book("fixture-book", "Source", "", listOf(unit), text)
        val source = QuestionSource(unit.id, unit.sourceSpans, 3)
        fun question() = JSONObject().put("prompt", "When can a saved book be read?")
            .put("options", JSONArray(listOf("Without a network connection", "Only with a server", "Only after payment", "Never offline")))
            .put("correctIndex", 0).put("explanation", text).put("sourcePage", 2).put("sourceExcerpt", text)
        fun payload(question: JSONObject) = JSONObject().put("sectionId", unit.id).put("questions", JSONArray().put(question))
        val valid = payload(question()).toString()
        val fixture = MediaFixture(emptyMap(), diagnosticResponsesCallLimit = 1)
        fixture.body = completedMediaEvent(valid)
        val result = fixture.response(source.content, QuestionValidator.schema(source.maxQuestions),
            QuestionValidator.INSTRUCTIONS + " Make at most ${source.maxQuestions} questions.")
        assertEquals(valid, result)
        val parsed = QuestionValidator.parse(result, book, 0, source).single()
        assertEquals(0, parsed.correctIndex)
        assertEquals(2, parsed.sourcePage)
        assertEquals(text, parsed.sourceExcerpt)
        assertEquals(3, fixture.lastRequest.getJSONObject("text").getJSONObject("format").getJSONObject("schema")
            .getJSONObject("properties").getJSONObject("questions").getInt("maxItems"))
        assertTrue(fixture.lastRequest.getString("instructions").endsWith("Make at most 3 questions."))
        fixture.assertTransportCleanup(1, 1)
        assertTrue(fixture.records.isEmpty())
        val stopped = fixture.failure()
        assertEquals(ProviderFailureKind.INVALID_RESPONSE, stopped.kind)
        assertEquals("Generation stopped. Your book and ready questions are saved.", stopped.message)
        fixture.assertTransportCleanup(2, 1)
        assertEquals("stage=responses_open class=ProviderException code=provider_failure failure_kind=invalid_response", fixture.records.single().line())
        val finalized = MediaFixture(emptyMap(), diagnosticResponsesCallLimit = 1)
        finalized.body = finalizedFallbackMediaEvent(valid)
        val finalOutput = finalized.response(source.content, QuestionValidator.schema(source.maxQuestions),
            QuestionValidator.INSTRUCTIONS + " Make at most ${source.maxQuestions} questions.")
        assertEquals(valid, finalOutput)
        assertEquals(parsed, QuestionValidator.parse(finalOutput, book, 0, source).single())
        assertTrue(finalized.records.isEmpty())
        assertEquals(3, finalized.lastRequest.getJSONObject("text").getJSONObject("format").getJSONObject("schema")
            .getJSONObject("properties").getJSONObject("questions").getInt("maxItems"))
        assertEquals(ProviderFailureKind.INVALID_RESPONSE, finalized.failure().kind)
        finalized.assertTransportCleanup(2, 1)
        assertEquals("stage=responses_open class=ProviderException code=provider_failure failure_kind=invalid_response", finalized.records.single().line())
        for (invalid in listOf(payload(question().put("correctIndex", 4)), payload(question().put("sourcePage", 99)),
            payload(question().put("sourceExcerpt", "FAKE_SECRET_FABRICATED_SOURCE")), payload(question()).put("sectionId", "FAKE_SECRET_WRONG_SECTION"))) {
            val rejected = MediaFixture(emptyMap())
            val invalidText = invalid.toString()
            rejected.body = finalizedFallbackMediaEvent(invalidText)
            val terminalOutput = rejected.response(source.content, QuestionValidator.schema(source.maxQuestions))
            assertEquals(invalidText, terminalOutput) // Protocol completion alone is not question acceptance.
            assertTrue(runCatching { QuestionValidator.parse(terminalOutput, book, 0, source) }.exceptionOrNull() is IllegalArgumentException)
            rejected.assertTransportCleanup(1, 1)
            assertTrue(rejected.records.isEmpty())
        }
    }

    @Test fun firstContentTypeValueAndFirstMatchingHeaderRemainAuthoritative() = runBlocking {
        val cases = listOf(
            mapOf("Content-Type" to listOf("application/json", "text/event-stream")) to "json",
            mapOf("Content-Type" to listOf("FAKE_SECRET_FIRST", "TEXT/EVENT-STREAM")) to "other",
            linkedMapOf("content-type" to listOf("text/html"), "CONTENT-TYPE" to listOf("text/event-stream")) to "html",
        )
        for ((headers, category) in cases) {
            val fixture = MediaFixture(headers)
            fixture.assertMediaFailure(category)
            fixture.assertRequestsAndCleanup(readsExpected = false)
        }
        val fixture = MediaFixture(mapOf("content-type" to listOf("TEXT/EVENT-STREAM", "application/json")))
        assertEquals(fixture.output, fixture.response())
        fixture.assertRequestsAndCleanup(readsExpected = true)
        assertTrue(fixture.records.isEmpty())
    }

    @Test fun rejectedMediaCategoryDoesNotCarryIntoALaterParserFailure() = runBlocking {
        val fixture = MediaFixture(mapOf("Content-Type" to listOf("application/FAKE_SECRET_TYPE")))
        fixture.assertMediaFailure("other")
        fixture.headers = mapOf("Content-Type" to listOf("TEXT/EVENT-STREAM; FAKE_SECRET_PARAMETER"))
        fixture.body = "event: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\",\"delta\":\"FAKE_SECRET_BODY\"}\n\n"
        val error = fixture.failure()
        assertEquals(ProviderFailureKind.INVALID_RESPONSE, error.kind)
        assertEquals("ChatGPT did not return complete questions. Try again.", error.message)
        assertEquals("stage=responses_read class=ProviderException code=provider_failure status=200 failure_kind=invalid_response stream=missing_completed media=event_stream", fixture.records.last().line())
        assertEquals(ProviderStreamMedia.EVENT_STREAM, fixture.records.last().media)
        assertEquals(2, fixture.modelCalls)
        assertEquals(2, fixture.responseCalls)
        assertEquals(0, fixture.inputs.first().readCalls)
        assertTrue(fixture.inputs.last().readCalls > 0)
        assertTrue(fixture.inputs.all { it.closed })
        assertEquals(2, fixture.responseReleases)
    }

    @Test fun httpFailureBeforeTheMediaGateKeepsItsExistingErrorAndDiagnosticLine() = runBlocking {
        val fixture = MediaFixture(emptyMap())
        fixture.status = 503
        fixture.body = "{\"error\":{\"code\":\"FAKE_SECRET_CODE\",\"message\":\"FAKE_SECRET_BODY\"}}"
        val error = fixture.failure()
        assertEquals(ProviderFailureKind.NETWORK, error.kind)
        assertEquals("ChatGPT is unavailable. Try again later.", error.message)
        assertEquals("stage=responses_read class=ProviderException code=provider_failure status=503 failure_kind=network", fixture.records.single().line())
        assertNull(fixture.records.single().media)
        fixture.assertRequestsAndCleanup(readsExpected = true)
    }

    @Test fun unsafeFinalizedOutputHasOnlyFixedDiagnosticsAndCannotRefundTheCap() = runBlocking {
        val fixture = MediaFixture(emptyMap(), diagnosticResponsesCallLimit = 1)
        val invalid = JSONObject().put("type", "message").put("role", "assistant").put("status", "completed")
            .put("phase", "FAKE_SECRET_UNKNOWN_PHASE").put("content", JSONArray().put(JSONObject()
                .put("type", "output_text").put("text", "FAKE_SECRET_BODY")))
        fixture.body = mediaEvent("response.output_item.done", JSONObject().put("output_index", 3).put("item", invalid)) +
            mediaEvent("response.completed", JSONObject().put("response", JSONObject().put("status", "completed")))
        assertEquals(ProviderFailureKind.INVALID_RESPONSE, fixture.failure().kind)
        assertEquals("stage=responses_read class=ProviderException code=provider_failure status=200 failure_kind=invalid_response stream=message_phase media=missing", fixture.records.single().line())
        assertEquals(ProviderStreamMedia.MISSING, fixture.records.single().media)
        assertFalse(fixture.records.single().line().contains("FAKE_SECRET"))
        fixture.body = finalizedFallbackMediaEvent(fixture.output)
        assertEquals("Generation stopped. Your book and ready questions are saved.", fixture.failure().message)
        fixture.assertTransportCleanup(2, 1)
        assertEquals("stage=responses_open class=ProviderException code=provider_failure failure_kind=invalid_response", fixture.records.last().line())
    }

    @Test fun acceptedMediaFailuresRecordOnlyFixedHeaderAndStreamCategories() = runBlocking {
        for ((headers, category) in listOf(emptyMap<String, List<String>>() to "missing",
            mapOf("Content-Type" to listOf("TEXT/EVENT-STREAM; FAKE_SECRET_HEADER")) to "event_stream")) {
            val fixture = MediaFixture(headers)
            fixture.body = "data: [DONE]\n\n"
            assertEquals(ProviderFailureKind.INVALID_RESPONSE, fixture.failure().kind)
            assertEquals("stage=responses_read class=ProviderException code=provider_failure status=200 failure_kind=invalid_response stream=early_done media=$category",
                fixture.records.single().line())
            fixture.assertRequestsAndCleanup(readsExpected = true)
        }
    }

    @Test fun providerReadTimeoutIoAndInvalidUtf8KeepTheirSafeFailureAndCloseTransport() = runBlocking {
        for ((reason, error) in listOf("read_timeout" to SocketTimeoutException("FAKE_SECRET_TIMEOUT"),
            "read_io" to IOException("FAKE_SECRET_IO https://FAKE_SECRET_URL.test/?token=FAKE_SECRET_TOKEN"))) {
            val fixture = MediaFixture(mapOf("Content-Type" to listOf("text/event-stream; FAKE_SECRET_PARAMETER")))
            fixture.responseReadFailure = error
            val failure = fixture.failure()
            assertEquals(ProviderFailureKind.INVALID_RESPONSE, failure.kind)
            assertEquals("ChatGPT did not return complete questions. Try again.", failure.message)
            assertNull(failure.cause)
            assertEquals("stage=responses_read class=ProviderException code=provider_failure status=200 failure_kind=invalid_response stream=$reason media=event_stream",
                fixture.records.single().line())
            fixture.assertRequestsAndCleanup(readsExpected = true)
        }
        val fixture = MediaFixture(emptyMap())
        fixture.responseBytes = byteArrayOf(0xc3.toByte(), 0x28)
        assertEquals(ProviderFailureKind.INVALID_RESPONSE, fixture.failure().kind)
        assertEquals("stage=responses_read class=ProviderException code=provider_failure status=200 failure_kind=invalid_response stream=invalid_utf8 media=missing",
            fixture.records.single().line())
        fixture.assertRequestsAndCleanup(readsExpected = true)
    }

    @Test fun throwingDiagnosticSinkCannotReplaceFailureOrRefundTheCapOrSkipCleanup() = runBlocking {
        val fixture = MediaFixture(emptyMap(), diagnosticResponsesCallLimit = 1)
        fixture.body = "data: [DONE]\n\n"
        fixture.sinkFailure = IllegalStateException("FAKE_SECRET_SINK")
        val failure = fixture.failure()
        assertEquals(ProviderFailureKind.INVALID_RESPONSE, failure.kind)
        assertEquals("ChatGPT did not return complete questions. Try again.", failure.message)
        assertNull(failure.cause)
        assertEquals("early_done", fixture.records.single().streamReason!!.label)
        fixture.body = completedMediaEvent(fixture.output)
        assertEquals("Generation stopped. Your book and ready questions are saved.", fixture.failure().message)
        fixture.assertTransportCleanup(2, 1)
        assertEquals(2, fixture.records.size)
        assertEquals("stage=responses_open class=ProviderException code=provider_failure failure_kind=invalid_response", fixture.records.last().line())
        assertTrue(fixture.records.none { it.line().contains("FAKE_SECRET") })
    }

    @Test fun streamedProviderFailureKindsAreClosedAndNeverIncludeProviderErrorCodes() = runBlocking {
        val cases = listOf("insufficient_quota" to "limit", "subscription_sharing_user_not_eligible" to "no_plan",
            "subscription_sharing_invalid_user" to "auth", "subscription_sharing_usage_unavailable" to "network",
            "FAKE_SECRET_CODE" to "invalid_response")
        for ((code, kind) in cases) {
            val fixture = MediaFixture(emptyMap())
            fixture.body = mediaEvent("error", JSONObject().put("error", JSONObject().put("code", code).put("message", "FAKE_SECRET_BODY")))
            val failure = fixture.failure()
            assertEquals(kind, failure.kind.name.lowercase())
            assertEquals("stage=responses_read class=ProviderException code=provider_failure status=200 failure_kind=$kind stream=provider_error media=missing",
                fixture.records.single().line())
            assertFalse(fixture.records.single().line().contains(code))
            assertNull(failure.cause)
            fixture.assertRequestsAndCleanup(readsExpected = true)
        }
    }
}

class ProviderResponsesCapTest {
    @Test fun capOneAllowsOneCompletedOutputAndStopsTheNextSectionBeforePost() = runBlocking {
        val fixture = cappedFixture()
        assertEquals(fixture.output, fixture.response())
        assertCapDenied(fixture.failure())
        fixture.assertTransportCleanup(2, 1)
        assertEquals(1, fixture.inputs.size)
        assertCapRecord(fixture.records.single())
    }

    @Test fun parserHttpAndProviderFailuresNeverRefundTheReservationForManualRetry() = runBlocking {
        val cases = listOf(
            Triple(200, "data: [DONE]\n\n", ProviderFailureKind.INVALID_RESPONSE),
            Triple(503, "{\"error\":{\"code\":\"FAKE_SECRET_CODE\",\"message\":\"FAKE_SECRET_BODY\"}}", ProviderFailureKind.NETWORK),
            Triple(200, "event: response.failed\ndata: {\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"insufficient_quota\"}}}\n\n", ProviderFailureKind.LIMIT),
        )
        for ((status, body, kind) in cases) {
            val fixture = cappedFixture()
            fixture.status = status
            fixture.body = body
            assertEquals(kind, fixture.failure().kind)
            fixture.status = 200
            fixture.body = completedMediaEvent(fixture.output)
            assertCapDenied(fixture.failure())
            fixture.assertTransportCleanup(2, 1)
            assertTrue(fixture.inputs.single().readCalls > 0)
            assertEquals(2, fixture.records.size)
            assertCapRecord(fixture.records.last())
        }
    }

    @Test fun responsesOpenFailureNeverRefundsTheReservation() = runBlocking {
        val fixture = cappedFixture()
        fixture.responsesOpenFailure = IOException("FAKE_SECRET_OPEN https://FAKE_SECRET_URL.test/access_token=FAKE_SECRET_TOKEN")
        assertEquals(ProviderFailureKind.NETWORK, fixture.failure().kind)
        assertEquals("stage=responses_open class=IOException code=io_failure", fixture.records.single().line())
        fixture.responsesOpenFailure = null
        assertCapDenied(fixture.failure())
        fixture.assertTransportCleanup(2, 1)
        assertTrue(fixture.inputs.isEmpty())
        assertEquals(0, fixture.responseReleases)
        assertCapRecord(fixture.records.last())
    }

    @Test fun coroutineCancellationAfterReservationCannotEnableAnotherPost() = runBlocking {
        val fixture = cappedFixture()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fixture.beforeResponseReply = {
            entered.countDown()
            check(release.await(3, TimeUnit.SECONDS))
        }
        val first = async(Dispatchers.IO) { fixture.response() }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            first.cancel()
            release.countDown()
            val canceled = try { first.await(); null } catch (error: Exception) { error }
            assertTrue(canceled is CancellationException)
            assertTrue(fixture.records.isEmpty())
            fixture.beforeResponseReply = null
            assertCapDenied(fixture.failure())
            fixture.assertTransportCleanup(2, 1)
            assertTrue(fixture.inputs.single().readCalls > 0)
            assertCapRecord(fixture.records.single())
        } finally { release.countDown(); first.cancel() }
    }

    @Test fun competingCallsOnOneProviderOpenResponsesOnlyOnce() = runBlocking {
        val fixture = cappedFixture()
        val requests = (1..8).map { async(Dispatchers.IO) { runCatching { fixture.response() } } }
        val results = requests.map { it.await() }
        assertEquals(1, results.count { it.getOrNull() == fixture.output })
        val errors = results.mapNotNull { it.exceptionOrNull() }
        assertEquals(7, errors.size)
        errors.forEach { assertTrue(it is ProviderException); assertCapDenied(it as ProviderException) }
        fixture.assertTransportCleanup(8, 1)
        assertEquals(7, fixture.records.size)
        fixture.records.forEach { assertCapRecord(it) }
    }

    @Test fun sessionAndCatalogFailureBeforePostDoNotSpendTheReservation() = runBlocking {
        val fixture = cappedFixture()
        val sessionFailure = try { fixture.response(accountId = "FAKE_SECRET_WRONG_ACCOUNT"); null } catch (error: Exception) { error }
        assertTrue(sessionFailure is ProviderException)
        assertEquals(ProviderFailureKind.AUTH, (sessionFailure as ProviderException).kind)
        assertEquals(0, fixture.modelCalls)
        assertEquals(0, fixture.responseCalls)
        val models = fixture.modelsBody
        fixture.modelsBody = "FAKE_SECRET_CATALOG {broken"
        assertEquals(ProviderFailureKind.NETWORK, fixture.failure().kind)
        assertEquals(1, fixture.modelCalls)
        assertEquals(0, fixture.responseCalls)
        fixture.modelsBody = models
        assertEquals(fixture.output, fixture.response())
        fixture.assertTransportCleanup(2, 1)
        assertEquals(2, fixture.records.size)
        assertEquals("stage=session_check class=ProviderException code=provider_failure failure_kind=auth", fixture.records.first().line())
        assertEquals("stage=models_parse class=JSONException code=catalog_shape status=200", fixture.records.last().line())
    }

    @Test fun defaultZeroCapStillAllowsTwoSuccessfulSectionPosts() = runBlocking {
        val fixture = MediaFixture(emptyMap()) // Internal default remains uncapped, independent of diagnostic build config.
        assertEquals(fixture.output, fixture.response())
        assertEquals(fixture.output, fixture.response())
        fixture.assertTransportCleanup(2, 2)
        assertEquals(2, fixture.inputs.size)
        assertTrue(fixture.inputs.all { it.readCalls > 0 })
        assertTrue(fixture.records.isEmpty())
    }

    private fun cappedFixture() = MediaFixture(emptyMap(), diagnosticResponsesCallLimit = 1)
    private fun assertCapDenied(error: ProviderException) {
        assertEquals(ProviderFailureKind.INVALID_RESPONSE, error.kind)
        assertEquals("Generation stopped. Your book and ready questions are saved.", error.message)
    }
    private fun assertCapRecord(record: ProviderFailureRecord) {
        assertEquals("stage=responses_open class=ProviderException code=provider_failure failure_kind=invalid_response", record.line())
        assertNull(record.httpStatus)
        assertNull(record.media)
        assertFalse(record.line().contains("FAKE_SECRET"))
    }
}

/** Generated protocol data only. No browser, network, Android account or captured provider reply. */
private class MediaFixture(var headers: Map<String, List<String>>, diagnosticResponsesCallLimit: Int = 0) {
    val output = "{\"questions\":[{\"prompt\":\"FAKE_SECRET_BODY\"}]}"
    var body = completedMediaEvent(output)
    var status = 200
    var modelsBody = "{\"models\":[{\"visibility\":\"list\",\"slug\":\"listed-model\"}]}"
    var responsesOpenFailure: Exception? = null
    var responseReadFailure: Exception? = null
    var responseBytes: ByteArray? = null
    var sinkFailure: Exception? = null
    var completionSinkFailure: Exception? = null
    var beforeResponseReply: (() -> Unit)? = null
    var cancellations = 0
    var modelCalls = 0
    var responseCalls = 0
    var modelReleases = 0
    var responseReleases = 0
    val inputs = mutableListOf<TrackedInput>()
    private val modelInputs = mutableListOf<TrackedInput>()
    val records = mutableListOf<ProviderFailureRecord>()
    val completions = mutableListOf<ProviderCompletionRecord>()
    private val requestBodies = mutableListOf<JSONObject>()
    val lastRequest: JSONObject get() = requestBodies.last()
    private val now = 1_800_000_000_000L
    private val registration = Registration("FAKE_SECRET_CLIENT", "FAKE_SECRET_ACCOUNT", "https://FAKE_SECRET_ISSUER.test",
        "FAKE_SECRET_EMAIL@example.test", "FAKE_SECRET_ID_TOKEN", "FAKE_SECRET_ACCESS_TOKEN", "FAKE_SECRET_REFRESH_TOKEN",
        PLAN_SCOPES.split(' ').toSet(), now + 3_600_000, "FAKE_SECRET_NONCE")
    val account = registration.accountId!!
    private val store = object : CredentialStore {
        override fun load() = ConnectionData("FAKE_SECRET_HOST", registration.clientId, listOf(registration))
        override fun save(data: ConnectionData) { throw AssertionError("A current synthetic account must not refresh") }
    }
    private val transport = object : ChatGptHttp {
        override fun open(url: String, method: String, headers: Map<String, String>, body: ByteArray?): HttpReply {
            assertEquals("Bearer FAKE_SECRET_ACCESS_TOKEN", headers["Authorization"])
            return when (url) {
                "https://FAKE_SECRET_URL.test/v1/models" -> {
                    modelCalls++
                    assertEquals("GET", method)
                    assertNull(body)
                    val input = TrackedInput(modelsBody)
                    modelInputs += input
                    HttpReply(200, mapOf("Content-Type" to listOf("application/json")), input) { modelReleases++ }
                }
                "https://FAKE_SECRET_URL.test/v1/responses" -> {
                    responseCalls++
                    assertEquals("POST", method)
                    assertEquals("application/json", headers["Content-Type"])
                    requestBodies += JSONObject(body!!.toString(Charsets.UTF_8))
                    responsesOpenFailure?.let { throw it }
                    beforeResponseReply?.invoke()
                    val input = TrackedInput(this@MediaFixture.body, responseReadFailure, responseBytes)
                    inputs += input
                    HttpReply(status, this@MediaFixture.headers, input) { responseReleases++ }
                }
                else -> throw AssertionError("Unexpected synthetic endpoint")
            }
        }
        override fun cancelAll() { cancellations++ }
    }
    private val provider = ChatGptProvider(store, transport, ProviderEndpoints(resource = "https://FAKE_SECRET_URL.test/v1"),
        ProviderClock { now }, Dispatchers.Unconfined, ProviderFailureSink { records += it; sinkFailure?.let { error -> throw error } }, diagnosticResponsesCallLimit,
        ProviderCompletionSink { completions += it; completionSinkFailure?.let { error -> throw error } })

    suspend fun response(source: String = "FAKE_SECRET_SOURCE", schema: JSONObject = JSONObject().put("type", "object"),
        instructions: String = "FAKE_SECRET_INSTRUCTIONS", accountId: String = account) = provider.response(accountId, instructions, source, schema)
    suspend fun failure(): ProviderException {
        val error = try { response(); null } catch (error: Exception) { error }
        assertTrue(error is ProviderException)
        return error as ProviderException
    }
    suspend fun assertMediaFailure(category: String) {
        val error = failure()
        assertEquals(ProviderFailureKind.INVALID_RESPONSE, error.kind)
        assertEquals("ChatGPT did not return a question stream.", error.message)
        assertEquals("stage=responses_read class=ProviderException code=provider_failure status=200 failure_kind=invalid_response media=$category", records.single().line())
    }
    fun assertRequestsAndCleanup(readsExpected: Boolean) {
        assertTransportCleanup(1, 1)
        if (readsExpected) assertTrue(inputs.single().readCalls > 0) else assertEquals(0, inputs.single().readCalls)
        val body = requestBodies.single()
        assertEquals(setOf("model", "instructions", "input", "store", "stream", "text"), body.keySet())
        assertEquals("listed-model", body.getString("model"))
        assertEquals("FAKE_SECRET_INSTRUCTIONS", body.getString("instructions"))
        assertFalse(body.getBoolean("store"))
        assertTrue(body.getBoolean("stream"))
        assertEquals(1, body.getJSONArray("input").length())
        val input = body.getJSONArray("input").getJSONObject(0)
        assertEquals("user", input.getString("role"))
        assertEquals("FAKE_SECRET_SOURCE", input.getString("content"))
        val format = body.getJSONObject("text").getJSONObject("format")
        assertEquals("json_schema", format.getString("type"))
        assertEquals("section_questions", format.getString("name"))
        assertTrue(format.getBoolean("strict"))
        assertEquals("object", format.getJSONObject("schema").getString("type"))
    }
    fun assertTransportCleanup(expectedModels: Int, expectedResponses: Int) {
        assertEquals(expectedModels, modelCalls)
        assertEquals(expectedModels, modelReleases)
        assertEquals(expectedResponses, responseCalls)
        assertEquals(inputs.size, responseReleases)
        assertTrue(modelInputs.all { it.closed && it.readCalls > 0 })
        assertTrue(inputs.all { it.closed })
    }
}

private fun completedMediaEvent(output: String) = "event: response.completed\ndata: " + JSONObject().put("type", "response.completed")
    .put("response", JSONObject().put("status", "completed").put("output", JSONArray().put(JSONObject()
        .put("type", "message").put("role", "assistant").put("content", JSONArray().put(JSONObject()
            .put("type", "output_text").put("text", output)))))) + "\n\n"

private fun finalizedFallbackMediaEvent(output: String): String {
    fun message(phase: String, text: String) = JSONObject().put("type", "message").put("role", "assistant")
        .put("status", "completed").put("phase", phase).put("content", JSONArray().put(JSONObject()
            .put("type", "output_text").put("text", text)))
    return (mediaEvent("response.output_item.done", JSONObject().put("output_index", 12).put("item", message("final_answer", output))) +
        mediaEvent("response.output_item.done", JSONObject().put("output_index", 2).put("item", JSONObject().put("type", "reasoning"))) +
        mediaEvent("response.output_item.done", JSONObject().put("output_index", 4).put("item", message("commentary", "FAKE_SECRET_COMMENTARY"))) +
        mediaEvent("response.completed", JSONObject().put("response", JSONObject().put("status", "completed")))).replace("\n", "\r")
}

private fun mediaEvent(type: String, data: JSONObject) = "event: $type\ndata: ${data.put("type", type)}\n\n"

private class TrackedInput(value: String, private val failure: Exception? = null, bytes: ByteArray? = null) :
    ByteArrayInputStream(bytes ?: value.toByteArray(Charsets.UTF_8)) {
    var readCalls = 0
    var closed = false
    override fun read(): Int { readCalls++; failure?.let { throw it }; return super.read() }
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int { readCalls++; failure?.let { throw it }; return super.read(bytes, offset, length) }
    override fun close() { closed = true; super.close() }
}

private fun emptyTerminalRecoveryMediaEvent(result: String): String {
    val message = JSONObject().put("type", "message").put("role", "assistant").put("status", "completed")
        .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", result)))
    return mediaEvent("response.output_item.done", JSONObject().put("output_index", 0).put("item", message)) +
        mediaEvent("response.completed", JSONObject().put("response", JSONObject().put("status", "completed").put("output", JSONArray())))
}
