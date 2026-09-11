package com.codex.lockertest.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

public final class FaceAdminIntegrationSourceTest {
    @Test
    public void adminChooserHasFourListenerDrivenDestinations() throws Exception {
        String source = read(
                "app/src/main/java/com/codex/lockertest/ui/AdminFunctionOverlay.java");
        assertContains(source, "public final class AdminFunctionOverlay extends FrameLayout");
        assertContains(source, "void onSerialRequested()");
        assertContains(source, "void onFaceSdkRequested()");
        assertContains(source, "void onEnrollmentRequested()");
        assertContains(source, "void onHomeRequested()");
        assertEquals(1, occurrences(source, "\"串口调试\""));
        assertEquals(1, occurrences(source, "\"百度人脸 SDK\""));
        assertEquals(1, occurrences(source, "\"人脸/掌纹录入\""));
        assertEquals(1, occurrences(source, "\"返回首页\""));
        assertContains(source, "current.onSerialRequested()");
        assertContains(source, "current.onFaceSdkRequested()");
        assertContains(source, "current.onEnrollmentRequested()");
        assertContains(source, "current.onHomeRequested()");
        assertFalse(source.contains("startActivity"));
        assertFalse(source.contains("SerialGateway"));
        assertFalse(source.contains("AdminSerialActivity"));
        assertFalse(source.contains("FaceSubsystem"));
    }

    @Test
    public void adminOverlayMasksUppercasesAndTransfersClearedOwnedCharacters()
            throws Exception {
        String source = read(
                "app/src/main/java/com/codex/lockertest/ui/FaceSdkAdminOverlay.java");
        assertContains(source, "public final class FaceSdkAdminOverlay extends FrameLayout");
        assertContains(source, "new EditText(");
        assertContains(source, "PasswordTransformationMethod");
        assertContains(source, "InputFilter.AllCaps");
        assertContains(source, "new InputFilter.LengthFilter(4096)");
        assertContains(source, "setSingleLine(true)");
        assertContains(source, "setSaveEnabled(false)");
        assertContains(source, "void onActivateRequested(char[] ownedActivationCode)");
        assertContains(source, "void onDemoEnabledChanged(boolean enabled)");
        assertContains(source, "void onLivenessEnabledChanged(boolean enabled)");
        assertContains(source, "void onInitializeRuntimeRequested()");
        assertContains(source, "void onCloseRequested()");
        assertContains(source, "private boolean activationInFlight");
        assertContains(source, "private boolean renderingDemoState");
        assertContains(source, "private boolean renderingLivenessState");

        String take = methodSlice(source, "private char[] takeAndClearActivationCode()",
                "private void submitActivation()");
        assertContains(take, "Editable editable = activationInput.getText()");
        assertContains(take, "char[] owned = new char[");
        assertContains(take, "editable.getChars(");
        assertOrdered(take, "editable.getChars(", "editable.clear()", "return owned");
        assertFalse(take.contains("toString()"));

        String submit = methodSlice(source, "private void submitActivation()",
                "private void requestDemoState(");
        assertContains(submit, "if (activationInFlight) return");
        assertOrdered(submit, "takeAndClearActivationCode()",
                "activationInFlight = true", "current.onActivateRequested(owned)");
        assertOrdered(submit, "boolean transferred = false",
                "current.onActivateRequested(owned)", "transferred = true");
        String dispatchFailure = methodSlice(submit,
                "catch (RuntimeException | LinkageError ignored)", "finally");
        assertContains(dispatchFailure, "setActivationInFlight(false)");
        assertContains(submit, "Arrays.fill(owned, '\\0')");
        assertFalse(source.contains("getText().toString()"));
        assertFalse(Pattern.compile(
                "(?m)^\\s*(?:private|protected|public)\\s+"
                        + "(?:(?:static|final)\\s+)*char\\[\\]\\s+\\w+\\s*(?:=|;)")
                .matcher(source).find());
        assertFalse(Pattern.compile(
                "String\\s+(?:activation|license)(?:Code|Id|Text)\\s*(?:=|;|,|\\))",
                Pattern.CASE_INSENSITIVE).matcher(source).find());

        assertContains(source, "void setActivationInFlight(boolean inFlight)");
        assertContains(source, "activationInput.setEnabled(!inFlight)");
        assertContains(source, "activationButton.setEnabled(!inFlight)");
        assertContains(source, "void renderDemoState(boolean supported, boolean enabled)");
        String demo = methodSlice(source, "public void renderDemoState(",
                "public void clearActivationInput()");
        assertOrdered(demo, "renderingDemoState = true",
                "demoSwitch.setChecked(enabled)", "renderingDemoState = false");
        assertContains(source, "if (renderingDemoState) return");
        assertContains(source, "不进行身份验证");
        assertContains(source, "public void renderLivenessState(");
        assertContains(source, "livenessSwitch.setEnabled(snapshot.switchEnabled())");
        assertContains(source, "当前终端未授权活体检测，普通人脸抓拍仍可使用");
        assertContains(source, "活体模型初始化失败，普通人脸抓拍仍可使用");
    }

