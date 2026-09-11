package com.codex.lockertest.ui;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.codex.lockertest.face.FaceLicenseStateMachine;
import com.codex.lockertest.face.FaceLivenessControl;
import com.codex.lockertest.face.FaceRuntimeStateMachine;
import com.codex.lockertest.serial.SerialSessionState;
import com.codex.lockertest.ui.zip.ZipAdminScreenRouter;
import com.codex.lockertest.ui.zip.ZipScreenAsset;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumSet;
import org.junit.Test;

/** Pure-JVM contract for the complete v16 administrator route table. */
public final class ZipAdminScreenRoutingTest {
    private static final byte[] A1_COMMAND = new byte[] {
            (byte) 0x8A, 0x01, 0x01, 0x11, (byte) 0x9B
    };

    @Test
    public void everyAdministratorAssetFortyThroughFiftyFourIsReachableAndUnique() {
        EnumSet<ZipScreenAsset> assets = EnumSet.noneOf(ZipScreenAsset.class);
        add(assets, ZipAdminScreenRouter.assetForPin(
                ZipAdminScreenRouter.PinState.ENTRY), 40);
        add(assets, ZipAdminScreenRouter.assetForPin(
                ZipAdminScreenRouter.PinState.ERROR), 41);
        add(assets, ZipAdminScreenRouter.adminFunctions(), 42);

        add(assets, ZipAdminScreenRouter.assetForSerial(
                SerialSessionState.Phase.CLOSED,
                ZipAdminScreenRouter.SerialEvent.NONE), 43);
        add(assets, ZipAdminScreenRouter.assetForSerial(
                SerialSessionState.Phase.OPENING,
                ZipAdminScreenRouter.SerialEvent.NONE), 44);
        add(assets, ZipAdminScreenRouter.assetForSerial(
                SerialSessionState.Phase.OPEN,
                ZipAdminScreenRouter.SerialEvent.NONE), 45);
        add(assets, ZipAdminScreenRouter.assetForSerial(
                SerialSessionState.Phase.OPEN,
                ZipAdminScreenRouter.SerialEvent.MATCHER_SUCCESS), 46);
        add(assets, ZipAdminScreenRouter.assetForSerial(
                SerialSessionState.Phase.OPEN,
                ZipAdminScreenRouter.SerialEvent.MATCHER_FAILURE), 47);

        add(assets, face(FaceLicenseStateMachine.State.CHECKING_LOCAL,
                FaceRuntimeStateMachine.State.UNINITIALIZED,
                FaceLivenessControl.Capability.WAITING_FOR_LICENSE,
                ZipAdminScreenRouter.LicenseFailureKind.NONE, false), 48);
        add(assets, face(FaceLicenseStateMachine.State.INVALID,
                FaceRuntimeStateMachine.State.UNINITIALIZED,
                FaceLivenessControl.Capability.WAITING_FOR_LICENSE,
                ZipAdminScreenRouter.LicenseFailureKind.INVALID, false), 49);
        add(assets, face(FaceLicenseStateMachine.State.ACTIVATING_ONLINE,
                FaceRuntimeStateMachine.State.UNINITIALIZED,
                FaceLivenessControl.Capability.WAITING_FOR_LICENSE,
                ZipAdminScreenRouter.LicenseFailureKind.NONE, true), 50);
        add(assets, face(FaceLicenseStateMachine.State.READY,
                FaceRuntimeStateMachine.State.UNINITIALIZED,
                FaceLivenessControl.Capability.WAITING_FOR_LICENSE,
                ZipAdminScreenRouter.LicenseFailureKind.NONE, false), 51);
        add(assets, face(FaceLicenseStateMachine.State.READY,
                FaceRuntimeStateMachine.State.INITIALIZING,
                FaceLivenessControl.Capability.PROBING,
                ZipAdminScreenRouter.LicenseFailureKind.NONE, false), 52);
        add(assets, face(FaceLicenseStateMachine.State.READY,
                FaceRuntimeStateMachine.State.READY,
                FaceLivenessControl.Capability.SUPPORTED,
                ZipAdminScreenRouter.LicenseFailureKind.NONE, false), 53);
        add(assets, face(FaceLicenseStateMachine.State.READY,
                FaceRuntimeStateMachine.State.FAILED,
                FaceLivenessControl.Capability.FAILED,
                ZipAdminScreenRouter.LicenseFailureKind.NONE, false), 54);

        assertEquals(15, assets.size());
    }

