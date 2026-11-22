package com.deepstudent.testing.exitprobe;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

/** Host-side protocol checks; runnable with javac/JUnit without Android, Gradle, or a device. */
public final class ExitProbeProtocolTest {
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";

    @Test
    public void randomTokensAreLowercaseAndDistinct() {
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 256; i++) {
            String token = ExitProbeProtocol.newToken();
            assertTrue(ExitProbeProtocol.isToken(token));
            assertTrue(tokens.add(token));
        }
    }

    @Test
    public void tokenAndPidInputsAreStrict() {
        assertTrue(ExitProbeProtocol.isToken(TOKEN));
        assertFalse(ExitProbeProtocol.isToken(null));
        assertFalse(ExitProbeProtocol.isToken(TOKEN.toUpperCase()));
        assertFalse(ExitProbeProtocol.isToken(TOKEN + "0"));
        assertFalse(ExitProbeProtocol.isToken("g" + TOKEN.substring(1)));
        assertFalse(ExitProbeProtocol.isToken(TOKEN.substring(1)));
        assertEquals(1, ExitProbeProtocol.parsePid("1"));
        assertEquals(Integer.MAX_VALUE, ExitProbeProtocol.parsePid("2147483647"));
        for (String invalid : new String[] {null, "", "0", "-1", "+1", " 1", "1\n", "2147483648", "12345678901", "１２"}) {
            assertEquals(String.valueOf(invalid), -1, ExitProbeProtocol.parsePid(invalid));
        }
    }

    @Test
    public void cutoffIsCanonicalUnixMilliseconds() {
        assertEquals(1795171381152L, ExitProbeProtocol.parseEpochMs("1795171381152"));
        for (String invalid : new String[] {null, "", "1795171381", "0795171381152",
                "+179517138115", "1795171381152\n", "179517138115x", "12345678901234"}) {
            assertEquals(String.valueOf(invalid), -1L, ExitProbeProtocol.parseEpochMs(invalid));
        }
    }

    @Test
    public void summaryRequiresExactVersionedPayloadAndToken() {
        byte[] summary = ExitProbeProtocol.summary(TOKEN);
        assertTrue(summary.length <= 128);
        assertTrue(ExitProbeProtocol.matchesSummary(summary, TOKEN));
        assertFalse(ExitProbeProtocol.matchesSummary(null, TOKEN));
        assertFalse(ExitProbeProtocol.matchesSummary(summary, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
        assertFalse(ExitProbeProtocol.matchesSummary(TOKEN.getBytes(StandardCharsets.US_ASCII), TOKEN));
        assertFalse(ExitProbeProtocol.matchesSummary(
                (new String(summary, StandardCharsets.US_ASCII) + "extra").getBytes(StandardCharsets.US_ASCII), TOKEN));
        assertFalse(ExitProbeProtocol.matchesSummary(summary, "INVALID"));
    }

    @Test
    public void reasonsAndOnlyAllowlistedBoundedSystemCluesAreEmitted() {
        assertEquals("USER_REQUESTED", ExitProbeProtocol.reasonName(10));
        assertEquals("SIGNALED", ExitProbeProtocol.reasonName(2));
        assertEquals("UNKNOWN", ExitProbeProtocol.reasonName(0));
        assertEquals("UNRECOGNIZED", ExitProbeProtocol.reasonName(100));
        String sensitive = "SECRET /user/file forceStopPackageByUid com.vivo.sps:rms lmkd";
        assertEquals("forceStopPackageByUid,com.vivo.sps:rms,lmkd", ExitProbeProtocol.systemClues(sensitive));
        assertEquals("force-stop,low-memory", ExitProbeProtocol.systemClues("FORCE-STOP due to LOW MEMORY"));
        assertEquals("", ExitProbeProtocol.systemClues("SECRET /user/file"));
        assertEquals("", ExitProbeProtocol.systemClues(null));
        char[] padding = new char[1024];
        java.util.Arrays.fill(padding, 'x');
        assertEquals("", ExitProbeProtocol.systemClues(new String(padding) + "lmkd"));
    }
}
