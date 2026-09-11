package com.codex.lockertest.face;

import android.content.Context;
import android.content.SharedPreferences;

import com.codex.lockertest.face.verification.FaceVerificationAssembly;
import com.codex.lockertest.face.verification.FaceVerificationClient;
import com.codex.lockertest.face.verification.FaceVerificationEnvironment;

import java.util.IdentityHashMap;

/** Process singleton that owns authorization, detection and verification boundaries. */
public final class FaceSubsystem {
    private static final String LIVENESS_PREFERENCE_FILE = "face_liveness_policy_v1";
    private static final String LIVENESS_ENABLED_KEY = "rgb_liveness_enabled";

    public interface StateListener {
        void onStateChanged(Snapshot snapshot);
    }

    public interface Subscription extends AutoCloseable {
        @Override void close();
    }

    public static final class Snapshot {
        private final FaceLicenseStateMachine.State licenseState;
        private final FaceRuntimeStateMachine.State runtimeState;
        private final boolean deviceBindingAvailable;
        private final FaceLivenessControl.Snapshot liveness;

        private Snapshot(FaceLicenseStateMachine.State licenseState,
                FaceRuntimeStateMachine.State runtimeState,
                boolean deviceBindingAvailable,
                FaceLivenessControl.Snapshot liveness) {
            this.licenseState = licenseState;
            this.runtimeState = runtimeState;
            this.deviceBindingAvailable = deviceBindingAvailable;
            this.liveness = liveness;
        }

        public FaceLicenseStateMachine.State licenseState() { return licenseState; }
        public FaceRuntimeStateMachine.State runtimeState() { return runtimeState; }
        public boolean deviceBindingAvailable() { return deviceBindingAvailable; }
        public FaceLivenessControl.Snapshot liveness() { return liveness; }
    }

    private static volatile FaceSubsystem instance;

    private final Object lock = new Object();
    private final Context applicationContext;
    private final BaiduFaceLicenseManager licenseManager;
    private final BaiduFaceRuntime faceRuntime;
    private final FaceLivenessControl livenessControl;
    private final FaceVerificationAssembly verificationAssembly;
    private final FaceVerificationEnvironment verificationEnvironment;
    private final FaceVerificationClient verificationClient;
    private final FaceDeviceBindingProvider.Binding deviceBinding;
    private final FaceProcessBinding processBinding;
    private final IdentityHashMap<Registration, Boolean> registrations =
            new IdentityHashMap<Registration, Boolean>();
    private final FaceRuntimeStateMachine.OrderedDrainQueue<PendingNotification>
            notificationQueue =
            new FaceRuntimeStateMachine.OrderedDrainQueue<PendingNotification>(lock);
    private boolean invalidLicenseAppliedToLiveness;

    private FaceSubsystem(Context applicationContext) {
        this.applicationContext = applicationContext;
        this.deviceBinding = FaceDeviceBindingProvider.create(applicationContext);
        this.processBinding = new FaceProcessBinding();
        this.verificationAssembly = FaceVerificationAssembly.create(applicationContext);
        this.verificationEnvironment = verificationAssembly.environment();
        this.verificationClient = verificationAssembly.client();
        SharedPreferences livenessPreferences = applicationContext.getSharedPreferences(
                LIVENESS_PREFERENCE_FILE, Context.MODE_PRIVATE);
        this.livenessControl = new FaceLivenessControl(
                new FaceLivenessControl.BooleanStore() {
                    @Override public boolean read() {
                        return livenessPreferences.getBoolean(LIVENESS_ENABLED_KEY, false);
                    }

                    @Override public boolean write(boolean value) {
                        return livenessPreferences.edit()
                                .putBoolean(LIVENESS_ENABLED_KEY, value)
                                .commit();
                    }
                });
        this.licenseManager = BaiduFaceLicenseManager.create(this::publishState);
        this.faceRuntime = new BaiduFaceRuntime(livenessControl, this::publishState);
    }

    public static FaceSubsystem shared(Context context) {
        FaceSubsystem current = instance;
        if (current != null) return current;
        if (context == null) throw new IllegalArgumentException("context cannot be null");
        Context application = context.getApplicationContext();
        if (application == null) {
            throw new IllegalStateException("application context unavailable");
        }
        synchronized (FaceSubsystem.class) {
            current = instance;
            if (current == null) {
                current = new FaceSubsystem(application);
                instance = current;
            }
            return current;
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            return snapshotLocked();
        }
    }

    private Snapshot snapshotLocked() {
        FaceLicenseStateMachine.State licenseState = licenseManager.state();
        reconcileLivenessLicenseLocked(licenseState);
        return new Snapshot(licenseState, faceRuntime.state(),
                deviceBinding.isAvailable(), livenessControl.snapshot());
    }

