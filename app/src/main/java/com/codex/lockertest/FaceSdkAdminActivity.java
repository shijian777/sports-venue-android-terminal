package com.codex.lockertest;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import com.codex.lockertest.admin.AdminCapability;
import com.codex.lockertest.admin.AdminCapabilityAssembly;
import com.codex.lockertest.admin.AdminCapabilityPolicy;
import com.codex.lockertest.face.BaiduFaceLicenseManager;
import com.codex.lockertest.face.BaiduFaceRuntime;
import com.codex.lockertest.face.FaceBuildVariant;
import com.codex.lockertest.face.FaceLicenseStateMachine;
import com.codex.lockertest.face.FaceRuntimeStateMachine;
import com.codex.lockertest.face.FaceSubsystem;
import com.codex.lockertest.ui.FaceSdkAdminOverlay;
import com.codex.lockertest.ui.zip.ZipAdminScreenRouter;
import com.codex.lockertest.ui.zip.ZipScreenAsset;

import java.util.Arrays;

/** Administrator-only lifecycle wrapper around the process-scoped face subsystem. */
public final class FaceSdkAdminActivity extends com.codex.lockertest.admin.OnlineMaintenanceActivity {
    @Override protected com.codex.lockertest.admin.OnlineMaintenanceGrant.Target maintenanceTarget() {
        return com.codex.lockertest.admin.OnlineMaintenanceGrant.Target.FACE_SDK;
    }
    private final Handler handler = new Handler(Looper.getMainLooper());

    private AdminCapabilityPolicy adminCapabilityPolicy;
    private FaceSubsystem subsystem;
    private FaceSdkAdminOverlay overlay;
    private FaceSubsystem.Subscription stateSubscription;
    private BaiduFaceLicenseManager.Subscription localCheckSubscription;
    private BaiduFaceLicenseManager.Subscription activationSubscription;
    private BaiduFaceRuntime.Subscription runtimeSubscription;
    private UiOperation activeOperation;
    private long uiGeneration;
    private long nextOperationId;
    private boolean uiActive;
    private boolean activationInFlight;
    private ZipAdminScreenRouter.LicenseFailureKind licenseFailureKind =
            ZipAdminScreenRouter.LicenseFailureKind.NONE;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (isFinishing()) return;
        adminCapabilityPolicy = AdminCapabilityAssembly.create();
        configureWindow();
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        overlay = new FaceSdkAdminOverlay(this);
        overlay.setListener(new OverlayListener());
        setContentView(overlay);
        try {
            subsystem = FaceSubsystem.shared(getApplicationContext());
        } catch (RuntimeException | LinkageError failure) {
            overlay.showOperationMessage("人脸管理组件暂时不可用", true);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (isFinishing()) return;
        uiGeneration = nextPositive(uiGeneration);
        uiActive = true;
        final long generation = uiGeneration;
        final FaceSdkAdminOverlay sourceOverlay = overlay;
        if (sourceOverlay == null) {
            return;
        }
        boolean demoSupported = FaceBuildVariant.isLocalDemo();
        if (subsystem == null) {
            sourceOverlay.renderDemoState(false, false);
            sourceOverlay.renderLivenessState(null);
            sourceOverlay.renderScreen(ZipScreenAsset.FACE_SDK_ERROR, true);
            sourceOverlay.showOperationMessage("人脸管理组件暂时不可用", true);
            return;
        }

        FaceSubsystem.Subscription candidate = null;
        try {
            candidate = subsystem.subscribe(snapshot ->
                    postToUi(() -> {
                        if (!isCurrentUi(generation, sourceOverlay)) return;
                        renderSnapshot(snapshot, generation, sourceOverlay);
                    }));
        } catch (RuntimeException | LinkageError failure) {
            sourceOverlay.showOperationMessage("无法读取人脸组件状态", true);
        }
        if (isCurrentUi(generation, sourceOverlay)) {
            stateSubscription = candidate;
        } else {
            closeQuietly(candidate);
        }

        boolean demoEnabled = false;
        try {
            demoEnabled = subsystem.verificationEnvironment().isEnabled();
        } catch (RuntimeException | LinkageError failure) {
            sourceOverlay.showOperationMessage("无法读取本机模拟状态", true);
        }
        sourceOverlay.renderDemoState(demoSupported, demoSupported && demoEnabled);

        FaceSubsystem.Snapshot snapshot;
        try {
            snapshot = subsystem.snapshot();
        } catch (RuntimeException | LinkageError failure) {
            sourceOverlay.showOperationMessage("无法读取人脸组件状态", true);
            return;
        }
        renderSnapshot(snapshot, generation, sourceOverlay);
        if (snapshot.licenseState() == FaceLicenseStateMachine.State.UNKNOWN) {
            startLocalCheck(generation, sourceOverlay);
        }
    }

