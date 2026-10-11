package com.anchorreader.chatgpt

import org.json.JSONArray
import org.json.JSONObject

internal enum class ProviderEventClass(val label: String) {
    NONE("none"), CREATED("created"), PROGRESS("progress"), DELTA("delta"), TEXT_DONE("text_done"),
    CONTENT_DONE("content_done"), ITEM_DONE("item_done"), COMPLETED("completed"), FAILED("failed"),
    INCOMPLETE("incomplete"), REFUSAL("refusal"), OTHER("other"),
}

internal enum class ProviderFieldShape(val label: String) {
    UNSEEN("unseen"), ABSENT("absent"), NULL("null"), EMPTY("empty"), ARRAY("array"), OBJECT("object"),
    PRESENT("present"), OTHER("other"),
}

internal enum class ProviderTerminalStatus(val label: String) {
    UNSEEN("unseen"), ABSENT("absent"), NULL("null"), COMPLETED("completed"), INCOMPLETE("incomplete"),
    FAILED("failed"), OTHER("other"), INVALID("invalid"),
}

/** Fixed categories and saturated numbers only. No event, provider text or identity can be retained. */
internal class ProviderStreamEvidence internal constructor(
    private val events: Int,
    private val deltas: Int,
    private val deltaText: Int,
    private val textDone: Int,
    private val textDoneText: Int,
    private val contentDone: Int,
    private val contentDoneText: Int,
    private val itemDone: Int,
    private val itemDoneText: Int,
    private val acceptedFinalItems: Int,
    private val other: Int,
    private val completed: Int,
    private val priorEvent: ProviderEventClass,
    private val response: ProviderFieldShape,
    private val status: ProviderTerminalStatus,
    private val error: ProviderFieldShape,
    private val incomplete: ProviderFieldShape,
    private val output: ProviderFieldShape,
    private val outputItems: Int,
    private val usage: ProviderFieldShape,
    private val inputTokens: ProviderUsageCount,
    private val outputTokens: ProviderUsageCount,
    private val totalTokens: ProviderUsageCount,
) {
    init {
        require(listOf(events, deltas, deltaText, textDone, textDoneText, contentDone, contentDoneText,
            itemDone, itemDoneText, acceptedFinalItems, other, completed, outputItems).all { it in 0..COUNT_CAP })
    }

    fun line(): String = "events=$events deltas=$deltas delta_text=$deltaText text_done=$textDone text_done_text=$textDoneText" +
        " content_done=$contentDone content_done_text=$contentDoneText item_done=$itemDone item_done_text=$itemDoneText" +
        " final_items=$acceptedFinalItems other_events=$other completed=$completed prior_event=${priorEvent.label}" +
        " terminal_response=${response.label} terminal_status=${status.label} terminal_error=${error.label}" +
        " terminal_incomplete=${incomplete.label} terminal_output=${output.label} terminal_items=$outputItems" +
        " terminal_usage=${usage.label} input_tokens=${inputTokens.label()} output_tokens=${outputTokens.label()} total_tokens=${totalTokens.label()}"

    companion object { const val COUNT_CAP = 4096 }
}

internal enum class ProviderUsageShape(val label: String) { ABSENT("absent"), NULL("null"), INVALID("invalid"), NUMBER("number"), CAPPED("capped") }

internal class ProviderUsageCount private constructor(private val shape: ProviderUsageShape, private val count: Long = 0) {
    fun label(): String = if (shape == ProviderUsageShape.NUMBER) count.toString() else shape.label
    companion object {
        const val TOKEN_CAP = 1_000_000_000L
        fun read(usage: JSONObject?, name: String): ProviderUsageCount {
            if (usage == null || !usage.has(name)) return ProviderUsageCount(ProviderUsageShape.ABSENT)
            if (usage.isNull(name)) return ProviderUsageCount(ProviderUsageShape.NULL)
            val value = when (val raw = usage.opt(name)) {
                is Int -> raw.toLong()
                is Long -> raw
                else -> return ProviderUsageCount(ProviderUsageShape.INVALID)
            }
            return when {
                value < 0 -> ProviderUsageCount(ProviderUsageShape.INVALID)
                value > TOKEN_CAP -> ProviderUsageCount(ProviderUsageShape.CAPPED)
                else -> ProviderUsageCount(ProviderUsageShape.NUMBER, value)
            }
        }
    }
}