    @Test
    public void serialRoutesOnlyCorrelatedMatcherSuccessToFortySix() {
        assertEquals(ZipScreenAsset.ADMIN_SERIAL_CONNECTED,
                ZipAdminScreenRouter.assetForSerial(
                        SerialSessionState.Phase.OPEN,
                        ZipAdminScreenRouter.SerialEvent.WRITE_ACCEPTED));
        assertEquals(ZipScreenAsset.ADMIN_SERIAL_SUCCESS,
                ZipAdminScreenRouter.assetForSerial(
                        SerialSessionState.Phase.OPEN,
                        ZipAdminScreenRouter.SerialEvent.MATCHER_SUCCESS));
        assertEquals(ZipScreenAsset.ADMIN_SERIAL_FAILURE,
                ZipAdminScreenRouter.assetForSerial(
                        SerialSessionState.Phase.OPEN,
                        ZipAdminScreenRouter.SerialEvent.MATCHER_FAILURE));
        assertEquals(ZipScreenAsset.ADMIN_SERIAL_FAILURE,
                ZipAdminScreenRouter.assetForSerial(
                        SerialSessionState.Phase.CLOSED,
                        ZipAdminScreenRouter.SerialEvent.TRANSIENT_FAILURE));
        assertEquals(ZipScreenAsset.ADMIN_SERIAL_OPENING,
                ZipAdminScreenRouter.assetForSerial(
                        SerialSessionState.Phase.CLOSING,
                        ZipAdminScreenRouter.SerialEvent.NONE));
    }

    @Test
    public void serialRouterRejectsEventsThatCannotExistInTheTypedPhase() {
        expectIllegal(() -> ZipAdminScreenRouter.assetForSerial(
                SerialSessionState.Phase.CLOSED,
                ZipAdminScreenRouter.SerialEvent.WRITE_ACCEPTED));
        expectIllegal(() -> ZipAdminScreenRouter.assetForSerial(
                SerialSessionState.Phase.OPENING,
                ZipAdminScreenRouter.SerialEvent.WRITE_ACCEPTED));
        expectIllegal(() -> ZipAdminScreenRouter.assetForSerial(
                SerialSessionState.Phase.CLOSING,
                ZipAdminScreenRouter.SerialEvent.MATCHER_FAILURE));
        expectIllegal(() -> ZipAdminScreenRouter.assetForSerial(
                SerialSessionState.Phase.DISPOSED,
                ZipAdminScreenRouter.SerialEvent.MATCHER_SUCCESS));
    }

    @Test
    public void typedActionAvailabilityNeverCreatesAnInvisibleShellExit() {
        ZipAdminScreenRouter.ActionAvailability disconnected =
                ZipAdminScreenRouter.actionsForSerial(
                        SerialSessionState.Phase.CLOSED,
                        ZipAdminScreenRouter.SerialEvent.NONE);
        assertTrue(disconnected.nativeBackVisible());
        assertFalse(disconnected.retryVisible());
        assertFalse(disconnected.promptBackVisible());

        ZipAdminScreenRouter.ActionAvailability opening =
                ZipAdminScreenRouter.actionsForSerial(
                        SerialSessionState.Phase.OPENING,
                        ZipAdminScreenRouter.SerialEvent.NONE);
        assertFalse(opening.nativeBackVisible());
        assertFalse(opening.retryVisible());
        assertFalse(opening.promptBackVisible());

        ZipAdminScreenRouter.ActionAvailability serialFailure =
                ZipAdminScreenRouter.actionsForSerial(
                        SerialSessionState.Phase.OPEN,
                        ZipAdminScreenRouter.SerialEvent.MATCHER_FAILURE);
        assertFalse(serialFailure.nativeBackVisible());
        assertFalse(serialFailure.retryVisible());
        assertTrue(serialFailure.promptBackVisible());

        assertTrue(ZipAdminScreenRouter.actionsForFaceAsset(
                ZipScreenAsset.FACE_SDK_CHECKING_LICENSE, false).nativeBackVisible());
        assertFalse(ZipAdminScreenRouter.actionsForFaceAsset(
                ZipScreenAsset.FACE_SDK_ACTIVATING, false).nativeBackVisible());
        assertFalse(ZipAdminScreenRouter.actionsForFaceAsset(
                ZipScreenAsset.FACE_SDK_LICENSED, false).nativeBackVisible());
        assertFalse(ZipAdminScreenRouter.actionsForFaceAsset(
                ZipScreenAsset.FACE_SDK_INITIALIZING, false).nativeBackVisible());
        ZipAdminScreenRouter.ActionAvailability retryableError =
                ZipAdminScreenRouter.actionsForFaceAsset(
                        ZipScreenAsset.FACE_SDK_ERROR, true);
        assertFalse(retryableError.nativeBackVisible());
        assertTrue(retryableError.retryVisible());
        assertTrue(retryableError.promptBackVisible());
        assertFalse(ZipAdminScreenRouter.actionsForFaceAsset(
                ZipScreenAsset.FACE_SDK_ERROR, false).retryVisible());
    }

