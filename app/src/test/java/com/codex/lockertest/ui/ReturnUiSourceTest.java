package com.codex.lockertest.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** Source-level UI boundary tests for Android Views in the offline JVM build. */
public final class ReturnUiSourceTest {
    private static final String UI_ROOT =
            "app/src/main/java/com/codex/lockertest/ui/";

    @Test
    public void authenticationViewOffersAllReturnIdentityChoicesAndRenderStates()
            throws Exception {
        String source = read(UI_ROOT + "ReturnAuthView.java");

        assertContains(source, "public final class ReturnAuthView extends FrameLayout");
        assertContains(source, "enum State");
        assertContains(source, "READY");
        assertContains(source, "LOADING");
        assertContains(source, "ERROR");
        assertContains(source, "NETWORK_OFFLINE");
        assertContains(source,
                "void onCredentialSubmit(UnlockMethod method, String rawValue)");
        assertContains(source, "void onFaceRequested()");
        assertContains(source, "void onPalmRequested()");
        assertContains(source, "void onCancelRequested()");
        assertContains(source, "public void setListener(Listener listener)");
        assertContains(source, "public void render(State state, CharSequence detail)");
        assertContains(source,
                "public void render(ReturnScreenPresentation presentation,");
        assertContains(source, "presentation.canIdentity()");
        assertContains(source, "presentation.canRetry()");
        assertContains(source, "离场还柜身份验证");
        assertContains(source, "手机号");
        assertContains(source, "取柜码");
        assertContains(source, "刷手环或扫描二维码");
        assertContains(source, "持续监听");
        assertContains(source, "人脸验证");
        assertContains(source, "掌纹验证");
        assertContains(source, "网络异常");
        assertContains(source, "安全返回");
        assertContains(source, "identityActions.add(key)");
        assertContains(source, "new ZipPixelShell(");
    }

    @Test
    public void lockerViewRendersOnlyInjectedServerLockersAndExplicitActions()
            throws Exception {
        String source = read(UI_ROOT + "ReturnLockerView.java");

        assertContains(source, "public final class ReturnLockerView extends FrameLayout");
        assertContains(source,
                "void onLockerSelected(ReturnLocker locker)");
        assertContains(source,
                "void onLockerConfirmed(ReturnLocker locker)");
        assertContains(source, "void onCancelRequested()");
        assertContains(source,
                "public void render(List<ReturnLocker> lockers,");
        assertContains(source, "Collections.unmodifiableList(");
        assertContains(source, "new ArrayList<>(lockers)");
        assertContains(source, "locker.displayLabel()");
        assertContains(source, "locker.areaDisplayName()");
        assertContains(source,
                "current.onLockerSelected(visibleLockers.get(index))");
        assertContains(source, "current.onLockerConfirmed(selectedLocker)");
        assertContains(source, "多个柜门将按顺序逐个办理");
        assertContains(source, "presentation.canSelect()");
        assertContains(source, "presentation.canConfirmLocker()");
        assertContains(source, "IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS");
        assertFalse("view must not synthesize domain lockers",
                source.contains("new ReturnLocker("));
        assertFalse("view must not contain a fixed demo locker label",
                source.contains("\"A1\"") || source.contains("\"A2\""));
    }

    @Test
    public void progressViewHasDiscretePureRenderStatesAndCloseConfirmation()
            throws Exception {
        String source = read(UI_ROOT + "ReturnProgressView.java");

        assertContains(source, "public final class ReturnProgressView extends FrameLayout");
        assertContains(source, "enum State");
        assertContains(source, "OPENING");
        assertContains(source, "WAITING_FOR_CLOSE");
        assertContains(source, "COMMITTING");
        assertContains(source, "SUCCESS");
        assertContains(source, "ERROR");
        assertContains(source, "void onDoorClosedConfirmed()");
        assertContains(source, "void onRetryRequested()");
        assertContains(source, "void onCancelRequested()");
        assertContains(source, "void onNextRequested()");
        assertContains(source, "void onHomeRequested()");
        assertContains(source,
                "public void render(State state, CharSequence lockerLabel,");
        assertContains(source,
                "boolean hasNext, boolean cancellable)");
        assertContains(source,
                "public void render(ReturnScreenPresentation presentation,");
        assertContains(source, "柜门已关闭");
        assertContains(source, "开柜成功不代表还柜完成");
        assertContains(source, "请务必关好柜门");
        assertContains(source, "点击只会重新检查柜门状态，不会直接完成还柜");
        assertContains(source, "countdownText");
        assertContains(source, "presentation.canDoorCloseConfirm()");
        assertContains(source, "presentation.canRetry()");
        assertContains(source, "presentation.canHome()");
        assertFalse("progress view must not own a timer", source.contains("Handler"));
        assertFalse("progress view must not own a clock", source.contains("SystemClock"));
        assertFalse("progress view must not schedule work", source.contains("postDelayed"));
    }

    @Test
    public void returnViewsStayInsideTheUiBoundary() throws Exception {
        String[] files = {
                "ReturnAuthView.java",
                "ReturnLockerView.java",
                "ReturnProgressView.java"
        };
        String[] forbidden = {
                "com.codex.lockertest.serial",
                "com.codex.lockertest.protocol",
                "ReturnServiceClient",
                "SerialGateway",
                "CustomerSerialTransmitter",
                "android.hardware",
                "java.net",
                "HttpURLConnection",
                "byte[]"
        };
        for (String file : files) {
            String source = read(UI_ROOT + file);
            assertContains(source, "new ZipPixelShell(");
            assertContains(source, "ZipKioskShell.unit(context");
            for (String token : forbidden) {
                assertFalse(file + " leaked non-UI dependency: " + token,
                        source.contains(token));
            }
        }
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