    @Override
    protected void onStop() {
        uiActive = false;
        uiGeneration = nextPositive(uiGeneration);
        cancelUiOperations();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        uiActive = false;
        uiGeneration = nextPositive(uiGeneration);
        cancelUiOperations();
        if (overlay != null) {
            overlay.setListener(null);
            overlay.clearActivationInput();
        }
        super.onDestroy();
    }

    private void configureWindow() {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SECURE);
        hideSystemUi();
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    private void renderSnapshot(FaceSubsystem.Snapshot snapshot, long generation,
            FaceSdkAdminOverlay sourceOverlay) {
        if (!isCurrentUi(generation, sourceOverlay) || snapshot == null) return;
        try {
            sourceOverlay.renderStates(snapshot.licenseState(), snapshot.runtimeState());
            sourceOverlay.renderLivenessState(snapshot.liveness());
            sourceOverlay.renderScreen(routeForSnapshot(snapshot));
        } catch (RuntimeException | LinkageError ignored) {
            sourceOverlay.renderScreen(ZipScreenAsset.FACE_SDK_ERROR, false);
            sourceOverlay.showOperationMessage("核心模型或授权状态异常", true);
            return;
        }
        if (snapshot.licenseState() == FaceLicenseStateMachine.State.READY
                && snapshot.runtimeState()
                == FaceRuntimeStateMachine.State.UNINITIALIZED
                && activeOperation == null) {
            startRuntimeInitialization(generation, sourceOverlay);
        }
    }

    private ZipScreenAsset routeForSnapshot(FaceSubsystem.Snapshot snapshot) {
        FaceLicenseStateMachine.State license = snapshot.licenseState();
        if (license == FaceLicenseStateMachine.State.READY) {
            licenseFailureKind = ZipAdminScreenRouter.LicenseFailureKind.NONE;
        } else if (license == FaceLicenseStateMachine.State.INVALID) {
            licenseFailureKind = ZipAdminScreenRouter.LicenseFailureKind.INVALID;
        } else if (license == FaceLicenseStateMachine.State.FAILED
                && licenseFailureKind
                == ZipAdminScreenRouter.LicenseFailureKind.NONE) {
            licenseFailureKind = ZipAdminScreenRouter.LicenseFailureKind.RETRYABLE;
        } else if (license == FaceLicenseStateMachine.State.UNKNOWN
                || license == FaceLicenseStateMachine.State.CHECKING_LOCAL
                || license == FaceLicenseStateMachine.State.ACTIVATING_ONLINE) {
            licenseFailureKind = ZipAdminScreenRouter.LicenseFailureKind.NONE;
        }
        return ZipAdminScreenRouter.assetForFace(
                license,
                snapshot.runtimeState(),
                snapshot.liveness().capability(),
                licenseFailureKind,
                activationInFlight
                        && license != FaceLicenseStateMachine.State.READY);
    }