    @Test
    public void faceNativeActionMatrixHasNoDuplicateOrHiddenHotZones() {
        assertFaceActions(ZipScreenAsset.FACE_SDK_CHECKING_LICENSE,
                true, false, false);
        assertFaceActions(ZipScreenAsset.FACE_SDK_AWAITING_ACTIVATION,
                true, false, false);
        assertFaceActions(ZipScreenAsset.FACE_SDK_ACTIVATING,
                false, false, false);
        assertFaceActions(ZipScreenAsset.FACE_SDK_LICENSED,
                false, false, false);
        assertFaceActions(ZipScreenAsset.FACE_SDK_INITIALIZING,
                false, false, false);
        assertFaceActions(ZipScreenAsset.FACE_SDK_READY,
                true, false, false);
        ZipAdminScreenRouter.ActionAvailability error =
                ZipAdminScreenRouter.actionsForFaceAsset(
                        ZipScreenAsset.FACE_SDK_ERROR, true);
        assertFalse(error.nativeBackVisible());
        assertTrue(error.retryVisible());
        assertTrue(error.promptBackVisible());
    }

    @Test
    public void requestGateRequiresOneAcceptedExactCurrentRequest() {
        ZipAdminScreenRouter.SerialRequestGate<Object> gate =
                new ZipAdminScreenRouter.SerialRequestGate<Object>();
        Object lease = new Object();
        Object staleLease = new Object();
        ZipAdminScreenRouter.SerialRequestGate.Request<Object> request = gate.begin(
                7L, lease,
                ZipAdminScreenRouter.SerialRequestGate.Kind.A1_TEST,
                A1_COMMAND);
        assertTrue(request != null);
        assertNull(gate.begin(7L, lease,
                ZipAdminScreenRouter.SerialRequestGate.Kind.RAW_HEX,
                new byte[] {0x01}));
        assertEquals(7L, request.generation());
        assertTrue(request.lease() == lease);
        assertEquals(ZipAdminScreenRouter.SerialRequestGate.Kind.A1_TEST,
                request.kind());
        assertArrayEquals(A1_COMMAND, request.command());

        assertFalse(gate.accept(request, 6L, lease, request.kind(), A1_COMMAND));
        assertFalse(gate.accept(request, 7L, staleLease, request.kind(), A1_COMMAND));
        assertFalse(gate.accept(request, 7L, lease, request.kind(),
                new byte[] {(byte) 0x8A, 0x01, 0x02, 0x11, (byte) 0x98}));
        assertTrue(gate.accept(request, 7L, lease, request.kind(), A1_COMMAND));

        assertEquals(ZipAdminScreenRouter.SerialRequestGate.Completion.IGNORED,
                gate.onMatcher(request, 7L, staleLease, request.kind(), A1_COMMAND,
                        ZipAdminScreenRouter.SerialRequestGate.MatcherEvent.SUCCESS));
        assertEquals(ZipAdminScreenRouter.SerialRequestGate.Completion.IGNORED,
                gate.onMatcher(request, 7L, lease, request.kind(),
                        new byte[] {(byte) 0x8A},
                        ZipAdminScreenRouter.SerialRequestGate.MatcherEvent.SUCCESS));
        assertEquals(ZipAdminScreenRouter.SerialRequestGate.Completion.MATCHER_SUCCESS,
                gate.onMatcher(request, 7L, lease, request.kind(), A1_COMMAND,
                        ZipAdminScreenRouter.SerialRequestGate.MatcherEvent.SUCCESS));
        assertFalse(gate.hasPending());
    }

