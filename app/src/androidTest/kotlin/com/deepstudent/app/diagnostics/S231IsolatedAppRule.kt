package com.deepstudent.app.diagnostics

import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.app.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** This rule must wrap the Compose activity rule, before any target Activity can launch. */
internal class S231IsolatedAppRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val isolatedId = "com.deepstudent.app.s2device"
            assertEquals(isolatedId, BuildConfig.APPLICATION_ID)
            assertEquals(isolatedId, InstrumentationRegistry.getInstrumentation().targetContext.packageName)
            base.evaluate()
        }
    }
}