    private void startLocalCheck(long generation, FaceSdkAdminOverlay sourceOverlay) {
        UiOperation operation = beginUiOperation(
                OperationKind.LOCAL_CHECK, generation, sourceOverlay);
        if (operation == null) return;
        BaiduFaceLicenseManager.Subscription candidate = null;
        try {
            candidate = subsystem.checkLocal(new BaiduFaceLicenseManager.Listener() {
                @Override
                public void onReady() {
                    postToUi(() -> completeLicenseOperation(
                            operation, sourceOverlay, true));
                }

                @Override
                public void onFailure(int safeCode, String safeMessage) {
                    postToUi(() -> completeLicenseOperation(
                            operation, sourceOverlay, false,
                            ZipAdminScreenRouter.LicenseFailureKind.RETRYABLE));
                }

                @Override
                public void onFailure(BaiduFaceLicenseManager.FailureKind kind,
                        int safeCode, String safeMessage) {
                    postToUi(() -> completeLicenseOperation(
                            operation, sourceOverlay, false,
                            mapLicenseFailure(kind)));
                }
            });
        } catch (RuntimeException | LinkageError failure) {
            postToUi(() -> completeLicenseOperation(operation, sourceOverlay, false,
                    ZipAdminScreenRouter.LicenseFailureKind.RETRYABLE));
        }
        installLicenseSubscription(operation, candidate, false);
    }

    private void requestActivation(char[] ownedActivationCode,
            long generation, FaceSdkAdminOverlay sourceOverlay) {
        boolean transferred = false;
        try {
            if (ownedActivationCode == null
                    || !isCurrentUi(generation, sourceOverlay)) {
                return;
            }
            if (!adminCapabilityPolicy.allows(
                    AdminCapability.ACTIVATE_FACE_SDK, isNetworkOnline(), false)) {
                sourceOverlay.setActivationInFlight(false);
                sourceOverlay.showOperationMessage("联网后才能激活百度人脸 SDK", true);
                return;
            }
            if (subsystem == null) {
                sourceOverlay.setActivationInFlight(false);
                return;
            }
            if (activationInFlight) {
                return;
            }
            UiOperation operation = beginUiOperation(
                    OperationKind.ACTIVATION, generation, sourceOverlay);
            if (operation == null) {
                if (isCurrentUi(generation, sourceOverlay)) {
                    sourceOverlay.setActivationInFlight(false);
                }
                return;
            }
            activationInFlight = true;
            sourceOverlay.setActivationInFlight(true);
            BaiduFaceLicenseManager.Subscription candidate =
                    subsystem.activateOnline(ownedActivationCode,
                            new BaiduFaceLicenseManager.Listener() {
                                @Override
                                public void onReady() {
                                    postToUi(() -> completeLicenseOperation(
                                            operation, sourceOverlay, true));
                                }

                                @Override
                                public void onFailure(int safeCode, String safeMessage) {
                                    postToUi(() -> completeLicenseOperation(
                                            operation, sourceOverlay, false,
                                            ZipAdminScreenRouter.LicenseFailureKind.RETRYABLE));
                                }

                                @Override
                                public void onFailure(
                                        BaiduFaceLicenseManager.FailureKind kind,
                                        int safeCode, String safeMessage) {
                                    postToUi(() -> completeLicenseOperation(
                                            operation, sourceOverlay, false,
                                            mapLicenseFailure(kind)));
                                }
                            });
            transferred = true;
            installLicenseSubscription(operation, candidate, true);
        } catch (RuntimeException | LinkageError failure) {
            UiOperation operation = activeOperation;
            if (operation != null && operation.kind == OperationKind.ACTIVATION) {
                postToUi(() -> completeLicenseOperation(
                        operation, sourceOverlay, false,
                        ZipAdminScreenRouter.LicenseFailureKind.RETRYABLE));
            }
        } finally {
            if (!transferred && ownedActivationCode != null) {
                Arrays.fill(ownedActivationCode, '\0');
            }
        }
    }

    private void completeLicenseOperation(UiOperation operation,
            FaceSdkAdminOverlay sourceOverlay, boolean ready) {
        completeLicenseOperation(operation, sourceOverlay, ready,
                ZipAdminScreenRouter.LicenseFailureKind.NONE);
    }

