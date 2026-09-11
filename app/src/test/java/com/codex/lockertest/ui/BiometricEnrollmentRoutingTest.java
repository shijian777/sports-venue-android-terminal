package com.codex.lockertest.ui;

import com.codex.lockertest.face.FaceLicenseStateMachine;
import com.codex.lockertest.face.FaceRuntimeStateMachine;
import com.codex.lockertest.ui.zip.BiometricEnrollmentScreenRouter;
import com.codex.lockertest.ui.zip.ZipScreenAsset;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class BiometricEnrollmentRoutingTest {
    @Test
    public void typedOriginReturnsToTheEntryThatOpenedPage55() {
        for (BiometricEnrollmentScreenRouter.Origin origin
                : BiometricEnrollmentScreenRouter.Origin.values()) {
            BiometricEnrollmentScreenRouter.Route choice =
                    BiometricEnrollmentScreenRouter.choice(origin);
            assertEquals(ZipScreenAsset.ENROLLMENT_CHOICE, choice.asset());
            assertEquals(origin, choice.origin());
            assertEquals(BiometricEnrollmentScreenRouter.Screen.CHOICE,
                    choice.screen());
            assertEquals(origin,
                    BiometricEnrollmentScreenRouter.backTarget(choice));
            assertEquals(origin == BiometricEnrollmentScreenRouter.Origin.HOME
                            ? BiometricEnrollmentScreenRouter.BackDestination.HOME
                            : BiometricEnrollmentScreenRouter.BackDestination.ADMIN,
                    BiometricEnrollmentScreenRouter.backDestination(choice));
        }
    }

    @Test
    public void page55RoutesFaceTo56AndPalmOnlyToExplicitPage57() {
        BiometricEnrollmentScreenRouter.Route face = readyFace(
                BiometricEnrollmentScreenRouter.Origin.HOME, true);
        assertEquals(ZipScreenAsset.FACE_ENROLLMENT_CAPTURING, face.asset());
        assertEquals(BiometricEnrollmentScreenRouter.Screen.FACE_CAPTURE,
                face.screen());
        assertTrue(face.cameraOwned());

        BiometricEnrollmentScreenRouter.Route palm =
                BiometricEnrollmentScreenRouter.palmUnavailable(
                        BiometricEnrollmentScreenRouter.Origin.ADMIN);
        assertEquals(ZipScreenAsset.PALM_ENROLLMENT_UNAVAILABLE, palm.asset());
        assertEquals(BiometricEnrollmentScreenRouter.Screen.PALM_UNAVAILABLE,
                palm.screen());
        assertFalse(palm.cameraOwned());
        assertEquals(BiometricEnrollmentScreenRouter.Origin.ADMIN,
                BiometricEnrollmentScreenRouter.backTarget(palm));
        assertEquals(BiometricEnrollmentScreenRouter.BackDestination.CHOICE,
                BiometricEnrollmentScreenRouter.backDestination(palm));
        assertEquals(BiometricEnrollmentScreenRouter.BackDestination.CHOICE,
                BiometricEnrollmentScreenRouter.backDestination(face));
    }

    @Test
    public void facePreflightIsFailClosedInTheRequiredTypedOrder() {
        assertBlocked(false, readyLicense(), readyRuntime(), true, true,
                BiometricEnrollmentScreenRouter.PreflightStep.NETWORK,
                BiometricEnrollmentScreenRouter.Blocker.NETWORK_OFFLINE);
        assertBlocked(true, FaceLicenseStateMachine.State.INVALID, readyRuntime(),
                true, true,
                BiometricEnrollmentScreenRouter.PreflightStep.LICENSE,
                BiometricEnrollmentScreenRouter.Blocker.LICENSE_NOT_READY);
        assertBlocked(true, readyLicense(), FaceRuntimeStateMachine.State.FAILED,
                true, true,
                BiometricEnrollmentScreenRouter.PreflightStep.RUNTIME,
                BiometricEnrollmentScreenRouter.Blocker.RUNTIME_NOT_READY);
        assertBlocked(true, readyLicense(), readyRuntime(), false, true,
                BiometricEnrollmentScreenRouter.PreflightStep.CAMERA_PERMISSION,
                BiometricEnrollmentScreenRouter.Blocker.CAMERA_PERMISSION_DENIED);
        assertBlocked(true, readyLicense(), readyRuntime(), true, false,
                BiometricEnrollmentScreenRouter.PreflightStep.CAMERA_ACQUIRE,
                BiometricEnrollmentScreenRouter.Blocker.CAMERA_UNAVAILABLE);
    }

    @Test
    public void everyPreflightFailureRemainsOn55WithoutCameraOwnership() {
        BiometricEnrollmentScreenRouter.Route[] failures = new BiometricEnrollmentScreenRouter.Route[] {
                preflight(false, readyLicense(), readyRuntime(), true, true),
                preflight(true, FaceLicenseStateMachine.State.UNKNOWN,
                        readyRuntime(), true, true),
                preflight(true, readyLicense(),
                        FaceRuntimeStateMachine.State.INITIALIZING, true, true),
                preflight(true, readyLicense(), readyRuntime(), false, true),
                preflight(true, readyLicense(), readyRuntime(), true, false)
        };
        for (BiometricEnrollmentScreenRouter.Route failure : failures) {
            assertEquals(ZipScreenAsset.ENROLLMENT_CHOICE, failure.asset());
            assertEquals(BiometricEnrollmentScreenRouter.Screen.CHOICE,
                    failure.screen());
            assertFalse(failure.cameraOwned());
            assertTrue(failure.blocker()
                    != BiometricEnrollmentScreenRouter.Blocker.NONE);
        }
    }

    @Test
    public void aNewSessionCannotStartUntilThePreviousGenerationIsInvalidatedAndCleared() {
        BiometricEnrollmentScreenRouter.EnrollmentSessionGate gate =
                new BiometricEnrollmentScreenRouter.EnrollmentSessionGate();
        long first = gate.begin();
        byte[] jpeg = filled(19, (byte) 0x5a);
        byte[] bestImage = filled(11, (byte) 0x33);
        assertTrue(gate.acceptOwnedBuffer(first, jpeg));
        assertTrue(gate.acceptOwnedBuffer(first, bestImage));
        assertTrue(gate.isCurrent(first));

        expectIllegalState(gate::begin);
        assertTrue(gate.invalidate(first));
        assertTrue(gate.clearInvalidated(first));
        assertAllZero(jpeg);
        assertAllZero(bestImage);
        long second = gate.begin();
        assertFalse(gate.isCurrent(first));
        assertTrue(gate.isCurrent(second));
        byte[] stale = filled(7, (byte) 0x44);
        assertFalse(gate.acceptOwnedBuffer(first, stale));
        assertAllZero(stale);

        byte[] current = filled(13, (byte) 0x22);
        assertTrue(gate.acceptOwnedBuffer(second, current));
        gate.invalidateAndClear();
        assertFalse(gate.isCurrent(second));
        assertAllZero(current);
    }

    @Test
    public void invalidationRejectsLateCallbacksBeforeCameraCloseThenClearsExactGeneration() {
        BiometricEnrollmentScreenRouter.EnrollmentSessionGate gate =
                new BiometricEnrollmentScreenRouter.EnrollmentSessionGate();
        long generation = gate.begin();
        byte[] capturedJpeg = filled(17, (byte) 0x6c);
        assertTrue(gate.acceptOwnedBuffer(generation, capturedJpeg));

        // Exit order is generation invalidation, camera/encoder close, then byte clearing.
        assertTrue(gate.invalidate(generation));
        assertFalse(gate.isCurrent(generation));
        assertFalse(allZero(capturedJpeg));
        byte[] lateJpeg = filled(9, (byte) 0x71);
        assertFalse(gate.acceptOwnedBuffer(generation, lateJpeg));
        assertAllZero(lateJpeg);

        assertTrue(gate.clearInvalidated(generation));
        assertAllZero(capturedJpeg);

        long next = gate.begin();
        byte[] nextJpeg = filled(5, (byte) 0x27);
        assertTrue(gate.acceptOwnedBuffer(next, nextJpeg));
        assertFalse(gate.clearInvalidated(generation));
        assertFalse(allZero(nextJpeg));
        gate.invalidateAndClear();
        assertAllZero(nextJpeg);
    }

    @Test
    public void routerIsAndroidFreeAndIllegalStateFailsClosed() throws Exception {
        String source = readMain(
                "com/codex/lockertest/ui/zip/BiometricEnrollmentScreenRouter.java");
        assertFalse(source.contains("import android."));
        assertFalse(source.contains("android."));
        expectIllegal(() -> BiometricEnrollmentScreenRouter.choice(null));
        expectIllegal(() -> BiometricEnrollmentScreenRouter.facePreflight(
                null, true, readyLicense(), readyRuntime(), true, true));
        expectIllegal(() -> BiometricEnrollmentScreenRouter.facePreflight(
                BiometricEnrollmentScreenRouter.Origin.HOME,
                true, null, readyRuntime(), true, true));
    }

    private static void assertBlocked(boolean network,
            FaceLicenseStateMachine.State license,
            FaceRuntimeStateMachine.State runtime,
            boolean permission, boolean camera,
            BiometricEnrollmentScreenRouter.PreflightStep expectedStep,
            BiometricEnrollmentScreenRouter.Blocker expectedBlocker) {
        BiometricEnrollmentScreenRouter.Route route =
                preflight(network, license, runtime, permission, camera);
        assertEquals(expectedStep, route.failedStep());
        assertEquals(expectedBlocker, route.blocker());
        assertFalse(route.cameraOwned());
    }

    private static BiometricEnrollmentScreenRouter.Route readyFace(
            BiometricEnrollmentScreenRouter.Origin origin, boolean camera) {
        return BiometricEnrollmentScreenRouter.facePreflight(
                origin, true, readyLicense(), readyRuntime(), true, camera);
    }

    private static BiometricEnrollmentScreenRouter.Route preflight(
            boolean network, FaceLicenseStateMachine.State license,
            FaceRuntimeStateMachine.State runtime,
            boolean permission, boolean camera) {
        return BiometricEnrollmentScreenRouter.facePreflight(
                BiometricEnrollmentScreenRouter.Origin.HOME,
                network, license, runtime, permission, camera);
    }

    private static FaceLicenseStateMachine.State readyLicense() {
        return FaceLicenseStateMachine.State.READY;
    }

    private static FaceRuntimeStateMachine.State readyRuntime() {
        return FaceRuntimeStateMachine.State.READY;
    }

    private static byte[] filled(int size, byte value) {
        byte[] bytes = new byte[size];
        Arrays.fill(bytes, value);
        return bytes;
    }

    private static void assertAllZero(byte[] bytes) {
        assertArrayEquals(new byte[bytes.length], bytes);
    }

    private static boolean allZero(byte[] bytes) {
        for (byte value : bytes) {
            if (value != 0) return false;
        }
        return true;
    }

    private static void expectIllegal(Runnable action) {
        try {
            action.run();
            throw new AssertionError("illegal enrollment route must fail closed");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage() != null
                    && expected.getMessage().length() > 0);
        }
    }

    private static void expectIllegalState(Runnable action) {
        try {
            action.run();
            throw new AssertionError("dirty enrollment generation must block begin");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage() != null
                    && expected.getMessage().length() > 0);
        }
    }

    private static String readMain(String relative) throws IOException {
        return new String(Files.readAllBytes(projectRoot().resolve(
                "app/src/main/java").resolve(relative)), StandardCharsets.UTF_8);
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