    @Test
    public void requestGateSeparatesWriterAcceptanceFailureAndTimeout() {
        ZipAdminScreenRouter.SerialRequestGate<Object> gate =
                new ZipAdminScreenRouter.SerialRequestGate<Object>();
        Object lease = new Object();
        ZipAdminScreenRouter.SerialRequestGate.Request<Object> raw = gate.begin(
                11L, lease, ZipAdminScreenRouter.SerialRequestGate.Kind.RAW_HEX,
                new byte[] {0x01, 0x02});
        assertTrue(gate.accept(raw, 11L, lease, raw.kind(), raw.command()));
        assertEquals(ZipAdminScreenRouter.SerialRequestGate.Completion.WRITE_COMPLETED,
                gate.onWritten(raw, 11L, lease, raw.kind(), raw.command()));
        assertFalse(gate.hasPending());

        ZipAdminScreenRouter.SerialRequestGate.Request<Object> failed = gate.begin(
                12L, lease, ZipAdminScreenRouter.SerialRequestGate.Kind.A1_TEST,
                A1_COMMAND);
        assertTrue(gate.accept(failed, 12L, lease, failed.kind(), A1_COMMAND));
        assertEquals(ZipAdminScreenRouter.SerialRequestGate.Completion.TRANSIENT_FAILURE,
                gate.onSendFailed(failed, 12L, lease, failed.kind(), A1_COMMAND));

        ZipAdminScreenRouter.SerialRequestGate.Request<Object> timeout = gate.begin(
                13L, lease, ZipAdminScreenRouter.SerialRequestGate.Kind.A1_TEST,
                A1_COMMAND);
        assertTrue(gate.accept(timeout, 13L, lease, timeout.kind(), A1_COMMAND));
        assertEquals(ZipAdminScreenRouter.SerialRequestGate.Completion.IGNORED,
                gate.onTimeout(timeout, 12L, lease, timeout.kind(), A1_COMMAND));
        assertEquals(ZipAdminScreenRouter.SerialRequestGate.Completion.TIMEOUT,
                gate.onTimeout(timeout, 13L, lease, timeout.kind(), A1_COMMAND));
        assertEquals(3000L, ZipAdminScreenRouter.SerialRequestGate.TIMEOUT_MILLIS);
    }

    @Test
    public void exactA1InputCanNeverCreateARawRequestOrSeedADelayedFrame() {
        byte[] genericRaw = new byte[] {0x01, 0x02, 0x03};
        assertEquals(ZipAdminScreenRouter.SerialRequestGate.Kind.A1_TEST,
                ZipAdminScreenRouter.classifySerialCommand(A1_COMMAND));
        assertEquals(ZipAdminScreenRouter.SerialRequestGate.Kind.RAW_HEX,
                ZipAdminScreenRouter.classifySerialCommand(genericRaw));

        ZipAdminScreenRouter.SerialRequestGate<Object> gate =
                new ZipAdminScreenRouter.SerialRequestGate<Object>();
        Object lease = new Object();
        expectIllegal(() -> gate.begin(20L, lease,
                ZipAdminScreenRouter.SerialRequestGate.Kind.RAW_HEX, A1_COMMAND));

        ZipAdminScreenRouter.SerialRequestGate.Request<Object> raw = gate.begin(
                20L, lease, ZipAdminScreenRouter.SerialRequestGate.Kind.RAW_HEX,
                genericRaw);
        assertTrue(gate.accept(raw, 20L, lease, raw.kind(), genericRaw));
        assertEquals(ZipAdminScreenRouter.SerialRequestGate.Completion.WRITE_COMPLETED,
                gate.onWritten(raw, 20L, lease, raw.kind(), genericRaw));

        ZipAdminScreenRouter.SerialRequestGate.Request<Object> typedA1 = gate.begin(
                20L, lease, ZipAdminScreenRouter.classifySerialCommand(A1_COMMAND),
                A1_COMMAND);
        assertEquals(ZipAdminScreenRouter.SerialRequestGate.Kind.A1_TEST,
                typedA1.kind());
        assertTrue(gate.accept(typedA1, 20L, lease, typedA1.kind(), A1_COMMAND));
        assertEquals(ZipAdminScreenRouter.SerialRequestGate.Completion.MATCHER_SUCCESS,
                gate.onMatcher(typedA1, 20L, lease, typedA1.kind(), A1_COMMAND,
                        ZipAdminScreenRouter.SerialRequestGate.MatcherEvent.SUCCESS));
    }

