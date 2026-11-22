package com.deepstudent.engines.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PRootBoundedOutputTest {
    @Test fun outputBudgetKeepsFinalVersionReceiptWithoutGrowing() {
        val output = PRootBoundedOutput(64)
        repeat(100) { output.append(ByteArray(31) { 'x'.code.toByte() }, 31) }
        val receipt = "DSTU_VERSION\tgit\t2.43.0\n".toByteArray()
        output.append(receipt, receipt.size)
        assertTrue(output.truncated)
        assertEquals(64, output.text().length)
        assertTrue(output.text().endsWith(receipt.toString(Charsets.UTF_8)))
    }

    @Test fun outputBelowBudgetIsLosslessIncludingUtf8() {
        val output = PRootBoundedOutput(64)
        val bytes = "Ubuntu 中文 👋".toByteArray()
        bytes.forEach { output.append(byteArrayOf(it), 1) }
        assertEquals("Ubuntu 中文 👋", output.text())
        assertFalse(output.truncated)
    }
}
