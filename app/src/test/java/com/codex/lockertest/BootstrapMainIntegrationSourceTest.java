package com.codex.lockertest;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class BootstrapMainIntegrationSourceTest {
    @Test
    public void activityOwnsBootstrapRuntimeAndUsesItsDynamicReadiness() throws Exception {
        String main = main();
        String onCreate = slice(main, "    protected void onCreate(Bundle savedInstanceState)",
                "    @Override\n    protected void onStart()");

        contains(onCreate, "bootstrapRuntime = BootstrapAssembly.create(getApplicationContext())");
        contains(onCreate, "bootstrapUiSessionGate = new BootstrapUiSessionGate(bootstrapRuntime)");
        contains(onCreate, "bootstrapRuntime.readinessSource()");
        contains(onCreate, "new CustomerActionBoundary(terminalReadinessSource)");
        contains(onCreate,
                "new KioskFlowModel(credentialPolicy, runtime.layoutPolicy(), terminalReadinessSource)");
        assertFalse(onCreate.contains("runtime.localDemo()"));
    }

    @Test
    public void lifecycleInvalidatesBeforeCleanupAndClosesRuntime() throws Exception {
        String main = main();
        String onStop = slice(main, "    protected void onStop()",
                "    @Override\n    protected void onDestroy()");
        before(onStop, "stopBootstrapSession();", "pauseReturnJourneyForBackground();");
        before(onStop, "stopBootstrapSession();", "super.onStop();");

        String onDestroy = slice(main, "    protected void onDestroy()",
                "    @Override\n    public void onWindowFocusChanged");
        before(onDestroy, "closeBootstrapRuntime();", "super.onDestroy();");
    }

    @Test
    public void lateCallbacksRequireActivityRuntimeSessionAndServerGeneration()
            throws Exception {
        String callback = slice(main(), "    private void handleBootstrapSnapshot(",
                "    private void retryBootstrapSession()");
        contains(callback, "!active");
        contains(callback, "runtime != bootstrapRuntime");
        contains(callback, "session != bootstrapUiGeneration");
        contains(callback, "snapshot.generation() != bootstrapRuntimeGeneration");
        contains(callback, "flow.screen() == KioskFlowModel.Screen.HOME");
        contains(callback, "renderHome();");
    }

    @Test
    public void bootstrapRetryUsesOnlyRuntimeRestartAndNeverCustomerEffects()
            throws Exception {
        String retry = slice(main(), "    private void retryBootstrapSession()",
                "    private void stopBootstrapSession()");
        contains(retry, "bootstrapUiSessionGate.restartBootstrap()");
        assertFalse(retry.contains("CustomerActionBoundary"));
        assertFalse(retry.contains("SERIAL_GATEWAY_OWNER"));
        assertFalse(retry.contains("customerUnlockAuthorizer"));
        assertFalse(retry.contains("ReturnService"));
    }

    private static String main() throws Exception {
        return new String(Files.readAllBytes(root().resolve(
                "app/src/main/java/com/codex/lockertest/MainActivity.java")),
                StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static Path root() {
        Path candidate = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("app/build.gradle"))) return candidate;
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("project root not found");
    }

    private static String slice(String source, String start, String end) {
        int first = source.indexOf(start);
        int last = source.indexOf(end, first + start.length());
        assertTrue("missing start: " + start, first >= 0);
        assertTrue("missing end: " + end, last >= 0);
        return source.substring(first, last);
    }

    private static void contains(String source, String token) {
        assertTrue("missing token: " + token, source.contains(token));
    }

    private static void before(String source, String first, String second) {
        contains(source, first);
        contains(source, second);
        assertTrue(first + " must precede " + second,
                source.indexOf(first) < source.indexOf(second));
    }
}
