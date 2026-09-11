package com.codex.lockertest.ui;

import com.codex.lockertest.bootstrap.BaseSettingSnapshot;
import com.codex.lockertest.bootstrap.BasicDataSnapshot;
import com.codex.lockertest.bootstrap.BootstrapSnapshot;
import com.codex.lockertest.bootstrap.DeviceRegistration;

import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class BootstrapHomePresentationTest {
    @Test
    public void mappedBootstrapFailuresStayClosedAndExposeOnlySafeRecoveryGuidance()
            throws Exception {
        BootstrapReadinessMapper mapper = new BootstrapReadinessMapper();
        for (BootstrapSnapshot.FailureReason reason : BootstrapSnapshot.FailureReason.values()) {
            BootstrapSnapshot snapshot = blocked(reason);
            BootstrapHomePresentation presentation = BootstrapHomePresentation.createForOnlineBrowsing(
                    mapper.map(snapshot), snapshot, true);
            assertFalse(reason.name(), presentation.customerActionsEnabled());
            assertTrue(reason.name(), presentation.adminAvailable());
            assertFalse(reason.name(), presentation.serverCapabilitiesKnown());
            assertTrue(presentation.recognitionTypes().isEmpty());
            String exposed = presentation.venueName() + presentation.usageText()
                    + presentation.statusMessage();
            assertFalse(exposed.contains("RK3288-RAW-SERIAL"));
            assertFalse(exposed.contains("sysCode"));
            assertFalse(exposed.contains("sign="));
            assertFalse(exposed.contains("https://"));
            assertFalse(exposed.contains("private.example"));
            assertTrue(presentation.statusMessage().length() <= 120);
        }
    }

    @Test
    public void connectionFailuresKeepRetryAndAdminWhileShowingTheActualFailure()
            throws Exception {
        BootstrapSnapshot.FailureReason[] reasons = {
                BootstrapSnapshot.FailureReason.TIMEOUT,
                BootstrapSnapshot.FailureReason.NETWORK,
                BootstrapSnapshot.FailureReason.HTTP
        };
        String[] messages = {
                "服务器响应超时，请重试",
                "服务器连接失败，请检查网络后重试",
                "服务器暂不可用，请稍后重试"
        };
        BootstrapReadinessMapper mapper = new BootstrapReadinessMapper();
        for (int index = 0; index < reasons.length; index++) {
            BootstrapSnapshot snapshot = blocked(reasons[index]);
            BootstrapHomePresentation presentation = BootstrapHomePresentation.create(
                    mapper.map(snapshot), snapshot);
            assertEquals(messages[index], presentation.statusMessage());
            assertTrue(presentation.retryAvailable());
            assertTrue(presentation.adminAvailable());
            assertFalse(presentation.customerActionsEnabled());
        }
    }

    @Test
    public void onlineBrowsingEnablesHomeOnlyWithoutOpeningLegacySerialBoundary() throws Exception {
        TerminalReadiness readiness = TerminalReadiness.readyReadOnly();
        BootstrapHomePresentation home = BootstrapHomePresentation.createForOnlineBrowsing(
                readiness, readySnapshot(), true);
        assertTrue(home.customerActionsEnabled());
        assertFalse(readiness.customerActionsEnabled());
        assertFalse(new CustomerActionBoundary(readiness).run(
                CustomerActionBoundary.Effect.SERIAL_SEND,
                () -> { throw new AssertionError("Online home must not enable serial"); }));
        assertEquals("空闲柜：12　总数：32", home.usageText());
    }

    @Test
    public void onlineBrowsingRequiresBothReadyBootstrapAndConfiguredService() throws Exception {
        assertFalse(BootstrapHomePresentation.createForOnlineBrowsing(
                TerminalReadiness.readyReadOnly(), readySnapshot(), false)
                .customerActionsEnabled());
        assertFalse(BootstrapHomePresentation.createForOnlineBrowsing(
                TerminalReadiness.networkUnavailable(), readySnapshot(), true)
                .customerActionsEnabled());
        assertFalse(BootstrapHomePresentation.createForOnlineBrowsing(
                TerminalReadiness.readyReadOnly(), blocked(BootstrapSnapshot.FailureReason.NETWORK), true)
                .customerActionsEnabled());
    }

    @Test
    public void onlineBrowsingDoesNotChangeLocalDemoAvailability() throws Exception {
        assertTrue(BootstrapHomePresentation.createForOnlineBrowsing(
                TerminalReadiness.localDemoReady(), blocked(BootstrapSnapshot.FailureReason.NETWORK), false)
                .customerActionsEnabled());
    }

    @Test
    public void readyServerSnapshotShowsOnlySafeVenueAndLockerCounts() throws Exception {
        BootstrapHomePresentation presentation = BootstrapHomePresentation.create(
                TerminalReadiness.readyReadOnly(), readySnapshot());

        assertEquals("VENUE", presentation.venueName());
        // Venue has its own line; counts must fit the existing narrow information panel.
        assertEquals("空闲柜：12　总数：32", presentation.usageText());
        assertEquals("服务器基础配置已读取，客户功能仍未开放",
                presentation.statusMessage());
        assertFalse(presentation.customerActionsEnabled());
        assertFalse(presentation.retryAvailable());
        assertTrue(presentation.adminAvailable());
        assertTrue(presentation.serverCapabilitiesKnown());
        assertEquals(Collections.singletonList("1"), presentation.recognitionTypes());

        String exposed = presentation.venueName() + presentation.usageText()
                + presentation.statusMessage();
        assertFalse(exposed.contains("RK3288-RAW-SERIAL"));
        assertFalse(exposed.contains("https://"));
        assertFalse(exposed.contains("sysCode"));
        assertFalse(exposed.contains("authorization"));
        assertFalse(exposed.contains("logo-secret"));
    }

    @Test
    public void readyServerSnapshotProjectsWarmAndReturnTipsAsSeparateSafeCopy()
            throws Exception {
        BootstrapHomePresentation presentation = BootstrapHomePresentation.create(
                TerminalReadiness.readyReadOnly(), readySnapshot(
                        Collections.singletonList("1"),
                        "使用说明第一行\n使用说明第二行",
                        "还柜前请确认物品已取出",
                        "温馨提示第一行\n温馨提示第二行\u0000PRIVATE"));

        assertEquals("温馨提示第一行\n温馨提示第二行PRIVATE",
                presentation.warmTipsText());
        assertEquals("还柜前请确认物品已取出",
                presentation.returnTipsText());
        assertFalse(presentation.warmTipsText().contains("使用说明"));
    }

    @Test
    public void missingOptionalServerTipsUseNoticeThenCustomerSafeDefaults()
            throws Exception {
        BootstrapHomePresentation useNotice = BootstrapHomePresentation.create(
                TerminalReadiness.readyReadOnly(), readySnapshot(
                        Collections.singletonList("1"),
                        "先刷手环，再按页面提示操作", "", "  "));
        assertEquals("先刷手环，再按页面提示操作",
                useNotice.warmTipsText());
        assertEquals("先刷手环，再按页面提示操作",
                useNotice.returnTipsText());

        BootstrapHomePresentation defaults = BootstrapHomePresentation.create(
                TerminalReadiness.localDemoReady(),
                blocked(BootstrapSnapshot.FailureReason.NETWORK));
        assertEquals("请直接刷手环或扫描二维码，按页面提示完成操作。",
                defaults.warmTipsText());
        assertEquals("请验证身份后选择要归还的柜门，按页面提示完成还柜。",
                defaults.returnTipsText());
    }

    @Test
    public void nonReadySnapshotDoesNotClaimServerCapabilities() throws Exception {
        BootstrapHomePresentation presentation = BootstrapHomePresentation.create(
                TerminalReadiness.localDemoReady(),
                blocked(BootstrapSnapshot.FailureReason.NETWORK));

        assertFalse(presentation.serverCapabilitiesKnown());
        assertTrue(presentation.recognitionTypes().isEmpty());
    }

    @Test
    public void serverRecognitionTypesAreCopiedIntoAnImmutablePresentation() throws Exception {
        ArrayList<String> mutableTypes = new ArrayList<>(Arrays.asList("3", "5"));
        BootstrapHomePresentation presentation = BootstrapHomePresentation.create(
                TerminalReadiness.readyReadOnly(), readySnapshot(mutableTypes));

        mutableTypes.clear();
        assertEquals(Arrays.asList("3", "5"), presentation.recognitionTypes());
        try {
            presentation.recognitionTypes().add("1");
            throw new AssertionError("recognition types must be immutable");
        } catch (UnsupportedOperationException expected) {
            // Expected: rendering permissions cannot drift after projection.
        }
    }

    @Test
    public void everyProductionStateKeepsCustomerControlsClosedAndAdminAvailable()
            throws Exception {
        TerminalReadiness[] states = {
                TerminalReadiness.readyReadOnly(),
                TerminalReadiness.serverConnecting(),
                TerminalReadiness.serverNotConfigured(),
                TerminalReadiness.serverContractUnapproved(),
                TerminalReadiness.deviceSerialUnavailable(),
                TerminalReadiness.deviceNotRegistered(),
                TerminalReadiness.serverRequestRejected(),
                TerminalReadiness.deviceClockInvalid(),
                TerminalReadiness.serverTimeout(),
                TerminalReadiness.networkUnavailable(),
                TerminalReadiness.serverUnavailable(),
                TerminalReadiness.serverSecurityError(),
                TerminalReadiness.serverResponseInvalid(),
                TerminalReadiness.bootstrapStopped()
        };
        for (TerminalReadiness state : states) {
            BootstrapHomePresentation presentation = BootstrapHomePresentation.create(
                    state, blocked(BootstrapSnapshot.FailureReason.NETWORK));
            assertFalse(state.state().name(), presentation.customerActionsEnabled());
            assertTrue(state.state().name(), presentation.adminAvailable());
            assertFalse(presentation.statusMessage().contains("RK3288-RAW-SERIAL"));
        }
    }

    @Test
    public void retryAppearsOnlyForRecoverableRuntimeFailures() throws Exception {
        for (BootstrapSnapshot.FailureReason reason : new BootstrapSnapshot.FailureReason[] {
                BootstrapSnapshot.FailureReason.SERIAL_UNAVAILABLE,
                BootstrapSnapshot.FailureReason.CLOCK_INVALID,
                BootstrapSnapshot.FailureReason.TIMEOUT,
                BootstrapSnapshot.FailureReason.NETWORK,
                BootstrapSnapshot.FailureReason.TLS,
                BootstrapSnapshot.FailureReason.REDIRECT,
                BootstrapSnapshot.FailureReason.HTTP,
                BootstrapSnapshot.FailureReason.INVALID_RESPONSE
        }) {
            assertTrue(reason.name(), BootstrapHomePresentation.create(
                    TerminalReadiness.networkUnavailable(), blocked(reason))
                    .retryAvailable());
        }
        for (BootstrapSnapshot.FailureReason reason : new BootstrapSnapshot.FailureReason[] {
                BootstrapSnapshot.FailureReason.CONFIGURATION,
                BootstrapSnapshot.FailureReason.KEY_MISSING,
                BootstrapSnapshot.FailureReason.REMOTE_REJECTED,
                BootstrapSnapshot.FailureReason.CONTRACT
        }) {
            assertFalse(reason.name(), BootstrapHomePresentation.create(
                    TerminalReadiness.serverNotConfigured(), blocked(reason))
                    .retryAvailable());
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void missingReadinessIsRejected() throws Exception {
        BootstrapHomePresentation.create(null, blocked(
                BootstrapSnapshot.FailureReason.NETWORK));
    }

    private static BootstrapSnapshot readySnapshot() throws Exception {
        return readySnapshot(Collections.singletonList("1"));
    }

    private static BootstrapSnapshot readySnapshot(java.util.List<String> recognitionTypes)
            throws Exception {
        return readySnapshot(recognitionTypes, "", "", "");
    }

    private static BootstrapSnapshot readySnapshot(
            java.util.List<String> recognitionTypes,
            String useNotice,
            String returnNotice,
            String cozyTips) throws Exception {
        DeviceRegistration registration = construct(
                DeviceRegistration.class,
                new Class<?>[] {String.class, String.class},
                new Object[] {"M", "D"});
        BaseSettingSnapshot base = construct(
                BaseSettingSnapshot.class,
                new Class<?>[] {String.class, String.class, java.util.List.class,
                        String.class, String.class, String.class, String.class,
                        String.class, int.class, java.util.List.class, String.class,
                        String.class, java.util.List.class, String.class},
                new Object[] {"VENUE", "1", recognitionTypes,
                        useNotice, returnNotice, "logo-secret", "COMPANY", cozyTips, 0,
                        Collections.emptyList(), "https://hidden", "",
                        Collections.emptyList(), "1"});
        BasicDataSnapshot basic = construct(
                BasicDataSnapshot.class,
                new Class<?>[] {int.class, int.class, String.class},
                new Object[] {32, 12, "hidden-code"});
        Method ready = BootstrapSnapshot.class.getDeclaredMethod(
                "ready", long.class, String.class, DeviceRegistration.class,
                BaseSettingSnapshot.class, BasicDataSnapshot.class);
        ready.setAccessible(true);
        return (BootstrapSnapshot) ready.invoke(
                null, 4L, "RK3288-RAW-SERIAL", registration, base, basic);
    }

    private static BootstrapSnapshot blocked(BootstrapSnapshot.FailureReason reason)
            throws Exception {
        Method state = BootstrapSnapshot.class.getDeclaredMethod(
                "state", long.class, BootstrapSnapshot.Phase.class,
                BootstrapSnapshot.Endpoint.class,
                BootstrapSnapshot.FailureReason.class, String.class);
        state.setAccessible(true);
        return (BootstrapSnapshot) state.invoke(null, 3L,
                BootstrapSnapshot.Phase.BLOCKED,
                BootstrapSnapshot.Endpoint.NONE, reason,
                "RK3288-RAW-SERIAL sysCode=PRIVATE sign=PRIVATE https://private.example/bootstrap");
    }

    private static <T> T construct(
            Class<T> type, Class<?>[] parameters, Object[] arguments) throws Exception {
        Constructor<T> constructor = type.getDeclaredConstructor(parameters);
        constructor.setAccessible(true);
        return constructor.newInstance(arguments);
    }

}
