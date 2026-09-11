package com.codex.lockertest.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;
import org.junit.Test;

/** Source contract for the customer-home return entry without an Android JVM runtime. */
public final class ZipHomeViewSourceTest {
    @Test
    public void returnEntryHasASeparateCallbackAndCannotHijackCredentialSubmit()
            throws Exception {
        String source = readHome();

        assertTrue(Pattern.compile(
                "default\\s+void\\s+onReturnRequested\\s*\\(\\s*\\)\\s*\\{\\s*\\}")
                .matcher(source).find());
        assertContains(source, "\"离场还柜\"");
        assertContains(source, "notifyReturnRequested()");

        String returnHandler = slice(source,
                "private void notifyReturnRequested()",
                "private Button createInputField(");
        assertContains(returnHandler, "current.onReturnRequested()");
        assertFalse(returnHandler.contains("onCredentialSubmit"));
        assertFalse(returnHandler.contains("onFaceRequested"));
        assertFalse(returnHandler.contains("onUnavailableSelected"));

        String credentialSubmit = slice(source,
                "private void submitCurrentCredential()",
                "private void refreshFields()");
        assertContains(credentialSubmit,
                "current.onCredentialSubmit(method, rawValue)");
        assertFalse(credentialSubmit.contains("onReturnRequested"));
        assertContains(source,
                "confirmButton.setOnClickListener(view -> submitCurrentCredential())");
    }

    @Test
    public void existingHomeInteractionsAndResponsiveShellRemainPresent()
            throws Exception {
        String source = readHome();

        assertContains(source,
                "new ZipPixelShell(context, ZipScreenAsset.HOME_WAITING)");
        assertContains(source,
                "void onCredentialSubmit(UnlockMethod method, String rawValue)");
        assertContains(source, "default void onFaceRequested() { }");
        assertContains(source, "void onUnavailableSelected(UnlockMethod method)");
        assertContains(source, "void onAdminRequested()");
        assertContains(source, "default void onBootstrapRetryRequested() { }");
        assertContains(source, "UnlockMethod.PHONE");
        assertContains(source, "UnlockMethod.PASSWORD");
        assertContains(source, "\"人脸识别\"");
        assertContains(source, "\"掌纹录入\"");
        assertContains(source, "请直接刷手环或扫描二维码");
        assertContains(source, "重新连接服务器");
        assertContains(source, "notifyBootstrapRetryRequested()");
        assertFalse(source.contains("registerCustomerAction(bootstrapRetryButton)"));
        assertContains(source, "ZipKioskShell.unit(context, designUnits)");
        assertContains(source, "ZipKioskShell.designDp(context, designUnits)");
    }

    private static String readHome() throws IOException {
        return read("app/src/main/java/com/codex/lockertest/ui/ZipHomeView.java");
    }

    private static String slice(String source, String marker, String next) {
        int start = source.indexOf(marker);
        if (start < 0) fail("missing marker: " + marker);
        int end = source.indexOf(next, start + marker.length());
        if (end < 0) fail("missing end marker: " + next);
        return source.substring(start, end);
    }

    private static void assertContains(String source, String token) {
        assertTrue("missing token: " + token, source.contains(token));
    }

    private static String read(String relative) throws IOException {
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
