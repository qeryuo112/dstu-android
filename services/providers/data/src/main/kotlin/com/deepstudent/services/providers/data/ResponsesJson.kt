package com.deepstudent.services.providers.data

import com.deepstudent.core.common.json.JsonValue

/*
 * Wire accessors for the Responses stream, mirroring the frozen desktop JSON helpers
 * (`llm-providers/src/json.ts` at e6b2657fd59014a73f5bbb1d14c2db732cf23e87): a field of
 * the wrong shape behaves exactly like an absent field instead of failing the frame.
 *
 * `indexAt` reproduces `i64At` (`json.ts:169`): only integer literals inside the signed
 * 64-bit range count, and the result is narrowed at the provider event boundary, where
 * the stream event index is a non-negative 32-bit value. Negative and overflowing
 * indexes therefore read as absent and fall back to the caller's default, matching the
 * signed-off Chat decoder's index handling.
 */

/** Item and stage literals shared by the Responses decoder helpers. */
internal const val RESPONSES_TYPE_FUNCTION = "function"
internal const val RESPONSES_TYPE_FUNCTION_CALL = "function_call"
internal const val RESPONSES_TYPE_WEB_SEARCH_CALL = "web_search_call"
internal const val RESPONSES_TYPE_REASONING = "reasoning"
internal const val RESPONSES_TYPE_MESSAGE = "message"
internal const val RESPONSES_STAGE_IN_PROGRESS = "in_progress"
internal const val RESPONSES_STAGE_SEARCHING = "searching"
internal const val RESPONSES_STAGE_COMPLETED = "completed"
internal const val RESPONSES_STAGE_FAILED = "failed"
internal const val RESPONSES_SYNTHETIC_SEARCH_ID = "resp_web_search"

/** `string(field(holder, key))`: only a JSON string value counts. */
internal fun JsonValue.Object.textAt(key: String): String? = (this[key] as? JsonValue.Text)?.value

/** `object(field(holder, key))`: only a JSON object counts; arrays and scalars read as absent. */
internal fun JsonValue.Object.objectAt(key: String): JsonValue.Object? = this[key] as? JsonValue.Object

/** `array(field(holder, key))`: only a JSON array counts. */
internal fun JsonValue.Object.arrayAt(key: String): JsonValue.Array? = this[key] as? JsonValue.Array

/** `i64At(holder, key)` (`json.ts:169`): integer literals inside the signed 64-bit range, negatives included. */
internal fun JsonValue.Object.i64At(key: String): Long? = (this[key] as? JsonValue.Number)?.asLongOrNull()

/** `i64At(holder, key)` narrowed to the non-negative 32-bit stream event index boundary. */
internal fun JsonValue.Object.indexAt(key: String): Int? = (this[key] as? JsonValue.Number)
    ?.asLongOrNull()
    ?.takeIf { it in 0..Int.MAX_VALUE.toLong() }
    ?.toInt()

/** `asU32` from the frozen desktop usage extraction: integers only, clamped to the unsigned range. */
internal fun JsonValue.Object.u32At(key: String): Long? = (this[key] as? JsonValue.Number)
    ?.asLongOrNull()
    ?.takeIf { it >= 0 }
    ?.coerceAtMost(U32_MAX)

private const val U32_MAX = 0xFFFF_FFFFL