    private void reconcileLivenessLicenseLocked(
            FaceLicenseStateMachine.State licenseState) {
        if (licenseState == FaceLicenseStateMachine.State.INVALID) {
            if (!invalidLicenseAppliedToLiveness) {
                livenessControl.onLicenseUnavailable();
                invalidLicenseAppliedToLiveness = true;
            }
        } else if (licenseState == FaceLicenseStateMachine.State.READY) {
            invalidLicenseAppliedToLiveness = false;
        }
    }

    public Subscription subscribe(StateListener listener) {
        if (listener == null) throw new IllegalArgumentException("listener cannot be null");
        Registration registration = new Registration(this, listener);
        boolean shouldDrain;
        synchronized (lock) {
            registrations.put(registration, Boolean.TRUE);
            shouldDrain = notificationQueue.enqueueLocked(
                    new PendingNotification(registration, snapshotLocked()));
        }
        if (shouldDrain) drainNotifications();
        return registration;
    }

    public BaiduFaceLicenseManager.Subscription checkLocal(
            BaiduFaceLicenseManager.Listener listener) {
        return licenseManager.checkLocal(applicationContext, listener);
    }

    public BaiduFaceLicenseManager.Subscription activateOnline(
            char[] ownedLicenseId, BaiduFaceLicenseManager.Listener listener) {
        return licenseManager.activateOnline(applicationContext, ownedLicenseId, listener);
    }

    public BaiduFaceRuntime.Subscription initializeRuntime(BaiduFaceRuntime.Listener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener cannot be null");
        }
        FaceLicenseStateMachine.State licenseState;
        try {
            licenseState = licenseManager.state();
        } catch (RuntimeException | LinkageError ignored) {
            licenseState = null;
        }
        if (licenseState != FaceLicenseStateMachine.State.READY) {
            notifyRuntimeLicenseUnavailableImmediately(listener);
            return closedRuntimeSubscription();
        }
        return faceRuntime.initialize(applicationContext, listener);
    }

    private static void notifyRuntimeLicenseUnavailableImmediately(
            BaiduFaceRuntime.Listener listener) {
        try {
            listener.onFailure(0L, BaiduFaceRuntime.SAFE_RUNTIME_ERROR,
                    "人脸检测组件暂时不可用");
        } catch (RuntimeException | LinkageError ignored) { }
    }

    private static BaiduFaceRuntime.Subscription closedRuntimeSubscription() {
        return () -> { };
    }

    public BaiduFaceRuntime runtime() { return faceRuntime; }

    public boolean setLivenessEnabled(boolean enabled) {
        boolean actual = livenessControl.setRequestedEnabled(enabled);
        publishState();
        return actual;
    }

    public FaceVerificationEnvironment verificationEnvironment() {
        return verificationEnvironment;
    }

    public FaceVerificationClient verificationClient() { return verificationClient; }

    public boolean hasDeviceBinding() { return deviceBinding.isAvailable(); }

    public String deviceBinding() { return deviceBinding.value(); }

    public String processBinding() { return processBinding.value(); }

    public long currentPolicyEpoch() {
        return verificationEnvironment.currentPolicyEpoch();
    }

    private void publishState() {
        boolean shouldDrain = false;
        synchronized (lock) {
            Snapshot current = snapshotLocked();
            for (Registration registration : registrations.keySet()) {
                if (notificationQueue.enqueueLocked(
                        new PendingNotification(registration, current))) {
                    shouldDrain = true;
                }
            }
        }
        if (shouldDrain) drainNotifications();
    }

    private void drainNotifications() {
        notificationQueue.drain(this::deliverPending);
    }

    private void deliverPending(PendingNotification pending) {
        StateListener listener;
        synchronized (lock) {
            if (!pending.registration.active
                    || !registrations.containsKey(pending.registration)) return;
            listener = pending.registration.listener;
        }
        try { listener.onStateChanged(pending.snapshot); }
        catch (RuntimeException | LinkageError ignored) { }
    }

    private void close(Registration registration) {
        synchronized (lock) {
            registration.active = false;
            registrations.remove(registration);
        }
    }

    private static final class Registration implements Subscription {
        private final FaceSubsystem owner;
        private final StateListener listener;
        private boolean active = true;

        Registration(FaceSubsystem owner, StateListener listener) {
            this.owner = owner;
            this.listener = listener;
        }

        @Override public void close() { owner.close(this); }
    }

    private static final class PendingNotification {
        private final Registration registration;
        private final Snapshot snapshot;

        PendingNotification(Registration registration, Snapshot snapshot) {
            this.registration = registration;
            this.snapshot = snapshot;
        }
    }
}
