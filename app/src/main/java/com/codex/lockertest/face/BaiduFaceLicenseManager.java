package com.codex.lockertest.face;

import android.content.Context;

import com.baidu.idl.main.facesdk.callback.Callback;
import com.codex.lockertest.face.baidu.license.BdFaceAuth;
import com.codex.lockertest.face.baidu.license.CodeDetail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Process-scoped Baidu authorization boundary. */
public interface BaiduFaceLicenseManager {
    enum FailureKind { INVALID, UNAVAILABLE }

    interface Listener {
        void onReady();
        void onFailure(int safeCode, String safeMessage);

        /**
         * Detailed callback for adapters which need retry semantics. Existing
         * listeners remain source-compatible through the legacy callback.
         */
        default void onFailure(FailureKind kind, int safeCode, String safeMessage) {
            onFailure(safeCode, safeMessage);
        }
    }

    interface Subscription extends AutoCloseable {
        @Override void close();
    }

    Subscription checkLocal(Context context, Listener listener);
    Subscription activateOnline(Context context, char[] ownedLicenseId, Listener listener);
    @Deprecated void cancelUiWait();
    FaceLicenseStateMachine.State state();

    static BaiduFaceLicenseManager create(Runnable stateChangeHook) {
        return new Default(stateChangeHook);
    }

    final class Default implements BaiduFaceLicenseManager {
        private static final long OPERATION_TIMEOUT_MILLIS = 15000L;
        private static final int MAX_ACTIVATION_CHARACTERS = 4096;
        private static final int SAFE_INVALID = 2001;
        private static final int SAFE_OPERATION_ERROR = 2002;
        private static final String INVALID_MESSAGE = "百度人脸授权无效";
        private static final String OPERATION_MESSAGE = "百度人脸授权暂时不可用";

