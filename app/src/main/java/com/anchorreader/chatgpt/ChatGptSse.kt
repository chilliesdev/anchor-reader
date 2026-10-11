package com.anchorreader.chatgpt

import com.anchorreader.chatgpt.ProviderStreamReason.*
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.InputStream
import java.io.InputStreamReader
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Only the completed response is authoritative. Partial deltas are never returned. */
internal fun readCompletedResponse(input: InputStream): String {
    val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    val reader = InputStreamReader(StreamByteLimit(input, 1024 * 1024), decoder)
    var deltaBytes = 0
    var eventBytes = 0
    val data = StringBuilder()
    val finalized = sortedMapOf<Long, JSONObject>()
    var finalizedResult = false
    var eventName = ""
    var sawData = false
    fun dispatch(): String? {
        if (data.isEmpty()) { eventName = ""; return null }
        val text = data.toString().removeSuffix("\n")
        data.setLength(0)
        eventBytes = 0
        if (text == "[DONE]") throw invalidStream(EARLY_DONE)
        val event = streamField(EVENT_JSON) { JSONObject(text) }
        val type = event.optString("type", eventName)
        if (eventName.isNotEmpty() && event.has("type") && type != eventName) throw invalidStream(EVENT_TYPE)
        eventName = ""
        when (type) {
            "response.output_text.delta" -> {
                deltaBytes += streamField(DELTA_SHAPE) { event.getString("delta") }.toByteArray(Charsets.UTF_8).size
                if (deltaBytes > 128 * 1024) throw invalidStream(DELTA_BOUND)
            }
            "response.refusal.delta", "response.refusal.done" -> throw invalidStream(REFUSAL)
            "response.incomplete" -> throw invalidStream(INCOMPLETE)
            "response.failed", "error" -> {
                val error = event.optJSONObject("error") ?: event.optJSONObject("response")?.optJSONObject("error")
                val failure = providerErrorCode(error?.optString("code"), 0)
                throw ProviderException(failure.kind, failure.message!!, PROVIDER_ERROR)
            }
            "response.output_item.done" -> {
                val index = when (val value = streamField(FINALIZED_INDEX) { event.get("output_index") }) {
                    is Int -> value.toLong()
                    is Long -> value
                    else -> throw invalidStream(FINALIZED_INDEX)
                }
                if (index < 0) throw invalidStream(FINALIZED_INDEX)
                if (finalized.containsKey(index)) throw invalidStream(FINALIZED_DUPLICATE)
                if (finalized.size == 32) throw invalidStream(FINALIZED_COUNT)
                val item = streamField(FINALIZED_ITEM) { event.getJSONObject("item") }
                if (completedItemText(item) != null) {
                    if (finalizedResult) throw invalidStream(RESULT_MULTIPLE)
                    finalizedResult = true
                }
                finalized[index] = item
            }
            "response.completed" -> return completedText(streamField(COMPLETED_RESPONSE) { event.getJSONObject("response") }, finalized.values)
        }
        return null
    }
    val line = StringBuilder()
    fun consumeLine(): String? {
        val value = line.toString()
        line.setLength(0)
        if (value.toByteArray(Charsets.UTF_8).size > 64 * 1024) throw invalidStream(LINE_BOUND)
        if (value.isEmpty()) return dispatch()
        if (value.startsWith(':')) return null
        val name = value.substringBefore(':')
        val field = value.substringAfter(':', "").removePrefix(" ")
        when (name) {
            "event" -> eventName = field
            "data" -> {
                sawData = true
                eventBytes += field.toByteArray(Charsets.UTF_8).size + 1
                if (eventBytes > 256 * 1024) throw invalidStream(EVENT_BOUND)
                data.append(field).append('\n')
            }
        }
        return null
    }
    var skipLf = false
    try {
        while (true) {
            val value = reader.read()
            if (value == -1) break
            if (skipLf) {
                skipLf = false
                if (value == '\n'.code) continue
            }
            when (value) {
                '\r'.code -> { skipLf = true; consumeLine()?.let { return it } }
                '\n'.code -> consumeLine()?.let { return it }
                else -> { line.append(value.toChar()); if (line.length > 64 * 1024) throw invalidStream(LINE_BOUND) }
            }
        }
        // A terminal event still needs its SSE blank-line delimiter.
        throw invalidStream(if (sawData) MISSING_COMPLETED else NO_SSE_DATA)
    } catch (error: ProviderException) {
        throw error
    } catch (_: CharacterCodingException) {
        throw invalidStream(INVALID_UTF8)
    } catch (_: SocketTimeoutException) {
        throw invalidStream(READ_TIMEOUT)
    } catch (_: IOException) {
        throw invalidStream(READ_IO)
    } catch (_: Exception) {
        throw invalidStream(UNEXPECTED)
    }
}

