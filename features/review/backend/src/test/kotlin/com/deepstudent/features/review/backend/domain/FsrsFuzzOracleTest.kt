package com.deepstudent.features.review.backend.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class FsrsFuzzOracleTest {
    @Test fun frozenDesktopUtf16FuzzFactorsMatch() {
        val fixture = File("../../../fixtures/s1-18/scheduler-vectors.json")
        val vectors = Json.parseToJsonElement(fixture.readText()).jsonObject["fuzzFactors"]!!.jsonArray
        for (entry in vectors) {
            val value = entry.jsonObject
            assertEquals(value["expected"]!!.jsonPrimitive.double,
                FsrsFuzz.factorFor(value["id"]!!.jsonPrimitive.content, value["reps"]!!.jsonPrimitive.int), 1e-15)
        }
    }
}
