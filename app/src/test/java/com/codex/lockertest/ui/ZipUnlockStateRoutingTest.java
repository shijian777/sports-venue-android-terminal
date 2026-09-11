package com.codex.lockertest.ui;

import com.codex.lockertest.ui.zip.ZipLockerScreenRouter;
import com.codex.lockertest.ui.zip.ZipScreenAsset;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class ZipUnlockStateRoutingTest {
    @Test
    public void resultRoutesCoverNineteenThroughTwentySixWithExactActionMatrix() {
        int expectedId = 19;
        Set<ZipScreenAsset> unique = new LinkedHashSet<>();
        for (ZipLockerScreenRouter.ResultState state
                : ZipLockerScreenRouter.ResultState.values()) {
            ZipScreenAsset asset = ZipLockerScreenRouter.assetFor(state);
            assertEquals(expectedId++, asset.id());
            assertTrue(unique.add(asset));
        }
        assertEquals(27, expectedId);

        assertEquals(ZipLockerScreenRouter.ResultActions.NONE,
                ZipLockerScreenRouter.actionsFor(
                        ZipLockerScreenRouter.ResultState.VALIDATING));
        assertEquals(ZipLockerScreenRouter.ResultActions.NONE,
                ZipLockerScreenRouter.actionsFor(
                        ZipLockerScreenRouter.ResultState.CONNECTING));
        assertEquals(ZipLockerScreenRouter.ResultActions.NONE,
                ZipLockerScreenRouter.actionsFor(
                        ZipLockerScreenRouter.ResultState.WAITING_RESPONSE));
        assertEquals(ZipLockerScreenRouter.ResultActions.HOME,
                ZipLockerScreenRouter.actionsFor(
                        ZipLockerScreenRouter.ResultState.SUCCESS));
        for (ZipLockerScreenRouter.ResultState state : new ZipLockerScreenRouter.ResultState[] {
                ZipLockerScreenRouter.ResultState.BOARD_REJECTED,
                ZipLockerScreenRouter.ResultState.DEVICE_CONNECTION_FAILED,
                ZipLockerScreenRouter.ResultState.SEND_FAILED,
                ZipLockerScreenRouter.ResultState.COMMUNICATION_TIMEOUT}) {
            assertEquals(ZipLockerScreenRouter.ResultActions.RETRY_AND_HOME,
                    ZipLockerScreenRouter.actionsFor(state));
        }

        expectIllegalArgument(() -> ZipLockerScreenRouter.assetFor(
                (ZipLockerScreenRouter.ResultState) null));
        expectIllegalArgument(() -> ZipLockerScreenRouter.actionsFor(null));
    }

    @Test
    public void customerCopyUsesExplicitServerLabelWithoutInventingProtocolAddressing() {
        String label = "北侧泳池-07";
        assertEquals("正在验证权限，请稍候…",
                ZipLockerScreenRouter.messageFor(
                        ZipLockerScreenRouter.ResultState.VALIDATING, null));
        assertEquals("正在连接设备，请稍候…",
                ZipLockerScreenRouter.messageFor(
                        ZipLockerScreenRouter.ResultState.CONNECTING, null));
        assertEquals("正在等待锁板返回，请勿重复操作…",
                ZipLockerScreenRouter.messageFor(
                        ZipLockerScreenRouter.ResultState.WAITING_RESPONSE, null));
        assertEquals(label + "号柜门已打开，请存放物品后关闭柜门。",
                ZipLockerScreenRouter.messageFor(
                        ZipLockerScreenRouter.ResultState.SUCCESS, label));
        assertEquals(label + "号柜门开启失败，请再次尝试。",
                ZipLockerScreenRouter.messageFor(
                        ZipLockerScreenRouter.ResultState.BOARD_REJECTED, label));
        assertEquals("设备连接失败，请联系管理员。",
                ZipLockerScreenRouter.messageFor(
                        ZipLockerScreenRouter.ResultState.DEVICE_CONNECTION_FAILED, null));
        assertEquals("开柜指令发送失败，请再次尝试。",
                ZipLockerScreenRouter.messageFor(
                        ZipLockerScreenRouter.ResultState.SEND_FAILED, null));
        assertEquals("设备无响应，请再次尝试。",
                ZipLockerScreenRouter.messageFor(
                        ZipLockerScreenRouter.ResultState.COMMUNICATION_TIMEOUT, null));
        expectIllegalArgument(() -> ZipLockerScreenRouter.messageFor(
                ZipLockerScreenRouter.ResultState.SUCCESS, "  "));
        expectIllegalArgument(() -> ZipLockerScreenRouter.messageFor(null, label));
    }

    @Test
    public void resultOverlayUsesPixelAssetsAndHasNoInFlightReturnTarget() throws IOException {
        String source = read("app/src/main/java/com/codex/lockertest/ui/ResultOverlay.java");

        assertContains(source,
                "new ZipPixelShell(context, ZipScreenAsset.UNLOCK_VALIDATING)");
        assertContains(source, "pixelShell.setScreenAsset(ZipLockerScreenRouter.assetFor(state))");
        assertContains(source, "ZipLockerScreenRouter.actionsFor(state)");
        assertContains(source, "pixelShell.setOnSafeHomeRequested(actions == ResultActions.NONE");
        assertContains(source, "? null : this::returnHome)");
        assertFalse(source.contains("setPromptDimmed("));
        assertFalse(source.contains("removeAllViews()"));
        assertFalse(source.contains("UnlockCoordinator"));
        assertFalse(source.contains("SerialGateway"));
        assertFalse(source.contains("LockerProtocol"));
        assertFalse(source.contains("message.contains("));
        assertFalse(source.contains("title.contains("));
        assertContains(source, "SUCCESS_RETURN_MILLIS = 3_000L");
        assertContains(source, "showLockerSuccess(String displayLabel)");
        assertContains(source, "showLockerFailure(String displayLabel)");
    }

    @Test
    public void pixelPromptOwnsDynamicTextAndExactOneShotActionsWithoutLegacyStacking()
            throws IOException {
        String source = read("app/src/main/java/com/codex/lockertest/ui/ZipPromptOverlay.java");

        assertContains(source, "public static ZipPromptOverlay pixelPrompt(");
        assertContains(source, "place(prompt, title, 440, 335, 400, 48)");
        assertContains(source, "place(prompt, detail, 430, 386, 420, 40)");
        assertContains(source, "place(prompt, supporting, 430, 422, 420, 35)");
        assertContains(source, "place(prompt, actionCover, 430, 466, 420, 118)");
        assertContains(source, "place(prompt, retry, 470, 480, 150, 46)");
        assertContains(source, "place(prompt, home, 660, 480, 150, 46)");
        assertContains(source, "place(prompt, home, 565, 480, 150, 46)");
        assertContains(source, "if (actionDelivered)");
        assertContains(source, "actionDelivered = true");
        assertContains(source, "ResultActions.NONE");
        assertContains(source, "retry.setVisibility(GONE)");
        assertContains(source, "home.setVisibility(GONE)");
    }

    @Test
    public void mainMapsCoordinatorCategoriesExactlyAndSeparatesLabelFromTarget()
            throws IOException {
        String source = read("app/src/main/java/com/codex/lockertest/MainActivity.java");
        String submit = slice(source,
                "private void submitLocker(",
                "private void enqueueDiscoveryCompleted(");
        String render = slice(source,
                "private void renderUnlockState(",
                "private void reserveCustomerOperation(");

        assertContains(submit, "LockerTarget selectedTarget = target");
        assertContains(submit, "samePhysicalTarget(selectedTarget, request.target())");
        assertTrue(submit.indexOf("flow.confirmLockerAt(target, confirmationEpochMillis)",
                submit.indexOf("customerUnlockAuthorizer.authorize("))
                < submit.indexOf("unlockCoordinator.startAuthorized("));
        assertContains(submit, "unlockCoordinator.startAuthorized(");
        assertContains(submit, "selectedTarget);");
        assertFalse(submit.contains("unlockCoordinator.start(\n                selectedLockerLabel"));

        assertContains(render, "String selectedLockerLabel = flow.pendingLockerLabel()");
        assertContains(render, "resultOverlay.showLockerSuccess(selectedLockerLabel)");
        assertContains(render, "selectedLockerFailure(selectedTarget).equals(detail)");
        assertContains(render, "resultOverlay.showLockerFailure(selectedLockerLabel)");
        assertContains(render, "resultOverlay.showSendFailure()");
        assertContains(render, "resultOverlay.showTimeout()");
        assertContains(render, "resultOverlay.showDeviceConnectionFailure()");
        assertContains(render, "case CONNECTING:");
        assertContains(render, "case QUIETING:");
        assertContains(render, "case WAITING_ACK:");
        assertContains(render, "flow.finishSuccessfulRequest()");
        assertContains(render, "flow.finishUnlockFailure()");
    }

    private static String read(String path) throws IOException {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    private static String slice(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from);
        assertTrue("missing start marker " + start, from >= 0);
        assertTrue("missing end marker " + end, to > from);
        return source.substring(from, to);
    }

    private static void assertContains(String source, String token) {
        assertTrue("missing token: " + token, source.contains(token));
    }

    private static void expectIllegalArgument(Runnable action) {
        try {
            action.run();
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected fail-closed boundary.
        }
    }
}