    @Test
    public void ambiguousA1TerminalBlocksLateByteAndImmediateRetryAcrossScreens() {
        ZipAdminScreenRouter.SerialA1RetryFence fence =
                new ZipAdminScreenRouter.SerialA1RetryFence();
        ZipAdminScreenRouter.SerialRequestGate<Object> firstScreen =
                new ZipAdminScreenRouter.SerialRequestGate<Object>();
        Object firstLease = new Object();
        ZipAdminScreenRouter.SerialRequestGate.Request<Object> timedOut =
                firstScreen.begin(31L, firstLease,
                        ZipAdminScreenRouter.SerialRequestGate.Kind.A1_TEST, A1_COMMAND);
        assertTrue(firstScreen.accept(timedOut, 31L, firstLease,
                timedOut.kind(), A1_COMMAND));
        assertEquals(ZipAdminScreenRouter.SerialRequestGate.Completion.TIMEOUT,
                firstScreen.onTimeout(timedOut, 31L, firstLease,
                        timedOut.kind(), A1_COMMAND));
        fence.onAmbiguousTerminal();
        assertFalse(fence.canBeginA1());
        assertEquals(ZipAdminScreenRouter.SerialRequestGate.Completion.IGNORED,
                firstScreen.onMatcher(timedOut, 31L, firstLease,
                        timedOut.kind(), A1_COMMAND,
                        ZipAdminScreenRouter.SerialRequestGate.MatcherEvent.SUCCESS));

        // A new Activity has a new request gate, but shares this process-session fence.
        ZipAdminScreenRouter.SerialRequestGate<Object> reenteredScreen =
                new ZipAdminScreenRouter.SerialRequestGate<Object>();
        assertFalse(fence.canBeginA1());
        assertFalse(fence.observe(SerialSessionState.Phase.OPEN));
        assertFalse(fence.observe(SerialSessionState.Phase.CLOSING));
        assertFalse(fence.observe(SerialSessionState.Phase.CLOSED));
        assertFalse(fence.observe(SerialSessionState.Phase.OPENING));
        assertTrue(fence.observe(SerialSessionState.Phase.OPEN));
        assertTrue(fence.canBeginA1());
        assertTrue(reenteredScreen.begin(32L, new Object(),
                ZipAdminScreenRouter.SerialRequestGate.Kind.A1_TEST,
                A1_COMMAND) != null);
    }

    @Test
    public void openDispatchRejectionUsesTypedPhaseInsteadOfPretendingBusy() {
        assertEquals(ZipAdminScreenRouter.OpenDispatchResult.OPEN_ACCEPTED,
                ZipAdminScreenRouter.classifyOpenDispatch(true,
                        SerialSessionState.Phase.OPENING));
        assertEquals(ZipAdminScreenRouter.OpenDispatchResult.BUSY,
                ZipAdminScreenRouter.classifyOpenDispatch(false,
                        SerialSessionState.Phase.OPENING));
        assertEquals(ZipAdminScreenRouter.OpenDispatchResult.BUSY,
                ZipAdminScreenRouter.classifyOpenDispatch(false,
                        SerialSessionState.Phase.CLOSING));
        assertEquals(ZipAdminScreenRouter.OpenDispatchResult.OPEN_REJECTED,
                ZipAdminScreenRouter.classifyOpenDispatch(false,
                        SerialSessionState.Phase.CLOSED));
        assertEquals(ZipAdminScreenRouter.OpenDispatchResult.OPEN_REJECTED,
                ZipAdminScreenRouter.classifyOpenDispatch(false,
                        SerialSessionState.Phase.DISPOSED));
    }