    @Test
    public void adminActivityUsesOneProcessFacadeAndGenerationGatedUiWaits()
            throws Exception {
        String source = read(
                "app/src/main/java/com/codex/lockertest/FaceSdkAdminActivity.java");
        assertContains(source, "public final class FaceSdkAdminActivity extends com.codex.lockertest.admin.OnlineMaintenanceActivity");
        assertContains(source, "if (isFinishing()) return;");
        assertContains(source, "maintenanceAuthorized()");
        assertContains(source, "WindowManager.LayoutParams.FLAG_SECURE");
        assertContains(source, "FaceSubsystem.shared(getApplicationContext())");
        assertContains(source, "new FaceSdkAdminOverlay(");
        assertContains(source, "FaceSubsystem.Subscription");
        assertContains(source, "BaiduFaceLicenseManager.Subscription");
        assertContains(source, "BaiduFaceRuntime.Subscription");
        assertContains(source, "subsystem.subscribe(");
        assertContains(source, "subsystem.checkLocal(");
        assertContains(source, "subsystem.activateOnline(");
        assertContains(source, "subsystem.initializeRuntime(");
        assertContains(source, "verificationEnvironment().isEnabled()");
        assertContains(source, "verificationEnvironment().setEnabled(requestedEnabled)");
        assertContains(source, "FaceBuildVariant.isLocalDemo()");
        assertContains(source, "sourceOverlay.renderLivenessState(snapshot.liveness())");
        assertContains(source, "subsystem.setLivenessEnabled(enabled)");
        assertContains(source, "FaceLicenseStateMachine.OPERATION_TIMEOUT_MILLIS");
        assertContains(source, "handler.postDelayed(");
        assertContains(source, "handler.removeCallbacks(");
        assertContains(source, "handler.post(");
        assertContains(source, "long uiGeneration");
        assertContains(source, "boolean uiActive");
        assertContains(source, "isCurrentUi(");
        assertContains(source, "sourceOverlay");
        assertContains(source, "activationInFlight");
        assertContains(source, "Arrays.fill(");

        String start = methodSlice(source, "protected void onStart()",
                "protected void onStop()");
        assertContains(start, "uiGeneration");
        assertContains(start, "uiActive = true");
        assertContains(start, "subsystem.subscribe(");
        assertContains(start, "verificationEnvironment().isEnabled()");
        assertOrdered(start, "FaceBuildVariant.isLocalDemo()", "if (subsystem == null");
        String unavailable = methodSlice(start, "if (subsystem == null) {", "return;");
        assertContains(unavailable, "sourceOverlay.renderDemoState(false, false)");
        String stop = methodSlice(source, "protected void onStop()",
                "protected void onDestroy()");
        assertOrdered(stop, "uiActive = false", "uiGeneration",
                "cancelUiOperations()", "super.onStop()");
        String destroy = methodSlice(source, "protected void onDestroy()", "private void");
        assertOrdered(destroy, "uiActive = false", "uiGeneration",
                "cancelUiOperations()");
        String post = methodSlice(source, "private void postToUi(Runnable action)",
                "private void cancelUiOperations()");
        assertContains(post, "try { action.run(); }");
        assertContains(post, "catch (RuntimeException | LinkageError ignored)");

        assertFalse(source.contains("cancelUiWait()"));
        assertFalse(source.contains("new FaceSubsystem"));
        assertFalse(source.contains("BaiduFaceLicenseManager.create"));
        assertFalse(Pattern.compile("new\\s+BaiduFaceRuntime\\s*\\(")
                .matcher(source).find());
        assertFalse(source.contains("SerialGateway"));
        assertFalse(source.contains("ProcessSerialGateway"));
        assertFalse(source.contains("android.hardware.Camera"));
        assertFalse(source.contains("FaceCaptureSession"));
        assertFalse(source.contains("FaceVerificationClient"));
        assertFalse(source.contains("java.io.File"));
        assertFalse(source.contains("license.key"));
        assertFalse(source.contains("license.ini"));
    }

