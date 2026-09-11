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
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

public final class FaceUiSourceTest {
    private static final String DEFAULT_BANNER = "本机联调：未进行身份比对";

    @Test
    public void homeFaceAndPalmEnrollmentKeepTheirDedicatedCallbacks()
            throws Exception {
        String source = read("app/src/main/java/com/codex/lockertest/ui/ZipHomeView.java");
        assertTrue(Pattern.compile(
                "default\\s+void\\s+onFaceRequested\\s*\\(\\s*\\)\\s*\\{\\s*\\}")
                .matcher(source).find());
        String actions = methodSlice(source, "private FrameLayout buildRightInteractions(",
                "private static void distributeFinalActions(");
        assertContains(actions, "current.onFaceRequested()");
        assertContains(actions, "current.onEnrollmentRequested()");
        assertFalse(actions.contains("onUnavailableSelected"));

        assertContains(source, "\"人脸识别\"");
        assertContains(source, "\"掌纹录入\"");
        assertContains(source, "请直接刷手环或扫描二维码");
        assertContains(source, "void onCredentialSubmit(UnlockMethod method, String rawValue)");
        assertContains(source, "void onAdminRequested()");
    }

    @Test
    public void facePageUsesRealSurfacePreviewAndListenerOnlyStateSurface()
            throws Exception {
        String source = read(
                "app/src/main/java/com/codex/lockertest/ui/FaceRecognitionView.java");
        assertContains(source, "public final class FaceRecognitionView extends FrameLayout");
        assertContains(source,
                "new ZipPixelShell(context, ZipScreenAsset.FACE_PREPARING)");
        assertContains(source, "new SurfaceView(");
        assertContains(source, "SurfaceHolder previewHolder()");
        assertContains(source, ".getHolder()");
        assertContains(source, "new FaceSecurityBanner(");
        assertContains(source, "void onReturnRequested()");
        assertContains(source, "void onRetryRequested()");
        assertContains(source, "void showPreparing()");
        assertContains(source, "void showDetecting(String hint, FaceBox box)");
        assertContains(source, "void showVerifying()");
        assertContains(source, "void showPermissionDenied(boolean permanentlyDenied)");
        assertContains(source, "void showFailure(String message, boolean retryable)");
        assertContains(source, "void setListener(Listener listener)");
        assertContains(source, "onReturnRequested()");
        assertContains(source, "onRetryRequested()");
        assertTrue(Pattern.compile(
                "try\\s*\\{\\s*current\\.onReturnRequested\\(\\);\\s*}\\s*"
                        + "catch\\s*\\(RuntimeException\\s*\\|\\s*LinkageError\\s+ignored\\)")
                .matcher(source).find());
        assertTrue(Pattern.compile(
                "try\\s*\\{\\s*current\\.onRetryRequested\\(\\);\\s*}\\s*"
                        + "catch\\s*\\(RuntimeException\\s*\\|\\s*LinkageError\\s+ignored\\)")
                .matcher(source).find());
        assertContains(source, "setContentDescription");

        assertFalse(source.contains("com.baidu"));
        assertFalse(source.contains("FaceSubsystem"));
        assertFalse(source.contains("FaceVerification"));
        assertFalse(source.contains("SerialGateway"));
        assertFalse(source.contains("android.hardware.Camera"));
        assertFalse(source.contains("byte[]"));
        assertFalse(source.contains("java.net"));
    }

    @Test
    public void faceBoxIsImmutableFiniteValidatedAndOverlayClearsStaleBoxes()
            throws Exception {
        String source = read(
                "app/src/main/java/com/codex/lockertest/ui/FaceRecognitionView.java");
        String faceBox = slice(source, "public static final class FaceBox",
                "public interface Listener");
        assertContains(faceBox, "private final int frameWidth");
        assertContains(faceBox, "private final int frameHeight");
        assertContains(faceBox, "private final float centerX");
        assertContains(faceBox, "private final float centerY");
        assertContains(faceBox, "private final float width");
        assertContains(faceBox, "private final float height");
        assertContains(faceBox, "IllegalArgumentException");
        assertTrue(occurrences(faceBox, "Float.isNaN") >= 4);
        assertTrue(occurrences(faceBox, "Float.isInfinite") >= 4);

        String preparing = methodSlice(source, "public void showPreparing()",
                "public void showDetecting(");
        assertContains(preparing, "ZipCustomerScreenRouter.State.FACE_PREPARING");
        assertContains(preparing, "null);");
        String verifying = methodSlice(source, "public void showVerifying()",
                "public void showPermissionDenied(");
        assertContains(verifying, "ZipCustomerScreenRouter.State.FACE_UPLOADING");
        assertContains(verifying, "null);");
        String permission = methodSlice(source, "public void showPermissionDenied(",
                "public void showFailure(");
        assertContains(permission, "showErrorState(");
        String failure = methodSlice(source, "public void showFailure(",
                "public void setListener(");
        assertContains(failure, "showErrorState(");
        String errorState = methodSlice(source, "private void showErrorState(",
                "private void configureErrorButtons(");
        assertContains(errorState, "previewOverlay.setFaceBox(null)");
        String activeState = methodSlice(source, "private void showActiveState(",
                "private void showErrorState(");
        assertContains(activeState, "previewOverlay.setFaceBox(box)");
        assertContains(source, "Math.max(");
        assertContains(source, "Math.min(");
    }

