package com.codex.lockertest.face;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public final class FaceSubsystemLivenessSourceTest {
    @Test
    public void subsystemOwnsPersistentLivenessPolicyAndPublishesItInSnapshot()
            throws Exception {
        String source = read("app/src/main/java/com/codex/lockertest/face/FaceSubsystem.java");
        assertContains(source, "face_liveness_policy_v1");
        assertContains(source, "rgb_liveness_enabled");
        assertContains(source, "new FaceLivenessControl.BooleanStore()");
        assertContains(source, "private final FaceLivenessControl livenessControl");
        assertContains(source, "private final FaceLivenessControl.Snapshot liveness");
        assertContains(source, "public FaceLivenessControl.Snapshot liveness()");
        assertContains(source, "new BaiduFaceRuntime(livenessControl, this::publishState)");
        assertContains(source, "public boolean setLivenessEnabled(boolean enabled)");
        assertContains(source, "livenessControl.setRequestedEnabled(enabled)");
        assertContains(source, "publishState()");
        assertFalse(source.contains("android.util.Log"));
        assertFalse(source.contains("System.out"));
    }

    private static void assertContains(String source, String token) {
        assertTrue("missing source token: " + token, source.contains(token));
    }

    private static String read(String relative) throws Exception {
        return new String(Files.readAllBytes(projectRoot().resolve(relative)),
                StandardCharsets.UTF_8);
    }

    private static Path projectRoot() {
        Path candidate = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        for (int index = 0; index < 8 && candidate != null; index++) {
            if (Files.isRegularFile(candidate.resolve("app/build.gradle"))) return candidate;
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("project root not found");
    }
}