    @Test
    public void rejectedActivationAttemptRestoresTheOverlaySubmissionGuard()
            throws Exception {
        String source = read(
                "app/src/main/java/com/codex/lockertest/FaceSdkAdminActivity.java");
        String request = methodSlice(source, "private void requestActivation(",
                "private void completeLicenseOperation(");
        String rejected = methodSlice(request,
                "UiOperation operation = beginUiOperation(",
                "activationInFlight = true");
        assertContains(rejected, "if (operation == null) {");
        assertOrdered(rejected, "if (operation == null) {",
                "sourceOverlay.setActivationInFlight(false)", "return");
    }

    @Test
    public void unavailableSubsystemRestoresTheOverlaySubmissionGuard()
            throws Exception {
        String source = read(
                "app/src/main/java/com/codex/lockertest/FaceSdkAdminActivity.java");
        String request = methodSlice(source, "private void requestActivation(",
                "private void completeLicenseOperation(");
        String preflight = methodSlice(request, "boolean transferred = false",
                "UiOperation operation = beginUiOperation(");
        assertContains(preflight, "if (subsystem == null) {");
        assertOrdered(preflight, "if (subsystem == null) {",
                "sourceOverlay.setActivationInFlight(false)", "return");
    }

    @Test
    public void activityTransfersOwnedCharactersOnlyAfterSubsystemCallReturns()
            throws Exception {
        String source = read(
                "app/src/main/java/com/codex/lockertest/FaceSdkAdminActivity.java");
        String request = methodSlice(source, "private void requestActivation(",
                "private void completeLicenseOperation(");
        assertOrdered(request, "subsystem.activateOnline(", "transferred = true");
    }

    @Test
    public void demoPolicyWriteFailureRendersTheActualEnvironmentState()
            throws Exception {
        String source = read(
                "app/src/main/java/com/codex/lockertest/FaceSdkAdminActivity.java");
        String request = methodSlice(source, "private void requestDemoState(",
                "private UiOperation beginUiOperation(");
        String failure = methodSlice(request,
                "catch (RuntimeException | LinkageError failure)",
                "if (isCurrentUi(");
        assertContains(failure,
                "actual = subsystem.verificationEnvironment().isEnabled()");
        assertContains(failure, "无法更新本机模拟状态");
    }

    @Test
    public void faceAdminManifestEntryIsExactNonExportedLandscapeAndUnfiltered()
            throws Exception {
        String manifest = read("app/src/main/AndroidManifest.xml");
        Matcher activity = Pattern.compile(
                "<activity\\s+([^>]*android:name=\\\"\\.FaceSdkAdminActivity\\\"[^>]*)/>",
                Pattern.DOTALL).matcher(manifest);
        assertTrue("missing self-closing FaceSdkAdminActivity entry", activity.find());
        String attributes = activity.group(1);
        assertContains(attributes, "android:exported=\"false\"");
        assertContains(attributes, "android:screenOrientation=\"landscape\"");
        assertContains(attributes,
                "android:configChanges=\"keyboardHidden|orientation|screenSize\"");
        assertEquals(1, occurrences(manifest, "android:name=\".FaceSdkAdminActivity\""));
        assertEquals(2, occurrences(manifest, "<intent-filter>"));
        assertEquals(3, occurrences(manifest, "<uses-permission"));
        assertContains(manifest, "android.permission.CAMERA");
        assertContains(manifest, "android.permission.INTERNET");
        assertContains(manifest, "android.permission.ACCESS_NETWORK_STATE");
        assertContains(manifest, "android.hardware.camera");
        assertContains(manifest, "android:required=\"false\"");
        assertContains(manifest, "android:allowBackup=\"false\"");
        assertContains(manifest, "android:name=\".AdminSerialActivity\"");
        assertContains(manifest, "android:name=\"com.baidu.liantian.LiantianActivity\"");
        assertContains(manifest, "android:exported=\"true\"");
        assertContains(manifest, "com.baidu.action.Liantian.VIEW");
        assertContains(manifest, "com.baidu.category.liantian");
        assertFalse(manifest.contains("READ_EXTERNAL_STORAGE"));
        assertFalse(manifest.contains("WRITE_EXTERNAL_STORAGE"));
        assertFalse(manifest.contains("MANAGE_EXTERNAL_STORAGE"));
    }

