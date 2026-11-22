package com.deepstudent.app.fsrs

import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.services.engine.api.fsrs.FsrsReviewInput
import com.deepstudent.services.engine.api.fsrs.FsrsTrainingItem
import org.json.JSONArray
import org.json.JSONObject

internal fun fsrsFixture(name: String): JSONObject = InstrumentationRegistry.getInstrumentation().context.assets
    .open(name).bufferedReader().use { JSONObject(it.readText()) }

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
internal fun JSONArray.doubles(): List<Double> = (0 until length()).map { getDouble(it) }
internal fun JSONArray.longs(): List<Long> = (0 until length()).map { getLong(it) }
internal fun JSONObject.optionalLong(key: String): Long? = if (!has(key) || isNull(key)) null else getLong(key)
internal fun JSONObject.optionalDouble(key: String): Double? = if (!has(key) || isNull(key)) null else getDouble(key)
internal fun JSONObject.optionalString(key: String): String? = if (!has(key) || isNull(key)) null else getString(key)
internal fun JSONObject.trainingItems(): List<FsrsTrainingItem> = getJSONArray("items").objects().map { item ->
    FsrsTrainingItem(item.getJSONArray("reviews").objects().map { FsrsReviewInput(it.getInt("rating"), it.getLong("delta_t")) })
}