    private void completeLicenseOperation(UiOperation operation,
            FaceSdkAdminOverlay sourceOverlay, boolean ready,
            ZipAdminScreenRouter.LicenseFailureKind failureKind) {
        if (!isCurrentUi(operation.generation, sourceOverlay)
                || !claimUiOperation(operation)) return;
        licenseFailureKind = ready
                ? ZipAdminScreenRouter.LicenseFailureKind.NONE : failureKind;
        if (operation.kind == OperationKind.ACTIVATION) {
            activationInFlight = false;
            sourceOverlay.setActivationInFlight(false);
        }
        sourceOverlay.showOperationMessage(
                ready ? "授权状态已更新" : "授权操作失败，请重试", !ready);
        renderCurrentSnapshot(operation.generation, sourceOverlay);
    }

    private static ZipAdminScreenRouter.LicenseFailureKind mapLicenseFailure(
            BaiduFaceLicenseManager.FailureKind kind) {
        if (kind == null) {
            return ZipAdminScreenRouter.LicenseFailureKind.RETRYABLE;
        }
        switch (kind) {
            case INVALID:
                return ZipAdminScreenRouter.LicenseFailureKind.INVALID;
            case UNAVAILABLE:
            default:
                return ZipAdminScreenRouter.LicenseFailureKind.RETRYABLE;
        }
    }

    private void requestRuntimeInitialization(
            long generation, FaceSdkAdminOverlay sourceOverlay) {
        if (!isCurrentUi(generation, sourceOverlay)) return;
        if (subsystem == null) {
            retryUnavailableSubsystem(generation, sourceOverlay);
            return;
        }
        FaceSubsystem.Snapshot snapshot;
        try {
            snapshot = subsystem.snapshot();
        } catch (RuntimeException | LinkageError failure) {
            sourceOverlay.showOperationMessage("无法读取模型状态", true);
            return;
        }
        if (snapshot.licenseState() != FaceLicenseStateMachine.State.READY
                || (snapshot.runtimeState()
                != FaceRuntimeStateMachine.State.UNINITIALIZED
                && snapshot.runtimeState()
                != FaceRuntimeStateMachine.State.FAILED)) {
            sourceOverlay.showOperationMessage("当前状态不能初始化模型", true);
            return;
        }
        startRuntimeInitialization(generation, sourceOverlay);
    }

    private void retryUnavailableSubsystem(
            long generation, FaceSdkAdminOverlay sourceOverlay) {
        if (!isCurrentUi(generation, sourceOverlay) || subsystem != null) return;
        final FaceSubsystem recovered;
        try {
            recovered = FaceSubsystem.shared(getApplicationContext());
        } catch (RuntimeException | LinkageError failure) {
            sourceOverlay.renderScreen(ZipScreenAsset.FACE_SDK_ERROR, true);
            sourceOverlay.showOperationMessage("人脸管理组件暂时不可用", true);
            return;
        }
        if (!isCurrentUi(generation, sourceOverlay)) return;
        subsystem = recovered;

        FaceSubsystem.Subscription candidate = null;
        try {
            candidate = recovered.subscribe(snapshot ->
                    postToUi(() -> {
                        if (!isCurrentUi(generation, sourceOverlay)) return;
                        renderSnapshot(snapshot, generation, sourceOverlay);
                    }));
        } catch (RuntimeException | LinkageError failure) {
            subsystem = null;
            sourceOverlay.renderScreen(ZipScreenAsset.FACE_SDK_ERROR, true);
            sourceOverlay.showOperationMessage("无法读取人脸组件状态", true);
            return;
        }
        if (!isCurrentUi(generation, sourceOverlay)) {
            closeQuietly(candidate);
            return;
        }
        stateSubscription = candidate;

        boolean demoSupported = FaceBuildVariant.isLocalDemo();
        boolean demoEnabled = false;
        try {
            demoEnabled = recovered.verificationEnvironment().isEnabled();
        } catch (RuntimeException | LinkageError failure) {
            sourceOverlay.showOperationMessage("无法读取本机模拟状态", true);
        }
        sourceOverlay.renderDemoState(
                demoSupported, demoSupported && demoEnabled);

        FaceSubsystem.Snapshot snapshot;
        try {
            snapshot = recovered.snapshot();
        } catch (RuntimeException | LinkageError failure) {
            sourceOverlay.renderScreen(ZipScreenAsset.FACE_SDK_ERROR, true);
            sourceOverlay.showOperationMessage("无法读取人脸组件状态", true);
            return;
        }
        renderSnapshot(snapshot, generation, sourceOverlay);
        if (snapshot.licenseState() == FaceLicenseStateMachine.State.UNKNOWN) {
            startLocalCheck(generation, sourceOverlay);
        } else if (snapshot.licenseState() == FaceLicenseStateMachine.State.READY
                && snapshot.runtimeState() == FaceRuntimeStateMachine.State.FAILED) {
            startRuntimeInitialization(generation, sourceOverlay);
        }
    }

