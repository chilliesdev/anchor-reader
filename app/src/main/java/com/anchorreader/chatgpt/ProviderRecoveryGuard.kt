package com.anchorreader.chatgpt

import org.json.JSONObject

/** Bounded per-call identities stay in memory. Only the conflict flag leaves this guard. */
internal class ProviderRecoveryGuard {
    var hasConflict: Boolean = false
        private set
    private var responseId: String? = null
    private var sequence: Long? = null
    private val itemIds = mutableMapOf<Long, String>()
    private val added = mutableSetOf<Long>()
    private val done = mutableSetOf<Long>()

    fun observe(type: String, event: JSONObject) {
        if (hasConflict) return
        if (event.has("sequence_number")) {
            val next = nonnegativeInteger(event.opt("sequence_number"))
            if (next == null || sequence?.let { next <= it } == true) hasConflict = true else sequence = next
        }
        suppliedId(event, "response_id")?.let(::checkResponseId)
        if (type in RESPONSE_EVENTS) event.optJSONObject("response")?.let { response -> suppliedId(response, "id")?.let(::checkResponseId) }
        if (type !in ITEM_EVENTS) return
        val eventId = suppliedId(event, "item_id")
        val nestedId = if (type == "response.output_item.added" || type == "response.output_item.done")
            event.optJSONObject("item")?.let { suppliedId(it, "id") } else null
        if (eventId != null && nestedId != null && eventId != nestedId) hasConflict = true
        val id = nestedId ?: eventId
        val knownIndex = id?.let { supplied -> itemIds.entries.firstOrNull { it.value == supplied }?.key }
        val suppliedIndex = if (event.has("output_index"))
            nonnegativeInteger(event.opt("output_index")) ?: run { hasConflict = true; return } else null
        if (knownIndex != null && (knownIndex in done || suppliedIndex?.let { it != knownIndex } == true)) {
            hasConflict = true
            return
        }
        val index = suppliedIndex ?: knownIndex ?: return
        if (id != null) {
            if (itemIds[index]?.let { it != id } == true || itemIds.any { (otherIndex, otherId) -> otherIndex != index && otherId == id }) {
                hasConflict = true
            } else if (index !in itemIds && itemIds.size == 32) hasConflict = true else itemIds[index] = id
        }
        if (index in done) hasConflict = true
        when (type) {
            "response.output_item.added" -> if (added.size == 32 || !added.add(index)) hasConflict = true
            "response.output_item.done" -> if (done.size == 32 || !done.add(index)) hasConflict = true
        }
    }

    private fun checkResponseId(id: String) {
        if (responseId != null && responseId != id) hasConflict = true else responseId = id
    }
    private fun suppliedId(json: JSONObject, name: String): String? {
        if (!json.has(name)) return null
        val value = json.opt(name)
        if (value !is String || value.length !in 1..256) { hasConflict = true; return null }
        return value
    }
    private fun nonnegativeInteger(value: Any?): Long? = when (value) {
        is Int -> value.toLong().takeIf { it >= 0 }
        is Long -> value.takeIf { it >= 0 }
        else -> null
    }
    private companion object {
        val RESPONSE_EVENTS = setOf("response.created", "response.in_progress", "response.completed", "response.failed", "response.incomplete")
        val ITEM_EVENTS = setOf("response.output_item.added", "response.output_item.done", "response.output_text.delta", "response.output_text.done",
            "response.content_part.added", "response.content_part.done")
    }
}
