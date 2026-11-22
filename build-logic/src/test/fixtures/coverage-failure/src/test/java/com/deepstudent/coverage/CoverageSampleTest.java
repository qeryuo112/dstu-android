package com.deepstudent.coverage;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class CoverageSampleTest {
    @Test
    public void coversOnlyOneMethod() {
        assertEquals(1, new CoverageSample().covered());
    }
}