    private void startRuntimeInitialization(
            long generation, FaceSdkAdminOverlay sourceOverlay) {
        UiOperation operation = beginUiOperation(
                OperationKind.RUNTIME, generation, sourceOverlay);
        if (operation == null) return;
        BaiduFaceRuntime.Subscription candidate = null;
        try {
            candidate = subsystem.initializeRuntime(new BaiduFaceRuntime.Listener() {
                @Override
                public void onReady(long runtimeGeneration) {
                    postToUi(() -> completeRuntimeOperation(
                            operation, sourceOverlay, true));
                }

                @Override
                public void onFailure(long runtimeGeneration,
                        int safeCode, String safeMessage) {
                    postToUi(() -> completeRuntimeOperation(
                            operation, sourceOverlay, false));
                }
            });
        } catch (RuntimeException | LinkageError failure) {
            postToUi(() -> completeRuntimeOperation(operation, sourceOverlay, false));
        }
        installRuntimeSubscription(operation, candidate);
    }

    private void completeRuntimeOperation(UiOperation operation,
            FaceSdkAdminOverlay sourceOverlay, boolean ready) {
        if (!isCurrentUi(operation.generation, sourceOverlay)
                || !claimUiOperation(operation)) return;
        sourceOverlay.showOperationMessage(
                ready ? "模型已就绪" : "模型初始化失败，请重试", !ready);
        renderCurrentSnapshot(operation.generation, sourceOverlay);
    }

    private void requestDemoState(boolean requestedEnabled,
            long generation, FaceSdkAdminOverlay sourceOverlay) {
        if (!isCurrentUi(generation, sourceOverlay) || subsystem == null) return;
        boolean supported = FaceBuildVariant.isLocalDemo();
        boolean actual = false;
        try {
            if (supported) {
                actual = subsystem.verificationEnvironment().setEnabled(requestedEnabled);
            } else {
                actual = subsystem.verificationEnvironment().setEnabled(false);
            }
        } catch (RuntimeException | LinkageError failure) {
            try {
                actual = subsystem.verificationEnvironment().isEnabled();
            } catch (RuntimeException | LinkageError ignored) {
                actual = false;
            }
            sourceOverlay.showOperationMessage("无法更新本机模拟状态", true);
        }
        if (isCurrentUi(generation, sourceOverlay)) {
            sourceOverlay.renderDemoState(supported, supported && actual);
        }
    }