/** Observes shape only; it never changes acceptance or stores a JSONObject. */
internal class ProviderStreamObserver {
    private var events = 0
    private var deltas = 0
    private var deltaText = 0
    private var textDone = 0
    private var textDoneText = 0
    private var contentDone = 0
    private var contentDoneText = 0
    private var itemDone = 0
    private var itemDoneText = 0
    private var acceptedFinalItems = 0
    private var other = 0
    private var completed = 0
    private var lastEvent = ProviderEventClass.NONE
    private var priorEvent = ProviderEventClass.NONE
    private var response = ProviderFieldShape.UNSEEN
    private var status = ProviderTerminalStatus.UNSEEN
    private var error = ProviderFieldShape.UNSEEN
    private var incomplete = ProviderFieldShape.UNSEEN
    private var output = ProviderFieldShape.UNSEEN
    private var outputItems = 0
    private var usage = ProviderFieldShape.UNSEEN
    private var inputTokens = ProviderUsageCount.read(null, "input_tokens")
    private var outputTokens = ProviderUsageCount.read(null, "output_tokens")
    private var totalTokens = ProviderUsageCount.read(null, "total_tokens")

    fun observe(type: String, event: JSONObject) {
        events = increment(events)
        val category = when (type) {
            "response.created" -> ProviderEventClass.CREATED
            "response.in_progress" -> ProviderEventClass.PROGRESS
            "response.output_text.delta" -> {
                deltas = increment(deltas)
                if (hasText(event.opt("delta"))) deltaText = increment(deltaText)
                ProviderEventClass.DELTA
            }
            "response.output_text.done" -> {
                textDone = increment(textDone)
                if (hasText(event.opt("text"))) textDoneText = increment(textDoneText)
                ProviderEventClass.TEXT_DONE
            }
            "response.content_part.done" -> {
                contentDone = increment(contentDone)
                if (hasText(event.optJSONObject("part")?.opt("text"))) contentDoneText = increment(contentDoneText)
                ProviderEventClass.CONTENT_DONE
            }
            "response.output_item.done" -> {
                itemDone = increment(itemDone)
                val content = event.optJSONObject("item")?.optJSONArray("content")
                if (content != null && (0 until minOf(content.length(), 32)).any { hasText(content.optJSONObject(it)?.opt("text")) }) {
                    itemDoneText = increment(itemDoneText)
                }
                ProviderEventClass.ITEM_DONE
            }
            "response.completed" -> {
                completed = increment(completed)
                priorEvent = lastEvent
                observeTerminal(event)
                ProviderEventClass.COMPLETED
            }
            "response.failed", "error" -> ProviderEventClass.FAILED
            "response.incomplete" -> ProviderEventClass.INCOMPLETE
            "response.refusal.delta", "response.refusal.done" -> ProviderEventClass.REFUSAL
            else -> { other = increment(other); ProviderEventClass.OTHER }
        }
        lastEvent = category
    }

    fun acceptedFinalItem() { acceptedFinalItems = increment(acceptedFinalItems) }

    fun snapshot() = ProviderStreamEvidence(events, deltas, deltaText, textDone, textDoneText, contentDone,
        contentDoneText, itemDone, itemDoneText, acceptedFinalItems, other, completed, priorEvent,
        response, status, error, incomplete, output, outputItems, usage, inputTokens, outputTokens, totalTokens)

    private fun observeTerminal(event: JSONObject) {
        response = shape(event, "response")
        val terminal = event.optJSONObject("response") ?: return
        status = when {
            !terminal.has("status") -> ProviderTerminalStatus.ABSENT
            terminal.isNull("status") -> ProviderTerminalStatus.NULL
            terminal.opt("status") !is String -> ProviderTerminalStatus.INVALID
            else -> when (terminal.optString("status")) {
                "completed" -> ProviderTerminalStatus.COMPLETED
                "incomplete" -> ProviderTerminalStatus.INCOMPLETE
                "failed" -> ProviderTerminalStatus.FAILED
                else -> ProviderTerminalStatus.OTHER
            }
        }
        error = shape(terminal, "error")
        incomplete = shape(terminal, "incomplete_details")
        output = shape(terminal, "output")
        outputItems = minOf(terminal.optJSONArray("output")?.length() ?: 0, ProviderStreamEvidence.COUNT_CAP)
        usage = shape(terminal, "usage")
        val usageObject = terminal.optJSONObject("usage")
        inputTokens = ProviderUsageCount.read(usageObject, "input_tokens")
        outputTokens = ProviderUsageCount.read(usageObject, "output_tokens")
        totalTokens = ProviderUsageCount.read(usageObject, "total_tokens")
    }

    private fun shape(json: JSONObject, name: String): ProviderFieldShape = when {
        !json.has(name) -> ProviderFieldShape.ABSENT
        json.isNull(name) -> ProviderFieldShape.NULL
        else -> when (val value = json.opt(name)) {
            is JSONArray -> if (value.length() == 0) ProviderFieldShape.EMPTY else ProviderFieldShape.ARRAY
            is JSONObject -> ProviderFieldShape.OBJECT
            else -> ProviderFieldShape.OTHER
        }
    }
    private fun increment(value: Int) = minOf(value + 1, ProviderStreamEvidence.COUNT_CAP)
    private fun hasText(value: Any?) = value is String && value.isNotBlank()
}