        private final Object lock = new Object();
        private final FaceLicenseStateMachine machine = new FaceLicenseStateMachine();
        private final BdFaceAuth helper = new BdFaceAuth();
        private final ScheduledExecutorService scheduler =
                Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "face-license-state");
                    thread.setDaemon(true);
                    return thread;
                });
        private final IdentityHashMap<Registration, Boolean> registrations =
                new IdentityHashMap<Registration, Boolean>();
        private final Runnable stateChangeHook;
        private ScheduledFuture<?> timeoutFuture;

        Default(Runnable stateChangeHook) {
            this.stateChangeHook = stateChangeHook == null ? () -> { } : stateChangeHook;
        }

        @Override
        public Subscription checkLocal(Context context, Listener listener) {
            requireListener(listener);
            Context application = application(context);
            FaceLicenseStateMachine.StartResult start;
            Registration registration;
            synchronized (lock) {
                start = machine.startLocalCheck();
                registration = addRegistration(listener, start.operationId());
            }
            if (machine.state() == FaceLicenseStateMachine.State.READY) {
                postReady(registration);
                return registration;
            }
            if (!start.shouldStart()) {
                if (start.operationId() <= 0L) {
                    stateChanged();
                    postFailure(registration, SAFE_OPERATION_ERROR, OPERATION_MESSAGE);
                }
                return registration;
            }
            stateChanged();
            if (application == null) {
                failStart(start, registration);
                return registration;
            }
            if (!armTimeout(start.operationId(),
                    FaceLicenseStateMachine.OperationKind.LOCAL_CHECK)) {
                finishFailure(start.operationId(),
                        FaceLicenseStateMachine.OperationKind.LOCAL_CHECK, false);
                return registration;
            }
            try {
                helper.checkLocal(application, callback(start.operationId(),
                        FaceLicenseStateMachine.OperationKind.LOCAL_CHECK));
            } catch (RuntimeException | LinkageError failure) {
                finishFailure(start.operationId(),
                        FaceLicenseStateMachine.OperationKind.LOCAL_CHECK, false);
            }
            return registration;
        }

        @Override
        public Subscription activateOnline(Context context, char[] ownedLicenseId,
                Listener listener) {
            FaceLicenseStateMachine.StartResult start = null;
            Registration registration = null;
            String temporaryActivation = null;
            try {
                requireListener(listener);
                Context application = application(context);
                synchronized (lock) {
                    start = machine.startOnlineActivation();
                    registration = addRegistration(listener, start.operationId());
                }
                if (machine.state() == FaceLicenseStateMachine.State.READY) {
                    postReady(registration);
                    return registration;
                }
                if (!start.shouldStart()) {
                    if (start.operationId() <= 0L) {
                        stateChanged();
                        postFailure(registration, SAFE_OPERATION_ERROR, OPERATION_MESSAGE);
                    }
                    return registration;
                }
                stateChanged();
                if (application == null || ownedLicenseId == null
                        || ownedLicenseId.length == 0
                        || ownedLicenseId.length > MAX_ACTIVATION_CHARACTERS) {
                    failStart(start, registration);
                    return registration;
                }
                temporaryActivation = new String(ownedLicenseId);
                if (temporaryActivation.trim().length() == 0) {
                    finishInvalid(start.operationId(),
                            FaceLicenseStateMachine.OperationKind.ONLINE_ACTIVATION);
                    return registration;
                }
                if (!armTimeout(start.operationId(),
                        FaceLicenseStateMachine.OperationKind.ONLINE_ACTIVATION)) {
                    finishFailure(start.operationId(),
                            FaceLicenseStateMachine.OperationKind.ONLINE_ACTIVATION, false);
                    return registration;
                }
                final long operationId = start.operationId();
                helper.activateOnline(application, temporaryActivation,
                        () -> tryBeginCommit(operationId),
                        callback(operationId,
                                FaceLicenseStateMachine.OperationKind.ONLINE_ACTIVATION));
                return registration;
            } catch (RuntimeException | LinkageError failure) {
                if (start != null && start.operationId() > 0L) {
                    finishFailure(start.operationId(),
                            FaceLicenseStateMachine.OperationKind.ONLINE_ACTIVATION, false);
                } else if (registration != null) {
                    postFailure(registration, SAFE_OPERATION_ERROR, OPERATION_MESSAGE);
                }
                return registration == null ? closedSubscription() : registration;
            } finally {
                if (ownedLicenseId != null) {
                    Arrays.fill(ownedLicenseId, '\0');
                }
                temporaryActivation = null;
            }
        }

        @Override
        @Deprecated
        public void cancelUiWait() {
            // Compatibility no-op. Exact Subscription.close() owns UI unsubscription.
        }

        @Override
        public FaceLicenseStateMachine.State state() {
            return machine.state();
        }

        private Callback callback(long operationId,
                FaceLicenseStateMachine.OperationKind kind) {
            return (code, ignoredNativeResponse) -> {
                if (code == 0) {
                    finishSuccess(operationId, kind);
                } else if (CodeDetail.isAuthorizationInvalidResult(code,
                        kind == FaceLicenseStateMachine.OperationKind.LOCAL_CHECK)) {
                    finishInvalid(operationId, kind);
                } else {
                    finishFailure(operationId, kind, false);
                }
            };
        }

        private boolean armTimeout(long operationId,
                FaceLicenseStateMachine.OperationKind kind) {
            try {
                ScheduledFuture<?> scheduled = scheduler.schedule(
                        () -> finishFailure(operationId, kind, true),
                        OPERATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
                synchronized (lock) {
                    timeoutFuture = scheduled;
                }
                return true;
            } catch (RuntimeException | LinkageError rejected) {
                return false;
            }
        }

        private boolean tryBeginCommit(long operationId) {
            synchronized (lock) {
                if (!machine.tryBeginCommit(operationId,
                        FaceLicenseStateMachine.OperationKind.ONLINE_ACTIVATION)) {
                    return false;
                }
                cancelTimeoutLocked();
                return true;
            }
        }

        private void finishSuccess(long operationId,
                FaceLicenseStateMachine.OperationKind kind) {
            List<Registration> listeners;
            synchronized (lock) {
                if (!machine.completeSuccess(operationId, kind)) return;
                cancelTimeoutLocked();
                listeners = claimOperationLocked(operationId);
            }
            stateChanged();
            for (Registration registration : listeners) notifyReady(registration);
        }

        private void finishInvalid(long operationId,
                FaceLicenseStateMachine.OperationKind kind) {
            List<Registration> listeners;
            synchronized (lock) {
                if (!machine.completeInvalid(operationId, kind)) return;
                cancelTimeoutLocked();
                listeners = claimOperationLocked(operationId);
            }
            stateChanged();
            for (Registration registration : listeners) {
                notifyFailure(registration, FailureKind.INVALID,
                        SAFE_INVALID, INVALID_MESSAGE);
            }
        }

        private void finishFailure(long operationId,
                FaceLicenseStateMachine.OperationKind kind, boolean timeout) {
            List<Registration> listeners;
            synchronized (lock) {
                boolean won = timeout ? machine.timeout(operationId, kind)
                        : machine.completeFailure(operationId, kind);
                if (!won) return;
                cancelTimeoutLocked();
                listeners = claimOperationLocked(operationId);
            }
            stateChanged();
            for (Registration registration : listeners) {
                notifyFailure(registration, FailureKind.UNAVAILABLE,
                        SAFE_OPERATION_ERROR, OPERATION_MESSAGE);
            }
        }

        private void failStart(FaceLicenseStateMachine.StartResult start,
                Registration registration) {
            if (start.shouldStart()) {
                finishFailure(start.operationId(), operationKindForState(), false);
            } else {
                postFailure(registration, SAFE_OPERATION_ERROR, OPERATION_MESSAGE);
            }
        }

        private FaceLicenseStateMachine.OperationKind operationKindForState() {
            return machine.state() == FaceLicenseStateMachine.State.ACTIVATING_ONLINE
                    ? FaceLicenseStateMachine.OperationKind.ONLINE_ACTIVATION
                    : FaceLicenseStateMachine.OperationKind.LOCAL_CHECK;
        }

        private Registration addRegistration(Listener listener, long operationId) {
            Registration registration = new Registration(this, listener, operationId);
            registrations.put(registration, Boolean.TRUE);
            return registration;
        }

        private List<Registration> claimOperationLocked(long operationId) {
            List<Registration> claimed = new ArrayList<Registration>();
            for (Registration registration
                    : new ArrayList<Registration>(registrations.keySet())) {
                if (registration.operationId == operationId && registration.active) {
                    registration.active = false;
                    registrations.remove(registration);
                    claimed.add(registration);
                }
            }
            return claimed;
        }

        private void postReady(Registration registration) {
            try {
                scheduler.execute(() -> {
                    if (claimSingle(registration)) notifyReady(registration);
                });
            } catch (RuntimeException | LinkageError rejected) {
                if (claimSingle(registration)) {
                    notifyFailure(registration, FailureKind.UNAVAILABLE,
                            SAFE_OPERATION_ERROR, OPERATION_MESSAGE);
                }
            }
        }

        private void postFailure(Registration registration, int code, String message) {
            try {
                scheduler.execute(() -> {
                    if (claimSingle(registration)) notifyFailure(registration, code, message);
                });
            } catch (RuntimeException | LinkageError rejected) {
                if (claimSingle(registration)) {
                    notifyFailure(registration, FailureKind.UNAVAILABLE, code, message);
                }
            }
        }

        private boolean claimSingle(Registration registration) {
            synchronized (lock) {
                if (!registration.active || !registrations.containsKey(registration)) {
                    return false;
                }
                registration.active = false;
                registrations.remove(registration);
                return true;
            }
        }

        private void notifyReady(Registration registration) {
            try { registration.listener.onReady(); }
            catch (RuntimeException | LinkageError ignored) { }
        }

        private void notifyFailure(Registration registration, int code, String message) {
            notifyFailure(registration, FailureKind.UNAVAILABLE, code, message);
        }

        private void notifyFailure(Registration registration, FailureKind kind,
                int code, String message) {
            try { registration.listener.onFailure(kind, code, message); }
            catch (RuntimeException | LinkageError ignored) { }
        }

        private void cancelTimeoutLocked() {
            ScheduledFuture<?> claimed = timeoutFuture;
            timeoutFuture = null;
            if (claimed != null) claimed.cancel(false);
        }

        private void close(Registration registration) {
            synchronized (lock) {
                registration.active = false;
                registrations.remove(registration);
            }
        }

        private void stateChanged() {
            try { stateChangeHook.run(); }
            catch (RuntimeException | LinkageError ignored) { }
        }

        private static void requireListener(Listener listener) {
            if (listener == null) throw new IllegalArgumentException("listener cannot be null");
        }

        private static Context application(Context context) {
            try {
                return context == null ? null : context.getApplicationContext();
            } catch (RuntimeException | LinkageError failure) {
                return null;
            }
        }

        private static Subscription closedSubscription() {
            return () -> { };
        }

        private static final class Registration implements Subscription {
            private final Default owner;
            private final Listener listener;
            private final long operationId;
            private boolean active = true;

            Registration(Default owner, Listener listener, long operationId) {
                this.owner = owner;
                this.listener = listener;
                this.operationId = operationId;
            }

            @Override public void close() { owner.close(this); }
        }
    }
}