    private void requestLivenessState(boolean enabled,
            long generation, FaceSdkAdminOverlay sourceOverlay) {
        if (!isCurrentUi(generation, sourceOverlay)) return;
        if (subsystem == null) {
            sourceOverlay.renderLivenessState(null);
            sourceOverlay.showOperationMessage("人脸管理组件暂时不可用", true);
            return;
        }
        boolean updateFailed = false;
        try {
            subsystem.setLivenessEnabled(enabled);
        } catch (RuntimeException | LinkageError failure) {
            updateFailed = true;
        }
        FaceSubsystem.Snapshot snapshot = null;
        try {
            snapshot = subsystem.snapshot();
        } catch (RuntimeException | LinkageError failure) {
            updateFailed = true;
        }
        if (!isCurrentUi(generation, sourceOverlay)) return;
        if (snapshot == null) {
            sourceOverlay.renderLivenessState(null);
        } else {
            sourceOverlay.renderLivenessState(snapshot.liveness());
        }
        if (updateFailed) {
            sourceOverlay.showOperationMessage("无法更新活体检测状态", true);
        }
    }

    private UiOperation beginUiOperation(OperationKind kind, long generation,
            FaceSdkAdminOverlay sourceOverlay) {
        if (!isCurrentUi(generation, sourceOverlay) || activeOperation != null) {
            return null;
        }
        nextOperationId = nextPositive(nextOperationId);
        UiOperation operation = new UiOperation(
                nextOperationId, generation, kind, sourceOverlay);
        operation.timeout = () -> onOperationTimeout(operation);
        activeOperation = operation;
        try {
            handler.postDelayed(operation.timeout,
                    FaceLicenseStateMachine.OPERATION_TIMEOUT_MILLIS);
            return operation;
        } catch (RuntimeException | LinkageError rejected) {
            activeOperation = null;
            operation.claimed = true;
            sourceOverlay.showOperationMessage("无法开始操作，请重试", true);
            return null;
        }
    }

    private void onOperationTimeout(UiOperation operation) {
        FaceSdkAdminOverlay sourceOverlay = operation.sourceOverlay;
        if (!isCurrentUi(operation.generation, sourceOverlay)
                || !claimUiOperation(operation)) return;
        if (operation.kind == OperationKind.ACTIVATION) {
            activationInFlight = false;
            sourceOverlay.setActivationInFlight(false);
        }
        if (operation.kind == OperationKind.ACTIVATION
                || operation.kind == OperationKind.LOCAL_CHECK) {
            licenseFailureKind = ZipAdminScreenRouter.LicenseFailureKind.RETRYABLE;
        }
        sourceOverlay.showOperationMessage("操作超时，请重试", true);
        renderCurrentSnapshot(operation.generation, sourceOverlay);
    }

    private boolean claimUiOperation(UiOperation operation) {
        if (operation == null || operation.claimed || activeOperation != operation) {
            return false;
        }
        operation.claimed = true;
        activeOperation = null;
        if (operation.timeout != null) {
            handler.removeCallbacks(operation.timeout);
        }
        closeOperationSubscription(operation);
        return true;
    }

    private void installLicenseSubscription(UiOperation operation,
            BaiduFaceLicenseManager.Subscription subscription, boolean activation) {
        if (subscription == null) {
            postToUi(() -> completeLicenseOperation(
                    operation, operation.sourceOverlay, false));
            return;
        }
        if (activeOperation != operation || operation.claimed
                || !isCurrentUi(operation.generation, operation.sourceOverlay)) {
            closeQuietly(subscription);
            return;
        }
        operation.licenseSubscription = subscription;
        if (activation) activationSubscription = subscription;
        else localCheckSubscription = subscription;
    }

    private void installRuntimeSubscription(
            UiOperation operation, BaiduFaceRuntime.Subscription subscription) {
        if (subscription == null) {
            postToUi(() -> completeRuntimeOperation(
                    operation, operation.sourceOverlay, false));
            return;
        }
        if (activeOperation != operation || operation.claimed
                || !isCurrentUi(operation.generation, operation.sourceOverlay)) {
            closeQuietly(subscription);
            return;
        }
        operation.runtimeSubscription = subscription;
        runtimeSubscription = subscription;
    }

