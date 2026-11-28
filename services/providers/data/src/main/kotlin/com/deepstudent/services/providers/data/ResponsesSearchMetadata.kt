package com.deepstudent.services.providers.data

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.providers.api.ProviderStreamEvent
import com.deepstudent.services.providers.api.ResponsesMetadataKind

/*
 * Web-search metadata for the Responses stream, ported from the frozen desktop
 * helpers `extract_web_search_sources` (providers/mod.rs:1309) and
 * `full_web_search_item_for_replay` (providers/mod.rs:1393) plus the adapter's
 * `_emitWebSearchItem` family in
 * `host/plugins/llm-providers/src/providers/openai-responses.ts`
 * (e6b2657fd59014a73f5bbb1d14c2db732cf23e87).
 *
 * Sources are normalized `{title,url,snippet}` records; the raw item is replayed
 * verbatim next to the normalized summary. Deduplication keeps a completed id from
 * being re-emitted with the same sources on the final response sweep.
 */

/** Normalizes search results, action sources, the page record and url_citation annotations. */
internal fun extractWebSearchSources(item: JsonValue): List<JsonValue.Object> {
    val sources = mutableListOf<JsonValue.Object>()
    for (raw in searchResultCandidates(item)) collectResultSource(raw, sources)
    for (raw in sourceAnnotations(item)) collectAnnotationSource(raw, sources)
    return sources
}

private fun searchResultCandidates(item: JsonValue): List<JsonValue> {
    val holder = item as? JsonValue.Object ?: return emptyList()
    return holder.arrayAt("search_results")?.values
        ?: holder.arrayAt("web_search_sources")?.values
        ?: holder.objectAt("action")?.arrayAt("sources")?.values
        ?: holder["page"]?.let { listOf(it) }
        ?: emptyList()
}

private fun sourceAnnotations(item: JsonValue): List<JsonValue> {
    val holder = item as? JsonValue.Object ?: return emptyList()
    return holder.arrayAt("content")?.values?.flatMap { part ->
        (part as? JsonValue.Object)?.arrayAt("annotations")?.values ?: emptyList()
    } ?: emptyList()
}

private fun collectResultSource(raw: JsonValue, sources: MutableList<JsonValue.Object>) {
    val result = raw as? JsonValue.Object ?: return
    val url = result.textAt("url") ?: ""
    if (url.isEmpty()) return
    sources += sourceRecord(result.textAt("title") ?: "", url, resultSnippet(result))
}

private fun resultSnippet(result: JsonValue.Object): String =
    result.textAt("text") ?: result.textAt("snippet") ?: result.textAt("description") ?: ""

private fun collectAnnotationSource(raw: JsonValue, sources: MutableList<JsonValue.Object>) {
    val annotation = raw as? JsonValue.Object ?: return
    if (annotation.textAt("type") != "url_citation") return
    val url = annotation.textAt("url") ?: ""
    if (url.isEmpty()) return
    if (sources.any { existing -> existing.textAt("url") == url }) return
    // The frozen desktop quotes the annotation title before the snippet here.
    sources += sourceRecord(annotation.textAt("title") ?: "", url, annotationSnippet(annotation))
}

private fun annotationSnippet(annotation: JsonValue.Object): String = annotation.textAt("title") ?: annotation.textAt("snippet") ?: ""

private fun sourceRecord(title: String, url: String, snippet: String): JsonValue.Object = JsonValue.Object.Builder()
    .put("title", title)
    .put("url", url)
    .put("snippet", snippet)
    .build()

/**
 * `full_web_search_item_for_replay`: typed items replay verbatim; an untyped
 * `{id,status}` record is promoted to a `web_search_call` item. A non-string `type`
 * behaves like a missing one and falls through to the id/status shape check.
 */
internal fun fullWebSearchItem(item: JsonValue.Object): JsonValue.Object? {
    val type = item.textAt("type")
    if (type != null) return if (type == RESPONSES_TYPE_WEB_SEARCH_CALL) item else null
    if (item.textAt("id") == null || !item.containsKey("status")) return null
    return JsonValue.Object.Builder()
        .putAll(item)
        .put("type", RESPONSES_TYPE_WEB_SEARCH_CALL)
        .build()
}

/** Emits web-search metadata with the adapter's completed-id deduplication state. */
internal class ResponsesSearchMetadata(private val deepseekWebTools: Boolean) {
    private val emittedIds = mutableSetOf<String>()
    private val emittedWithSourcesIds = mutableSetOf<String>()