    @Test
    public void task8UiMayChangeButSubsystemProtocolAndSelectionModelsRemainFrozen()
            throws Exception {
        assertEquals("A2B21C2EEECB3718E2A834654C274DDC7AD68FA36CB2DE19D0FA746038448FA7",
                sha256("app/src/main/java/com/codex/lockertest/face/FaceSubsystem.java"));
        assertEquals("F5355060CBEC9EB454923B91A7CE63FE60057DFB456789C87154F68EFCEF60E6",
                sha256("app/src/main/java/com/codex/lockertest/face/verification/FaceVerificationEnvironment.java"));
        assertEquals("E545A0A890D70271AB89CE3CB01FFF8BEFE88CF85A4C61D591D3F4685E3FE89D",
                sha256("app/src/main/java/com/codex/lockertest/ui/LockerSelectionModel.java"));
        assertEquals("F03D88B2424482D08B49B9F4701BBBE98DAFDC92B61F69557D2EA1C3F6BF27BF",
                sha256("app/src/main/java/com/codex/lockertest/ui/LockerGridPresentation.java"));
        assertEquals("54D8ADAAE1C37E9F2789F3AA0573DF8E8719952B91BA4F2C6614DF3714EE17CB",
                sha256("app/src/main/java/com/codex/lockertest/ui/UiKit.java"));

        String admin = read(
                "app/src/main/java/com/codex/lockertest/AdminSerialActivity.java");
        assertContains(admin, "ZipAdminScreenRouter.assetForSerial(");
        assertContains(admin, "SerialRequestGate.TIMEOUT_MILLIS");
        assertFalse(admin.contains("detail.startsWith("));
        String shell = read(
                "app/src/main/java/com/codex/lockertest/ui/ZipKioskShell.java");
        assertContains(shell,
                "public static int unit(Context context, float designUnits)");
        assertFrozenManifestExact();
    }

    @Test
    public void authorizedSourcesContainNoActivationCredentialOrImageLeak()
            throws Exception {
        Pattern activationShape = Pattern.compile(
                "(?<![A-Z0-9])[A-Z0-9]{4}(?:-[A-Z0-9]{4}){3}(?![A-Z0-9])");
        Pattern secret = Pattern.compile(
                "(?i)(-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|"
                        + "Bearer\\s+[A-Za-z0-9._~-]{16,}|"
                        + "https?://[^/\\s:@]+:[^/\\s@]+@|"
                        + "Base64|printStackTrace|getMessage\\s*\\(|"
                        + "android\\.util\\.Log|System\\.(?:out|err))");
        String[] files = {
                "app/src/main/java/com/codex/lockertest/ui/FaceRecognitionView.java",
                "app/src/main/java/com/codex/lockertest/ui/FaceSecurityBanner.java",
                "app/src/main/java/com/codex/lockertest/ui/FaceSdkAdminOverlay.java",
                "app/src/main/java/com/codex/lockertest/ui/AdminFunctionOverlay.java",
                "app/src/main/java/com/codex/lockertest/FaceSdkAdminActivity.java",
                "app/src/main/java/com/codex/lockertest/ui/ZipHomeView.java",
                "app/src/main/java/com/codex/lockertest/ui/LockerSelectionView.java",
                "app/src/main/AndroidManifest.xml"
        };
        for (String file : files) {
            String source = read(file);
            assertFalse(file + " contains activation-shaped content",
                    activationShape.matcher(source).find());
            assertFalse(file + " contains a prohibited secret/logging shape",
                    secret.matcher(source).find());
        }
    }