    private void closeOperationSubscription(UiOperation operation) {
        BaiduFaceLicenseManager.Subscription license = operation.licenseSubscription;
        operation.licenseSubscription = null;
        if (license != null) {
            if (localCheckSubscription == license) localCheckSubscription = null;
            if (activationSubscription == license) activationSubscription = null;
            closeQuietly(license);
        }
        BaiduFaceRuntime.Subscription runtime = operation.runtimeSubscription;
        operation.runtimeSubscription = null;
        if (runtime != null) {
            if (runtimeSubscription == runtime) runtimeSubscription = null;
            closeQuietly(runtime);
        }
    }

    private void renderCurrentSnapshot(
            long generation, FaceSdkAdminOverlay sourceOverlay) {
        if (!isCurrentUi(generation, sourceOverlay) || subsystem == null) return;
        try {
            renderSnapshot(subsystem.snapshot(), generation, sourceOverlay);
        } catch (RuntimeException | LinkageError ignored) {
            sourceOverlay.showOperationMessage("无法读取人脸组件状态", true);
        }
    }

    private boolean isCurrentUi(
            long generation, FaceSdkAdminOverlay sourceOverlay) {
        return maintenanceAuthorized() && uiActive && generation > 0L && generation == uiGeneration
                && sourceOverlay != null && sourceOverlay == overlay;
    }

    private void postToUi(Runnable action) {
        if (action == null) return;
        try {
            handler.post(() -> {
                try { action.run(); }
                catch (RuntimeException | LinkageError ignored) { }
            });
        }
        catch (RuntimeException | LinkageError ignored) { }
    }

    private void cancelUiOperations() {
        UiOperation operation = activeOperation;
        activeOperation = null;
        if (operation != null) {
            operation.claimed = true;
            if (operation.timeout != null) handler.removeCallbacks(operation.timeout);
            closeOperationSubscription(operation);
        }
        closeQuietly(stateSubscription);
        stateSubscription = null;
        closeQuietly(localCheckSubscription);
        localCheckSubscription = null;
        closeQuietly(activationSubscription);
        activationSubscription = null;
        closeQuietly(runtimeSubscription);
        runtimeSubscription = null;
        activationInFlight = false;
        if (overlay != null) {
            overlay.setActivationInFlight(false);
            overlay.clearActivationInput();
        }
    }

    private static long nextPositive(long current) {
        return current == Long.MAX_VALUE ? 1L : current + 1L;
    }

    private boolean isNetworkOnline() {
        try {
            ConnectivityManager manager = (ConnectivityManager)
                    getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo network = manager == null ? null : manager.getActiveNetworkInfo();
            return network != null && network.isConnected();
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) return;
        try { closeable.close(); }
        catch (Exception | LinkageError ignored) { }
    }

    private final class OverlayListener implements FaceSdkAdminOverlay.Listener {
        @Override
        public void onActivateRequested(char[] ownedActivationCode) {
            requestActivation(ownedActivationCode, uiGeneration, overlay);
        }

        @Override
        public void onDemoEnabledChanged(boolean enabled) {
            requestDemoState(enabled, uiGeneration, overlay);
        }

        @Override
        public void onLivenessEnabledChanged(boolean enabled) {
            requestLivenessState(enabled, uiGeneration, overlay);
        }

        @Override
        public void onInitializeRuntimeRequested() {
            requestRuntimeInitialization(uiGeneration, overlay);
        }

        @Override
        public void onCloseRequested() {
            finish();
        }
    }

    private enum OperationKind {
        LOCAL_CHECK,
        ACTIVATION,
        RUNTIME
    }

    private static final class UiOperation {
        private final long id;
        private final long generation;
        private final OperationKind kind;
        private final FaceSdkAdminOverlay sourceOverlay;
        private Runnable timeout;
        private BaiduFaceLicenseManager.Subscription licenseSubscription;
        private BaiduFaceRuntime.Subscription runtimeSubscription;
        private boolean claimed;

        UiOperation(long id, long generation, OperationKind kind,
                FaceSdkAdminOverlay sourceOverlay) {
            this.id = id;
            this.generation = generation;
            this.kind = kind;
            this.sourceOverlay = sourceOverlay;
        }
    }
}
