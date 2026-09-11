package com.codex.lockertest.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;
import org.junit.Test;

/** Source-level security contract for the Android administrator PIN control. */
public final class AdminPinOverlaySourceTest {
    @Test
    public void overlayUsesOnlyTheInjectedCredentialPolicyAndNoPinLiteral() throws Exception {
        String source = readMain("ui/AdminPinOverlay.java");

        assertContains(source, "AdminPinOverlay(Context context,");
        assertContains(source, "AdminCredentialPolicy adminPolicy");
        assertFalse(source.contains("AdminPinOverlay(Context context)"));
        assertFalse(source.contains("DemoCredentials"));
        assertFalse("common PIN UI must not contain a six-digit credential literal",
                Pattern.compile("\"[0-9]{6}\"").matcher(source).find());
    }

    @Test
    public void policyLengthDrivesPromptFilterConfirmationAndAuthentication() throws Exception {
        String source = readMain("ui/AdminPinOverlay.java");

        assertContains(source, "requiredLength = adminPolicy.requiredLength()");
        assertContains(source, "\"请输入\" + requiredLength + \"位管理员密码\"");
        assertContains(source, "new InputFilter.LengthFilter(requiredLength)");
        assertContains(source, "value.length() == requiredLength");
        String authenticate = methodSlice(source, "private void authenticate()",
                "private void clearPin(");
        assertContains(authenticate, "pinInput.length() != requiredLength");
    }

    @Test
    public void authenticationCopiesEditableToCharsAndAlwaysWipesBothCopies() throws Exception {
        String source = readMain("ui/AdminPinOverlay.java");
        String authenticate = methodSlice(source, "private void authenticate()",
                "private void clearPin(");

        assertContains(authenticate, "Editable editable = pinInput.getText()");
        assertContains(authenticate, "char[] candidate = new char[editable.length()]");
        assertOrdered(authenticate, "editable.getChars(", "adminPolicy.matches(candidate)");
        assertContains(authenticate, "finally");
        assertContains(authenticate, "Arrays.fill(candidate, '\\0')");
        assertContains(authenticate, "clearPin(false)");
        assertFalse(authenticate.contains("getText().toString()"));
        assertContains(source, "pinInput.getText().clear()");
    }

    @Test
    public void unavailablePolicyMessageIsExactAndPreventsMatching() throws Exception {
        String source = readMain("ui/AdminPinOverlay.java");
        String authenticate = methodSlice(source, "private void authenticate()",
                "private void clearPin(");

        assertOrdered(authenticate,
                "adminPolicy.unavailableMessage()",
                "renderPinFailure(unavailable)",
                "return;",
                "adminPolicy.matches(candidate)");
        String failure = methodSlice(source, "private void renderPinFailure(",
                "private void dismiss()");
        assertContains(failure, "message.setText(detail)");
    }

    private static String readMain(String path) throws IOException {
        Path main = projectRoot().resolve("app/src/main/java/com/codex/lockertest");
        return new String(Files.readAllBytes(main.resolve(path).normalize()),
                StandardCharsets.UTF_8);
    }

    private static String methodSlice(String source, String startToken, String endToken) {
        int start = source.indexOf(startToken);
        int end = source.indexOf(endToken, start + Math.max(0, startToken.length()));
        assertTrue("missing method slice " + startToken, start >= 0 && end > start);
        return source.substring(start, end);
    }

    private static void assertContains(String source, String token) {
        assertTrue("missing source token: " + token, source.contains(token));
    }

    private static void assertOrdered(String source, String... tokens) {
        int position = -1;
        for (String token : tokens) {
            int found = source.indexOf(token, position + 1);
            assertTrue("missing/out-of-order source token: " + token, found >= 0);
            position = found;
        }
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
