package com.codex.lockertest.ui.zip;

import com.codex.lockertest.face.FaceLicenseStateMachine;
import com.codex.lockertest.face.FaceRuntimeStateMachine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Android-free typed routing, preflight and sensitive-buffer gate for pages 55-57. */
public final class BiometricEnrollmentScreenRouter {
    public enum Origin {
        HOME,
        ADMIN
    }

    public enum Screen {
        CHOICE,
        FACE_CAPTURE,
        PALM_UNAVAILABLE
    }

    public enum BackDestination {
        HOME,
        ADMIN,
        CHOICE
    }

    public enum PreflightStep {
        NETWORK,
        LICENSE,
        RUNTIME,
        CAMERA_PERMISSION,
        CAMERA_ACQUIRE,
        COMPLETE
    }

    public enum Blocker {
        NONE,
        NETWORK_OFFLINE,
        LICENSE_NOT_READY,
        RUNTIME_NOT_READY,
        CAMERA_PERMISSION_DENIED,
        CAMERA_UNAVAILABLE
    }

    public static final class Route {
        private final Origin origin;
        private final Screen screen;
        private final ZipScreenAsset asset;
        private final PreflightStep failedStep;
        private final Blocker blocker;
        private final boolean cameraOwned;

        private Route(Origin origin, Screen screen, ZipScreenAsset asset,
                PreflightStep failedStep, Blocker blocker, boolean cameraOwned) {
            this.origin = origin;
            this.screen = screen;
            this.asset = asset;
            this.failedStep = failedStep;
            this.blocker = blocker;
            this.cameraOwned = cameraOwned;
        }

        public Origin origin() { return origin; }
        public Screen screen() { return screen; }
        public ZipScreenAsset asset() { return asset; }
        public PreflightStep failedStep() { return failedStep; }
        public Blocker blocker() { return blocker; }
        public boolean cameraOwned() { return cameraOwned; }
    }

    public static final class CameraAcquirePermit {
        private final Route blockedRoute;

        private CameraAcquirePermit(Route blockedRoute) {
            this.blockedRoute = blockedRoute;
        }

        public boolean allowed() { return blockedRoute == null; }
        public Route blockedRoute() { return blockedRoute; }
    }

    /** Owns only in-memory buffers for one enrollment generation. */
    public static final class EnrollmentSessionGate {
        private final List<byte[]> ownedBuffers = new ArrayList<byte[]>();
        private long generation;
        private boolean active;

        public synchronized long begin() {
            if (active || !ownedBuffers.isEmpty()) {
                throw new IllegalStateException(
                        "previous enrollment generation is not fully cleaned");
            }
            if (generation == Long.MAX_VALUE) {
                active = false;
                throw new IllegalStateException("enrollment generation exhausted");
            }
            generation++;
            active = true;
            return generation;
        }

        public synchronized boolean isCurrent(long expectedGeneration) {
            return active && expectedGeneration > 0L
                    && generation == expectedGeneration;
        }

        /** Takes ownership on success and always zeroes a stale/rejected buffer. */
        public synchronized boolean acceptOwnedBuffer(
                long expectedGeneration, byte[] ownedBuffer) {
            if (ownedBuffer == null) return false;
            if (!isCurrent(expectedGeneration)) {
                zero(ownedBuffer);
                return false;
            }
            ownedBuffers.add(ownedBuffer);
            return true;
        }

        public synchronized void invalidateAndClear() {
            active = false;
            clearBuffersLocked();
            if (generation != Long.MAX_VALUE) generation++;
        }

        /** Invalidates callbacks now; cleanup may zero buffers after camera/encoder close. */
        public synchronized boolean invalidate(long expectedGeneration) {
            if (!isCurrent(expectedGeneration)) return false;
            active = false;
            return true;
        }

        /** Clears only the invalidated generation, so late cleanup cannot wipe a newer session. */
        public synchronized boolean clearInvalidated(long expectedGeneration) {
            if (active || generation != expectedGeneration) return false;
            clearBuffersLocked();
            return true;
        }

        private void clearBuffersLocked() {
            for (byte[] buffer : ownedBuffers) zero(buffer);
            ownedBuffers.clear();
        }