    private static void assertFrozenManifestExact()
            throws IOException, NoSuchAlgorithmException {
        Path root = projectRoot();
        Path manifest = root.resolve("manual-build/v7-baseline/frozen-source.sha256");
        assertEquals("52176132906A473AD2731DFD944F3F881511637F482D12B8E6D28D41E0AA1EF0",
                sha256(manifest));
        List<String> lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
        assertEquals(24, lines.size());
        Set<Path> unique = new HashSet<Path>();
        int protectedMatches = 0;
        int task8UiOverrides = 0;
        int onlineSerialOverrides = 0;
        for (String line : lines) {
            int separator = line.indexOf("  ");
            assertTrue("malformed frozen manifest line", separator == 64);
            String expected = line.substring(0, separator);
            Path path = root.resolve(line.substring(separator + 2)).normalize();
            String relative = line.substring(separator + 2);
            assertTrue("duplicate frozen path", unique.add(path));
            assertTrue("missing frozen path: " + path, Files.isRegularFile(path));
            String actual = sha256(path);
            if (relative.equals(
                    "app/src/main/java/com/codex/lockertest/AdminSerialActivity.java")
                    || relative.equals(
                    "app/src/main/java/com/codex/lockertest/unlock/UnlockCoordinator.java")) {
                assertFalse("an exact Task 8 source must intentionally replace its v7 version",
                        expected.equals(actual));
                task8UiOverrides++;
            } else if (relative.equals(
                    "app/src/main/java/com/codex/lockertest/serial/SerialGateway.java")) {
                assertEquals("126959E56EEE9CAFE83E7CE4A11261EE50B6F127E6A9008315C3EC44CFA19119", expected);
                assertEquals("B881BD5858E9B8B410F41968E047139FC3AD5BD60D378ADF43BB7B88B92FD44B", actual);
                onlineSerialOverrides++;
            } else {
                assertEquals(path.toString(), expected, actual);
                protectedMatches++;
            }
        }
        assertEquals(24, unique.size());
        assertEquals(21, protectedMatches);
        assertEquals(2, task8UiOverrides);
        assertEquals(1, onlineSerialOverrides);
    }

    private static String javaMethodBody(String source, String methodName) {
        Matcher declaration = Pattern.compile(
                "(?m)^\\s*private\\s+[A-Za-z0-9_$.<>\\[\\]]+\\s+"
                        + Pattern.quote(methodName) + "\\s*\\(")
                .matcher(source);
        if (!declaration.find()) fail("missing method: " + methodName);
        int open = source.indexOf('{', declaration.end());
        if (open < 0) fail("missing method body: " + methodName);
        int depth = 0;
        for (int index = open; index < source.length(); index++) {
            char value = source.charAt(index);
            if (value == '{') depth++;
            if (value == '}' && --depth == 0) return source.substring(open, index + 1);
        }
        throw new AssertionError("unterminated method: " + methodName);
    }

    private static void assertOrdered(String source, String... tokens) {
        int position = -1;
        for (String token : tokens) {
            int found = source.indexOf(token, position + 1);
            assertTrue("missing/out-of-order token: " + token, found >= 0);
            position = found;
        }
    }

    private static int occurrences(String source, String token) {
        int result = 0;
        int at = 0;
        while ((at = source.indexOf(token, at)) >= 0) {
            result++;
            at += token.length();
        }
        return result;
    }

    private static String methodSlice(String source, String marker, String next) {
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

    private static String sha256(String relative)
            throws IOException, NoSuchAlgorithmException {
        return sha256(projectRoot().resolve(relative));
    }

    private static String sha256(Path path)
            throws IOException, NoSuchAlgorithmException {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(path));
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            result.append(Character.forDigit((value >>> 4) & 0x0f, 16));
            result.append(Character.forDigit(value & 0x0f, 16));
        }
        return result.toString().toUpperCase(Locale.ROOT);
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
