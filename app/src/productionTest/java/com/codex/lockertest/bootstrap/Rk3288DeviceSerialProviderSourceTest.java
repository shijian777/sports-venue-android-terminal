package com.codex.lockertest.bootstrap;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class Rk3288DeviceSerialProviderSourceTest {
    @Test
    public void androidAccessGuardsGetSerialBehindTheApi26Branch() throws Exception {
        String source = read("app/src/production/java/com/codex/lockertest/bootstrap/"
                + "AndroidHardwareSerialAccess.java");

        int guard = source.indexOf("if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)");
        int getSerial = source.indexOf("Build.getSerial()", guard);
        int fallback = source.indexOf("return null", getSerial);
        assertTrue(guard >= 0);
        assertTrue(getSerial > guard);
        assertTrue(fallback > getSerial);
    }

    @Test
    public void productionProviderUsesOnlyTheRk3288SerialSelector() throws Exception {
        String access = read("app/src/production/java/com/codex/lockertest/bootstrap/"
                + "AndroidHardwareSerialAccess.java");
        String provider = read("app/src/production/java/com/codex/lockertest/bootstrap/"
                + "Rk3288DeviceSerialProvider.java");
        String selector = read("app/src/main/java/com/codex/lockertest/bootstrap/"
                + "Rk3288SerialSelector.java");
        String combined = access + provider + selector;

        assertTrue(provider.contains("new AndroidHardwareSerialAccess()"));
        assertTrue(provider.contains("selector.select(access)"));
        assertTrue(selector.contains("\"ro.serialno\""));
        assertTrue(selector.contains("\"ro.boot.serialno\""));
        assertFalse(combined.contains("Settings.Secure"));
        assertFalse(combined.contains("ANDROID_ID"));
        assertFalse(combined.contains("NetworkInterface"));
        assertFalse(combined.contains("getMacAddress"));
        assertFalse(combined.contains("UUID"));
        assertFalse(combined.contains("randomUUID"));
        assertFalse(combined.contains("currentTimeMillis"));
    }

    private static String read(String relative) throws Exception {
        return new String(Files.readAllBytes(projectRoot().resolve(relative)),
                StandardCharsets.UTF_8);
    }

    private static Path projectRoot() {
        Path candidate = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        for (int depth = 0; depth < 8 && candidate != null; depth++) {
            if (Files.isRegularFile(candidate.resolve("app/build.gradle"))) return candidate;
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("project root not found");
    }
}