    @Test
    public void localDemoAssemblyAttachesExplicitWarningToBothFacePagePaths()
            throws Exception {
        String assembly = read("app/src/localDemo/java/com/codex/lockertest/runtime/RuntimeAssembly.java");
        String activity = read("app/src/main/java/com/codex/lockertest/MainActivity.java");

        assertContains(assembly, "public static FaceBannerPolicy createFaceBannerPolicy()");
        assertContains(assembly, "return \"" + DEFAULT_BANNER + "\";");
        assertContains(assembly, "return true;");
        assertContains(activity,
                "sourceView.setSecurityBanner(faceBannerPolicy.text(), faceBannerPolicy.visible())");
        assertContains(activity,
                "selection.setSecurityBanner(faceBannerPolicy.text(), faceBannerPolicy.visible())");
        assertFalse(Files.isRegularFile(Paths.get("app", "src", "localDemo", "java",
                "com", "codex", "lockertest", "ui", "FaceDemoBanner.java")));
    }

    @Test
    public void selectionBannerConsumesOnlyLayoutSpaceAndNeverMutatesModel()
            throws Exception {
        String source = read(
                "app/src/main/java/com/codex/lockertest/ui/LockerSelectionView.java");
        assertContains(source, "private final FaceSecurityBanner securityBanner");
        assertContains(source, "FrameLayout selectionHeader");
        assertContains(source, "selectionHeader.addView(instruction");
        assertContains(source, "selectionHeader.addView(securityBanner");
        assertContains(source, "body.addView(selectionHeader");
        assertContains(source, "unit(context, 32)");
        String setter = methodSlice(source,
                "public void setSecurityBanner(CharSequence text, boolean visible)",
                "public void setListener(");
        assertContains(setter, "securityBanner.setBannerText(text)");
        assertContains(setter, "visible ? View.VISIBLE : View.GONE");
        assertContains(setter, "visible ? View.GONE : View.VISIBLE");
        assertFalse(setter.contains("model."));
        assertFalse(setter.contains("areaWindowPage"));
        assertFalse(setter.contains("render()"));
        assertFalse(setter.contains("setSending"));
        assertFalse(setter.contains("applyDiscovery"));
        assertEquals("E545A0A890D70271AB89CE3CB01FFF8BEFE88CF85A4C61D591D3F4685E3FE89D",
                sha256("app/src/main/java/com/codex/lockertest/ui/LockerSelectionModel.java"));
        assertEquals("F03D88B2424482D08B49B9F4701BBBE98DAFDC92B61F69557D2EA1C3F6BF27BF",
                sha256("app/src/main/java/com/codex/lockertest/ui/LockerGridPresentation.java"));
    }

    @Test
    public void customerFaceViewsContainNoTechnicalOrCredentialText()
            throws Exception {
        Pattern literal = Pattern.compile("\\\"(?:\\\\.|[^\\\"\\\\])*\\\"");
        Pattern forbidden = Pattern.compile(
                "(?i)(百度|SDK|设备(?:号|ID)|激活(?:码)?|token|https?://|Base64|"
                        + "字节|JPEG|照片大小|错误码|/dev/|tty(?:S|USB|ACM))");
        String[] files = {
                "app/src/main/java/com/codex/lockertest/ui/FaceRecognitionView.java",
                "app/src/main/java/com/codex/lockertest/ui/FaceSecurityBanner.java"
        };
        for (String file : files) {
            Matcher literals = literal.matcher(read(file));
            while (literals.find()) {
                assertFalse(file + " leaked customer text: " + literals.group(),
                        forbidden.matcher(literals.group()).find());
            }
        }
    }

    private static int occurrences(String source, String token) {
        int count = 0;
        int at = 0;
        while ((at = source.indexOf(token, at)) >= 0) {
            count++;
            at += token.length();
        }
        return count;
    }

    private static String methodSlice(String source, String marker, String next) {
        return slice(source, marker, next);
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

    private static String sha256(String relative)
            throws IOException, NoSuchAlgorithmException {
        byte[] bytes = Files.readAllBytes(projectRoot().resolve(relative));
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
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
