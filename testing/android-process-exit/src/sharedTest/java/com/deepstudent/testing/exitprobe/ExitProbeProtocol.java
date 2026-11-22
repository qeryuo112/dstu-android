package com.deepstudent.testing.exitprobe;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Locale;

/** Wire-level rules shared by the listener and the control tests. No application data is stored. */
final class ExitProbeProtocol {
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final String SUMMARY_PREFIX = "dstu-process-exit-v1:";

    private ExitProbeProtocol() {}

    static boolean isToken(String token) {
        if (token == null || token.length() != 32) return false;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
        }
        return true;
    }

    static String newToken() {
        byte[] random = new byte[16];
        new SecureRandom().nextBytes(random);
        char[] token = new char[32];
        for (int i = 0; i < random.length; i++) {
            int value = random[i] & 0xff;
            token[i * 2] = HEX[value >>> 4];
            token[i * 2 + 1] = HEX[value & 15];
        }
        return new String(token);
    }

    static int parsePid(String value) {
        if (value == null || value.isEmpty() || value.length() > 10) return -1;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) < '0' || value.charAt(i) > '9') return -1;
        }
        try {
            int pid = Integer.parseInt(value);
            return pid > 0 ? pid : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    static long parseEpochMs(String value) {
        if (value == null || value.length() != 13 || value.charAt(0) == '0') return -1;
        long millis = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') return -1;
            millis = millis * 10 + (c - '0');
        }
        return millis;
    }

    static byte[] summary(String token) {
        if (!isToken(token)) throw new IllegalArgumentException("invalid token");
        return (SUMMARY_PREFIX + token).getBytes(StandardCharsets.US_ASCII);
    }

    static boolean matchesSummary(byte[] candidate, String token) {
        return isToken(token) && Arrays.equals(summary(token), candidate);
    }

    // ApplicationExitInfo reason codes are public constants, including newer SDK values.
    static String reasonName(int reason) {
        switch (reason) {
            case 0: return "UNKNOWN";
            case 1: return "EXIT_SELF";
            case 2: return "SIGNALED";
            case 3: return "LOW_MEMORY";
            case 4: return "CRASH";
            case 5: return "CRASH_NATIVE";
            case 6: return "ANR";
            case 7: return "INITIALIZATION_FAILURE";
            case 8: return "PERMISSION_CHANGE";
            case 9: return "EXCESSIVE_RESOURCE_USAGE";
            case 10: return "USER_REQUESTED";
            case 11: return "USER_STOPPED";
            case 12: return "DEPENDENCY_DIED";
            case 13: return "OTHER";
            case 14: return "FREEZER";
            case 15: return "PACKAGE_STATE_CHANGE";
            case 16: return "PACKAGE_UPDATED";
            default: return "UNRECOGNIZED";
        }
    }

    /**
     * Never return the system's raw description: ANR/kill messages may contain user data.
     * These fixed allowlisted hints are indicators only, not proof of a caller or cause.
     */
    static String systemClues(String description) {
        if (description == null) return "";
        String bounded = description.substring(0, Math.min(description.length(), 1024))
                .toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder();
        if (bounded.contains("forcestoppackagebyuid")) addClue(result, "forceStopPackageByUid");
        if (bounded.contains("force-stop") || bounded.contains("force stop")) {
            addClue(result, "force-stop");
        }
        if (bounded.contains("com.vivo.sps:rms")) addClue(result, "com.vivo.sps:rms");
        if (bounded.contains("lmkd")) addClue(result, "lmkd");
        if (bounded.contains("low memory")) addClue(result, "low-memory");
        return result.toString();
    }

    private static void addClue(StringBuilder result, String clue) {
        if (result.length() > 0) result.append(',');
        result.append(clue);
    }
}