    @Test
    public void staleRequestIdCannotCompleteANewerAcceptedA1Request() {
        ZipAdminScreenRouter.SerialRequestGate<Object> gate =
                new ZipAdminScreenRouter.SerialRequestGate<Object>();
        Object lease = new Object();
        ZipAdminScreenRouter.SerialRequestGate.Request<Object> stale = gate.begin(
                21L, lease, ZipAdminScreenRouter.SerialRequestGate.Kind.A1_TEST,
                A1_COMMAND);
        assertTrue(gate.accept(stale, 21L, lease, stale.kind(), A1_COMMAND));
        gate.clear();

        ZipAdminScreenRouter.SerialRequestGate.Request<Object> current = gate.begin(
                21L, lease, ZipAdminScreenRouter.SerialRequestGate.Kind.A1_TEST,
                A1_COMMAND);
        assertTrue(current.requestId() > stale.requestId());
        assertTrue(gate.accept(current, 21L, lease, current.kind(), A1_COMMAND));
        assertEquals(ZipAdminScreenRouter.SerialRequestGate.Completion.IGNORED,
                gate.onMatcher(stale, 21L, lease, stale.kind(), A1_COMMAND,
                        ZipAdminScreenRouter.SerialRequestGate.MatcherEvent.SUCCESS));
        assertEquals(ZipAdminScreenRouter.SerialRequestGate.Completion.PENDING,
                gate.onWritten(current, 21L, lease, current.kind(), A1_COMMAND));
        assertEquals(ZipAdminScreenRouter.SerialRequestGate.Completion.MATCHER_FAILURE,
                gate.onMatcher(current, 21L, lease, current.kind(), A1_COMMAND,
                        ZipAdminScreenRouter.SerialRequestGate.MatcherEvent.FAILURE));
        assertFalse(gate.hasPending());
    }

    @Test
    public void optionalLivenessNeverTurnsCoreReadyIntoSdkError() {
        for (FaceLivenessControl.Capability capability
                : FaceLivenessControl.Capability.values()) {
            if (capability == FaceLivenessControl.Capability.WAITING_FOR_LICENSE) {
                expectIllegal(() -> face(FaceLicenseStateMachine.State.READY,
                        FaceRuntimeStateMachine.State.READY, capability,
                        ZipAdminScreenRouter.LicenseFailureKind.NONE, false));
                continue;
            }
            assertEquals(ZipScreenAsset.FACE_SDK_READY,
                    face(FaceLicenseStateMachine.State.READY,
                            FaceRuntimeStateMachine.State.READY, capability,
                            ZipAdminScreenRouter.LicenseFailureKind.NONE, false));
        }
        assertFalse(ZipAdminScreenRouter.isLivenessToggleEnabled(
                FaceLivenessControl.Capability.UNSUPPORTED));
        assertFalse(ZipAdminScreenRouter.isLivenessToggleEnabled(
                FaceLivenessControl.Capability.FAILED));
        assertTrue(ZipAdminScreenRouter.isLivenessToggleEnabled(
                FaceLivenessControl.Capability.SUPPORTED));
    }