private fun completedText(response: JSONObject, finalized: Collection<JSONObject>): String {
    if (response.optString("status") != "completed") throw invalidStream(COMPLETED_STATUS)
    if (!response.isNull("error")) throw invalidStream(COMPLETED_ERROR)
    if (response.has("incomplete_details") && !response.isNull("incomplete_details")) throw invalidStream(COMPLETED_INCOMPLETE)
    val output = if (response.isNull("output")) JSONArray(finalized) else streamField(OUTPUT_SHAPE) { response.getJSONArray("output") }
    if (output.length() !in 1..32) throw invalidStream(when {
        response.isNull("output") && finalized.isEmpty() -> OUTPUT_MISSING
        output.length() == 0 -> OUTPUT_EMPTY
        else -> OUTPUT_COUNT
    })
    var text: String? = null
    for (index in 0 until output.length()) {
        completedItemText(streamField(OUTPUT_ITEM) { output.getJSONObject(index) })?.let {
            if (text != null) throw invalidStream(RESULT_MULTIPLE)
            text = it
        }
    }
    val result = text ?: throw invalidStream(RESULT_MISSING)
    if (result.isBlank()) throw invalidStream(RESULT_BLANK)
    if (result.toByteArray(Charsets.UTF_8).size > 128 * 1024) throw invalidStream(FINAL_BOUND)
    streamField(RESULT_JSON) {
        val json = JSONTokener(result)
        if (json.nextValue() !is JSONObject || json.nextClean() != 0.toChar()) throw invalidStream(RESULT_JSON)
    }
    return result
}

private fun completedItemText(item: JSONObject): String? {
    when (streamField(OUTPUT_TYPE) { item.getString("type") }) {
        "reasoning" -> return null
        "message" -> {
            if (item.optString("role") != "assistant") throw invalidStream(MESSAGE_ROLE)
            if (item.has("status") && streamField(MESSAGE_STATUS) { item.getString("status") } != "completed") throw invalidStream(MESSAGE_STATUS)
            val content = streamField(CONTENT_SHAPE) { item.getJSONArray("content") }
            if (content.length() != 1) throw invalidStream(if (content.length() == 0) CONTENT_EMPTY else CONTENT_COUNT)
            val part = streamField(CONTENT_ITEM) { content.getJSONObject(0) }
            if (streamField(TEXT_PART) { part.getString("type") } != "output_text") throw invalidStream(TEXT_PART)
            val text = streamField(TEXT_SHAPE) { part.getString("text") }
            if (text.toByteArray(Charsets.UTF_8).size > 128 * 1024) throw invalidStream(FINAL_BOUND)
            val phase = if (item.isNull("phase")) null else streamField(MESSAGE_PHASE_SHAPE) { item.getString("phase") }
            return when (phase) {
                null, "final_answer" -> text
                "commentary" -> null
                else -> throw invalidStream(MESSAGE_PHASE)
            }
        }
        else -> throw invalidStream(OUTPUT_UNSUPPORTED)
    }
}

private inline fun <T> streamField(reason: ProviderStreamReason, read: () -> T): T = try {
    read()
} catch (error: ProviderException) {
    throw error
} catch (_: Exception) {
    throw invalidStream(reason)
}

private fun invalidStream(reason: ProviderStreamReason) = ProviderException(ProviderFailureKind.INVALID_RESPONSE,
    "ChatGPT did not return complete questions. Try again.", reason)

private class StreamByteLimit(private val input: InputStream, private val limit: Int) : InputStream() {
    private var count = 0
    override fun read(): Int = input.read().also { if (it != -1 && ++count > limit) throw invalidStream(TOTAL_BOUND) }
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val size = input.read(bytes, offset, minOf(length, limit - count + 1))
        if (size > 0) { count += size; if (count > limit) throw invalidStream(TOTAL_BOUND) }
        return size
    }
    override fun close() = input.close()
}