    /** `_emitWebSearchItem`: one payload per stage; completed payloads carry normalized sources. */
    fun emitItem(item: JsonValue.Object, stage: String, outputIndex: Int?, events: MutableList<ProviderStreamEvent>) {
        val id = item.textAt("id") ?: item.textAt("call_id") ?: ""
        val sources = item.arrayAt("sources") ?: JsonValue.Array(extractWebSearchSources(item))
        if (isDuplicateCompleted(id, stage, sources)) return
        events += metadata(completedPayload(item, stage, id, sources), outputIndex)
    }

    private fun isDuplicateCompleted(id: String, stage: String, sources: JsonValue.Array): Boolean {
        if (stage != RESPONSES_STAGE_COMPLETED) {
            emittedIds.add(id)
            return false
        }
        val alreadyEmitted = !emittedIds.add(id)
        if (alreadyEmitted && id in emittedWithSourcesIds) return true
        if (sources.values.isNotEmpty()) emittedWithSourcesIds.add(id)
        return false
    }

    private fun completedPayload(item: JsonValue.Object, stage: String, id: String, sources: JsonValue.Array): JsonValue.Object {
        val payload = JsonValue.Object.Builder().put("id", id).put("stage", stage)
        if (stage == RESPONSES_STAGE_COMPLETED) payload.put("sources", sources)
        val fullItem = fullWebSearchItem(item)
        if (fullItem != null && replaysFullItem(stage)) payload.put("item", fullItem)
        return payload.build()
    }

    private fun replaysFullItem(stage: String): Boolean =
        !deepseekWebTools || stage == RESPONSES_STAGE_COMPLETED || stage == RESPONSES_STAGE_FAILED

    /** Bare progress payload for `web_search_call.in_progress`/`web_search_call.searching`. */
    fun emitProgress(frame: JsonValue.Object, defaultStage: String, events: MutableList<ProviderStreamEvent>) {
        val stage = frame.textAt("stage") ?: defaultStage
        val id = frame.textAt("call_id") ?: frame.textAt("item_id") ?: ""
        events += metadata(JsonValue.Object.Builder().put("id", id).put("stage", stage).build(), null)
    }

    /**
     * `_emitWebSearchFromResponse`: replays web-search items from the final output and,
     * unless DeepSeek web tools already own the flow, summarizes message citations into
     * one synthetic completed payload.
     */
    fun emitFromResponse(response: JsonValue.Object, events: MutableList<ProviderStreamEvent>) {
        val fallbackSources = mutableListOf<JsonValue.Object>()
        collectSearchItemSources(response, fallbackSources, events)
        if (fallbackSources.isEmpty() || deepseekWebTools) summarizeMessageSources(response, fallbackSources, events)
    }

    private fun collectSearchItemSources(
        response: JsonValue.Object,
        fallbackSources: MutableList<JsonValue.Object>,
        events: MutableList<ProviderStreamEvent>,
    ) {
        response.arrayAt("output")?.values?.forEach { raw ->
            val item = raw as? JsonValue.Object ?: return@forEach
            if (item.textAt("type") != RESPONSES_TYPE_WEB_SEARCH_CALL) return@forEach
            emitItem(item, item.textAt("status") ?: RESPONSES_STAGE_COMPLETED, null, events)
            fallbackSources += extractWebSearchSources(item)
        }
    }

    private fun summarizeMessageSources(
        response: JsonValue.Object,
        fallbackSources: MutableList<JsonValue.Object>,
        events: MutableList<ProviderStreamEvent>,
    ) {
        response.arrayAt("output")?.values?.forEach { raw ->
            val item = raw as? JsonValue.Object ?: return@forEach
            if (item.textAt("type") != RESPONSES_TYPE_MESSAGE) return@forEach
            fallbackSources += extractWebSearchSources(item)
        }
        if (fallbackSources.isEmpty()) return
        val summary = JsonValue.Object.Builder()
            .put("id", RESPONSES_SYNTHETIC_SEARCH_ID)
            .put("sources", JsonValue.Array(fallbackSources))
            .build()
        emitItem(summary, RESPONSES_STAGE_COMPLETED, null, events)
    }

    private fun metadata(payload: JsonValue.Object, outputIndex: Int?) = ProviderStreamEvent.ResponsesMetadata(
        ResponsesMetadataKind.WEB_SEARCH,
        payload.literal(),
        outputIndex,
        null,
    )
}