        private static void zero(byte[] buffer) {
            Arrays.fill(buffer, (byte) 0);
        }
    }

    private BiometricEnrollmentScreenRouter() { }

    public static Route choice(Origin origin) {
        requireOrigin(origin);
        return new Route(origin, Screen.CHOICE, ZipScreenAsset.ENROLLMENT_CHOICE,
                PreflightStep.COMPLETE, Blocker.NONE, false);
    }

    public static Route palmUnavailable(Origin origin) {
        requireOrigin(origin);
        return new Route(origin, Screen.PALM_UNAVAILABLE,
                ZipScreenAsset.PALM_ENROLLMENT_UNAVAILABLE,
                PreflightStep.COMPLETE, Blocker.NONE, false);
    }

    /** Evaluates the four gates that must pass before camera acquisition may start. */
    public static CameraAcquirePermit permitCameraAcquire(
            Origin origin, boolean networkOnline,
            FaceLicenseStateMachine.State license,
            FaceRuntimeStateMachine.State runtime,
            boolean cameraPermissionGranted) {
        requireOrigin(origin);
        if (license == null || runtime == null) {
            throw new IllegalArgumentException("typed face SDK state is required");
        }
        Route route = facePreflight(origin, networkOnline, license, runtime,
                cameraPermissionGranted, false);
        if (route.failedStep == PreflightStep.CAMERA_ACQUIRE) {
            return new CameraAcquirePermit(null);
        }
        return new CameraAcquirePermit(route);
    }

    /** Evaluates in the only approved order and never gives camera ownership on failure. */
    public static Route facePreflight(Origin origin, boolean networkOnline,
            FaceLicenseStateMachine.State license,
            FaceRuntimeStateMachine.State runtime,
            boolean cameraPermissionGranted,
            boolean cameraAcquired) {
        requireOrigin(origin);
        if (license == null || runtime == null) {
            throw new IllegalArgumentException("typed face SDK state is required");
        }
        if (!networkOnline) {
            return blocked(origin, PreflightStep.NETWORK, Blocker.NETWORK_OFFLINE);
        }
        if (license != FaceLicenseStateMachine.State.READY) {
            return blocked(origin, PreflightStep.LICENSE, Blocker.LICENSE_NOT_READY);
        }
        if (runtime != FaceRuntimeStateMachine.State.READY) {
            return blocked(origin, PreflightStep.RUNTIME, Blocker.RUNTIME_NOT_READY);
        }
        if (!cameraPermissionGranted) {
            return blocked(origin, PreflightStep.CAMERA_PERMISSION,
                    Blocker.CAMERA_PERMISSION_DENIED);
        }
        if (!cameraAcquired) {
            return blocked(origin, PreflightStep.CAMERA_ACQUIRE,
                    Blocker.CAMERA_UNAVAILABLE);
        }
        return new Route(origin, Screen.FACE_CAPTURE,
                ZipScreenAsset.FACE_ENROLLMENT_CAPTURING,
                PreflightStep.COMPLETE, Blocker.NONE, true);
    }

    public static Origin backTarget(Route route) {
        if (route == null || route.origin == null) {
            throw new IllegalArgumentException("typed enrollment route is required");
        }
        return route.origin;
    }

    /** Pages 56/57 always return to 55; only page 55 exits to its typed origin. */
    public static BackDestination backDestination(Route route) {
        if (route == null || route.origin == null || route.screen == null) {
            throw new IllegalArgumentException("typed enrollment route is required");
        }
        if (route.screen != Screen.CHOICE) return BackDestination.CHOICE;
        return route.origin == Origin.HOME
                ? BackDestination.HOME : BackDestination.ADMIN;
    }

    private static Route blocked(
            Origin origin, PreflightStep step, Blocker blocker) {
        return new Route(origin, Screen.CHOICE, ZipScreenAsset.ENROLLMENT_CHOICE,
                step, blocker, false);
    }

    private static void requireOrigin(Origin origin) {
        if (origin == null) {
            throw new IllegalArgumentException("enrollment origin is required");
        }
    }
}