    @Test
    public void faceRouterExhaustivelyAcceptsOnlyStateMachineCompatibleTuples() {
        for (FaceLicenseStateMachine.State license
                : FaceLicenseStateMachine.State.values()) {
            for (FaceRuntimeStateMachine.State runtime
                    : FaceRuntimeStateMachine.State.values()) {
                for (FaceLivenessControl.Capability liveness
                        : FaceLivenessControl.Capability.values()) {
                    for (ZipAdminScreenRouter.LicenseFailureKind failure
                            : ZipAdminScreenRouter.LicenseFailureKind.values()) {
                        for (boolean activating : new boolean[] {false, true}) {
                            ZipScreenAsset expected = expectedFaceAsset(
                                    license, runtime, liveness, failure, activating);
                            if (expected == null) {
                                expectIllegal(() -> face(license, runtime, liveness,
                                        failure, activating));
                            } else {
                                assertEquals(tuple(license, runtime, liveness,
                                                failure, activating),
                                        expected, face(license, runtime, liveness,
                                                failure, activating));
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    public void invalidOrRetryableLicenseNeedsActivationButCoreFailureUsesError() {
        assertEquals(ZipScreenAsset.FACE_SDK_AWAITING_ACTIVATION,
                face(FaceLicenseStateMachine.State.FAILED,
                        FaceRuntimeStateMachine.State.UNINITIALIZED,
                        FaceLivenessControl.Capability.WAITING_FOR_LICENSE,
                        ZipAdminScreenRouter.LicenseFailureKind.RETRYABLE, false));
        assertEquals(ZipScreenAsset.FACE_SDK_ERROR,
                face(FaceLicenseStateMachine.State.FAILED,
                        FaceRuntimeStateMachine.State.UNINITIALIZED,
                        FaceLivenessControl.Capability.WAITING_FOR_LICENSE,
                        ZipAdminScreenRouter.LicenseFailureKind.CORE, false));
    }

    @Test
    public void missingOrIllegalTypedStateFailsClosedAndRouterIsAndroidFree()
            throws Exception {
        expectIllegal(() -> ZipAdminScreenRouter.assetForPin(null));
        expectIllegal(() -> ZipAdminScreenRouter.assetForSerial(
                null, ZipAdminScreenRouter.SerialEvent.NONE));
        expectIllegal(() -> ZipAdminScreenRouter.assetForSerial(
                SerialSessionState.Phase.OPEN, null));
        expectIllegal(() -> face(null, FaceRuntimeStateMachine.State.UNINITIALIZED,
                FaceLivenessControl.Capability.WAITING_FOR_LICENSE,
                ZipAdminScreenRouter.LicenseFailureKind.NONE, false));
        expectIllegal(() -> face(FaceLicenseStateMachine.State.READY,
                FaceRuntimeStateMachine.State.READY, null,
                ZipAdminScreenRouter.LicenseFailureKind.NONE, false));
        expectIllegal(() -> face(FaceLicenseStateMachine.State.READY,
                FaceRuntimeStateMachine.State.READY,
                FaceLivenessControl.Capability.SUPPORTED,
                ZipAdminScreenRouter.LicenseFailureKind.RETRYABLE, false));

        String source = readRouter();
        assertFalse(source.contains("import android."));
        assertFalse(source.contains("android."));
        assertFalse(source.contains("startsWith("));
        assertTrue(source.contains("throw new IllegalArgumentException"));
    }

    @Test
    public void inFlightPagesExposeNoFakeCancelRole() {
        assertFalse(ZipScreenAsset.ADMIN_SERIAL_OPENING.actions().contains(
                com.codex.lockertest.ui.zip.ZipActionRole.CANCEL));
        assertFalse(ZipScreenAsset.FACE_SDK_ACTIVATING.actions().contains(
                com.codex.lockertest.ui.zip.ZipActionRole.CANCEL));
        assertFalse(ZipScreenAsset.FACE_SDK_INITIALIZING.actions().contains(
                com.codex.lockertest.ui.zip.ZipActionRole.CANCEL));
    }

    private static ZipScreenAsset face(FaceLicenseStateMachine.State license,
            FaceRuntimeStateMachine.State runtime,
            FaceLivenessControl.Capability liveness,
            ZipAdminScreenRouter.LicenseFailureKind failure,
            boolean activationInFlight) {
        return ZipAdminScreenRouter.assetForFace(
                license, runtime, liveness, failure, activationInFlight);
    }

    private static ZipScreenAsset expectedFaceAsset(
            FaceLicenseStateMachine.State license,
            FaceRuntimeStateMachine.State runtime,
            FaceLivenessControl.Capability liveness,
            ZipAdminScreenRouter.LicenseFailureKind failure,
            boolean activationInFlight) {
        if (license == FaceLicenseStateMachine.State.READY) {
            if (activationInFlight
                    || failure != ZipAdminScreenRouter.LicenseFailureKind.NONE
                    || runtime == FaceRuntimeStateMachine.State.RELEASED) return null;
            switch (runtime) {
                case UNINITIALIZED:
                    return liveness == FaceLivenessControl.Capability.WAITING_FOR_LICENSE
                            ? ZipScreenAsset.FACE_SDK_LICENSED : null;
                case INITIALIZING:
                    return ZipScreenAsset.FACE_SDK_INITIALIZING;
                case READY:
                    return liveness == FaceLivenessControl.Capability.WAITING_FOR_LICENSE
                            ? null : ZipScreenAsset.FACE_SDK_READY;
                case FAILED:
                    return liveness == FaceLivenessControl.Capability.WAITING_FOR_LICENSE
                            || liveness == FaceLivenessControl.Capability.UNSUPPORTED
                            || liveness == FaceLivenessControl.Capability.FAILED
                            ? ZipScreenAsset.FACE_SDK_ERROR : null;
                default:
                    return null;
            }
        }
        if (runtime != FaceRuntimeStateMachine.State.UNINITIALIZED
                || liveness != FaceLivenessControl.Capability.WAITING_FOR_LICENSE) {
            return null;
        }
        if (license == FaceLicenseStateMachine.State.ACTIVATING_ONLINE) {
            return failure == ZipAdminScreenRouter.LicenseFailureKind.NONE
                    ? ZipScreenAsset.FACE_SDK_ACTIVATING : null;
        }
        ZipScreenAsset terminal;
        switch (license) {
            case UNKNOWN:
            case CHECKING_LOCAL:
                if (failure != ZipAdminScreenRouter.LicenseFailureKind.NONE) return null;
                terminal = ZipScreenAsset.FACE_SDK_CHECKING_LICENSE;
                break;
            case INVALID:
                if (failure != ZipAdminScreenRouter.LicenseFailureKind.NONE
                        && failure != ZipAdminScreenRouter.LicenseFailureKind.INVALID) {
                    return null;
                }
                terminal = ZipScreenAsset.FACE_SDK_AWAITING_ACTIVATION;
                break;
            case FAILED:
                if (failure == ZipAdminScreenRouter.LicenseFailureKind.RETRYABLE
                        || failure == ZipAdminScreenRouter.LicenseFailureKind.INVALID) {
                    terminal = ZipScreenAsset.FACE_SDK_AWAITING_ACTIVATION;
                } else if (failure == ZipAdminScreenRouter.LicenseFailureKind.CORE) {
                    terminal = ZipScreenAsset.FACE_SDK_ERROR;
                } else {
                    return null;
                }
                break;
            default:
                return null;
        }
        return activationInFlight ? ZipScreenAsset.FACE_SDK_ACTIVATING : terminal;
    }

    private static String tuple(FaceLicenseStateMachine.State license,
            FaceRuntimeStateMachine.State runtime,
            FaceLivenessControl.Capability liveness,
            ZipAdminScreenRouter.LicenseFailureKind failure,
            boolean activationInFlight) {
        return license + "/" + runtime + "/" + liveness + "/" + failure
                + "/activation=" + activationInFlight;
    }

    private static void add(EnumSet<ZipScreenAsset> assets,
            ZipScreenAsset asset, int expectedId) {
        assertEquals(expectedId, asset.id());
        assertTrue("duplicate administrator asset " + asset, assets.add(asset));
    }

    private static void assertFaceActions(ZipScreenAsset asset,
            boolean topBack, boolean retry, boolean promptBack) {
        ZipAdminScreenRouter.ActionAvailability actions =
                ZipAdminScreenRouter.actionsForFaceAsset(asset, retry);
        assertEquals(asset + " top back", topBack, actions.nativeBackVisible());
        assertEquals(asset + " retry", retry, actions.retryVisible());
        assertEquals(asset + " prompt back", promptBack,
                actions.promptBackVisible());
    }

    private static void expectIllegal(Runnable action) {
        try {
            action.run();
            fail("illegal typed route must fail closed");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage() != null
                    && expected.getMessage().length() > 0);
        }
    }

    private static String readRouter() throws IOException {
        return new String(Files.readAllBytes(projectRoot().resolve(
                "app/src/main/java/com/codex/lockertest/ui/zip/ZipAdminScreenRouter.java")),
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
