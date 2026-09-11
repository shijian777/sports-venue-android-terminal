package com.codex.lockertest;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.codex.lockertest.admin.AdminCapability;
import com.codex.lockertest.admin.AdminCapabilityAssembly;
import com.codex.lockertest.admin.AdminCapabilityPolicy;
import com.codex.lockertest.bootstrap.BootstrapAssembly;
import com.codex.lockertest.bootstrap.BootstrapRuntime;
import com.codex.lockertest.bootstrap.BootstrapSnapshot;
import com.codex.lockertest.business.OnlineCustomerAssembly;
import com.codex.lockertest.business.journey.OnlineCustomerCoordinator;
import com.codex.lockertest.business.journey.OnlineUnlockExecutor;
import com.codex.lockertest.ui.business.OnlineCustomerHost;
import com.codex.lockertest.integration.CustomerConnectionWakeupPolicy;
import com.codex.lockertest.integration.CustomerSerialTransmitter;
import com.codex.lockertest.integration.CustomerSerialWritePolicy.CustomerSerialPhase;
import com.codex.lockertest.integration.GenerationGate;
import com.codex.lockertest.integration.OwnershipHandoffGate;
import com.codex.lockertest.integration.OnlineUnlockDispatchPermit;
import com.codex.lockertest.integration.PersistentSerialConnectionPolicy;
import com.codex.lockertest.integration.ProcessSerialGatewayOwner;
import com.codex.lockertest.face.AndroidFaceJpegEncoder;
import com.codex.lockertest.face.BaiduFaceLicenseManager;
import com.codex.lockertest.face.BaiduFaceRuntime;
import com.codex.lockertest.face.Camera1FaceCameraController;
import com.codex.lockertest.face.FaceCaptureSession;
import com.codex.lockertest.face.FaceEnrollmentCaptureController;
import com.codex.lockertest.face.FaceFrame;
import com.codex.lockertest.face.FaceFrameQualityGate;
import com.codex.lockertest.face.FaceLicenseStateMachine;
import com.codex.lockertest.face.FaceLivenessControl;
import com.codex.lockertest.face.FaceRecognitionController;
import com.codex.lockertest.face.FaceRuntimeStateMachine;
import com.codex.lockertest.face.FaceSubsystem;
import com.codex.lockertest.face.verification.FaceVerificationEnvironment;
import com.codex.lockertest.face.verification.FaceVerificationResult;
import com.codex.lockertest.face.verification.FaceVerificationTicketValidator;
import com.codex.lockertest.model.FeatureAvailability;
import com.codex.lockertest.model.LockerTarget;
import com.codex.lockertest.model.LockerZone;
import com.codex.lockertest.model.UnlockMethod;
import com.codex.lockertest.runtime.CredentialAdmission;
import com.codex.lockertest.runtime.CredentialAdmissionPolicy;
import com.codex.lockertest.runtime.CustomerUnlockAuthorizer;
import com.codex.lockertest.runtime.CustomerUnlockAuthorization;
import com.codex.lockertest.runtime.AdminCredentialPolicy;
import com.codex.lockertest.runtime.FaceBannerPolicy;
import com.codex.lockertest.runtime.RuntimeAssembly;
import com.codex.lockertest.runtime.RuntimeSerialLog;
import com.codex.lockertest.runtime.RuntimeServices;
import com.codex.lockertest.returnflow.ReturnDoorSession;
import com.codex.lockertest.returnflow.ReturnFlowController;
import com.codex.lockertest.returnflow.ReturnFlowModel;
import com.codex.lockertest.returnflow.ReturnIdentity;
import com.codex.lockertest.returnflow.ReturnLocker;
import com.codex.lockertest.returnflow.ReturnServiceAssembly;
import com.codex.lockertest.serial.BoardDiscoveryCoordinator;
import com.codex.lockertest.serial.SerialConfig;
import com.codex.lockertest.serial.SerialGateway;
import com.codex.lockertest.serial.SerialSessionState;
import com.codex.lockertest.serial.SerialWriteAttribution;
import com.codex.lockertest.protocol.DoorStateProtocol;
import com.codex.lockertest.ui.AdminFunctionOverlay;
import com.codex.lockertest.ui.AdminPinOverlay;
import com.codex.lockertest.ui.BiometricEnrollmentChoiceView;
import com.codex.lockertest.ui.BootstrapHomePresentation;
import com.codex.lockertest.ui.BootstrapUiSessionGate;
import com.codex.lockertest.ui.CredentialRecoveryCountdown;
import com.codex.lockertest.ui.CustomerActionBoundary;
import com.codex.lockertest.ui.FaceRecognitionView;
import com.codex.lockertest.ui.FaceEnrollmentView;
import com.codex.lockertest.ui.IdCardEventDrain;
import com.codex.lockertest.ui.IdCardScanSession;
import com.codex.lockertest.ui.KioskFlowModel;
import com.codex.lockertest.ui.LockerSelectionModel;
import com.codex.lockertest.ui.LockerSelectionView;
import com.codex.lockertest.ui.ResultOverlay;
import com.codex.lockertest.ui.ReturnAuthView;
import com.codex.lockertest.ui.ReturnLockerView;
import com.codex.lockertest.ui.ReturnProgressView;
import com.codex.lockertest.ui.TerminalReadinessSource;
import com.codex.lockertest.ui.UnavailableMethodView;
import com.codex.lockertest.ui.ZipHomeView;
import com.codex.lockertest.ui.zip.BiometricEnrollmentScreenRouter;
import com.codex.lockertest.ui.zip.ReturnScreenPresentation;
import com.codex.lockertest.ui.zip.ReturnScreenResolver;
import com.codex.lockertest.unlock.UnlockCoordinator;
import com.codex.lockertest.unlock.AuthorizedUnlockRequest;

import java.text.SimpleDateFormat;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends Activity {
    private enum CustomerOperationKind {
        NONE,
        DISCOVERY,
        UNLOCK,
        ONLINE_UNLOCK,
        RETURN
    }

    private enum FaceJourney {
        NONE,
        NORMAL,
        RETURN
    }

    private enum EnrollmentOrigin {
        HOME,
        ADMIN
    }

    private interface CustomerProtocolSink {
        void onConnected();

        void onConnectionFailed(String detail);

        void onSent(byte[] payload);

        void onBytes(byte[] bytes);

        void onSendFailed(byte[] payload, String detail);
    }

    private static final class GatewaySnapshot {
        final SerialSessionState.Phase phase;
        final SerialConfig config;

        GatewaySnapshot(SerialSessionState.Phase phase, SerialConfig config) {
            this.phase = phase;
            this.config = config;
        }
    }

    private static final String OPEN_FAILURE = "设备连接失败，请联系管理员。";
    private static final String SEND_FAILURE = "开柜指令发送失败，请再次尝试。";
    private static final String TIMEOUT_FAILURE = "设备无响应，请再次尝试。";
    private static final int NO_SCANNED_CHARACTER = -1;
    private static final long PASSIVE_SCAN_IDLE_COMPLETION_MILLIS = 500L;
    private static final long PASSIVE_SCAN_EVENT_DRAIN_MILLIS = 700L;
    private static final int INVALID_CREDENTIAL_RECOVERY_SECONDS = 8;
    private static final long INVALID_CREDENTIAL_TICK_MILLIS = 1_000L;
    /** Transport safety limit only; credential validity never depends on its length. */
    private static final int PASSIVE_SCAN_MAX_CHARACTERS = 4096;
    private static final int NO_CONSUMED_ID_CARD_KEY = -1;
    private static final int FACE_CAMERA_PERMISSION_REQUEST = 7301;
    private static final int ENROLLMENT_CAMERA_PERMISSION_REQUEST = 7302;
    private static final long FACE_CLEANUP_BARRIER_TIMEOUT_MILLIS = 15_000L;
    private static final long RETURN_POLL_INTERVAL_MILLIS = 500L;
    private static final long RETURN_POLL_BACKOFF_MILLIS = 1_000L;
    private static final long RETURN_POLL_TIMEOUT_MILLIS = 900L;
    private static final long RETURN_PALM_SIMULATION_MILLIS = 650L;
    private static final int RETURN_TERMINAL_COUNTDOWN_SECONDS = 8;
    private static final long RETURN_TERMINAL_TICK_MILLIS = 1_000L;
    private static final ExecutorService RETURN_SERVICE_EXECUTOR =
            Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "locker-return-service");
                thread.setDaemon(true);
                return thread;
            });
    private static final ExecutorService FACE_CLEANUP_EXECUTOR =
            Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "locker-face-cleanup");
                thread.setDaemon(true);
                return thread;
            });
    private static final ProcessSerialGatewayOwner<SerialGateway> SERIAL_GATEWAY_OWNER =
            ProcessSerialGatewayOwner.shared();
    private static final PersistentSerialConnectionPolicy SERIAL_CONNECTION_POLICY =
            PersistentSerialConnectionPolicy.shared();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final RuntimeSerialLog runtimeLog = RuntimeSerialLog.shared();
    private final GenerationGate gatewayGate = new GenerationGate();
    private final OwnershipHandoffGate adminHandoffGate = new OwnershipHandoffGate();
    private KioskFlowModel flow;
    private AdminCredentialPolicy adminCredentialPolicy;
    private AdminCapabilityPolicy adminCapabilityPolicy;
    private CredentialAdmissionPolicy credentialPolicy;
    private CustomerUnlockAuthorizer customerUnlockAuthorizer;
    private FaceBannerPolicy faceBannerPolicy;
    private FeatureAvailability featureAvailability;
    private BootstrapRuntime bootstrapRuntime;
    private OnlineCustomerHost onlineCustomerHost;
    private volatile OnlineUnlockDispatchPermit onlineUnlockPermit;
    private OnlineUnlockExecutor.Listener onlineUnlockListener;
    private volatile AtomicBoolean onlineUnlockCancelled;
    private BootstrapUiSessionGate bootstrapUiSessionGate;
    private TerminalReadinessSource terminalReadinessSource;
    private BootstrapHomePresentation bootstrapHomePresentation;
    private long bootstrapUiGeneration;
    private long bootstrapRuntimeGeneration = -1L;
    private CustomerActionBoundary customerActionBoundary;
    private final IdCardScanSession idCardScanSession =
            new IdCardScanSession(PASSIVE_SCAN_MAX_CHARACTERS);
    private final IdCardEventDrain idCardEventDrain = new IdCardEventDrain();
    private final CredentialRecoveryCountdown credentialRecoveryCountdown =
            new CredentialRecoveryCountdown(INVALID_CREDENTIAL_RECOVERY_SECONDS);
    private final SimpleDateFormat timeFormat =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.CHINA);
    private final CustomerSerialTransmitter customerSerialTransmitter =
            new CustomerSerialTransmitter(
                    this::writeAuthorizedCustomerPayload,
                    this::appendCustomerLog);

    private FrameLayout root;
    private ZipHomeView homeView;
    private LockerSelectionView lockerSelectionView;
    private ResultOverlay resultOverlay;
    private AdminPinOverlay adminPinOverlay;
    private AdminFunctionOverlay adminFunctionOverlay;
    private FaceRecognitionView faceRecognitionView;
    private FaceRecognitionController faceController;
    private com.codex.lockertest.face.verification.OnlineFaceVerificationClient onlineFaceClient;
    private BiometricEnrollmentChoiceView biometricEnrollmentChoiceView;
    private FaceEnrollmentView faceEnrollmentView;
    private FrameLayout enrollmentStack;
    private FaceEnrollmentCaptureController enrollmentCaptureController;
    private final BiometricEnrollmentScreenRouter.EnrollmentSessionGate
            enrollmentSessionGate =
            new BiometricEnrollmentScreenRouter.EnrollmentSessionGate();
    private BiometricEnrollmentScreenRouter.Route enrollmentRoute;
    private EnrollmentOrigin enrollmentOrigin;
    private boolean enrollmentJourneyActive;
    private boolean enrollmentRgbLiveness;
    private long enrollmentGeneration;
    private long enrollmentSensitiveGeneration;
    private long activeEnrollmentSessionId;
    private long permissionEnrollmentGeneration;
    private BiometricEnrollmentChoiceView permissionEnrollmentView;
    private boolean enrollmentPermissionRequestPending;
    private long enrollmentCleanupBarrierToken;
    private long enrollmentCleanupBarrierGeneration;
    private FaceEnrollmentView enrollmentCleanupBarrierView;
    private Runnable enrollmentCleanupBarrierTimeout;
    private FaceRecognitionController.Success pendingFaceSuccess;
    private FaceJourney faceJourney = FaceJourney.NONE;
    private FaceSubsystem faceSubsystem;
    private BoardDiscoveryCoordinator discoveryCoordinator;
    private UnlockCoordinator unlockCoordinator;
    private UnlockCoordinator returnUnlockCoordinator;
    private ReturnFlowController returnFlowController;
    private ReturnAuthView returnAuthView;
    private ReturnLockerView returnLockerView;
    private ReturnProgressView returnProgressView;

    private boolean returnJourneyActive;
    private boolean returnLocalDemo;
    private boolean returnServiceBusy;
    private boolean returnBackgrounded;
    private boolean returnResumeRequiresAuth;
    private boolean returnUnlockConsumed;
    private boolean returnSerialFailure;
    private boolean returnCommitDeferred;
    private boolean returnAuthenticationFailed;
    private boolean returnCredentialReading;
    private boolean returnImmediatePollPending;
    private long returnGeneration;
    private long returnAsyncEpoch;
    private long nextReturnOperationId;
    private long returnOperationId;
    private long returnSerialProtocolId;
    private long returnUnlockAttemptId;
    private long returnUnlockDispatchGeneration;
    private long returnUnlockDispatchOperationId;
    private long returnUnlockDispatchCustomerToken;
    private long returnActivePollId;
    private ReturnLocker returnChosenLocker;
    private AuthorizedUnlockRequest returnAuthorizedRequest;
    private AuthorizedUnlockRequest returnUnlockDispatchRequest;
    private ReturnDoorSession returnDoorSession;
    private String returnStatusMessage;
    private String returnUnlockGateFailureMessage;
    private Runnable returnPollTask;
    private Runnable returnPollTimeoutTask;
    private Runnable returnPalmTask;
    private Runnable returnTerminalTask;
    private int returnTerminalSecondsRemaining;

    private CustomerOperationKind customerOperationKind = CustomerOperationKind.NONE;
    private CustomerProtocolSink customerProtocolSink;
    private ProcessSerialGatewayOwner.Lease<SerialGateway> customerGatewayLease;
    private SerialGateway.Subscription customerGatewaySubscription;
    private PersistentSerialConnectionPolicy.Attempt customerConnectionAttempt;
    private long customerOperationToken;
    private long customerOperationUiGeneration;
    private long customerProtocolId;
    private long customerGatewayGeneration;
    private boolean customerProtocolConnected;
    private CustomerSerialPhase customerSerialPhase = CustomerSerialPhase.TERMINAL;
    private SerialWriteAttribution customerWriteAttribution;
    private LockerTarget customerConfirmedTarget;
    private boolean customerOperationTokenExhausted;

    private long uiGeneration;
    private long faceGeneration;
    private long activeFaceSessionId;
    private long permissionFaceGeneration;
    private FaceRecognitionView permissionFaceView;
    private FaceRecognitionController permissionFaceController;
    private boolean facePermissionRequestPending;
    private String faceDeferredMessage;
    private boolean faceDeferredRetryable = true;
    private boolean faceDeferredPermissionDenied;
    private boolean faceDeferredPermissionPermanent;
    private long faceCleanupBarrierToken;
    private long faceCleanupBarrierGeneration;
    private FaceRecognitionView faceCleanupBarrierView;
    private Runnable faceCleanupBarrierTimeout;
    private static volatile boolean faceCleanupUnavailable;
    private boolean active;
    private boolean adminLaunchPending;
    private boolean onlineMaintenanceHandoff;
    private com.codex.lockertest.admin.OnlineMaintenanceGrant.Ticket onlineMaintenanceTicket;
    private Runnable idCardIdleCompletion;
    private Runnable invalidCredentialRecovery;
    private int consumedIdCardKeyCode = NO_CONSUMED_ID_CARD_KEY;
    private int consumedIdCardKeyDeviceId = Integer.MIN_VALUE;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        RuntimeServices runtime = RuntimeAssembly.create(getApplicationContext());
        faceBannerPolicy = RuntimeAssembly.createFaceBannerPolicy();
        adminCredentialPolicy = runtime.adminPolicy();
        adminCapabilityPolicy = AdminCapabilityAssembly.create();
        credentialPolicy = runtime.credentialPolicy();
        bootstrapRuntime = BootstrapAssembly.create(getApplicationContext());
        bootstrapUiSessionGate = new BootstrapUiSessionGate(bootstrapRuntime);
        terminalReadinessSource = bootstrapRuntime.readinessSource();
        bootstrapHomePresentation = BootstrapHomePresentation.create(
                terminalReadinessSource.current(), bootstrapRuntime.snapshot());
        customerActionBoundary = new CustomerActionBoundary(terminalReadinessSource);
        flow = new KioskFlowModel(credentialPolicy, runtime.layoutPolicy(), terminalReadinessSource);
        customerUnlockAuthorizer = runtime.unlockAuthorizer();
        featureAvailability = runtime.featureAvailability();
        configureWindow();
        root = new FrameLayout(this);
        setContentView(root);
        onlineCustomerHost = OnlineCustomerAssembly.create(this, new OnlineCustomerHost.Ui() {
            @Override public void show(View view) {
                if (!active) return;
                if (homeView != null) homeView.clearInputs();
                homeView = null;
                resetPassiveCredentialCapture();
                replaceRoot(view);
            }
            @Override public void home() {
                if (active) {
                    onlineFaceClient = null;
                    cancelFaceWorkAndInvalidate();
                    renderHome();
                }
            }
            @Override public void message(String message) {
                if (active && homeView != null) homeView.showValidationError(message);
            }
            @Override public void maintenanceRequested(
                    com.codex.lockertest.admin.OnlineMaintenanceGrant.Target target,
                    java.util.function.BooleanSupplier authorized, Runnable revokeSession) {
                launchOnlineMaintenance(target, authorized, revokeSession);
            }
        }, new OnlinePhysicalExecutor());
        try {
            faceSubsystem = FaceSubsystem.shared(getApplicationContext());
        } catch (RuntimeException | LinkageError ignored) {
            faceSubsystem = null;
        }

        BoardDiscoveryCoordinator.Scheduler discoveryScheduler = (task, delayMillis) -> {
            handler.postDelayed(task, delayMillis);
            return () -> handler.removeCallbacks(task);
        };
        discoveryCoordinator = new BoardDiscoveryCoordinator(
                new DiscoverySerialActions(),
                discoveryScheduler,
                new BoardDiscoveryCoordinator.Listener() {
                    @Override
                    public void onDiscoveryCompleted(
                            long scanId, List<LockerZone> onlineZones) {
                        enqueueDiscoveryCompleted(scanId, onlineZones);
                    }

                    @Override
                    public void onDiscoveryFailed(
                            long scanId, BoardDiscoveryCoordinator.Failure failure) {
                        enqueueDiscoveryFailed(scanId, failure);
                    }

                });

        unlockCoordinator = new UnlockCoordinator(
                new UnlockSerialActions(),
                (task, delayMillis) -> {
                    handler.postDelayed(task, delayMillis);
                    return () -> handler.removeCallbacks(task);
                },
                this::enqueueUnlockState);

        returnFlowController = new ReturnFlowController(
                ReturnServiceAssembly.create(), System::currentTimeMillis);
        returnLocalDemo = ReturnServiceAssembly.isLocalDemo();
        returnUnlockCoordinator = new UnlockCoordinator(
                new ReturnUnlockSerialActions(),
                (task, delayMillis) -> {
                    handler.postDelayed(task, delayMillis);
                    return () -> handler.removeCallbacks(task);
                },
                this::handleReturnUnlockState);

        flow.returnHome();
        renderScreen();
    }

    @Override
    protected void onStart() {
        super.onStart();
        active = true;
        if (onlineMaintenanceHandoff) {
            onlineMaintenanceHandoff = false;
            if (onlineMaintenanceTicket != null) onlineMaintenanceTicket.finish(false);
            onlineMaintenanceTicket = null;
            if (onlineCustomerHost != null && onlineCustomerHost.resumeAdmin()) return;
        }
        startBootstrapSession();
        if (customerActionsEnabled() && returnJourneyActive) {
            customerActionBoundary.run(
                    CustomerActionBoundary.Effect.RESUME,
                    () -> resumeReturnJourneyFromBackground());
        } else if (flow.screen() == KioskFlowModel.Screen.FACE_RECOGNITION) {
            returnHome("人脸识别已中断，请重新选择");
        } else {
            returnHome("顾客页面重新进入");
        }
        ensureInitialDefaultConnection();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // A rejected/failed child can return before Main ever reaches onStop/onStart.
        if (onlineMaintenanceHandoff) {
            onlineMaintenanceHandoff = false;
            if (onlineMaintenanceTicket != null) onlineMaintenanceTicket.finish(false);
            onlineMaintenanceTicket = null;
            if (onlineCustomerHost == null || !onlineCustomerHost.resumeAdmin()) {
                startBootstrapSession();
                returnHome("维护会话已结束，请重新登录");
            }
        }
    }

    @Override
    protected void onStop() {
        if (!onlineMaintenanceHandoff) {
            if (onlineCustomerHost != null) onlineCustomerHost.unbind();
            stopBootstrapSession();
        }
        pauseReturnJourneyForBackground();
        abandonEnrollmentJourney();
        active = false;
        resetPassiveCredentialCapture();
        idCardEventDrain.clear();
        resetConsumedIdCardKey();
        cancelPendingAdminLaunch();
        invalidateCustomerWork(true);
        cancelFaceWorkAndInvalidate();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (onlineMaintenanceTicket != null) onlineMaintenanceTicket.finish(true);
        onlineMaintenanceTicket = null;
        if (onlineCustomerHost != null) onlineCustomerHost.close();
        closeBootstrapRuntime();
        cancelReturnTimers();
        abandonEnrollmentJourney();
        cancelFaceWorkAndInvalidate();
        super.onDestroy();
    }

    private void startBootstrapSession() {
        BootstrapRuntime runtime = bootstrapRuntime;
        if (runtime == null) return;
        final long session = ++bootstrapUiGeneration;
        bootstrapRuntimeGeneration = -1L;
        long generation = runtime.start(snapshot -> handler.post(
                () -> handleBootstrapSnapshot(runtime, session, snapshot)));
        bootstrapRuntimeGeneration = generation;
        refreshBootstrapHomePresentation();
    }

    private void handleBootstrapSnapshot(
            BootstrapRuntime runtime, long session, BootstrapSnapshot snapshot) {
        if (!active
                || runtime != bootstrapRuntime
                || session != bootstrapUiGeneration
                || snapshot == null
                || snapshot.generation() != bootstrapRuntimeGeneration
                || snapshot != runtime.snapshot()) {
            return;
        }
        refreshBootstrapHomePresentation();
        if (flow.screen() == KioskFlowModel.Screen.HOME
                && (onlineCustomerHost == null || !onlineCustomerHost.showingJourney())) {
            renderHome();
        }
    }

    private void retryBootstrapSession() {
        if (!active || bootstrapUiSessionGate == null
                || !bootstrapUiSessionGate.canRetryBootstrap()) {
            return;
        }
        long generation = bootstrapUiSessionGate.restartBootstrap();
        if (generation < 0L) return;
        bootstrapRuntimeGeneration = generation;
        refreshBootstrapHomePresentation();
        if (flow.screen() == KioskFlowModel.Screen.HOME) renderHome();
    }

    private void stopBootstrapSession() {
        bootstrapUiGeneration++;
        bootstrapRuntimeGeneration = -1L;
        BootstrapRuntime runtime = bootstrapRuntime;
        if (runtime != null) runtime.cancel();
    }

    private void closeBootstrapRuntime() {
        bootstrapUiGeneration++;
        bootstrapRuntimeGeneration = -1L;
        BootstrapRuntime runtime = bootstrapRuntime;
        bootstrapRuntime = null;
        if (runtime != null) runtime.close();
    }

    private void refreshBootstrapHomePresentation() {
        BootstrapRuntime runtime = bootstrapRuntime;
        if (runtime == null) return;
        if (onlineCustomerHost != null) onlineCustomerHost.bind(runtime.snapshot());
        if (onlineFaceClient != null && (onlineCustomerHost == null
                || !onlineCustomerHost.isCurrentFace(onlineFaceClient))) {
            onlineFaceClient = null;
            cancelFaceWorkAndInvalidate();
        }
        bootstrapHomePresentation = BootstrapHomePresentation.createForOnlineBrowsing(
                runtime.readinessSource().current(), runtime.snapshot(),
                onlineCustomerHost != null && onlineCustomerHost.ready());
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUi();
        }
    }

    @Override
    public void onBackPressed() {
        if (!active) {
            return;
        }
        if (onlineCustomerHost != null && onlineCustomerHost.hasJourney()) {
            returnHome("顾客取消在线操作");
            return;
        }
        if (enrollmentJourneyActive) {
            handleEnrollmentBack();
            return;
        }
        if (returnJourneyActive) {
            cancelReturnJourneyFromUser();
            return;
        }
        KioskFlowModel.Screen priorScreen = flow.screen();
        KioskFlowModel.ResultContext priorContext = flow.resultContext();
        if (priorScreen == KioskFlowModel.Screen.HOME
                || (priorScreen == KioskFlowModel.Screen.RESULT
                && priorContext == KioskFlowModel.ResultContext.NONE)) {
            return;
        }
        cancelFaceWorkAndInvalidate();
        cancelPendingAdminLaunch();
        invalidateCustomerWork(true);
        if (!flow.back()) {
            return;
        }
        renderScreen();
        if (priorContext == KioskFlowModel.ResultContext.DISCOVERY_FAILURE
                && flow.screen() == KioskFlowModel.Screen.LOCKER_SELECTION) {
            startDiscoveryOperation();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event == null) {
            return false;
        }
        if (onlineCustomerHost != null && onlineCustomerHost.showingAdmin()) {
            return super.dispatchKeyEvent(event);
        }
        int action = event.getAction();
        int keyCode = event.getKeyCode();
        int scannedCharacter = scannedCredentialCharacter(event);
        boolean terminator = isIdCardTerminator(keyCode);
        boolean idCardInputKey = scannedCharacter != NO_SCANNED_CHARACTER || terminator;
        if (idCardInputKey && onlineCustomerHost != null && onlineCustomerHost.ready()
                && active && flow != null && flow.screen() == KioskFlowModel.Screen.HOME
                && !adminLaunchPending && adminPinOverlay == null
                && (homeView != null || onlineCustomerHost.showingJourney())) {
            return onlineCustomerHost.scannerKey(event.getDeviceId(), scannedCharacter,
                    terminator, action, event.getRepeatCount());
        }
        if (!customerActionsEnabled()
                && idCardInputKey
                && flow != null
                && (flow.screen() == KioskFlowModel.Screen.HOME
                || returnJourneyActive)) {
            return true;
        }
        if (idCardEventDrain.shouldConsume(
                SystemClock.uptimeMillis(),
                event.getDeviceId(),
                idCardInputKey)) {
            if (action == KeyEvent.ACTION_UP
                    && keyCode == consumedIdCardKeyCode
                    && event.getDeviceId() == consumedIdCardKeyDeviceId) {
                resetConsumedIdCardKey();
            }
            return true;
        }
        if (action == KeyEvent.ACTION_UP
                && keyCode == consumedIdCardKeyCode
                && event.getDeviceId() == consumedIdCardKeyDeviceId) {
            resetConsumedIdCardKey();
            return true;
        }
        if (!isPassiveCredentialCaptureActive()) {
            return super.dispatchKeyEvent(event);
        }

        if (scannedCharacter == NO_SCANNED_CHARACTER && !terminator) {
            return super.dispatchKeyEvent(event);
        }
        if (action == KeyEvent.ACTION_DOWN) {
            consumedIdCardKeyCode = keyCode;
            consumedIdCardKeyDeviceId = event.getDeviceId();
            if (event.getRepeatCount() == 0) {
                if (terminator) {
                    finishPassiveCredentialInputNow(event.getDeviceId());
                } else {
                    char character = scannedCharacter <= Character.MAX_VALUE
                            ? (char) scannedCharacter : Character.MAX_VALUE;
                    acceptPassiveCredentialCharacter(event.getDeviceId(), character);
                }
            }
        }
        return true;
    }

    private void returnHome(String reason) {
        if (onlineCustomerHost != null) onlineCustomerHost.cancel();
        onlineFaceClient = null;
        faceJourney = FaceJourney.NONE;
        enrollmentJourneyActive = false;
        enrollmentOrigin = null;
        enrollmentRoute = null;
        cancelEnrollmentWorkAndInvalidate();
        cancelFaceWorkAndInvalidate();
        invalidateCustomerWork(true);
        pendingFaceSuccess = null;
        clearFaceDeferredFailure();
        flow.returnHome();
        appendCustomerLog("[Status] " + reason);
        renderScreen();
    }

    private void cancelPendingAdminLaunch() {
        adminLaunchPending = false;
        adminHandoffGate.invalidate();
    }

    private boolean customerActionsEnabled() {
        return customerActionBoundary != null
                && customerActionBoundary.customerActionsEnabled();
    }

    private void ensureInitialDefaultConnection() {
        if (!customerActionsEnabled()) {
            return;
        }
        ProcessSerialGatewayOwner.Lease<SerialGateway> bootstrapLease =
                customerActionBoundary.call(
                        CustomerActionBoundary.Effect.SERIAL_CONNECT,
                        () -> SERIAL_GATEWAY_OWNER.acquire(
                                ProcessSerialGatewayOwner.Role.CUSTOMER,
                                SerialGateway::new),
                        null);
        if (bootstrapLease == null) {
            return;
        }
        PersistentSerialConnectionPolicy.Action action =
                SERIAL_GATEWAY_OWNER.withGateway(
                        bootstrapLease,
                        gateway -> SERIAL_CONNECTION_POLICY.onMainStarted(gateway.phase()),
                        PersistentSerialConnectionPolicy.Action.FAIL);
        if (action == PersistentSerialConnectionPolicy.Action.OPEN_DEFAULTS) {
            boolean accepted = SERIAL_GATEWAY_OWNER.withGateway(
                    bootstrapLease,
                    gateway -> gateway.open(SerialConfig.defaults()),
                    false);
            if (accepted) {
                appendCustomerLog("[Status] 进程首次启动，正在按默认配置打开串口");
            }
        }
        SERIAL_GATEWAY_OWNER.relinquish(bootstrapLease);
    }

    private void renderScreen() {
        if (returnJourneyActive) {
            renderReturnSnapshot();
            return;
        }
        if (flow.screen() != KioskFlowModel.Screen.HOME) {
            resetPassiveCredentialCapture();
        }
        switch (flow.screen()) {
            case HOME:
                renderHome();
                break;
            case FACE_RECOGNITION:
                renderFaceRecognition();
                break;
            case UNAVAILABLE:
                renderUnavailable();
                break;
            case LOCKER_SELECTION:
                renderLockerSelection();
                break;
            case RESULT:
                if (resultOverlay != null) {
                    resultOverlay.bringToFront();
                }
                break;
            case ADMIN_PIN:
                renderAdminPin();
                break;
            default:
                throw new IllegalArgumentException("unsupported customer screen");
        }
    }

    private void renderHome() {
        resetPassiveCredentialCapture();
        idCardEventDrain.clear();
        resetConsumedIdCardKey();
        homeView = createHomeView();
        lockerSelectionView = null;
        resultOverlay = null;
        adminPinOverlay = null;
        adminFunctionOverlay = null;
        faceRecognitionView = null;
        biometricEnrollmentChoiceView = null;
        faceEnrollmentView = null;
        enrollmentStack = null;
        replaceRoot(homeView);
    }

    private ZipHomeView createHomeView() {
        ZipHomeView home = new ZipHomeView(
                this, credentialPolicy, featureAvailability, bootstrapHomePresentation);
        home.setListener(new ZipHomeView.Listener() {
            @Override
            public void onCredentialSubmit(UnlockMethod method, String rawValue) {
                submitCredential(method, rawValue);
            }

            @Override
            public void onUnavailableSelected(UnlockMethod method) {
                openUnavailable(method);
            }

            @Override
            public void onFaceRequested() {
                if (onlineCustomerHost != null) {
                    beginOnlineFaceRecognition();
                    return;
                }
                beginFaceRecognition();
            }

            @Override
            public void onReturnRequested() {
                if (onlineCustomerHost != null) {
                    onlineCustomerHost.prepareReturn();
                    return;
                }
                beginReturnJourney();
            }

            @Override
            public void onEnrollmentRequested() {
                if (onlineCustomerHost != null) {
                    home.showValidationError("掌纹与服务器用户的关联规则尚未配置");
                    return;
                }
                beginEnrollment(EnrollmentOrigin.HOME);
            }

            @Override
            public void onAdminRequested() {
                if (onlineCustomerHost != null) {
                    onlineCustomerHost.beginAdmin();
                    return;
                }
                showAdminPin();
            }

            @Override
            public void onBootstrapRetryRequested() {
                retryBootstrapSession();
            }
        });
        if (onlineCustomerHost != null) {
            home.setReturnMode(onlineCustomerHost.returning());
            home.setPairedCredentialListener((phone, pickupCode) -> {
                if (!active || home != homeView || adminLaunchPending || adminPinOverlay != null) return;
                onlineCustomerHost.submitPhone(phone, pickupCode);
            });
            if (onlineCustomerHost.ready()) home.showOnlineStatus(onlineCustomerHost.homeStatus());
        }
        home.setCredentialRecoveryListener(() -> {
            if (homeView != home || !isPassiveCredentialCaptureActive()) {
                return;
            }
            resetPassiveCredentialCapture();
            home.showCredentialWaiting();
        });
        return home;
    }

    private void beginEnrollment(EnrollmentOrigin origin) {
        if (origin == EnrollmentOrigin.HOME) {
            if (!customerActionsEnabled()) {
                return;
            }
            boolean requested = customerActionBoundary.call(
                    CustomerActionBoundary.Effect.HOME_ENROLLMENT,
                    flow::requestEnrollment,
                    false);
            if (!requested) {
                return;
            }
        }
        if (!active || origin == null || enrollmentJourneyActive
                || returnJourneyActive || adminLaunchPending) {
            return;
        }
        boolean validOrigin = origin == EnrollmentOrigin.HOME
                ? flow.screen() == KioskFlowModel.Screen.HOME
                : flow.screen() == KioskFlowModel.Screen.ADMIN_PIN
                        && adminFunctionOverlay != null;
        if (!validOrigin) return;
        cancelFaceWorkAndInvalidate();
        invalidateCustomerWork(true);
        resetPassiveCredentialCapture();
        enrollmentJourneyActive = true;
        enrollmentOrigin = origin;
        renderEnrollmentChoice();
    }

    private void renderEnrollmentChoice() {
        if (!active || !enrollmentJourneyActive || enrollmentOrigin == null) return;
        cancelEnrollmentWorkAndInvalidate();
        final BiometricEnrollmentChoiceView choice =
                new BiometricEnrollmentChoiceView(this, enrollmentOrigin == EnrollmentOrigin.ADMIN);
        biometricEnrollmentChoiceView = choice;
        enrollmentRoute = BiometricEnrollmentScreenRouter.choice(
                enrollmentRouterOrigin());
        choice.setListener(new BiometricEnrollmentChoiceView.Listener() {
            @Override public void onFaceRequested() {
                if (isCurrentEnrollmentChoice(choice)) {
                    beginFaceEnrollmentPreflight();
                }
            }

            @Override public void onPalmRequested() {
                if (!isCurrentEnrollmentChoice(choice)) return;
                enrollmentRoute = BiometricEnrollmentScreenRouter.palmUnavailable(
                        enrollmentRouterOrigin());
                choice.showPalmUnavailable();
            }

            @Override public void onRetryRequested() {
                if (isCurrentEnrollmentChoice(choice)) {
                    retryFaceEnrollmentPreflight();
                }
            }

            @Override public void onBackRequested() {
                if (isCurrentEnrollmentChoice(choice)) handleEnrollmentBack();
            }
        });
        homeView = null;
        adminFunctionOverlay = null;
        faceRecognitionView = null;
        faceEnrollmentView = null;
        enrollmentStack = null;
        replaceRoot(choice);
    }

    private void beginFaceEnrollmentPreflight() {
        BiometricEnrollmentChoiceView choice = biometricEnrollmentChoiceView;
        if (!isCurrentEnrollmentChoice(choice)) return;
        FaceSubsystem.Snapshot snapshot = safeFaceSnapshot();
        FaceLicenseStateMachine.State license = snapshot == null
                ? FaceLicenseStateMachine.State.UNKNOWN : snapshot.licenseState();
        FaceRuntimeStateMachine.State runtime = snapshot == null
                ? FaceRuntimeStateMachine.State.UNINITIALIZED : snapshot.runtimeState();
        boolean permissionGranted = cameraPermissionGranted();
        BiometricEnrollmentScreenRouter.CameraAcquirePermit permit =
                BiometricEnrollmentScreenRouter.permitCameraAcquire(
                        enrollmentRouterOrigin(), isNetworkOnline(), license, runtime,
                        permissionGranted);
        if (!permit.allowed()) {
            BiometricEnrollmentScreenRouter.Route blocked = permit.blockedRoute();
            if (blocked.blocker()
                    == BiometricEnrollmentScreenRouter.Blocker.CAMERA_PERMISSION_DENIED) {
                requestEnrollmentCameraPermission(choice);
            } else {
                showEnrollmentBlocker(choice, blocked);
            }
            return;
        }
        FaceLivenessControl.Snapshot liveness = snapshot == null
                ? null : snapshot.liveness();
        enrollmentRgbLiveness = liveness != null
                && liveness.capability() == FaceLivenessControl.Capability.SUPPORTED
                && liveness.mode() == FaceLivenessControl.Mode.RGB_LIVENESS;
        startEnrollmentCameraAcquire(choice);
    }

    private FaceSubsystem.Snapshot safeFaceSnapshot() {
        FaceSubsystem subsystem = faceSubsystem;
        if (subsystem == null) return null;
        try { return subsystem.snapshot(); }
        catch (RuntimeException | LinkageError ignored) { return null; }
    }

    private boolean cameraPermissionGranted() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || checkSelfPermission(Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestEnrollmentCameraPermission(
            BiometricEnrollmentChoiceView choice) {
        if (!isCurrentEnrollmentChoice(choice) || enrollmentPermissionRequestPending) {
            return;
        }
        enrollmentPermissionRequestPending = true;
        permissionEnrollmentGeneration = enrollmentGeneration;
        permissionEnrollmentView = choice;
        try {
            requestPermissions(new String[] {Manifest.permission.CAMERA},
                    ENROLLMENT_CAMERA_PERMISSION_REQUEST);
        } catch (RuntimeException | LinkageError ignored) {
            clearEnrollmentPermissionRequest();
            showEnrollmentBlocker(choice,
                    BiometricEnrollmentScreenRouter.facePreflight(
                            enrollmentRouterOrigin(), true,
                            FaceLicenseStateMachine.State.READY,
                            FaceRuntimeStateMachine.State.READY,
                            false, false));
        }
    }

    private void startEnrollmentCameraAcquire(
            BiometricEnrollmentChoiceView choice) {
        if (!isCurrentEnrollmentChoice(choice)) return;
        cancelEnrollmentWorkAndInvalidate();
        enrollmentGeneration = nextGeneration(enrollmentGeneration);
        final long expectedGeneration = enrollmentGeneration;

        final FaceEnrollmentView sourceView = new FaceEnrollmentView(this);
        faceEnrollmentView = sourceView;
        sourceView.setListener(new FaceEnrollmentView.Listener() {
            @Override public void onBackRequested() {
                if (isCurrentEnrollmentView(sourceView, expectedGeneration)) {
                    renderEnrollmentChoice();
                }
            }

            @Override public void onRetryRequested() {
                if (!isCurrentEnrollmentView(sourceView, expectedGeneration)) return;
                renderEnrollmentChoice();
                handler.post(MainActivity.this::retryFaceEnrollmentPreflight);
            }
        });

        FrameLayout stack = new FrameLayout(this);
        enrollmentStack = stack;
        root.removeAllViews();
        stack.addView(sourceView, match());
        stack.addView(choice, match());
        root.addView(stack, match());
        awaitEnrollmentCleanupBarrier(sourceView, expectedGeneration);
    }

    private void awaitEnrollmentCleanupBarrier(
            FaceEnrollmentView sourceView, long expectedGeneration) {
        if (!isCurrentEnrollmentView(sourceView, expectedGeneration)) return;
        enrollmentCleanupBarrierToken = nextGeneration(enrollmentCleanupBarrierToken);
        final long expectedToken = enrollmentCleanupBarrierToken;
        enrollmentCleanupBarrierGeneration = expectedGeneration;
        enrollmentCleanupBarrierView = sourceView;
        Runnable timeout = () -> failEnrollmentCleanupBarrier(
                sourceView, expectedGeneration, expectedToken);
        enrollmentCleanupBarrierTimeout = timeout;
        if (!handler.postDelayed(timeout, FACE_CLEANUP_BARRIER_TIMEOUT_MILLIS)) {
            failEnrollmentCleanupBarrier(sourceView, expectedGeneration, expectedToken);
            return;
        }
        try {
            FACE_CLEANUP_EXECUTOR.execute(() -> handler.post(() ->
                    completeEnrollmentCleanupBarrier(
                            sourceView, expectedGeneration, expectedToken)));
        } catch (RejectedExecutionException ignored) {
            faceCleanupUnavailable = true;
            failEnrollmentCleanupBarrier(sourceView, expectedGeneration, expectedToken);
        }
    }

    private void completeEnrollmentCleanupBarrier(
            FaceEnrollmentView sourceView, long expectedGeneration,
            long expectedToken) {
        if (!isCurrentEnrollmentBarrier(sourceView, expectedGeneration, expectedToken)) {
            return;
        }
        clearEnrollmentCleanupBarrier();
        if (faceCleanupUnavailable || faceSubsystem == null) {
            failEnrollmentCameraAcquire(
                    BiometricEnrollmentScreenRouter.Blocker.CAMERA_UNAVAILABLE);
            return;
        }
        try {
            enrollmentSensitiveGeneration = enrollmentSessionGate.begin();
        } catch (IllegalStateException ignored) {
            faceCleanupUnavailable = true;
            failEnrollmentCameraAcquire(
                    BiometricEnrollmentScreenRouter.Blocker.CAMERA_UNAVAILABLE);
            return;
        }
        FaceEnrollmentCaptureController controller;
        try {
            controller = createEnrollmentCaptureController(
                    sourceView, expectedGeneration);
        } catch (RuntimeException | LinkageError ignored) {
            controller = null;
        }
        if (controller == null
                || !isCurrentEnrollmentView(sourceView, expectedGeneration)) {
            enqueueEnrollmentControllerCleanup(controller,
                    enrollmentSensitiveGeneration);
            failEnrollmentCameraAcquire(
                    BiometricEnrollmentScreenRouter.Blocker.CAMERA_UNAVAILABLE);
            return;
        }
        enrollmentCaptureController = controller;
        long sessionId;
        try { sessionId = controller.start(); }
        catch (RuntimeException | LinkageError ignored) { sessionId = 0L; }
        activeEnrollmentSessionId = sessionId;
        if (sessionId == 0L) {
            failEnrollmentCameraAcquire(
                    BiometricEnrollmentScreenRouter.Blocker.CAMERA_UNAVAILABLE);
        }
    }

    private FaceEnrollmentCaptureController createEnrollmentCaptureController(
            FaceEnrollmentView sourceView, long expectedGeneration) {
        final FaceEnrollmentCaptureController[] owner =
                new FaceEnrollmentCaptureController[1];
        final long sensitiveGeneration = enrollmentSensitiveGeneration;
        FaceCaptureSession.Scheduler scheduler = (task, delayMillis) -> {
            boolean accepted = handler.postDelayed(task, delayMillis);
            return accepted ? () -> handler.removeCallbacks(task) : null;
        };
        FaceEnrollmentCaptureController.Listener listener =
                new FaceEnrollmentCaptureController.Listener() {
                    @Override public void onCameraReady(long sessionId) {
                        postEnrollmentCallback(expectedGeneration, sourceView,
                                owner[0], sessionId,
                                () -> revealEnrollmentFaceView(sourceView));
                    }

                    @Override public void onStateChanged(long sessionId,
                            FaceCaptureSession.State state) {
                        postEnrollmentCallback(expectedGeneration, sourceView,
                                owner[0], sessionId,
                                () -> renderEnrollmentState(sourceView, state));
                    }

                    @Override public void onQualityDecision(long sessionId,
                            FaceFrameQualityGate.Decision decision) {
                        postEnrollmentCallback(expectedGeneration, sourceView,
                                owner[0], sessionId,
                                () -> sourceView.showDetecting(
                                        decision.customerHint(), null));
                    }

                    @Override public boolean onCaptureCompleted(
                            long sessionId, byte[] ownedJpeg) {
                        boolean accepted = enrollmentSessionGate.acceptOwnedBuffer(
                                sensitiveGeneration, ownedJpeg);
                        if (accepted) {
                            postEnrollmentCallback(expectedGeneration, sourceView,
                                    owner[0], sessionId,
                                    sourceView::showCaptureComplete);
                        }
                        return accepted;
                    }

                    @Override public void onFailure(long sessionId,
                            String safeMessage, boolean retryable) {
                        postEnrollmentCallback(expectedGeneration, sourceView,
                                owner[0], sessionId,
                                () -> transitionEnrollmentToSafeFailure(
                                        sourceView, expectedGeneration, owner[0],
                                        sessionId, safeMessage, retryable));
                    }
                };
        FaceEnrollmentCaptureController result =
                new FaceEnrollmentCaptureController(
                        faceSubsystem.runtime(), sourceView.previewHolder(),
                        displayRotationDegrees(), scheduler,
                        new FaceFrameQualityGate(), listener);
        owner[0] = result;
        return result;
    }

    private void revealEnrollmentFaceView(FaceEnrollmentView sourceView) {
        FaceSubsystem.Snapshot snapshot = safeFaceSnapshot();
        FaceLicenseStateMachine.State license = snapshot == null
                ? FaceLicenseStateMachine.State.UNKNOWN : snapshot.licenseState();
        FaceRuntimeStateMachine.State runtime = snapshot == null
                ? FaceRuntimeStateMachine.State.UNINITIALIZED : snapshot.runtimeState();
        BiometricEnrollmentScreenRouter.Route route =
                BiometricEnrollmentScreenRouter.facePreflight(
                        enrollmentRouterOrigin(), isNetworkOnline(), license, runtime,
                        cameraPermissionGranted(), true);
        if (route.screen() != BiometricEnrollmentScreenRouter.Screen.FACE_CAPTURE) {
            failEnrollmentCameraAcquire(route.blocker());
            return;
        }
        enrollmentRoute = route;
        FrameLayout stack = enrollmentStack;
        BiometricEnrollmentChoiceView choice = biometricEnrollmentChoiceView;
        if (stack != null && choice != null && choice.getParent() == stack) {
            stack.removeView(choice);
        }
        sourceView.showDetecting(enrollmentRgbLiveness
                ? "请正对摄像头，正在进行活体检测"
                : "请正对摄像头", null);
        sourceView.bringToFront();
    }

    private void renderEnrollmentState(
            FaceEnrollmentView sourceView, FaceCaptureSession.State state) {
        if (state == FaceCaptureSession.State.PREPARING) {
            sourceView.showPreparing();
        } else if (state == FaceCaptureSession.State.DETECTING) {
            sourceView.showDetecting(enrollmentRgbLiveness
                    ? "请正对摄像头，正在进行活体检测"
                    : "请正对摄像头", null);
        } else if (state == FaceCaptureSession.State.CAPTURING
                || state == FaceCaptureSession.State.VERIFYING) {
            sourceView.showCapturing();
        }
    }

    private void transitionEnrollmentToSafeFailure(
            FaceEnrollmentView sourceView, long expectedGeneration,
            FaceEnrollmentCaptureController expectedController,
            long expectedSessionId, String safeMessage, boolean retryable) {
        if (!isCurrentEnrollmentCallback(expectedGeneration, sourceView,
                expectedController, expectedSessionId)) return;

        enrollmentGeneration = nextGeneration(enrollmentGeneration);
        final long failureGeneration = enrollmentGeneration;
        activeEnrollmentSessionId = 0L;
        clearEnrollmentPermissionRequest();
        clearEnrollmentCleanupBarrier();
        FaceEnrollmentCaptureController controller = enrollmentCaptureController;
        enrollmentCaptureController = null;
        final long sensitiveGeneration = enrollmentSensitiveGeneration;
        enrollmentSensitiveGeneration = 0L;
        enrollmentSessionGate.invalidate(sensitiveGeneration);
        bindEnrollmentFailureActions(sourceView, failureGeneration);
        enqueueEnrollmentControllerCleanup(controller, sensitiveGeneration);

        FrameLayout stack = enrollmentStack;
        BiometricEnrollmentChoiceView choice = biometricEnrollmentChoiceView;
        if (stack != null && choice != null && choice.getParent() == stack) {
            stack.removeView(choice);
        }
        sourceView.showFailure(safeMessage, retryable);
        sourceView.bringToFront();
    }

    private void bindEnrollmentFailureActions(
            FaceEnrollmentView sourceView, long failureGeneration) {
        sourceView.setListener(new FaceEnrollmentView.Listener() {
            @Override public void onBackRequested() {
                if (isCurrentEnrollmentView(sourceView, failureGeneration)) {
                    renderEnrollmentChoice();
                }
            }

            @Override public void onRetryRequested() {
                if (!isCurrentEnrollmentView(sourceView, failureGeneration)) return;
                renderEnrollmentChoice();
                handler.post(MainActivity.this::retryFaceEnrollmentPreflight);
            }
        });
    }

    private void retryFaceEnrollmentPreflight() {
        if (enrollmentOrigin == EnrollmentOrigin.ADMIN) {
            beginFaceEnrollmentPreflight();
            return;
        }
        customerActionBoundary.run(
                CustomerActionBoundary.Effect.RETRY,
                this::beginFaceEnrollmentPreflight);
    }

    private void postEnrollmentCallback(long expectedGeneration,
            FaceEnrollmentView expectedView,
            FaceEnrollmentCaptureController expectedController,
            long expectedSessionId, Runnable action) {
        handler.post(() -> {
            if (isCurrentEnrollmentCallback(expectedGeneration, expectedView,
                    expectedController, expectedSessionId)) {
                action.run();
            }
        });
    }

    private boolean isCurrentEnrollmentCallback(long expectedGeneration,
            FaceEnrollmentView expectedView,
            FaceEnrollmentCaptureController expectedController,
            long expectedSessionId) {
        return isCurrentEnrollmentView(expectedView, expectedGeneration)
                && enrollmentCaptureController == expectedController
                && activeEnrollmentSessionId == expectedSessionId;
    }

    private boolean isCurrentEnrollmentView(
            FaceEnrollmentView expectedView, long expectedGeneration) {
        if (enrollmentOrigin == EnrollmentOrigin.ADMIN) {
            return active && enrollmentJourneyActive
                    && enrollmentGeneration == expectedGeneration
                    && faceEnrollmentView == expectedView;
        }
        return customerActionBoundary.call(
                CustomerActionBoundary.Effect.ASYNC_CALLBACK,
                () -> active && enrollmentJourneyActive
                        && enrollmentGeneration == expectedGeneration
                        && faceEnrollmentView == expectedView,
                false);
    }

    private boolean isCurrentEnrollmentChoice(
            BiometricEnrollmentChoiceView expectedView) {
        if (enrollmentOrigin == EnrollmentOrigin.ADMIN) {
            return active && enrollmentJourneyActive
                    && biometricEnrollmentChoiceView == expectedView
                    && enrollmentRoute != null
                    && enrollmentRoute.screen()
                    != BiometricEnrollmentScreenRouter.Screen.FACE_CAPTURE;
        }
        return customerActionBoundary.call(
                CustomerActionBoundary.Effect.ASYNC_CALLBACK,
                () -> active && enrollmentJourneyActive
                        && biometricEnrollmentChoiceView == expectedView
                        && enrollmentRoute != null
                        && enrollmentRoute.screen()
                        != BiometricEnrollmentScreenRouter.Screen.FACE_CAPTURE,
                false);
    }

    private void showEnrollmentBlocker(
            BiometricEnrollmentChoiceView choice,
            BiometricEnrollmentScreenRouter.Route blocked) {
        if (!isCurrentEnrollmentChoice(choice) || blocked == null) return;
        enrollmentRoute = blocked;
        choice.showBlocker(blocked.blocker());
    }

    private void failEnrollmentCameraAcquire(
            BiometricEnrollmentScreenRouter.Blocker blocker) {
        if (!enrollmentJourneyActive) return;
        renderEnrollmentChoice();
        BiometricEnrollmentChoiceView choice = biometricEnrollmentChoiceView;
        if (choice == null) return;
        FaceLicenseStateMachine.State license = FaceLicenseStateMachine.State.READY;
        FaceRuntimeStateMachine.State runtime = FaceRuntimeStateMachine.State.READY;
        BiometricEnrollmentScreenRouter.Route blocked;
        if (blocker == BiometricEnrollmentScreenRouter.Blocker.CAMERA_UNAVAILABLE) {
            blocked = BiometricEnrollmentScreenRouter.facePreflight(
                    enrollmentRouterOrigin(), true, license, runtime, true, false);
        } else {
            FaceSubsystem.Snapshot snapshot = safeFaceSnapshot();
            blocked = BiometricEnrollmentScreenRouter.facePreflight(
                    enrollmentRouterOrigin(),
                    blocker != BiometricEnrollmentScreenRouter.Blocker.NETWORK_OFFLINE,
                    snapshot == null ? FaceLicenseStateMachine.State.UNKNOWN
                            : snapshot.licenseState(),
                    snapshot == null ? FaceRuntimeStateMachine.State.UNINITIALIZED
                            : snapshot.runtimeState(),
                    blocker != BiometricEnrollmentScreenRouter.Blocker
                            .CAMERA_PERMISSION_DENIED,
                    false);
        }
        showEnrollmentBlocker(choice, blocked);
    }

    private void handleEnrollmentBack() {
        if (!enrollmentJourneyActive || enrollmentRoute == null) return;
        BiometricEnrollmentScreenRouter.BackDestination destination =
                BiometricEnrollmentScreenRouter.backDestination(enrollmentRoute);
        if (destination == BiometricEnrollmentScreenRouter.BackDestination.CHOICE) {
            renderEnrollmentChoice();
        } else {
            returnFromEnrollment();
        }
    }

    private void returnFromEnrollment() {
        EnrollmentOrigin origin = enrollmentOrigin;
        enrollmentJourneyActive = false;
        enrollmentOrigin = null;
        enrollmentRoute = null;
        cancelEnrollmentWorkAndInvalidate();
        biometricEnrollmentChoiceView = null;
        if (origin == EnrollmentOrigin.ADMIN
                && flow.screen() == KioskFlowModel.Screen.ADMIN_PIN) {
            renderAdminFunctions();
        } else {
            flow.returnHome();
            renderScreen();
        }
    }

    private void abandonEnrollmentJourney() {
        if (!enrollmentJourneyActive && enrollmentCaptureController == null
                && faceEnrollmentView == null) return;
        enrollmentJourneyActive = false;
        enrollmentOrigin = null;
        enrollmentRoute = null;
        cancelEnrollmentWorkAndInvalidate();
        biometricEnrollmentChoiceView = null;
    }

    private BiometricEnrollmentScreenRouter.Origin enrollmentRouterOrigin() {
        if (enrollmentOrigin == EnrollmentOrigin.HOME) {
            return BiometricEnrollmentScreenRouter.Origin.HOME;
        }
        if (enrollmentOrigin == EnrollmentOrigin.ADMIN) {
            return BiometricEnrollmentScreenRouter.Origin.ADMIN;
        }
        throw new IllegalStateException("enrollment origin is unavailable");
    }

    private void beginFaceRecognition() {
        if (!customerActionsEnabled()
                || !active
                || adminLaunchPending
                || flow.screen() != KioskFlowModel.Screen.HOME) {
            return;
        }
        if (!customerActionBoundary.call(
                CustomerActionBoundary.Effect.FACE,
                flow::beginFaceRecognition,
                false)) {
            return;
        }
        invalidateCustomerWork(true);
        faceJourney = FaceJourney.NORMAL;
        if (customerOperationToken <= 0L
                || customerOperationTokenExhausted
                || !customerSerialTransmitter.beginOperation(
                        customerOperationToken,
                        CustomerSerialPhase.FACE_PRE_SELECTION,
                        null)) {
            flow.returnHome();
            appendCustomerLog("[Face] customer operation unavailable");
            renderScreen();
            return;
        }
        customerSerialPhase = CustomerSerialPhase.FACE_PRE_SELECTION;
        customerWriteAttribution = null;
        customerConfirmedTarget = null;
        clearFaceDeferredFailure();
        renderScreen();
    }

    private void beginOnlineFaceRecognition() {
        if (!active || isFinishing() || adminLaunchPending || onlineCustomerHost == null
                || !onlineCustomerHost.ready() || onlineCustomerHost.showingJourney()) return;
        if (!isNetworkOnline()) {
            if (homeView != null) homeView.showValidationError("网络异常，暂时无法进行人脸验证");
            return;
        }
        if (faceSubsystem == null) {
            if (homeView != null) homeView.showValidationError("人脸组件不可用，请联系管理员");
            return;
        }
        onlineFaceClient = onlineCustomerHost.beginFace();
        if (onlineFaceClient == null) return;
        clearFaceDeferredFailure();
        // Reuse only camera/permission/Baidu capture; legacy local authorization and discovery stay closed.
        renderFaceRecognition();
    }

    private boolean isOnlineFaceActive() {
        return active && !isFinishing() && onlineFaceClient != null && onlineCustomerHost != null
                && onlineCustomerHost.isCurrentFace(onlineFaceClient);
    }

    private boolean faceActionsEnabled() {
        return customerActionsEnabled() || isOnlineFaceActive();
    }

    private void renderFaceRecognition() {
        cancelFaceWorkAndInvalidate();
        homeView = null;
        lockerSelectionView = null;
        resultOverlay = null;
        adminPinOverlay = null;
        adminFunctionOverlay = null;
        returnAuthView = null;
        returnLockerView = null;
        returnProgressView = null;

        final FaceRecognitionView sourceView = new FaceRecognitionView(this);
        sourceView.setSecurityBanner(faceBannerPolicy.text(), faceBannerPolicy.visible());
        faceRecognitionView = sourceView;
        final SurfaceHolder previewHolder = sourceView.previewHolder();
        final long expectedFaceGeneration = faceGeneration;
        sourceView.setListener(new FaceRecognitionView.Listener() {
            @Override
            public void onReturnRequested() {
                if (!faceActionsEnabled()) {
                    return;
                }
                if (active
                        && faceGeneration == expectedFaceGeneration
                        && faceRecognitionView == sourceView
                        && (flow.screen()
                        == KioskFlowModel.Screen.FACE_RECOGNITION
                        || isReturnFaceJourneyActive() || isOnlineFaceActive())) {
                    if (faceJourney == FaceJourney.RETURN) {
                        cancelReturnFaceRecognition();
                    } else {
                        returnHome("顾客取消人脸识别");
                    }
                }
            }

            @Override
            public void onRetryRequested() {
                if (!faceActionsEnabled()) {
                    return;
                }
                if (isOnlineFaceActive()) {
                    if (faceGeneration == expectedFaceGeneration && faceRecognitionView == sourceView) {
                        clearFaceDeferredFailure();
                        renderFaceRecognition();
                    }
                    return;
                }
                customerActionBoundary.run(
                        CustomerActionBoundary.Effect.RETRY,
                        () -> {
                            if (active
                                    && faceGeneration == expectedFaceGeneration
                                    && faceRecognitionView == sourceView
                                    && (flow.screen()
                                    == KioskFlowModel.Screen.FACE_RECOGNITION
                                    || isReturnFaceJourneyActive())) {
                                clearFaceDeferredFailure();
                                renderFaceRecognition();
                            }
                        });
            }
        });
        replaceRoot(sourceView);

        if (faceDeferredMessage != null) {
            if (faceDeferredPermissionDenied) {
                sourceView.showPermissionDenied(faceDeferredPermissionPermanent);
            } else {
                sourceView.showFailure(
                        faceDeferredMessage, faceDeferredRetryable);
            }
        } else if (faceSubsystem == null) {
            sourceView.showFailure("人脸功能暂时不可用，请联系管理员", false);
        } else {
            awaitFaceCleanupBarrier(
                    sourceView, previewHolder, expectedFaceGeneration);
        }
    }

    private void awaitFaceCleanupBarrier(
            FaceRecognitionView sourceView,
            SurfaceHolder previewHolder,
            long expectedFaceGeneration) {
        if (!isCurrentFaceView(sourceView, expectedFaceGeneration)
                || previewHolder == null) {
            return;
        }
        faceCleanupBarrierToken = nextGeneration(faceCleanupBarrierToken);
        final long expectedBarrierToken = faceCleanupBarrierToken;
        faceCleanupBarrierGeneration = expectedFaceGeneration;
        faceCleanupBarrierView = sourceView;
        Runnable timeout = () -> failFaceCleanupBarrier(
                sourceView, expectedFaceGeneration, expectedBarrierToken);
        faceCleanupBarrierTimeout = timeout;
        sourceView.showPreparing();
        if (!handler.postDelayed(
                timeout, FACE_CLEANUP_BARRIER_TIMEOUT_MILLIS)) {
            failFaceCleanupBarrier(
                    sourceView, expectedFaceGeneration, expectedBarrierToken);
            return;
        }

        WeakReference<MainActivity> activityReference =
                new WeakReference<>(this);
        WeakReference<FaceRecognitionView> viewReference =
                new WeakReference<>(sourceView);
        WeakReference<SurfaceHolder> holderReference =
                new WeakReference<>(previewHolder);
        try {
            FACE_CLEANUP_EXECUTOR.execute(() -> {
                MainActivity activity = activityReference.get();
                if (activity == null) {
                    return;
                }
                activity.handler.post(() -> {
                    MainActivity currentActivity = activityReference.get();
                    FaceRecognitionView currentView = viewReference.get();
                    SurfaceHolder currentHolder = holderReference.get();
                    if (currentActivity != null
                            && currentView != null
                            && currentHolder != null) {
                        currentActivity.completeFaceCleanupBarrier(
                                currentView,
                                currentHolder,
                                expectedFaceGeneration,
                                expectedBarrierToken);
                    }
                });
            });
        } catch (RejectedExecutionException ignored) {
            faceCleanupUnavailable = true;
            failFaceCleanupBarrier(
                    sourceView, expectedFaceGeneration, expectedBarrierToken);
        }
    }

    private void completeFaceCleanupBarrier(
            FaceRecognitionView sourceView,
            SurfaceHolder previewHolder,
            long expectedFaceGeneration,
            long expectedBarrierToken) {
        if (!isCurrentFaceBarrier(
                sourceView, expectedFaceGeneration, expectedBarrierToken)) {
            return;
        }
        clearFaceCleanupBarrier();
        if (faceCleanupUnavailable || faceSubsystem == null) {
            sourceView.showFailure(
                    "人脸组件无法安全启动，请联系管理员", false);
            return;
        }
        FaceRecognitionController controller;
        try {
            controller = createFaceController(
                    sourceView, previewHolder, expectedFaceGeneration);
        } catch (RuntimeException | LinkageError ignored) {
            controller = null;
        }
        if (controller == null
                || !isCurrentFaceView(sourceView, expectedFaceGeneration)) {
            if (controller != null) {
                enqueueFaceControllerCleanup(controller);
            }
            sourceView.showFailure(
                    "人脸功能暂时不可用，请联系管理员", false);
            return;
        }
        faceController = controller;
        startFaceController(
                sourceView, expectedFaceGeneration, controller);
    }

    private void failFaceCleanupBarrier(
            FaceRecognitionView sourceView,
            long expectedFaceGeneration,
            long expectedBarrierToken) {
        if (!isCurrentFaceBarrier(
                sourceView, expectedFaceGeneration, expectedBarrierToken)) {
            return;
        }
        clearFaceCleanupBarrier();
        sourceView.showFailure(
                "摄像头资源正在安全释放，请稍后重试", true);
    }

    private boolean isCurrentFaceBarrier(
            FaceRecognitionView sourceView,
            long expectedFaceGeneration,
            long expectedBarrierToken) {
        return isCurrentFaceView(sourceView, expectedFaceGeneration)
                && faceController == null
                && faceCleanupBarrierToken == expectedBarrierToken
                && faceCleanupBarrierGeneration == expectedFaceGeneration
                && faceCleanupBarrierView == sourceView
                && faceCleanupBarrierTimeout != null;
    }

    private boolean isCurrentFaceView(
            FaceRecognitionView sourceView,
            long expectedFaceGeneration) {
        if (isOnlineFaceActive()) {
            return faceGeneration == expectedFaceGeneration && faceRecognitionView == sourceView;
        }
        return customerActionBoundary.call(
                CustomerActionBoundary.Effect.ASYNC_CALLBACK,
                () -> active
                        && (flow.screen() == KioskFlowModel.Screen.FACE_RECOGNITION
                        || isReturnFaceJourneyActive())
                        && faceGeneration == expectedFaceGeneration
                        && faceRecognitionView == sourceView,
                false);
    }

    private void clearFaceCleanupBarrier() {
        Runnable timeout = faceCleanupBarrierTimeout;
        faceCleanupBarrierTimeout = null;
        faceCleanupBarrierGeneration = 0L;
        faceCleanupBarrierView = null;
        faceCleanupBarrierToken = nextGeneration(faceCleanupBarrierToken);
        if (timeout != null) {
            handler.removeCallbacks(timeout);
        }
    }

    private FaceRecognitionController createFaceController(
            final FaceRecognitionView sourceView,
            final SurfaceHolder previewHolder,
            final long expectedFaceGeneration) {
        final Camera1FaceCameraController cameraController =
                new Camera1FaceCameraController();
        final FaceRecognitionController[] owner =
                new FaceRecognitionController[1];

        FaceCaptureSession.Scheduler scheduler = (task, delayMillis) -> {
            boolean accepted = handler.postDelayed(task, delayMillis);
            return accepted ? () -> handler.removeCallbacks(task) : null;
        };
        FaceRecognitionController.LicensePort licensePort =
                (sessionId, callback) -> {
                    BaiduFaceLicenseManager.Subscription subscription =
                            faceSubsystem.checkLocal(
                                    new BaiduFaceLicenseManager.Listener() {
                                        @Override
                                        public void onReady() {
                                            callback.onReady(sessionId);
                                        }

                                        @Override
                                        public void onFailure(
                                                int safeCode, String safeMessage) {
                                            callback.onFailure(
                                                    sessionId,
                                                    FaceRecognitionController
                                                            .LicenseFailure.UNAVAILABLE,
                                                    "LICENSE_UNAVAILABLE");
                                        }

                                        @Override
                                        public void onFailure(
                                                BaiduFaceLicenseManager.FailureKind kind,
                                                int safeCode,
                                                String safeMessage) {
                                            boolean invalid = kind
                                                    == BaiduFaceLicenseManager
                                                            .FailureKind.INVALID;
                                            callback.onFailure(
                                                    sessionId,
                                                    invalid
                                                            ? FaceRecognitionController
                                                                    .LicenseFailure.INVALID
                                                            : FaceRecognitionController
                                                                    .LicenseFailure.UNAVAILABLE,
                                                    invalid
                                                            ? "LICENSE_INVALID"
                                                            : "LICENSE_UNAVAILABLE");
                                        }
                                    });
                    return subscription == null
                            ? () -> { }
                            : subscription::close;
                };
        FaceRecognitionController.RuntimePort runtimePort =
                new FaceRecognitionController.RuntimePort() {
                    @Override
                    public FaceCaptureSession.Cancellable initialize(
                            long sessionId, InitCallback callback) {
                        BaiduFaceRuntime.Subscription subscription =
                                faceSubsystem.initializeRuntime(
                                        new BaiduFaceRuntime.Listener() {
                                            @Override
                                            public void onReady(long generation) {
                                                callback.onReady(sessionId);
                                            }

                                            @Override
                                            public void onFailure(long generation,
                                                    int safeCode,
                                                    String safeMessage) {
                                                callback.onFailure(
                                                        sessionId,
                                                        "RUNTIME_FAILED");
                                            }
                                        });
                        return subscription == null
                                ? () -> { }
                                : subscription::close;
                    }

                    @Override
                    public FaceCaptureSession.Cancellable analyze(
                            long sessionId,
                            long frameId,
                            FaceFrame.Borrow ownedFrame,
                            AnalysisCallback callback) {
                        return faceSubsystem.runtime().analyze(
                                frameId,
                                ownedFrame,
                                new BaiduFaceRuntime.AnalysisCallback() {
                                    @Override
                                    public void onCompleted(
                                            long requestId,
                                            BaiduFaceRuntime.Analysis analysis) {
                                        if (analysis == null) {
                                            callback.onFailure(
                                                    sessionId,
                                                    requestId,
                                                    "ANALYSIS_FAILED");
                                            return;
                                        }
                                        BaiduFaceRuntime.PreviewBox box =
                                                analysis.previewBox();
                                        FaceRecognitionController.PreviewBox preview =
                                                box == null
                                                        ? null
                                                        : new FaceRecognitionController.PreviewBox(
                                                                box.frameWidth(),
                                                                box.frameHeight(),
                                                                box.centerX(),
                                                                box.centerY(),
                                                                box.width(),
                                                                box.height());
                                        callback.onCompleted(
                                                sessionId,
                                                requestId,
                                                new FaceRecognitionController.Analysis(
                                                        analysis.observation(),
                                                        preview));
                                    }

                                    @Override
                                    public void onFailure(long requestId,
                                            int safeCode, String safeMessage) {
                                        callback.onFailure(
                                                sessionId,
                                                requestId,
                                                "ANALYSIS_FAILED");
                                    }
                                });
                    }
                };
        FaceRecognitionController.PermissionPort permissionPort =
                new FaceRecognitionController.PermissionPort() {
                    @Override
                    public boolean isGranted() {
                        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                                || checkSelfPermission(Manifest.permission.CAMERA)
                                == PackageManager.PERMISSION_GRANTED;
                    }

                    @Override
                    public FaceCaptureSession.Cancellable requestOnce(
                            long sessionId, Callback callback) {
                        Runnable result = () -> {
                            boolean granted = Build.VERSION.SDK_INT
                                    < Build.VERSION_CODES.M
                                    || checkSelfPermission(Manifest.permission.CAMERA)
                                    == PackageManager.PERMISSION_GRANTED;
                            boolean permanent = !granted
                                    && Build.VERSION.SDK_INT
                                    >= Build.VERSION_CODES.M
                                    && !shouldShowRequestPermissionRationale(
                                            Manifest.permission.CAMERA);
                            callback.onResult(sessionId, granted, permanent);
                        };
                        boolean accepted = handler.post(result);
                        return accepted
                                ? () -> handler.removeCallbacks(result)
                                : null;
                    }
                };
        FaceRecognitionController.CameraPort cameraPort =
                new FaceRecognitionController.CameraPort() {
                    @Override
                    public FaceCaptureSession.Cancellable start(
                            long sessionId, Callback callback) {
                        return cameraController.start(
                                previewHolder,
                                displayRotationDegrees(),
                                new Camera1FaceCameraController.Listener() {
                                    @Override
                                    public void onCameraReady(
                                            Camera1FaceCameraController.CameraDescriptor
                                                    descriptor) {
                                        callback.onReady(sessionId);
                                    }

                                    @Override
                                    public void onPreviewFrame(
                                            FaceFrame ownedFrame) {
                                        callback.onFrame(sessionId, ownedFrame);
                                    }

                                    @Override
                                    public void onCameraError(
                                            String safeMessage,
                                            String diagnosticCode) {
                                        callback.onError(
                                                sessionId, "CAMERA_FAILED");
                                    }
                                });
                    }

                    @Override
                    public void stop(long sessionId) {
                        cameraController.stop();
                    }

                    @Override
                    public void close() {
                        cameraController.close();
                    }
                };
        FaceRecognitionController.BindingPort bindingPort =
                new FaceRecognitionController.BindingPort() {
                    @Override
                    public boolean isAvailable() {
                        return faceSubsystem.hasDeviceBinding();
                    }

                    @Override
                    public String deviceBinding() {
                        return faceSubsystem.deviceBinding();
                    }

                    @Override
                    public String processBinding() {
                        return faceSubsystem.processBinding();
                    }
                };
        FaceRecognitionController.Listener listener =
                new FaceRecognitionController.Listener() {
                    @Override
                    public void onStateChanged(long sessionId,
                            FaceCaptureSession.State state) {
                        postFaceCallback(
                                expectedFaceGeneration,
                                sourceView,
                                owner[0],
                                sessionId,
                                () -> renderFaceState(sourceView, state));
                    }

                    @Override
                    public void onQualityDecision(long sessionId,
                            FaceRecognitionController.QualityEvent event) {
                        postFaceCallback(
                                expectedFaceGeneration,
                                sourceView,
                                owner[0],
                                sessionId,
                                () -> renderFaceQuality(sourceView, event));
                    }

                    @Override
                    public void onTerminal(long sessionId,
                            FaceRecognitionController.TerminalOutcome outcome) {
                        postFaceCallback(
                                expectedFaceGeneration,
                                sourceView,
                                owner[0],
                                sessionId,
                                () -> renderFaceTerminal(sourceView, outcome));
                    }
                };
        FaceRecognitionController result = new FaceRecognitionController(
                SystemClock::elapsedRealtime,
                System::currentTimeMillis,
                scheduler,
                licensePort,
                runtimePort,
                permissionPort,
                cameraPort,
                new AndroidFaceJpegEncoder(),
                isOnlineFaceActive() ? onlineFaceClient : faceSubsystem.verificationClient(),
                bindingPort,
                new FaceFrameQualityGate(),
                (sessionId, stage, boundedCode) -> appendCustomerLog(
                        "[Face] stage=" + stage.name()
                                + " code=" + boundedCode),
                listener,
                isOnlineFaceActive() ? FaceRecognitionController.ONLINE_VERIFICATION_TIMEOUT_MILLIS
                        : com.codex.lockertest.face.FaceCaptureSession.DEFAULT_VERIFICATION_TIMEOUT_MILLIS);
        owner[0] = result;
        return result;
    }

    private void startFaceController(
            FaceRecognitionView sourceView,
            long expectedFaceGeneration,
            FaceRecognitionController expectedController) {
        boolean faceScreenCurrent =
                flow.screen() == KioskFlowModel.Screen.FACE_RECOGNITION
                        || isReturnFaceJourneyActive() || isOnlineFaceActive();
        boolean faceViewCurrent = faceRecognitionView == sourceView;
        if (!active
                || !faceScreenCurrent
                || !faceViewCurrent
                || faceGeneration != expectedFaceGeneration
                || faceController != expectedController) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            requestFaceCameraPermission(
                    sourceView, expectedFaceGeneration, expectedController);
            return;
        }
        sourceView.showPreparing();
        long sessionId;
        try {
            sessionId = expectedController.start();
        } catch (RuntimeException | LinkageError ignored) {
            sessionId = 0L;
        }
        activeFaceSessionId = sessionId;
        if (sessionId == 0L) {
            showFaceFailure("无法开始人脸识别，请重新尝试", true);
        }
    }

    private void requestFaceCameraPermission(
            FaceRecognitionView sourceView,
            long expectedFaceGeneration,
            FaceRecognitionController expectedController) {
        boolean faceScreenCurrent =
                flow.screen() == KioskFlowModel.Screen.FACE_RECOGNITION
                        || isReturnFaceJourneyActive() || isOnlineFaceActive();
        boolean faceViewCurrent = faceRecognitionView == sourceView;
        if (!active
                || !faceScreenCurrent
                || !faceViewCurrent
                || faceGeneration != expectedFaceGeneration
                || faceController != expectedController) {
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || checkSelfPermission(Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startFaceController(
                    sourceView, expectedFaceGeneration, expectedController);
            return;
        }
        if (facePermissionRequestPending) {
            return;
        }
        facePermissionRequestPending = true;
        permissionFaceGeneration = expectedFaceGeneration;
        permissionFaceView = sourceView;
        permissionFaceController = expectedController;
        try {
            requestPermissions(
                    new String[] {Manifest.permission.CAMERA},
                    FACE_CAMERA_PERMISSION_REQUEST);
        } catch (RuntimeException | LinkageError ignored) {
            showFacePermissionFailure(false);
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == FACE_CAMERA_PERMISSION_REQUEST) {
            if (!faceActionsEnabled()) {
                return;
            }
            FaceRecognitionView sourceView = permissionFaceView;
            FaceRecognitionController expectedController =
                    permissionFaceController;
            long expectedFaceGeneration = permissionFaceGeneration;
            clearFacePermissionRequest();
            if (!active
                    || sourceView == null
                    || expectedController == null
                    || faceRecognitionView != sourceView
                    || faceController != expectedController
                    || faceGeneration != expectedFaceGeneration
                    || (flow.screen()
                    != KioskFlowModel.Screen.FACE_RECOGNITION
                    && !isReturnFaceJourneyActive() && !isOnlineFaceActive())) {
                return;
            }
            boolean granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                    || checkSelfPermission(Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED;
            if (granted) {
                startFaceController(
                        sourceView, expectedFaceGeneration, expectedController);
            } else {
                boolean permanent = Build.VERSION.SDK_INT
                        >= Build.VERSION_CODES.M
                        && !shouldShowRequestPermissionRationale(
                                Manifest.permission.CAMERA);
                showFacePermissionFailure(permanent);
            }
            return;
        }
        if (requestCode == ENROLLMENT_CAMERA_PERMISSION_REQUEST) {
            EnrollmentOrigin permissionOrigin = enrollmentOrigin;
            if (permissionOrigin != EnrollmentOrigin.ADMIN
                    && !customerActionsEnabled()) {
                return;
            }
            BiometricEnrollmentChoiceView choice = permissionEnrollmentView;
            long expectedGeneration = permissionEnrollmentGeneration;
            clearEnrollmentPermissionRequest();
            if (!active || !enrollmentJourneyActive || choice == null
                    || biometricEnrollmentChoiceView != choice
                    || enrollmentGeneration != expectedGeneration) {
                return;
            }
            if (cameraPermissionGranted()) {
                beginFaceEnrollmentPreflight();
            } else {
                FaceSubsystem.Snapshot snapshot = safeFaceSnapshot();
                BiometricEnrollmentScreenRouter.Route blocked =
                        BiometricEnrollmentScreenRouter.facePreflight(
                                enrollmentRouterOrigin(), true,
                                snapshot == null
                                        ? FaceLicenseStateMachine.State.UNKNOWN
                                        : snapshot.licenseState(),
                                snapshot == null
                                        ? FaceRuntimeStateMachine.State.UNINITIALIZED
                                        : snapshot.runtimeState(),
                                false, false);
                showEnrollmentBlocker(choice, blocked);
            }
            return;
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    private void postFaceCallback(
            long expectedFaceGeneration,
            FaceRecognitionView expectedView,
            FaceRecognitionController expectedController,
            long expectedSessionId,
            Runnable action) {
        handler.post(() -> {
            if (!isCurrentFaceCallback(
                    expectedFaceGeneration,
                    expectedView,
                    expectedController,
                    expectedSessionId)) {
                return;
            }
            action.run();
        });
    }

    private boolean isCurrentFaceCallback(
            long expectedFaceGeneration,
            FaceRecognitionView expectedView,
            FaceRecognitionController expectedController,
            long expectedSessionId) {
        if (isOnlineFaceActive()) {
            return faceGeneration == expectedFaceGeneration && faceRecognitionView == expectedView
                    && faceController == expectedController && activeFaceSessionId == expectedSessionId;
        }
        return customerActionBoundary.call(
                CustomerActionBoundary.Effect.ASYNC_CALLBACK,
                () -> active
                        && (flow.screen() == KioskFlowModel.Screen.FACE_RECOGNITION
                        || isReturnFaceJourneyActive())
                        && faceGeneration == expectedFaceGeneration
                        && faceRecognitionView == expectedView
                        && faceController == expectedController
                        && activeFaceSessionId == expectedSessionId,
                false);
    }

    private void renderFaceState(
            FaceRecognitionView sourceView, FaceCaptureSession.State state) {
        if (state == FaceCaptureSession.State.PREPARING) {
            sourceView.showPreparing();
        } else if (state == FaceCaptureSession.State.DETECTING) {
            sourceView.showDetecting("请正对摄像头", null);
        } else if (state == FaceCaptureSession.State.CAPTURING
                || state == FaceCaptureSession.State.VERIFYING) {
            sourceView.showVerifying();
        }
    }

    private void renderFaceQuality(
            FaceRecognitionView sourceView,
            FaceRecognitionController.QualityEvent event) {
        FaceRecognitionView.FaceBox viewBox =
                safeFaceBox(event.previewBox());
        sourceView.showDetecting(event.customerHint(), viewBox);
    }

    private static FaceRecognitionView.FaceBox safeFaceBox(
            FaceRecognitionController.PreviewBox box) {
        if (box == null
                || box.frameWidth() <= 0
                || box.frameHeight() <= 0
                || box.width() <= 0.0f
                || box.height() <= 0.0f
                || Float.isNaN(box.centerX())
                || Float.isInfinite(box.centerX())
                || Float.isNaN(box.centerY())
                || Float.isInfinite(box.centerY())
                || Float.isNaN(box.width())
                || Float.isInfinite(box.width())
                || Float.isNaN(box.height())
                || Float.isInfinite(box.height())) {
            return null;
        }
        try {
            return new FaceRecognitionView.FaceBox(
                    box.frameWidth(),
                    box.frameHeight(),
                    box.centerX(),
                    box.centerY(),
                    box.width(),
                    box.height());
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private void renderFaceTerminal(
            FaceRecognitionView sourceView,
            FaceRecognitionController.TerminalOutcome outcome) {
        if (outcome != null && outcome.isSuccess()) {
            completeFaceRecognition(outcome.success());
            return;
        }
        FaceRecognitionController.FailureReason reason = outcome == null
                ? FaceRecognitionController.FailureReason.RUNTIME_FAILED
                : outcome.failureReason();
        if (reason == FaceRecognitionController.FailureReason.PERMISSION_DENIED
                || reason
                == FaceRecognitionController.FailureReason.PERMISSION_PERMANENTLY_DENIED) {
            showFacePermissionFailure(
                    reason == FaceRecognitionController.FailureReason
                            .PERMISSION_PERMANENTLY_DENIED);
            return;
        }
        showFaceFailure(
                outcome == null
                        ? "人脸识别失败，请重新尝试"
                        : outcome.safeMessage(),
                outcome == null || outcome.retryable());
    }

    private void showFaceFailure(String message, boolean retryable) {
        setFaceDeferredFailure(message, retryable);
        renderFaceRecognition();
    }

    private void showFacePermissionFailure(boolean permanentlyDenied) {
        setFaceDeferredFailure("需要摄像头权限才能进行人脸识别",
                !permanentlyDenied);
        faceDeferredPermissionDenied = true;
        faceDeferredPermissionPermanent = permanentlyDenied;
        renderFaceRecognition();
    }

    private void setFaceDeferredFailure(String message, boolean retryable) {
        faceDeferredMessage = message == null || message.trim().isEmpty()
                ? "人脸识别失败，请重新尝试"
                : message;
        faceDeferredRetryable = retryable;
        faceDeferredPermissionDenied = false;
        faceDeferredPermissionPermanent = false;
    }

    private void clearFaceDeferredFailure() {
        faceDeferredMessage = null;
        faceDeferredRetryable = true;
        faceDeferredPermissionDenied = false;
        faceDeferredPermissionPermanent = false;
    }

    private void completeFaceRecognition(
            FaceRecognitionController.Success success) {
        if (onlineFaceClient != null) {
            if (!isOnlineFaceActive() || success == null) return;
            com.codex.lockertest.face.verification.OnlineFaceVerificationClient client = onlineFaceClient;
            cancelFaceWorkAndInvalidate();
            onlineFaceClient = null;
            clearFaceDeferredFailure();
            if (!onlineCustomerHost.finishFace(client, success.result())) {
                returnHome("人脸验证已失效，请重新识别");
                if (homeView != null) homeView.showValidationError("人脸验证已失效，请重新识别");
            }
            return;
        }
        if (!customerActionsEnabled()) {
            return;
        }
        FaceVerificationResult result = success == null ? null : success.result();
        FaceJourney completedJourney = faceJourney;
        pendingFaceSuccess = success;
        cancelFaceWorkAndInvalidate();
        if (completedJourney == FaceJourney.RETURN) {
            faceJourney = FaceJourney.NONE;
            completeReturnFaceRecognition(success);
            return;
        }
        if (!flow.acceptFaceVerification(result, System.currentTimeMillis())) {
            pendingFaceSuccess = null;
            setFaceDeferredFailure("验证已过期，请重新识别", true);
            renderScreen();
            return;
        }
        pendingFaceSuccess = success;
        faceJourney = FaceJourney.NONE;
        clearFaceDeferredFailure();
        invalidateCustomerWork(true);
        renderScreen();
        startDiscoveryOperation();
    }

    private void beginReturnFaceRecognition() {
        if (!customerActionsEnabled()
                || !active
                || !isReturnAuthenticationActive()
                || returnServiceBusy) {
            return;
        }
        if (!isNetworkOnline()) {
            returnStatusMessage = "网络异常，暂时无法进行人脸还柜验证";
            renderReturnSnapshot();
            return;
        }
        invalidateCustomerWork(true);
        if (customerOperationToken <= 0L
                || customerOperationTokenExhausted
                || !customerSerialTransmitter.beginOperation(
                        customerOperationToken,
                        CustomerSerialPhase.FACE_PRE_SELECTION,
                        null)) {
            returnAuthenticationFailed = true;
            returnStatusMessage = "人脸识别准备失败，请重试";
            renderReturnSnapshot();
            return;
        }
        customerSerialPhase = CustomerSerialPhase.FACE_PRE_SELECTION;
        customerWriteAttribution = null;
        customerConfirmedTarget = null;
        returnAuthenticationFailed = false;
        faceJourney = FaceJourney.RETURN;
        clearFaceDeferredFailure();
        renderFaceRecognition();
    }

    private void completeReturnFaceRecognition(
            FaceRecognitionController.Success success) {
        if (!customerActionsEnabled()) {
            return;
        }
        FaceVerificationResult result = success == null ? null : success.result();
        pendingFaceSuccess = success;
        long now = System.currentTimeMillis();
        if (now < 0L) {
            pendingFaceSuccess = null;
            invalidateCustomerWork(true);
            returnAuthenticationFailed = true;
            returnStatusMessage = "人脸验证已过期，请重新识别";
            renderReturnSnapshot();
            return;
        }
        FaceVerificationEnvironment verificationEnvironment = faceSubsystem == null
                ? null : faceSubsystem.verificationEnvironment();
        FaceVerificationTicketValidator.TicketVerdict verdict = null;
        if (result != null && success != null && verificationEnvironment != null) {
            try {
                verdict = verificationEnvironment.validateTicket(
                        result,
                        success.expectedRequestId(),
                        faceSubsystem.deviceBinding(),
                        faceSubsystem.processBinding(),
                        now);
            } catch (RuntimeException ignored) {
                verdict = null;
            }
        }
        pendingFaceSuccess = null;
        invalidateCustomerWork(true);
        if (verdict != FaceVerificationTicketValidator.TicketVerdict.VALID) {
            returnAuthenticationFailed = true;
            returnStatusMessage = "人脸验证已过期，请重新识别";
            renderReturnSnapshot();
            return;
        }
        submitReturnIdentity(new ReturnIdentity(
                UnlockMethod.FACE, result.credential(), now));
    }

    private void cancelReturnFaceRecognition() {
        faceJourney = FaceJourney.NONE;
        cancelFaceWorkAndInvalidate();
        invalidateCustomerWork(true);
        clearFaceDeferredFailure();
        returnAuthenticationFailed = false;
        returnStatusMessage = "已取消人脸验证";
        renderReturnSnapshot();
    }

    private boolean isReturnFaceJourneyActive() {
        return returnJourneyActive && faceJourney == FaceJourney.RETURN;
    }

    private void cancelEnrollmentWorkAndInvalidate() {
        if (biometricEnrollmentChoiceView != null) {
            biometricEnrollmentChoiceView.closePalmHardwareTest();
        }
        enrollmentGeneration = nextGeneration(enrollmentGeneration);
        activeEnrollmentSessionId = 0L;
        clearEnrollmentPermissionRequest();
        clearEnrollmentCleanupBarrier();
        final long sensitiveGeneration = enrollmentSensitiveGeneration;
        enrollmentSensitiveGeneration = 0L;
        enrollmentSessionGate.invalidate(sensitiveGeneration);
        FaceEnrollmentCaptureController controller = enrollmentCaptureController;
        enrollmentCaptureController = null;
        faceEnrollmentView = null;
        enrollmentStack = null;
        if (controller == null) {
            enrollmentSessionGate.clearInvalidated(sensitiveGeneration);
        } else {
            enqueueEnrollmentControllerCleanup(controller, sensitiveGeneration);
        }
    }

    private void enqueueEnrollmentControllerCleanup(
            FaceEnrollmentCaptureController controller,
            long sensitiveGeneration) {
        if (controller == null) {
            enrollmentSessionGate.clearInvalidated(sensitiveGeneration);
            return;
        }
        try {
            FACE_CLEANUP_EXECUTOR.execute(() ->
                    runEnrollmentControllerCleanup(controller, sensitiveGeneration));
        } catch (RejectedExecutionException ignored) {
            faceCleanupUnavailable = true;
            runEnrollmentControllerCleanup(controller, sensitiveGeneration);
        }
    }

    private void runEnrollmentControllerCleanup(
            FaceEnrollmentCaptureController controller,
            long sensitiveGeneration) {
        try {
            controller.cancel();
        } catch (RuntimeException | LinkageError ignored) {
            faceCleanupUnavailable = true;
        }
        try {
            controller.close();
        } catch (RuntimeException | LinkageError ignored) {
            faceCleanupUnavailable = true;
        } finally {
            enrollmentSessionGate.clearInvalidated(sensitiveGeneration);
        }
    }

    private boolean isCurrentEnrollmentBarrier(
            FaceEnrollmentView sourceView, long expectedGeneration,
            long expectedToken) {
        return isCurrentEnrollmentView(sourceView, expectedGeneration)
                && enrollmentCaptureController == null
                && enrollmentCleanupBarrierToken == expectedToken
                && enrollmentCleanupBarrierGeneration == expectedGeneration
                && enrollmentCleanupBarrierView == sourceView
                && enrollmentCleanupBarrierTimeout != null;
    }

    private void failEnrollmentCleanupBarrier(
            FaceEnrollmentView sourceView, long expectedGeneration,
            long expectedToken) {
        if (!isCurrentEnrollmentBarrier(
                sourceView, expectedGeneration, expectedToken)) return;
        clearEnrollmentCleanupBarrier();
        failEnrollmentCameraAcquire(
                BiometricEnrollmentScreenRouter.Blocker.CAMERA_UNAVAILABLE);
    }

    private void clearEnrollmentCleanupBarrier() {
        Runnable timeout = enrollmentCleanupBarrierTimeout;
        enrollmentCleanupBarrierTimeout = null;
        enrollmentCleanupBarrierGeneration = 0L;
        enrollmentCleanupBarrierView = null;
        enrollmentCleanupBarrierToken = nextGeneration(enrollmentCleanupBarrierToken);
        if (timeout != null) handler.removeCallbacks(timeout);
    }

    private void clearEnrollmentPermissionRequest() {
        enrollmentPermissionRequestPending = false;
        permissionEnrollmentGeneration = 0L;
        permissionEnrollmentView = null;
    }

    private void cancelFaceWorkAndInvalidate() {
        faceGeneration = nextGeneration(faceGeneration);
        activeFaceSessionId = 0L;
        clearFacePermissionRequest();
        clearFaceCleanupBarrier();
        FaceRecognitionController controller = faceController;
        faceController = null;
        faceRecognitionView = null;
        enqueueFaceControllerCleanup(controller);
    }

    private void enqueueFaceControllerCleanup(
            FaceRecognitionController controller) {
        if (controller == null || faceCleanupUnavailable) {
            return;
        }
        try {
            FACE_CLEANUP_EXECUTOR.execute(
                    () -> runFaceControllerCleanup(controller));
        } catch (RejectedExecutionException ignored) {
            faceCleanupUnavailable = true;
        }
    }

    private static void runFaceControllerCleanup(
            FaceRecognitionController controller) {
        try {
            controller.cancel();
        } catch (RuntimeException | LinkageError ignored) {
            faceCleanupUnavailable = true;
            // Continue to close; no vendor exception may escape the cleanup lane.
        }
        try {
            controller.close();
        } catch (RuntimeException | LinkageError ignored) {
            faceCleanupUnavailable = true;
            // The poisoned camera must never be replaced in parallel.
        }
    }

    private void clearFacePermissionRequest() {
        facePermissionRequestPending = false;
        permissionFaceGeneration = 0L;
        permissionFaceView = null;
        permissionFaceController = null;
    }

    private int displayRotationDegrees() {
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        if (rotation == Surface.ROTATION_90) return 90;
        if (rotation == Surface.ROTATION_180) return 180;
        if (rotation == Surface.ROTATION_270) return 270;
        return 0;
    }

    private boolean isPassiveCredentialCaptureActive() {
        boolean ordinaryHome = customerActionsEnabled()
                && active
                && !adminLaunchPending
                && flow.screen() == KioskFlowModel.Screen.HOME
                && adminPinOverlay == null
                && homeView != null;
        return ordinaryHome || isReturnAuthenticationActive();
    }

    private boolean isReturnAuthenticationActive() {
        if (!customerActionsEnabled()
                || !active
                || !returnJourneyActive
                || faceJourney == FaceJourney.RETURN
                || returnAuthView == null
                || returnFlowController == null) {
            return false;
        }
        ReturnFlowController.Snapshot snapshot = returnFlowController.snapshot();
        return snapshot.state() == ReturnFlowModel.State.AUTHENTICATING
                || isReturnQueryRetryable(snapshot);
    }

    private boolean isReturnQueryRetryable() {
        if (!customerActionsEnabled()) {
            return false;
        }
        return returnFlowController != null
                && isReturnQueryRetryable(returnFlowController.snapshot());
    }

    private boolean isReturnQueryRetryable(ReturnFlowController.Snapshot snapshot) {
        return active
                && returnJourneyActive
                && !returnBackgrounded
                && faceJourney != FaceJourney.RETURN
                && returnAuthView != null
                && snapshot != null
                && snapshot.generation() == returnGeneration
                && snapshot.state() == ReturnFlowModel.State.LOADING
                && snapshot.error() != ReturnFlowController.ErrorCode.NONE
                && !returnServiceBusy;
    }

    private void acceptPassiveCredentialCharacter(int deviceId, char character) {
        if (!customerActionsEnabled()) {
            return;
        }
        IdCardScanSession.AppendResult result =
                customerActionBoundary.call(
                        CustomerActionBoundary.Effect.SCANNER_CREDENTIAL,
                        () -> idCardScanSession.appendCharacter(deviceId, character),
                        null);
        if (result == null) {
            return;
        }
        if (result == IdCardScanSession.AppendResult.IGNORED_OTHER_DEVICE) {
            return;
        }
        cancelInvalidCredentialRecovery();
        if (result == IdCardScanSession.AppendResult.FRAME_INVALID) {
            if (isReturnAuthenticationActive()) {
                showInvalidReturnCredential();
            } else {
                showInvalidCredentialWithAutoReset();
            }
        } else if (homeView != null) {
            homeView.showCredentialReading();
        } else if (returnAuthView != null) {
            returnAuthenticationFailed = false;
            returnCredentialReading = true;
            returnStatusMessage = "正在读取 ID 卡/二维码…";
            renderReturnSnapshot();
        }
        cancelIdCardIdleCompletion();
        final long completionGeneration = idCardScanSession.generation();
        idCardIdleCompletion = () -> customerActionBoundary.run(
                CustomerActionBoundary.Effect.ASYNC_CALLBACK,
                () -> {
                    idCardIdleCompletion = null;
                    finishPassiveCredentialInput(
                            idCardScanSession.finishIfCurrent(completionGeneration));
                });
        handler.postDelayed(
                idCardIdleCompletion, PASSIVE_SCAN_IDLE_COMPLETION_MILLIS);
    }

    private void finishPassiveCredentialInputNow(int deviceId) {
        if (!customerActionsEnabled()) {
            return;
        }
        IdCardScanSession.Completion completion = idCardScanSession.finish(deviceId);
        if (completion == null) {
            return;
        }
        cancelIdCardIdleCompletion();
        finishPassiveCredentialInput(completion);
    }

    private void finishPassiveCredentialInput(IdCardScanSession.Completion completion) {
        if (!customerActionsEnabled()
                || !isPassiveCredentialCaptureActive()
                || completion == null) {
            return;
        }
        boolean completingReturnCredential = isReturnAuthenticationActive();
        if (completingReturnCredential) {
            returnCredentialReading = false;
        }
        String rawCredential = completion.value();
        if (!completion.isValidFrame()) {
            appendCustomerLog("[Credential] rejected method=PASSIVE_SCAN length="
                    + completion.length());
            if (isReturnAuthenticationActive()) {
                showInvalidReturnCredential();
                return;
            }
            showInvalidCredentialWithAutoReset();
            return;
        }
        if (isReturnAuthenticationActive()) {
            submitReturnScannedCredential(rawCredential);
            idCardEventDrain.begin(
                    SystemClock.uptimeMillis(),
                    completion.deviceId(),
                    PASSIVE_SCAN_EVENT_DRAIN_MILLIS);
            return;
        }
        boolean accepted = customerActionBoundary.call(
                CustomerActionBoundary.Effect.SCANNER_CREDENTIAL,
                () -> flow.submitScannedCredential(rawCredential),
                false);
        UnlockMethod admittedMethod = flow.pendingCredentialMethod();
        String credentialType = admittedMethod == null
                ? "REJECTED" : admittedMethod.name();
        appendCustomerLog("[Credential] " + (accepted ? "accepted" : "rejected")
                + " method=" + credentialType + " length=" + rawCredential.length());
        if (!accepted) {
            showInvalidCredentialWithAutoReset();
            return;
        }
        idCardEventDrain.begin(
                SystemClock.uptimeMillis(),
                completion.deviceId(),
                PASSIVE_SCAN_EVENT_DRAIN_MILLIS);
        invalidateCustomerWork(true);
        renderScreen();
        startDiscoveryOperation();
    }

    private void submitReturnScannedCredential(String rawCredential) {
        if (!customerActionsEnabled()) {
            return;
        }
        if (isReturnQueryRetryable()) {
            retryReturnQuery();
            return;
        }
        if (!isReturnAuthenticationActive()
                || rawCredential == null
                || rawCredential.trim().isEmpty()) {
            returnStatusMessage = "凭证读取失败，请重新识别";
            renderReturnSnapshot();
            return;
        }
        CredentialAdmission admission = customerActionBoundary.call(
                CustomerActionBoundary.Effect.SCANNER_CREDENTIAL,
                () -> credentialPolicy.admit(UnlockMethod.ID_CARD, rawCredential),
                null);
        if (admission == null || !admission.accepted()) {
            showInvalidReturnCredential();
            return;
        }
        submitReturnIdentity(new ReturnIdentity(
                admission.method(), rawCredential, System.currentTimeMillis()));
    }

    private void beginReturnJourney() {
        if (!customerActionsEnabled()) {
            return;
        }
        if (!customerActionBoundary.call(
                CustomerActionBoundary.Effect.RETURN_JOURNEY,
                flow::requestReturnJourney,
                false)
                || !active
                || adminLaunchPending
                || returnJourneyActive) {
            return;
        }
        faceJourney = FaceJourney.NONE;
        cancelFaceWorkAndInvalidate();
        invalidateCustomerWork(true);
        flow.returnHome();
        pendingFaceSuccess = null;
        clearFaceDeferredFailure();
        resetReturnOperationState();
        returnJourneyActive = true;
        returnBackgrounded = false;
        returnResumeRequiresAuth = false;
        returnGeneration = returnFlowController.begin();
        returnStatusMessage = returnLocalDemo
                ? "本机模拟数据 · 请选择还柜验证方式"
                : "请选择还柜验证方式";
        renderReturnSnapshot();
    }

    private void renderReturnSnapshot() {
        if (!returnJourneyActive || returnFlowController == null) {
            return;
        }
        ReturnFlowController.Snapshot snapshot = returnFlowController.snapshot();
        if (snapshot.generation() != returnGeneration) {
            return;
        }
        if (snapshot.state() == ReturnFlowModel.State.HOME) {
            finishReturnJourneyToHome("还柜流程已结束");
            return;
        }
        ReturnScreenPresentation presentation = ReturnScreenResolver.resolve(
                buildReturnScreenInput(snapshot));
        updateReturnTerminalCountdown(snapshot, presentation);
        switch (presentation.surface()) {
            case AUTHENTICATION:
                renderReturnAuthentication(snapshot, presentation);
                return;
            case LOCKER_LIST:
                renderReturnLockerSelection(snapshot, presentation);
                return;
            case PROGRESS:
                renderReturnProgress(snapshot, presentation);
                return;
            default:
                throw new IllegalStateException("Unknown return surface");
        }
    }

    private ReturnScreenResolver.Input buildReturnScreenInput(
            ReturnFlowController.Snapshot snapshot) {
        boolean doorProofReady = returnDoorSession != null
                && returnDoorSession.isReadyToCommit();
        boolean authorizationRetryable = snapshot.state()
                == ReturnFlowModel.State.CONFIRMING
                && snapshot.error() != ReturnFlowController.ErrorCode.NONE
                && !returnUnlockConsumed;
        return ReturnScreenResolver.Input.builder(snapshot, isNetworkOnline())
                .serviceBusy(returnServiceBusy || returnCredentialReading)
                .chosenLocker(returnChosenLocker)
                .authorizedRequestPresent(returnAuthorizedRequest != null)
                .doorSessionPresent(returnDoorSession != null)
                .unlockConsumed(returnUnlockConsumed)
                .serialFailure(returnSerialFailure)
                .commitDeferred(returnCommitDeferred)
                .doorProofReady(doorProofReady)
                .commitInFlight(doorProofReady && returnServiceBusy)
                .authenticationFailed(returnAuthenticationFailed)
                .authorizationRetryable(authorizationRetryable)
                .buildInput();
    }

    private ReturnScreenPresentation currentReturnPresentation(
            ReturnFlowController.Snapshot snapshot) {
        return ReturnScreenResolver.resolve(buildReturnScreenInput(snapshot));
    }

    private void renderReturnAuthentication(ReturnFlowController.Snapshot snapshot,
            ReturnScreenPresentation presentation) {
        homeView = null;
        lockerSelectionView = null;
        resultOverlay = null;
        returnLockerView = null;
        returnProgressView = null;
        if (returnAuthView == null) {
            final ReturnAuthView view = new ReturnAuthView(this, credentialPolicy);
            returnAuthView = view;
            view.setListener(new ReturnAuthView.Listener() {
                @Override
                public void onCredentialSubmit(UnlockMethod method, String rawValue) {
                    if (returnAuthView != view || !isReturnAuthenticationActive()) {
                        return;
                    }
                    submitReturnFormCredential(method, rawValue);
                }

                @Override
                public void onFaceRequested() {
                    if (!customerActionsEnabled()) {
                        return;
                    }
                    if (returnAuthView == view) {
                        if (isReturnQueryRetryable()) {
                            retryReturnQuery();
                        } else {
                            beginReturnFaceRecognition();
                        }
                    }
                }

                @Override
                public void onPalmRequested() {
                    if (!customerActionsEnabled()) {
                        return;
                    }
                    if (returnAuthView == view) {
                        if (isReturnQueryRetryable()) {
                            retryReturnQuery();
                        } else {
                            beginReturnPalmRecognition();
                        }
                    }
                }

                @Override
                public void onCancelRequested() {
                    if (returnAuthView == view) {
                        cancelReturnJourneyFromUser();
                    }
                }

                @Override
                public void onRetryRequested() {
                    if (returnAuthView == view) retryPresentedReturnAction();
                }

                @Override
                public void onHomeRequested() {
                    if (returnAuthView == view) {
                        finishPresentedReturnJourneyToHome("还柜身份页返回首页");
                    }
                }
            });
            replaceRoot(view);
        }
        returnAuthView.render(presentation, returnDisplayMessage(returnStatusMessage));
    }

    private void renderReturnLockerSelection(ReturnFlowController.Snapshot snapshot,
            ReturnScreenPresentation presentation) {
        resetPassiveCredentialCapture();
        homeView = null;
        returnAuthView = null;
        returnProgressView = null;
        if (returnLockerView == null) {
            final ReturnLockerView view = new ReturnLockerView(this);
            returnLockerView = view;
            view.setListener(new ReturnLockerView.Listener() {
                @Override
                public void onLockerSelected(ReturnLocker locker) {
                    ReturnFlowController.Snapshot current =
                            returnFlowController.snapshot();
                    ReturnScreenPresentation currentPresentation =
                            currentReturnPresentation(current);
                    if (returnLockerView == view
                            && !returnServiceBusy
                            && currentPresentation.canSelect()
                            && current.state() == ReturnFlowModel.State.SELECTING) {
                        returnChosenLocker = locker;
                        renderReturnSnapshot();
                    }
                }

                @Override
                public void onLockerConfirmed(ReturnLocker locker) {
                    ReturnFlowController.Snapshot current =
                            returnFlowController.snapshot();
                    ReturnScreenPresentation currentPresentation =
                            currentReturnPresentation(current);
                    if (returnLockerView == view
                            && currentPresentation.canConfirmLocker()) {
                        confirmReturnLocker(locker);
                    }
                }

                @Override
                public void onCancelRequested() {
                    if (returnLockerView == view) {
                        cancelReturnJourneyFromUser();
                    }
                }

                @Override
                public void onHomeRequested() {
                    if (returnLockerView == view) {
                        finishPresentedReturnJourneyToHome("还柜终态返回首页");
                    }
                }
            });
            replaceRoot(view);
        }
        String message = returnDisplayMessage(returnStatusMessage);
        ReturnLocker displayedLocker = snapshot.state() == ReturnFlowModel.State.CONFIRMING
                ? snapshot.selectedLocker() : returnChosenLocker;
        returnLockerView.render(
                presentation, snapshot.remainingLockers(), displayedLocker,
                message, returnTerminalCountdownText(snapshot));
    }

    private void renderReturnProgress(ReturnFlowController.Snapshot snapshot,
            ReturnScreenPresentation presentation) {
        resetPassiveCredentialCapture();
        homeView = null;
        returnAuthView = null;
        returnLockerView = null;
        if (returnProgressView == null) {
            final ReturnProgressView view = new ReturnProgressView(this);
            returnProgressView = view;
            view.setListener(new ReturnProgressView.Listener() {
                @Override
                public void onDoorClosedConfirmed() {
                    acknowledgeReturnDoorClosed();
                }

                @Override
                public void onRetryRequested() {
                    retryPresentedReturnAction();
                }

                @Override
                public void onCancelRequested() {
                    cancelReturnJourneyFromUser();
                }

                @Override
                public void onNextRequested() {
                    returnStatusMessage = null;
                    renderReturnSnapshot();
                }

                @Override
                public void onHomeRequested() {
                    finishPresentedReturnJourneyToHome("还柜办理完成");
                }
            });
            replaceRoot(view);
        }
        ReturnLocker locker = snapshot.selectedLocker();
        String label = locker == null ? "--"
                : locker.areaDisplayName() + " · " + locker.displayLabel();
        returnProgressView.render(
                presentation, label, returnDisplayMessage(returnStatusMessage),
                returnTerminalCountdownText(snapshot));
    }

    private String returnDisplayMessage(String message) {
        if (!returnLocalDemo) return message;
        if (message == null || message.trim().isEmpty()) return "本机模拟数据";
        return message.startsWith("本机模拟数据")
                ? message : "本机模拟数据 · " + message;
    }

    private void submitReturnFormCredential(UnlockMethod method, String rawValue) {
        if (!customerActionsEnabled()) {
            return;
        }
        if (isReturnQueryRetryable()) {
            retryReturnQuery();
            return;
        }
        if (!isReturnAuthenticationActive()
                || (method != UnlockMethod.PHONE && method != UnlockMethod.PASSWORD)
                || rawValue == null
                || rawValue.trim().isEmpty()) {
            returnAuthenticationFailed = true;
            returnStatusMessage = "请输入有效手机号或密码";
            renderReturnSnapshot();
            return;
        }
        customerActionBoundary.run(
                CustomerActionBoundary.Effect.PHONE_CREDENTIAL,
                () -> submitReturnIdentity(new ReturnIdentity(
                        method, rawValue, System.currentTimeMillis())));
    }

    private void submitReturnIdentity(ReturnIdentity identity) {
        if (!customerActionsEnabled()
                || !returnJourneyActive
                || returnServiceBusy
                || identity == null) {
            return;
        }
        if (!isNetworkOnline()) {
            returnStatusMessage = "网络异常，断网禁止办理还柜";
            renderReturnSnapshot();
            return;
        }
        final long generation = returnGeneration;
        final long epoch = nextGeneration(returnAsyncEpoch);
        returnAsyncEpoch = epoch;
        returnAuthenticationFailed = false;
        returnServiceBusy = true;
        returnStatusMessage = "正在查询本人在用柜门…";
        renderReturnSnapshot();
        try {
            RETURN_SERVICE_EXECUTOR.execute(() -> customerActionBoundary.run(
                    CustomerActionBoundary.Effect.RETURN_SERVICE,
                    () -> {
                final boolean accepted = returnFlowController.authenticate(
                        generation, identity);
                final ReturnFlowController.Snapshot completed =
                        returnFlowController.snapshot();
                handler.post(() -> {
                    if (!isCurrentReturnAsync(generation, epoch)) {
                        return;
                    }
                    returnServiceBusy = false;
                    if (!accepted) {
                        returnStatusMessage = returnServiceMessage(completed.error());
                    } else if (completed.state() == ReturnFlowModel.State.FAILURE) {
                        returnStatusMessage = "未查询到需要归还的柜门";
                    } else {
                        returnStatusMessage = returnLocalDemo
                                ? "本机模拟数据 · 请选择要归还的柜门" : null;
                    }
                    renderReturnSnapshot();
                });
                    }));
        } catch (RejectedExecutionException ignored) {
            returnServiceBusy = false;
            returnStatusMessage = "还柜服务暂时不可用";
            renderReturnSnapshot();
        }
    }

    private void retryReturnQuery() {
        if (!customerActionsEnabled()) {
            return;
        }
        customerActionBoundary.run(
                CustomerActionBoundary.Effect.RETRY,
                () -> {
        if (!returnJourneyActive
                || !isReturnQueryRetryable()
                || !isNetworkOnline()) {
            returnStatusMessage = !isNetworkOnline()
                    ? "网络异常，断网禁止办理还柜"
                    : "当前查询无法重试，请重新验证";
            renderReturnSnapshot();
            return;
        }
        final long generation = returnGeneration;
        final long epoch = nextGeneration(returnAsyncEpoch);
        returnAsyncEpoch = epoch;
        returnServiceBusy = true;
        returnStatusMessage = "正在重新查询本人在用柜门…";
        renderReturnSnapshot();
        try {
            RETURN_SERVICE_EXECUTOR.execute(() -> customerActionBoundary.run(
                    CustomerActionBoundary.Effect.RETURN_SERVICE,
                    () -> {
                final boolean accepted = returnFlowController.retryQuery(generation);
                final ReturnFlowController.Snapshot completed =
                        returnFlowController.snapshot();
                handler.post(() -> {
                    if (!isCurrentReturnAsync(generation, epoch)) {
                        return;
                    }
                    returnServiceBusy = false;
                    if (!accepted) {
                        returnStatusMessage = returnServiceMessage(completed.error());
                    } else if (completed.state() == ReturnFlowModel.State.FAILURE) {
                        returnStatusMessage = "未查询到需要归还的柜门";
                    } else {
                        returnStatusMessage = returnLocalDemo
                                ? "本机模拟数据 · 请选择要归还的柜门" : null;
                    }
                    renderReturnSnapshot();
                });
                    }));
        } catch (RejectedExecutionException ignored) {
            returnServiceBusy = false;
            returnStatusMessage = "还柜服务暂时不可用，请重试";
            renderReturnSnapshot();
        }
        });
    }

    private void beginReturnPalmRecognition() {
        if (!customerActionsEnabled()
                || !isReturnAuthenticationActive()
                || returnServiceBusy) {
            return;
        }
        if (!returnLocalDemo) {
            returnAuthenticationFailed = true;
            returnStatusMessage = "掌纹设备尚未接入，无法办理还柜";
            renderReturnSnapshot();
            return;
        }
        cancelReturnPalmTask();
        final long generation = returnGeneration;
        final long epoch = nextGeneration(returnAsyncEpoch);
        returnAsyncEpoch = epoch;
        returnServiceBusy = true;
        returnStatusMessage = "本机模拟掌纹识别中…";
        renderReturnSnapshot();
        returnPalmTask = () -> customerActionBoundary.run(
                CustomerActionBoundary.Effect.PALM,
                () -> {
                    returnPalmTask = null;
                    if (!isCurrentReturnAsync(generation, epoch)
                            || !isReturnAuthenticationActive()) {
                        return;
                    }
                    returnServiceBusy = false;
                    submitReturnIdentity(new ReturnIdentity(
                            UnlockMethod.PALM,
                            "local-demo-palm-" + generation,
                            System.currentTimeMillis()));
                });
        handler.postDelayed(returnPalmTask, RETURN_PALM_SIMULATION_MILLIS);
    }

    private void confirmReturnLocker(ReturnLocker locker) {
        if (!customerActionsEnabled()) {
            return;
        }
        ReturnFlowController.Snapshot snapshot = returnFlowController.snapshot();
        ReturnScreenPresentation presentation = currentReturnPresentation(snapshot);
        if (!presentation.canConfirmLocker()) {
            returnStatusMessage = "当前状态不能确认柜门";
            renderReturnSnapshot();
            return;
        }
        if (returnJourneyActive
                && !returnServiceBusy
                && snapshot.state() == ReturnFlowModel.State.CONFIRMING
                && locker != null
                && locker.equals(snapshot.selectedLocker())
                && snapshot.operationId() == returnOperationId) {
            if (snapshot.error() != ReturnFlowController.ErrorCode.NONE) {
                retryReturnAuthorization(
                        snapshot.generation(), snapshot.operationId(), locker);
            } else {
                AuthorizedUnlockRequest request =
                        returnFlowController.authorizedRequestForStatusBaseline(
                                snapshot.generation(), snapshot.operationId());
                if (request == null) {
                    returnStatusMessage = "本次柜门授权已失效，请重新验证";
                    renderReturnSnapshot();
                } else if (!isNetworkOnline()) {
                    failReturnAuthorizationBeforeUnlock("网络异常，断网禁止开柜");
                } else {
                    returnAuthorizedRequest = request;
                    returnStatusMessage = "正在确认柜门关闭状态…";
                    startReturnSerialOperation(request);
                }
            }
            return;
        }
        if (!returnJourneyActive
                || returnServiceBusy
                || snapshot.state() != ReturnFlowModel.State.SELECTING
                || locker == null
                || locker != returnChosenLocker
                || !snapshot.remainingLockers().contains(locker)) {
            returnStatusMessage = "请选择服务器返回的有效柜门";
            renderReturnSnapshot();
            return;
        }
        if (!isNetworkOnline()) {
            returnStatusMessage = "网络异常，断网禁止开柜";
            renderReturnSnapshot();
            return;
        }
        final long operationId = nextReturnOperationId();
        if (operationId <= 0L) {
            returnStatusMessage = "还柜操作编号已耗尽，请联系管理员";
            renderReturnSnapshot();
            return;
        }
        final long generation = returnGeneration;
        final long epoch = nextGeneration(returnAsyncEpoch);
        returnAsyncEpoch = epoch;
        returnOperationId = operationId;
        returnServiceBusy = true;
        returnStatusMessage = "正在获取本次柜门授权…";
        renderReturnSnapshot();
        try {
            RETURN_SERVICE_EXECUTOR.execute(() -> customerActionBoundary.run(
                    CustomerActionBoundary.Effect.RETURN_SERVICE,
                    () -> {
                boolean authorized = returnFlowController.selectAndAuthorize(
                        generation, operationId, locker);
                AuthorizedUnlockRequest request = authorized
                        ? returnFlowController.authorizedRequestForStatusBaseline(
                        generation, operationId) : null;
                handler.post(() -> {
                    if (!isCurrentReturnAsync(generation, epoch)
                            || returnOperationId != operationId) {
                        return;
                    }
                    returnServiceBusy = false;
                    if (!authorized || request == null) {
                        returnStatusMessage = returnServiceMessage(
                                returnFlowController.snapshot().error());
                        renderReturnSnapshot();
                        return;
                    }
                    if (!isNetworkOnline()) {
                        failReturnAuthorizationBeforeUnlock(
                                "网络异常，断网禁止开柜");
                        return;
                    }
                    returnAuthorizedRequest = request;
                    returnStatusMessage = "正在确认柜门关闭状态…";
                    startReturnSerialOperation(request);
                });
                    }));
        } catch (RejectedExecutionException ignored) {
            returnServiceBusy = false;
            returnStatusMessage = "还柜服务暂时不可用";
            renderReturnSnapshot();
        }
    }

    private void retryReturnAuthorization(
            long generation, long operationId, ReturnLocker locker) {
        if (!customerActionsEnabled()) {
            return;
        }
        customerActionBoundary.run(
                CustomerActionBoundary.Effect.RETRY,
                () -> {
        ReturnFlowController.Snapshot snapshot = returnFlowController.snapshot();
        if (!returnJourneyActive
                || returnServiceBusy
                || generation != returnGeneration
                || operationId != returnOperationId
                || snapshot.generation() != generation
                || snapshot.operationId() != operationId
                || snapshot.state() != ReturnFlowModel.State.CONFIRMING
                || snapshot.error() == ReturnFlowController.ErrorCode.NONE
                || locker == null
                || !locker.equals(snapshot.selectedLocker())) {
            return;
        }
        if (!isNetworkOnline()) {
            returnStatusMessage = "网络异常，断网禁止开柜";
            renderReturnSnapshot();
            return;
        }
        final long epoch = nextGeneration(returnAsyncEpoch);
        returnAsyncEpoch = epoch;
        returnServiceBusy = true;
        returnStatusMessage = "正在重新获取本次柜门授权…";
        renderReturnSnapshot();
        try {
            RETURN_SERVICE_EXECUTOR.execute(() -> customerActionBoundary.run(
                    CustomerActionBoundary.Effect.RETURN_SERVICE,
                    () -> {
                final boolean authorized = returnFlowController.retryAuthorization(
                        generation, operationId);
                final AuthorizedUnlockRequest request = authorized
                        ? returnFlowController.authorizedRequestForStatusBaseline(
                        generation, operationId) : null;
                handler.post(() -> {
                    if (!isCurrentReturnAsync(generation, epoch)
                            || returnOperationId != operationId) {
                        return;
                    }
                    returnServiceBusy = false;
                    if (!authorized || request == null) {
                        returnStatusMessage = returnServiceMessage(
                                returnFlowController.snapshot().error());
                        renderReturnSnapshot();
                        return;
                    }
                    if (!isNetworkOnline()) {
                        failReturnAuthorizationBeforeUnlock(
                                "网络异常，断网禁止开柜");
                        return;
                    }
                    returnAuthorizedRequest = request;
                    returnStatusMessage = "正在确认柜门关闭状态…";
                    startReturnSerialOperation(request);
                });
                    }));
        } catch (RejectedExecutionException ignored) {
            returnServiceBusy = false;
            returnStatusMessage = "还柜服务暂时不可用，请重试";
            renderReturnSnapshot();
        }
        });
    }

    private void failReturnAuthorizationBeforeUnlock(String message) {
        boolean recorded = !returnUnlockConsumed
                && returnFlowController.markAuthorizationUnavailableBeforeUnlock(
                        returnGeneration, returnOperationId);
        returnAuthorizedRequest = null;
        returnSerialFailure = !recorded;
        returnStatusMessage = recorded
                ? message : "本次柜门授权状态无法恢复，请联系管理员";
        renderReturnSnapshot();
    }

    private void retryPresentedReturnAction() {
        if (!customerActionsEnabled()) {
            return;
        }
        customerActionBoundary.run(
                CustomerActionBoundary.Effect.RETRY,
                () -> {
        ReturnFlowController.Snapshot snapshot = returnFlowController.snapshot();
        if (!returnJourneyActive || returnServiceBusy) {
            return;
        }
        ReturnScreenPresentation presentation = currentReturnPresentation(snapshot);
        switch (presentation.retryKind()) {
            case AUTH:
                restartReturnIdentityEntry();
                return;
            case QUERY:
                if (snapshot.state() == ReturnFlowModel.State.OPENING
                        && snapshot.error()
                                == ReturnFlowController.ErrorCode.UNLOCK_FAILED) {
                    if (returnUnlockConsumed) {
                        returnStatusMessage = "开柜写入已消费，禁止重试或离开";
                        renderReturnSnapshot();
                        return;
                    }
                    invalidateCustomerWork(true);
                    long freshGeneration = customerActionBoundary.call(
                            CustomerActionBoundary.Effect.RETURN_SERVICE,
                            () -> returnFlowController.restartAfterUnlockFailure(
                                    snapshot.generation(), snapshot.operationId()),
                            0L);
                    if (freshGeneration > 0L) {
                        resetReturnOperationState();
                        returnGeneration = freshGeneration;
                        returnStatusMessage = returnLocalDemo
                                ? "本机模拟数据 · 已保留身份并重新查询本人柜门"
                                : "已保留身份并重新查询本人柜门";
                    }
                    renderReturnSnapshot();
                    return;
                }
                retryReturnQuery();
                return;
            case AUTHORIZATION:
                if (!returnUnlockConsumed) {
                    retryReturnAuthorization(snapshot.generation(),
                            snapshot.operationId(), snapshot.selectedLocker());
                }
                return;
            case STATUS:
                startReturnStatusResume();
                return;
            case COMMIT:
                retryPresentedReturnCommit(snapshot);
                return;
            case NONE:
            default:
                return;
        }
        });
    }

    private void restartReturnIdentityEntry() {
        if (!customerActionsEnabled()) {
            return;
        }
        customerActionBoundary.run(
                CustomerActionBoundary.Effect.RETRY,
                () -> {
        if (!returnJourneyActive || returnServiceBusy || returnUnlockConsumed) return;
        invalidateCustomerWork(true);
        returnFlowController.cancel();
        resetReturnOperationState();
        returnGeneration = returnFlowController.begin();
        returnAuthenticationFailed = false;
        returnStatusMessage = returnLocalDemo
                ? "本机模拟数据 · 请重新验证还柜身份"
                : "请重新验证还柜身份";
        renderReturnSnapshot();
        });
    }

    private void retryPresentedReturnCommit(
            ReturnFlowController.Snapshot snapshot) {
        if (!customerActionsEnabled()) {
            return;
        }
        customerActionBoundary.run(
                CustomerActionBoundary.Effect.RETRY,
                () -> {
        if (snapshot == null) return;
        if (snapshot.state() == ReturnFlowModel.State.COMMITTING
                && snapshot.error() == ReturnFlowController.ErrorCode.COMMIT_FAILED) {
            retryReturnCommit(snapshot.generation(), snapshot.operationId());
            return;
        }
        if (returnCommitDeferred
                && returnDoorSession != null
                && returnDoorSession.isReadyToCommit()) {
            completeReturnAfterVerifiedClose();
            return;
        }
        returnStatusMessage = "关门结果尚未具备安全提交条件";
        renderReturnSnapshot();
        });
    }

    /** Compatibility entry retained for integrations; it delegates to the typed router. */
    private void retryReturnJourney() {
        if (!customerActionsEnabled()) {
            return;
        }
        customerActionBoundary.run(
                CustomerActionBoundary.Effect.RETRY,
                this::retryPresentedReturnAction);
    }

    private void retryReturnCommit(long generation, long operationId) {
        if (!customerActionsEnabled()) {
            return;
        }
        customerActionBoundary.run(
                CustomerActionBoundary.Effect.RETRY,
                () -> {
        if (!isNetworkOnline()) {
            returnStatusMessage = "网络异常，关门结果已保留，请联网后重试";
            renderReturnSnapshot();
            return;
        }
        final long epoch = nextGeneration(returnAsyncEpoch);
        returnAsyncEpoch = epoch;
        returnServiceBusy = true;
        returnStatusMessage = "正在重新提交还柜结果…";
        renderReturnSnapshot();
        try {
            RETURN_SERVICE_EXECUTOR.execute(() -> customerActionBoundary.run(
                    CustomerActionBoundary.Effect.RETURN_SERVICE,
                    () -> {
                boolean completed = returnFlowController.retryCommit(
                        generation, operationId);
                handler.post(() -> {
                    if (!isCurrentReturnAsync(generation, epoch)) {
                        return;
                    }
                    returnServiceBusy = false;
                    ReturnFlowController.Snapshot snapshot =
                            returnFlowController.snapshot();
                    boolean committed = completed
                            && snapshot.error() == ReturnFlowController.ErrorCode.NONE
                            && (snapshot.state() == ReturnFlowModel.State.SUCCESS
                            || snapshot.state() == ReturnFlowModel.State.SELECTING);
                    if (committed) {
                        clearCompletedReturnPhysicalOperation();
                    }
                    returnStatusMessage = committed
                            ? (snapshot.state() == ReturnFlowModel.State.SUCCESS
                            ? "还柜完成" : "本柜门还柜完成，请选择下一个柜门")
                            : "提交失败，关门结果已保留";
                    renderReturnSnapshot();
                });
                    }));
        } catch (RejectedExecutionException ignored) {
            returnServiceBusy = false;
            returnStatusMessage = "提交服务暂时不可用";
            renderReturnSnapshot();
        }
        });
    }

    private void cancelReturnJourneyFromUser() {
        ReturnFlowController.Snapshot snapshot = returnFlowController.snapshot();
        ReturnScreenPresentation presentation = currentReturnPresentation(snapshot);
        if (returnUnlockConsumed
                || (!presentation.canBack() && !presentation.canCancel())) {
            returnStatusMessage = "柜门操作已开始，请确认关门后完成流程";
            renderReturnSnapshot();
            return;
        }
        finishReturnJourneyToHome("用户取消离场还柜");
    }

    private void finishPresentedReturnJourneyToHome(String reason) {
        ReturnFlowController.Snapshot snapshot = returnFlowController.snapshot();
        ReturnScreenPresentation presentation = currentReturnPresentation(snapshot);
        if (returnUnlockConsumed || !presentation.canHome()) {
            returnStatusMessage = "当前柜门操作不能返回首页";
            renderReturnSnapshot();
            return;
        }
        finishReturnJourneyToHome(reason);
    }

    private void finishReturnJourneyToHome(String reason) {
        cancelReturnTimers();
        faceJourney = FaceJourney.NONE;
        cancelFaceWorkAndInvalidate();
        if (returnDoorSession != null) {
            returnDoorSession.cancel(returnGeneration, returnOperationId);
        }
        invalidateCustomerWork(true);
        returnFlowController.cancel();
        returnJourneyActive = false;
        returnBackgrounded = false;
        returnResumeRequiresAuth = false;
        resetReturnOperationState();
        flow.returnHome();
        appendCustomerLog("[Return] " + reason);
        renderScreen();
    }

    private void pauseReturnJourneyForBackground() {
        if (!returnJourneyActive) {
            return;
        }
        returnBackgrounded = true;
        returnAsyncEpoch = nextGeneration(returnAsyncEpoch);
        cancelReturnTimers();
        cancelReturnPalmTask();
        if (returnOperationId > 0L
                && returnDoorSession != null
                && returnDoorSession.isReadyToCommit()) {
            // Do not query the controller while its serialized commit may own its lock.
            // onStart places a FIFO barrier behind that work and reconciles its snapshot.
            returnResumeRequiresAuth = false;
            return;
        }
        ReturnFlowController.Snapshot snapshot = returnFlowController.snapshot();
        if (returnUnlockConsumed
                && (snapshot.state() == ReturnFlowModel.State.OPENING
                || snapshot.state() == ReturnFlowModel.State.WAITING_FOR_CLOSE)) {
            if (returnDoorSession != null) {
                returnDoorSession.onDisconnected(
                        returnGeneration, returnOperationId);
            }
            if (snapshot.state() == ReturnFlowModel.State.WAITING_FOR_CLOSE) {
                returnFlowController.markStatusUncertain(
                        returnGeneration, returnOperationId);
            }
            returnResumeRequiresAuth = false;
        } else if (snapshot.state() != ReturnFlowModel.State.COMMITTING
                && snapshot.state() != ReturnFlowModel.State.SUCCESS) {
            returnResumeRequiresAuth = true;
        }
    }

    private void resumeReturnJourneyFromBackground() {
        if (!customerActionsEnabled()) {
            return;
        }
        customerActionBoundary.run(
                CustomerActionBoundary.Effect.RESUME,
                () -> {
        returnBackgrounded = false;
        if (returnResumeRequiresAuth) {
            returnResumeRequiresAuth = false;
            returnFlowController.cancel();
            resetReturnOperationState();
            returnGeneration = returnFlowController.begin();
            returnStatusMessage = "操作已中断，请重新进行还柜验证";
            renderReturnSnapshot();
            return;
        }
        if (returnServiceBusy
                && returnOperationId > 0L
                && returnDoorSession != null
                && returnDoorSession.isReadyToCommit()) {
            queueReturnCommitReconcile(returnGeneration, returnOperationId);
            return;
        }
        ReturnFlowController.Snapshot snapshot = returnFlowController.snapshot();
        if (returnOperationId > 0L
                && (snapshot.state() == ReturnFlowModel.State.COMMITTING
                || (returnDoorSession != null
                && returnDoorSession.isReadyToCommit()))) {
            queueReturnCommitReconcile(returnGeneration, returnOperationId);
            return;
        }
        if (returnUnlockConsumed
                && snapshot.state() == ReturnFlowModel.State.WAITING_FOR_CLOSE) {
            startReturnStatusResume();
            return;
        }
        if (returnUnlockConsumed
                && snapshot.state() == ReturnFlowModel.State.OPENING) {
            returnStatusMessage = "开柜结果不确定，禁止自动重发，请联系管理员";
        }
        renderReturnSnapshot();
        });
    }

    /**
     * Runs after any pre-background return service call on the process-wide FIFO lane. The
     * barrier never dispatches serial work; it only asks the UI generation to reconcile the
     * controller's authoritative commit result.
     */
    private void queueReturnCommitReconcile(long generation, long operationId) {
        if (!customerActionsEnabled()) {
            return;
        }
        if (!active
                || !returnJourneyActive
                || returnBackgrounded
                || generation != returnGeneration
                || operationId <= 0L
                || operationId != returnOperationId) {
            return;
        }
        final long epoch = nextGeneration(returnAsyncEpoch);
        returnAsyncEpoch = epoch;
        returnServiceBusy = true;
        returnStatusMessage = "正在核对还柜提交结果…";
        renderReturnSnapshot();
        try {
            RETURN_SERVICE_EXECUTOR.execute(() -> customerActionBoundary.run(
                    CustomerActionBoundary.Effect.ASYNC_CALLBACK,
                    () -> handler.post(() -> reconcileReturnCommitState(
                            generation, operationId, epoch))));
        } catch (RejectedExecutionException ignored) {
            if (returnGeneration == generation
                    && returnOperationId == operationId
                    && returnAsyncEpoch == epoch) {
                returnServiceBusy = false;
                returnCommitDeferred = true;
                returnStatusMessage = "提交结果暂时无法核对，请重试";
                renderReturnSnapshot();
            }
        }
    }

    private void reconcileReturnCommitState(
            long generation, long operationId, long epoch) {
        if (!isCurrentReturnAsync(generation, epoch)
                || operationId != returnOperationId) {
            return;
        }
        ReturnFlowController.Snapshot snapshot = returnFlowController.snapshot();
        if (snapshot.generation() != generation) {
            return;
        }
        returnServiceBusy = false;
        boolean committed = snapshot.error() == ReturnFlowController.ErrorCode.NONE
                && (snapshot.state() == ReturnFlowModel.State.SELECTING
                || snapshot.state() == ReturnFlowModel.State.SUCCESS);
        if (committed) {
            clearCompletedReturnPhysicalOperation();
            returnStatusMessage = snapshot.state() == ReturnFlowModel.State.SUCCESS
                    ? "还柜完成" : "本柜门还柜完成，请选择下一个柜门";
            renderReturnSnapshot();
            return;
        }
        if (snapshot.state() == ReturnFlowModel.State.COMMITTING
                && snapshot.error() == ReturnFlowController.ErrorCode.COMMIT_FAILED) {
            returnStatusMessage = "提交失败，关门结果已保留，请重试";
            renderReturnSnapshot();
            return;
        }
        if (snapshot.state() == ReturnFlowModel.State.WAITING_FOR_CLOSE
                && returnDoorSession != null
                && returnDoorSession.isReadyToCommit()) {
            completeReturnAfterVerifiedClose();
            return;
        }
        returnCommitDeferred = true;
        returnStatusMessage = "还柜提交状态无法确认，请重试";
        renderReturnSnapshot();
    }

    private void resetReturnOperationState() {
        cancelReturnTimers();
        cancelReturnPalmTask();
        returnServiceBusy = false;
        returnUnlockConsumed = false;
        returnSerialFailure = false;
        returnCommitDeferred = false;
        returnAuthenticationFailed = false;
        returnCredentialReading = false;
        returnImmediatePollPending = false;
        returnOperationId = 0L;
        returnSerialProtocolId = 0L;
        returnUnlockAttemptId = 0L;
        returnActivePollId = 0L;
        returnChosenLocker = null;
        returnAuthorizedRequest = null;
        clearReturnUnlockDispatchBinding();
        returnDoorSession = null;
        returnStatusMessage = null;
        returnAuthView = null;
        returnLockerView = null;
        returnProgressView = null;
    }

    private void cancelReturnTimers() {
        if (returnPollTask != null) {
            handler.removeCallbacks(returnPollTask);
            returnPollTask = null;
        }
        if (returnPollTimeoutTask != null) {
            handler.removeCallbacks(returnPollTimeoutTask);
            returnPollTimeoutTask = null;
        }
        cancelReturnTerminalCountdown();
    }

    private void cancelReturnPalmTask() {
        if (returnPalmTask != null) {
            handler.removeCallbacks(returnPalmTask);
            returnPalmTask = null;
        }
    }

    private boolean isCurrentReturnAsync(long generation, long epoch) {
        return customerActionBoundary.call(
                CustomerActionBoundary.Effect.ASYNC_CALLBACK,
                () -> customerActionsEnabled()
                        && active
                        && returnJourneyActive
                        && !returnBackgrounded
                        && returnGeneration == generation
                        && returnAsyncEpoch == epoch
                        && returnFlowController.snapshot().generation() == generation,
                false);
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

    private long nextReturnOperationId() {
        if (nextReturnOperationId == Long.MAX_VALUE) {
            return 0L;
        }
        nextReturnOperationId++;
        return nextReturnOperationId;
    }

    private static String returnServiceMessage(ReturnFlowController.ErrorCode error) {
        if (error == ReturnFlowController.ErrorCode.NO_LOCKERS) {
            return "未查询到需要归还的柜门";
        }
        if (error == ReturnFlowController.ErrorCode.RETRYABLE_FAILURE) {
            return "服务繁忙，请稍后重试";
        }
        if (error == ReturnFlowController.ErrorCode.AUTHORIZATION_EXPIRED) {
            return "本次开柜授权已过期，请重新获取本柜授权";
        }
        if (error == ReturnFlowController.ErrorCode.REJECTED) {
            return "身份或柜门授权未通过";
        }
        return "还柜服务尚未接入或暂时不可用";
    }

    private void startReturnSerialOperation(AuthorizedUnlockRequest request) {
        if (!customerActionsEnabled()
                || !active
                || !returnJourneyActive
                || returnBackgrounded
                || request == null
                || request.operationId() != returnOperationId) {
            return;
        }
        invalidateCustomerWork(true);
        if (customerOperationToken <= 0L
                || customerOperationTokenExhausted
                || !customerSerialTransmitter.beginAuthorizedReturn(
                        customerOperationToken, request)) {
            returnSerialFailure = true;
            returnStatusMessage = "无法建立安全串口会话，请联系管理员";
            renderReturnSnapshot();
            return;
        }
        customerSerialPhase = CustomerSerialPhase.RETURN_DOOR_STATUS;
        returnSerialFailure = false;
        customerConfirmedTarget = request.target();
        customerWriteAttribution = SerialWriteAttribution.doorStatus(request.target());
        reserveCustomerOperation(CustomerOperationKind.RETURN);
        returnSerialProtocolId = request.operationId();
        customerProtocolId = returnSerialProtocolId;
        returnUnlockAttemptId = 0L;
        returnActivePollId = 0L;
        returnImmediatePollPending = false;
        returnDoorSession = new ReturnDoorSession(
                returnGeneration, request.operationId(), request.target());
        renderReturnSnapshot();
        requestCustomerBinding(CustomerOperationKind.RETURN, returnSerialProtocolId);
    }

    /** Rebuilds only the status-monitoring transport; a consumed authorization is never resent. */
    private void startReturnStatusResume() {
        if (!customerActionsEnabled()) {
            return;
        }
        AuthorizedUnlockRequest request = returnAuthorizedRequest;
        ReturnDoorSession session = returnDoorSession;
        ReturnFlowController.Snapshot snapshot = returnFlowController.snapshot();
        boolean statusRecoveryRequired =
                snapshot.error() == ReturnFlowController.ErrorCode.STATUS_UNCERTAIN
                || returnSerialFailure;
        if (!active
                || !returnJourneyActive
                || returnBackgrounded
                || !returnUnlockConsumed
                || request == null
                || session == null
                || request.operationId() != returnOperationId
                || (session.isReadyToCommit() && !statusRecoveryRequired)) {
            if (session != null
                    && session.isReadyToCommit()
                    && !statusRecoveryRequired) {
                completeReturnAfterVerifiedClose();
            } else {
                renderReturnSnapshot();
            }
            return;
        }
        invalidateCustomerWork(true);
        if (customerOperationToken <= 0L
                || customerOperationTokenExhausted
                || !customerSerialTransmitter.beginAuthorizedReturn(
                        customerOperationToken, request)) {
            returnSerialFailure = true;
            returnStatusMessage = "柜门状态监测无法恢复，请联系管理员";
            renderReturnSnapshot();
            return;
        }
        customerSerialPhase = CustomerSerialPhase.RETURN_DOOR_STATUS;
        returnSerialFailure = false;
        customerConfirmedTarget = request.target();
        customerWriteAttribution = SerialWriteAttribution.doorStatus(request.target());
        reserveCustomerOperation(CustomerOperationKind.RETURN);
        returnSerialProtocolId = request.operationId();
        customerProtocolId = returnSerialProtocolId;
        returnActivePollId = 0L;
        returnImmediatePollPending = false;
        requestCustomerBinding(CustomerOperationKind.RETURN, returnSerialProtocolId);
    }

    private void sendReturnDoorStatusQuery() {
        if (!customerActionsEnabled()) {
            return;
        }
        ReturnDoorSession session = returnDoorSession;
        AuthorizedUnlockRequest request = returnAuthorizedRequest;
        if (session == null
                || request == null
                || !isCurrentOperation(
                        CustomerOperationKind.RETURN,
                        returnSerialProtocolId,
                        customerOperationUiGeneration,
                        customerOperationToken)
                || customerSerialPhase != CustomerSerialPhase.RETURN_DOOR_STATUS
                || session.hasPollInFlight()) {
            return;
        }
        long pollId = session.beginPoll(returnGeneration, returnOperationId);
        if (pollId <= 0L) {
            return;
        }
        returnActivePollId = pollId;
        byte[] query = DoorStateProtocol.query(request.target());
        CustomerSerialTransmitter.SendResult result = customerActionBoundary.call(
                CustomerActionBoundary.Effect.SERIAL_SEND,
                () -> customerSerialTransmitter.send(
                        CustomerSerialPhase.RETURN_DOOR_STATUS,
                        customerOperationToken,
                        SerialWriteAttribution.doorStatus(request.target()),
                        query),
                null);
        if (result == null) {
            return;
        }
        if (!result.accepted()) {
            returnActivePollId = 0L;
            handleReturnDoorEvent(session.onPollTimeout(
                    returnGeneration, returnOperationId));
            scheduleReturnDoorPoll(RETURN_POLL_BACKOFF_MILLIS);
        }
    }

    private void startReturnAuthorizedUnlock() {
        if (!customerActionsEnabled()) {
            return;
        }
        if (!isNetworkOnline()) {
            failReturnAuthorizationBeforeUnlock("网络异常，断网禁止开柜");
            return;
        }
        AuthorizedUnlockRequest request = returnFlowController.beginUnlockDispatch(
                returnGeneration, returnOperationId);
        if (request == null) {
            returnStatusMessage = returnServiceMessage(
                    returnFlowController.snapshot().error());
            renderReturnSnapshot();
            return;
        }
        if (customerOperationKind != CustomerOperationKind.RETURN
                || customerSerialPhase != CustomerSerialPhase.RETURN_DOOR_STATUS
                || !customerSerialTransmitter.transitionAuthorizedReturn(
                        customerOperationToken, CustomerSerialPhase.RETURN_UNLOCK)) {
            failReturnUnlockBeforeWrite("开柜授权已失效或串口状态异常");
            return;
        }
        customerSerialPhase = CustomerSerialPhase.RETURN_UNLOCK;
        customerWriteAttribution = SerialWriteAttribution.returnUnlock(request.target());
        returnStatusMessage = "正在发送本次服务器授权的开柜指令…";
        renderReturnSnapshot();
        bindReturnUnlockDispatch(request);
        long attemptId = returnUnlockCoordinator.startAuthorized(request);
        returnUnlockAttemptId = attemptId;
        if (attemptId <= 0L) {
            failReturnUnlockBeforeWrite("无法启动本次开柜，请联系管理员");
        }
    }

    private void failReturnUnlockBeforeWrite(String message) {
        clearReturnUnlockDispatchBinding();
        if (returnUnlockConsumed) {
            returnStatusMessage = "开柜写入已消费，禁止重试或离开";
            renderReturnSnapshot();
            return;
        }
        boolean recorded = returnFlowController.markUnlockFailed(
                returnGeneration, returnOperationId);
        returnSerialFailure = true;
        returnStatusMessage = recorded
                ? message : "开柜前置状态无法恢复，请联系管理员";
        cancelReturnTimers();
        renderReturnSnapshot();
    }

    private void bindReturnUnlockDispatch(AuthorizedUnlockRequest request) {
        returnUnlockDispatchGeneration = returnGeneration;
        returnUnlockDispatchOperationId = returnOperationId;
        returnUnlockDispatchCustomerToken = customerOperationToken;
        returnUnlockDispatchRequest = request;
        returnUnlockGateFailureMessage = null;
    }

    private void clearReturnUnlockDispatchBinding() {
        returnUnlockDispatchGeneration = 0L;
        returnUnlockDispatchOperationId = 0L;
        returnUnlockDispatchCustomerToken = 0L;
        returnUnlockDispatchRequest = null;
        returnUnlockGateFailureMessage = null;
    }

    private void handleReturnUnlockState(
            long attemptId, UnlockCoordinator.State state, String detail) {
        if (!customerActionsEnabled()
                || !active
                || !returnJourneyActive
                || returnBackgrounded
                || attemptId <= 0L
                || (returnUnlockAttemptId > 0L && attemptId != returnUnlockAttemptId)) {
            return;
        }
        if (state == UnlockCoordinator.State.SUCCESS) {
            ReturnDoorSession.Event ack = returnDoorSession == null
                    ? ReturnDoorSession.Event.REJECTED
                    : returnDoorSession.markUnlockAck(
                            returnGeneration, returnOperationId, true);
            if (ack == ReturnDoorSession.Event.REJECTED
                    || !returnFlowController.markUnlockAccepted(
                            returnGeneration, returnOperationId)) {
                returnStatusMessage = "开柜回执无法关联本次还柜，请联系管理员";
                cancelReturnTimers();
                invalidateCustomerWork(false);
                renderReturnSnapshot();
                return;
            }
            clearReturnUnlockDispatchBinding();
            returnStatusMessage = "柜门已打开，请取出物品并关好柜门";
            renderReturnSnapshot();
            scheduleReturnDoorPoll(0L);
            return;
        }
        if (state == UnlockCoordinator.State.FAILURE) {
            String gateFailure = returnUnlockGateFailureMessage;
            returnSerialFailure = true;
            if (returnDoorSession != null) {
                returnDoorSession.markUnlockAck(
                        returnGeneration, returnOperationId, false);
            }
            returnFlowController.markUnlockFailed(returnGeneration, returnOperationId);
            returnStatusMessage = safeDetail(
                    gateFailure,
                    safeDetail(detail, "柜门未能打开，请联系管理员"));
            cancelReturnTimers();
            invalidateCustomerWork(false);
            renderReturnSnapshot();
            return;
        }
        if (state == UnlockCoordinator.State.WAITING_ACK) {
            returnStatusMessage = "开柜指令已发送，正在等待设备回执…";
        } else if (state == UnlockCoordinator.State.QUIETING) {
            returnStatusMessage = "正在准备锁控设备…";
        } else {
            returnStatusMessage = "正在建立安全开柜会话…";
        }
        renderReturnSnapshot();
    }

    private void acknowledgeReturnDoorClosed() {
        if (!customerActionsEnabled()) {
            return;
        }
        ReturnFlowController.Snapshot snapshot = returnFlowController.snapshot();
        if (snapshot.state() != ReturnFlowModel.State.WAITING_FOR_CLOSE
                || returnDoorSession == null) {
            return;
        }
        if (!returnDoorSession.isStableCloseObserved()) {
            returnStatusMessage = "柜门尚未稳定关闭，请关好柜门后再确认";
            renderReturnSnapshot();
            return;
        }
        if (snapshot.error() == ReturnFlowController.ErrorCode.STATUS_UNCERTAIN) {
            returnFlowController.resumeStatusMonitoring(
                    returnGeneration, returnOperationId);
        }
        if (!returnFlowController.acknowledgeDoorClosed(
                    returnGeneration, returnOperationId)) {
            returnStatusMessage = "无法确认本次柜门状态，请重试";
            renderReturnSnapshot();
            return;
        }
        ReturnDoorSession.Event event = returnDoorSession.acknowledgeDoorClosed(
                returnGeneration, returnOperationId);
        if (event != ReturnDoorSession.Event.REQUEST_IMMEDIATE_POLL) {
            returnStatusMessage = "柜门确认已失效，请联系管理员";
            renderReturnSnapshot();
            return;
        }
        returnStatusMessage = "正在重新读取柜门硬件状态…";
        renderReturnSnapshot();
        requestImmediateReturnPoll();
    }

    private void requestImmediateReturnPoll() {
        if (returnPollTask != null) {
            handler.removeCallbacks(returnPollTask);
            returnPollTask = null;
        }
        if (returnDoorSession != null && returnDoorSession.hasPollInFlight()) {
            returnImmediatePollPending = true;
            return;
        }
        returnImmediatePollPending = false;
        scheduleReturnDoorPoll(0L);
    }

    private void scheduleReturnDoorPoll(long delayMillis) {
        if (!customerActionsEnabled()
                || !active
                || !returnJourneyActive
                || returnBackgrounded
                || returnDoorSession == null
                || returnDoorSession.isReadyToCommit()
                || customerOperationKind != CustomerOperationKind.RETURN
                || customerSerialPhase != CustomerSerialPhase.RETURN_DOOR_STATUS) {
            return;
        }
        if (returnPollTask != null) {
            handler.removeCallbacks(returnPollTask);
        }
        final long generation = returnGeneration;
        final long operationId = returnOperationId;
        final long localToken = customerOperationToken;
        returnPollTask = () -> customerActionBoundary.run(
                CustomerActionBoundary.Effect.ASYNC_CALLBACK,
                () -> {
                    returnPollTask = null;
                    if (generation != returnGeneration
                            || operationId != returnOperationId
                            || localToken != customerOperationToken
                            || returnBackgrounded) {
                        return;
                    }
                    sendReturnDoorStatusQuery();
                });
        handler.postDelayed(returnPollTask, Math.max(0L, delayMillis));
    }

    private void armReturnPollTimeout(long pollId) {
        if (returnPollTimeoutTask != null) {
            handler.removeCallbacks(returnPollTimeoutTask);
        }
        final long generation = returnGeneration;
        final long operationId = returnOperationId;
        final long localToken = customerOperationToken;
        returnPollTimeoutTask = () -> customerActionBoundary.run(
                CustomerActionBoundary.Effect.ASYNC_CALLBACK,
                () -> {
                    returnPollTimeoutTask = null;
                    if (generation != returnGeneration
                            || operationId != returnOperationId
                            || localToken != customerOperationToken
                            || pollId != returnActivePollId
                            || returnDoorSession == null) {
                        return;
                    }
                    returnActivePollId = 0L;
                    handleReturnDoorEvent(returnDoorSession.onPollTimeout(
                            generation, operationId));
                    scheduleReturnDoorPoll(RETURN_POLL_BACKOFF_MILLIS);
                });
        handler.postDelayed(returnPollTimeoutTask, RETURN_POLL_TIMEOUT_MILLIS);
    }

    private void handleReturnDoorBytes(byte[] bytes) {
        if (!customerActionsEnabled()) {
            return;
        }
        ReturnDoorSession session = returnDoorSession;
        if (session == null || bytes == null || bytes.length == 0) {
            return;
        }
        boolean hadPoll = session.hasPollInFlight();
        ReturnDoorSession.Event event = session.onBytes(
                returnGeneration, returnOperationId,
                Arrays.copyOf(bytes, bytes.length));
        if (hadPoll && !session.hasPollInFlight()) {
            returnActivePollId = 0L;
            if (returnPollTimeoutTask != null) {
                handler.removeCallbacks(returnPollTimeoutTask);
                returnPollTimeoutTask = null;
            }
        }
        handleReturnDoorEvent(event);
        if (returnImmediatePollPending && !session.hasPollInFlight()) {
            returnImmediatePollPending = false;
            requestImmediateReturnPoll();
        }
    }

    private void handleReturnDoorEvent(ReturnDoorSession.Event event) {
        if (!customerActionsEnabled()
                || event == null
                || event == ReturnDoorSession.Event.NONE) {
            return;
        }
        if (event == ReturnDoorSession.Event.BASELINE_CLOSED) {
            startReturnAuthorizedUnlock();
            return;
        }
        if (event == ReturnDoorSession.Event.REQUEST_IMMEDIATE_POLL) {
            requestImmediateReturnPoll();
            return;
        }
        if (event == ReturnDoorSession.Event.STATUS_UNCERTAIN) {
            ReturnFlowController.Snapshot snapshot = returnFlowController.snapshot();
            if (returnDoorSession != null
                    && returnDoorSession.isReadyToCommit()
                    && snapshot.state() == ReturnFlowModel.State.WAITING_FOR_CLOSE
                    && snapshot.error() == ReturnFlowController.ErrorCode.NONE) {
                // A disconnect after the fresh, exact CLOSED proof cannot revoke that proof.
                // Finish only the server commit path; never rebuild or resend RETURN_UNLOCK.
                returnSerialFailure = false;
                completeReturnAfterVerifiedClose();
                return;
            }
            if (snapshot.state() == ReturnFlowModel.State.WAITING_FOR_CLOSE) {
                returnFlowController.markStatusUncertain(
                        returnGeneration, returnOperationId);
            }
            returnStatusMessage = "柜门状态暂时无法确认，正在重试…";
            renderReturnSnapshot();
            scheduleReturnDoorPoll(RETURN_POLL_BACKOFF_MILLIS);
            return;
        }
        ReturnFlowController.Snapshot snapshot = returnFlowController.snapshot();
        if (snapshot.error() == ReturnFlowController.ErrorCode.STATUS_UNCERTAIN) {
            returnFlowController.resumeStatusMonitoring(
                    returnGeneration, returnOperationId);
        }
        if (event == ReturnDoorSession.Event.READY_TO_COMMIT) {
            completeReturnAfterVerifiedClose();
            return;
        }
        if (event == ReturnDoorSession.Event.WAITING_FOR_OPEN) {
            returnStatusMessage = "等待检测到柜门打开…";
        } else if (event == ReturnDoorSession.Event.WAITING_FOR_STABLE_CLOSE) {
            returnStatusMessage = "请关好柜门，系统正在连续确认关闭状态…";
        } else if (event == ReturnDoorSession.Event.STABLE_CLOSE) {
            returnStatusMessage = "柜门已稳定关闭，请点击“柜门已关闭”进行最终复查";
        }
        renderReturnSnapshot();
        scheduleReturnDoorPoll(RETURN_POLL_INTERVAL_MILLIS);
    }

    private void completeReturnAfterVerifiedClose() {
        if (!customerActionsEnabled()) {
            return;
        }
        ReturnDoorSession session = returnDoorSession;
        if (session == null || !session.isReadyToCommit() || returnServiceBusy) {
            return;
        }
        if (!isNetworkOnline()) {
            returnCommitDeferred = true;
            returnStatusMessage = "网络异常，关门结果已保留，请联网后重试";
            invalidateCustomerWork(false);
            renderReturnSnapshot();
            return;
        }
        cancelReturnTimers();
        invalidateCustomerWork(false);
        final long generation = returnGeneration;
        final long operationId = returnOperationId;
        final long epoch = nextGeneration(returnAsyncEpoch);
        returnAsyncEpoch = epoch;
        returnServiceBusy = true;
        returnSerialFailure = false;
        returnCommitDeferred = false;
        returnStatusMessage = "柜门已确认关闭，正在提交还柜结果…";
        renderReturnSnapshot();
        try {
            RETURN_SERVICE_EXECUTOR.execute(() -> customerActionBoundary.run(
                    CustomerActionBoundary.Effect.RETURN_SERVICE,
                    () -> {
                boolean accepted = returnFlowController.markStableDoorClosed(
                        generation, operationId);
                handler.post(() -> {
                    if (!isCurrentReturnAsync(generation, epoch)) {
                        return;
                    }
                    returnServiceBusy = false;
                    ReturnFlowController.Snapshot snapshot =
                            returnFlowController.snapshot();
                    boolean committed = accepted
                            && snapshot.error() == ReturnFlowController.ErrorCode.NONE
                            && (snapshot.state() == ReturnFlowModel.State.SUCCESS
                            || snapshot.state() == ReturnFlowModel.State.SELECTING);
                    if (committed) {
                        clearCompletedReturnPhysicalOperation();
                        returnStatusMessage = snapshot.state() == ReturnFlowModel.State.SUCCESS
                                ? "还柜完成" : "本柜门还柜完成，请选择下一个柜门";
                    } else {
                        returnStatusMessage = "提交失败，关门结果已保留，请重试";
                    }
                    renderReturnSnapshot();
                });
                    }));
        } catch (RejectedExecutionException ignored) {
            returnServiceBusy = false;
            returnCommitDeferred = true;
            returnStatusMessage = "提交服务暂时不可用，请重试";
            renderReturnSnapshot();
        }
    }

    private void clearCompletedReturnPhysicalOperation() {
        cancelReturnTimers();
        returnUnlockConsumed = false;
        returnSerialFailure = false;
        returnCommitDeferred = false;
        returnImmediatePollPending = false;
        returnOperationId = 0L;
        returnSerialProtocolId = 0L;
        returnUnlockAttemptId = 0L;
        returnActivePollId = 0L;
        returnChosenLocker = null;
        returnAuthorizedRequest = null;
        clearReturnUnlockDispatchBinding();
        returnDoorSession = null;
    }

    private void showInvalidReturnCredential() {
        returnAuthenticationFailed = true;
        returnStatusMessage = "凭证读取失败，请重新识别";
        renderReturnSnapshot();
    }

    private void updateReturnTerminalCountdown(ReturnFlowController.Snapshot snapshot,
            ReturnScreenPresentation presentation) {
        if (snapshot == null || presentation == null || !presentation.autoHome()) {
            cancelReturnTerminalCountdown();
            return;
        }
        if (returnTerminalTask != null) {
            return;
        }
        returnTerminalSecondsRemaining = RETURN_TERMINAL_COUNTDOWN_SECONDS;
        final long generation = returnGeneration;
        returnTerminalTask = new Runnable() {
            @Override
            public void run() {
                if (!customerActionBoundary.run(
                        CustomerActionBoundary.Effect.ASYNC_CALLBACK,
                        this::runReady)) {
                    return;
                }
            }

            private void runReady() {
                if (returnTerminalTask != this
                        || !active
                        || !returnJourneyActive
                        || returnBackgrounded
                        || generation != returnGeneration) {
                    cancelReturnTerminalCountdown();
                    return;
                }
                ReturnFlowController.Snapshot current =
                        returnFlowController.snapshot();
                if (current.state() == ReturnFlowModel.State.HOME
                        || !currentReturnPresentation(current).autoHome()) {
                    cancelReturnTerminalCountdown();
                    return;
                }
                returnTerminalSecondsRemaining--;
                if (returnTerminalSecondsRemaining <= 0) {
                    returnTerminalTask = null;
                    finishPresentedReturnJourneyToHome("还柜终态倒计时结束");
                    return;
                }
                renderReturnSnapshot();
                handler.postDelayed(this, RETURN_TERMINAL_TICK_MILLIS);
            }
        };
        handler.postDelayed(returnTerminalTask, RETURN_TERMINAL_TICK_MILLIS);
    }

    private String returnTerminalCountdownText(ReturnFlowController.Snapshot snapshot) {
        if (snapshot == null || returnTerminalTask == null) {
            return null;
        }
        return returnTerminalSecondsRemaining + " 秒后自动返回首页";
    }

    private void cancelReturnTerminalCountdown() {
        if (returnTerminalTask != null) {
            handler.removeCallbacks(returnTerminalTask);
            returnTerminalTask = null;
        }
        returnTerminalSecondsRemaining = 0;
    }

    private void showInvalidCredentialWithAutoReset() {
        cancelInvalidCredentialRecovery();
        final ZipHomeView expectedHome = homeView;
        final long expectedUiGeneration = uiGeneration;
        if (!isPassiveCredentialCaptureActive() || expectedHome == null) {
            return;
        }

        credentialRecoveryCountdown.start();
        expectedHome.showInvalidCredential(
                credentialRecoveryCountdown.secondsRemaining());
        invalidCredentialRecovery = new Runnable() {
            @Override
            public void run() {
                if (!customerActionBoundary.run(
                        CustomerActionBoundary.Effect.ASYNC_CALLBACK,
                        this::runReady)) {
                    return;
                }
            }

            private void runReady() {
                if (invalidCredentialRecovery != this) {
                    return;
                }
                if (!active
                        || adminLaunchPending
                        || adminPinOverlay != null
                        || flow.screen() != KioskFlowModel.Screen.HOME
                        || homeView != expectedHome
                        || uiGeneration != expectedUiGeneration) {
                    cancelInvalidCredentialRecovery();
                    return;
                }
                if (!credentialRecoveryCountdown.tick()) {
                    invalidCredentialRecovery = null;
                    resetPassiveCredentialCapture();
                    expectedHome.showCredentialWaiting();
                    return;
                }
                expectedHome.showInvalidCredential(
                        credentialRecoveryCountdown.secondsRemaining());
                handler.postDelayed(this, INVALID_CREDENTIAL_TICK_MILLIS);
            }
        };
        handler.postDelayed(
                invalidCredentialRecovery, INVALID_CREDENTIAL_TICK_MILLIS);
    }

    private void resetPassiveCredentialCapture() {
        cancelInvalidCredentialRecovery();
        cancelIdCardIdleCompletion();
        idCardScanSession.reset();
    }

    private void cancelInvalidCredentialRecovery() {
        Runnable pending = invalidCredentialRecovery;
        invalidCredentialRecovery = null;
        credentialRecoveryCountdown.cancel();
        if (pending != null) {
            handler.removeCallbacks(pending);
        }
    }

    private void resetConsumedIdCardKey() {
        consumedIdCardKeyCode = NO_CONSUMED_ID_CARD_KEY;
        consumedIdCardKeyDeviceId = Integer.MIN_VALUE;
    }

    private void cancelIdCardIdleCompletion() {
        Runnable pending = idCardIdleCompletion;
        idCardIdleCompletion = null;
        if (pending != null) {
            handler.removeCallbacks(pending);
        }
    }

    private static int scannedCredentialCharacter(KeyEvent event) {
        int unicode = event.getUnicodeChar();
        if (unicode != 0) {
            return unicode;
        }
        int keyCode = event.getKeyCode();
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            return '0' + keyCode - KeyEvent.KEYCODE_0;
        }
        if (keyCode >= KeyEvent.KEYCODE_NUMPAD_0
                && keyCode <= KeyEvent.KEYCODE_NUMPAD_9) {
            return '0' + keyCode - KeyEvent.KEYCODE_NUMPAD_0;
        }
        if (keyCode == KeyEvent.KEYCODE_MINUS) {
            return event.isShiftPressed() ? '_' : '-';
        }
        if (keyCode == KeyEvent.KEYCODE_GRAVE) {
            return event.isShiftPressed() ? '~' : '`';
        }
        return NO_SCANNED_CHARACTER;
    }

    private static boolean isIdCardTerminator(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                || keyCode == KeyEvent.KEYCODE_TAB;
    }

    private void submitCredential(UnlockMethod method, String rawValue) {
        if (!customerActionsEnabled()
                || !active
                || flow.screen() != KioskFlowModel.Screen.HOME
                || adminLaunchPending) {
            return;
        }
        boolean accepted = customerActionBoundary.call(
                CustomerActionBoundary.Effect.PHONE_CREDENTIAL,
                () -> flow.submitCredential(method, rawValue),
                false);
        appendCustomerLog("[Credential] " + (accepted ? "accepted" : "rejected")
                + " method=" + methodName(method)
                + " length=" + (rawValue == null ? 0 : rawValue.length()));
        if (!accepted) {
            if (homeView != null) {
                homeView.showValidationError(flow.credentials().validationError());
            }
            return;
        }

        invalidateCustomerWork(true);
        renderScreen();
        startDiscoveryOperation();
    }

    private void openUnavailable(UnlockMethod method) {
        if (!customerActionsEnabled()
                || !active
                || !customerActionBoundary.call(
                        CustomerActionBoundary.Effect.PALM,
                        () -> flow.openUnavailable(method),
                        false)) {
            return;
        }
        invalidateCustomerWork(true);
        renderScreen();
    }

    private void renderUnavailable() {
        homeView = null;
        lockerSelectionView = null;
        resultOverlay = null;
        adminPinOverlay = null;
        adminFunctionOverlay = null;
        faceRecognitionView = null;
        UnavailableMethodView unavailable =
                new UnavailableMethodView(this, flow.unavailableMethod());
        unavailable.setListener(new UnavailableMethodView.Listener() {
            @Override
            public void onReturnHome() {
                if (active && flow.screen() == KioskFlowModel.Screen.UNAVAILABLE) {
                    returnHome("从未接入方式返回首页");
                }
            }

            @Override
            public void onShowUnavailableAgain() {
                // This is a page-only demo action and deliberately has no serial side effect.
            }
        });
        replaceRoot(unavailable);
    }

    private void renderLockerSelection() {
        homeView = null;
        adminPinOverlay = null;
        adminFunctionOverlay = null;
        faceRecognitionView = null;
        LockerSelectionView selection =
                new LockerSelectionView(this, flow.lockerSelection());
        selection.setSecurityBanner(faceBannerPolicy.text(), faceBannerPolicy.visible());
        selection.setTargetListener(new LockerSelectionView.TargetListener() {
            @Override
            public void onConfirmLocker(LockerTarget target) {
                submitLocker(selection, target);
            }

            @Override
            public void onCancel() {
                if (!active
                        || selection != lockerSelectionView
                        || flow.screen() != KioskFlowModel.Screen.LOCKER_SELECTION) {
                    return;
                }
                invalidateCustomerWork(true);
                if (flow.cancelLockerSelection()) {
                    pendingFaceSuccess = null;
                    renderScreen();
                }
            }
        });
        selection.refreshFromModel();

        lockerSelectionView = selection;
        resultOverlay = createResultOverlay();
        root.removeAllViews();
        root.addView(selection, match());
        root.addView(resultOverlay, match());
    }

    private ResultOverlay createResultOverlay() {
        ResultOverlay overlay = new ResultOverlay(this);
        overlay.setListener(new ResultOverlay.Listener() {
            @Override
            public void onRetry() {
                if (!customerActionsEnabled()) {
                    return;
                }
                customerActionBoundary.run(
                        CustomerActionBoundary.Effect.RETRY,
                        () -> {
                    if (!active) {
                        return;
                    }
                    KioskFlowModel.ResultContext context = flow.resultContext();
                    if (context != KioskFlowModel.ResultContext.DISCOVERY_FAILURE
                            && context != KioskFlowModel.ResultContext.UNLOCK_FAILURE) {
                        return;
                    }
                    invalidateCustomerWork(true);
                    if (!flow.retry()) {
                        if (flow.screen() == KioskFlowModel.Screen.HOME) {
                            renderScreen();
                        }
                        return;
                    }
                    renderScreen();
                    if (context == KioskFlowModel.ResultContext.DISCOVERY_FAILURE) {
                        startDiscoveryOperation();
                    }
                });
            }

            @Override
            public void onReturnHome() {
                if (active) {
                    returnHome("顾客从结果页返回首页");
                }
            }
        });
        return overlay;
    }

    private void showAdminPin() {
        if (!active || adminLaunchPending || adminPinOverlay != null
                || !flow.openAdminPin()) {
            return;
        }
        cancelFaceWorkAndInvalidate();
        invalidateCustomerWork(true);
        pendingFaceSuccess = null;
        renderScreen();
    }

    private void launchOnlineMaintenance(
            com.codex.lockertest.admin.OnlineMaintenanceGrant.Target target,
            java.util.function.BooleanSupplier authorized, Runnable revokeSession) {
        if (!active || onlineMaintenanceHandoff || onlineCustomerHost == null
                || !onlineCustomerHost.showingAdmin()) return;
        com.codex.lockertest.admin.OnlineMaintenanceGrant.Ticket ticket =
                com.codex.lockertest.admin.OnlineMaintenanceGrant.shared().issue(target, authorized, () -> {
                    revokeSession.run();
                    if (!active) stopBootstrapSession();
                });
        if (ticket == null) { revokeSession.run(); return; }
        invalidateCustomerWork(true);
        onlineMaintenanceTicket = ticket;
        onlineMaintenanceHandoff = true;
        try {
            Class<?> destination = target == com.codex.lockertest.admin.OnlineMaintenanceGrant.Target.SERIAL
                    ? AdminSerialActivity.class : FaceSdkAdminActivity.class;
            Intent intent = new Intent(this, destination);
            intent.putExtra(com.codex.lockertest.admin.OnlineMaintenanceActivity.EXTRA_TICKET, ticket.id());
            startActivity(intent);
        } catch (RuntimeException | LinkageError failure) {
            onlineMaintenanceHandoff = false;
            ticket.finish(true);
            onlineMaintenanceTicket = null;
            Toast.makeText(this, "维护页面暂时不可用，请重新登录后重试", Toast.LENGTH_SHORT).show();
        }
    }

    private void renderAdminPin() {
        if (homeView == null) {
            homeView = createHomeView();
            replaceRoot(homeView);
        }
        AdminPinOverlay overlay = new AdminPinOverlay(this, adminCredentialPolicy);
        overlay.setListener(new AdminPinOverlay.Listener() {
            @Override
            public void onAdminAuthenticated() {
                renderAdminFunctions();
            }

            @Override
            public void onDismissed() {
                if (flow.screen() != KioskFlowModel.Screen.ADMIN_PIN) {
                    return;
                }
                invalidateCustomerWork(true);
                if (flow.back()) {
                    renderScreen();
                }
            }
        });
        adminPinOverlay = overlay;
        root.addView(overlay, match());
        overlay.bringToFront();
    }

    private void renderAdminFunctions() {
        if (!active
                || flow.screen() != KioskFlowModel.Screen.ADMIN_PIN) {
            return;
        }
        removeAdminPinOverlay();
        homeView = null;
        lockerSelectionView = null;
        resultOverlay = null;
        faceRecognitionView = null;
        AdminFunctionOverlay chooser = new AdminFunctionOverlay(this);
        chooser.setListener(new AdminFunctionOverlay.Listener() {
            @Override
            public void onSerialRequested() {
                if (active && adminFunctionOverlay == chooser) {
                    if (!allowsAdminCapability(AdminCapability.VIEW_DIAGNOSTICS)) {
                        showAdminCapabilityDenied("当前维护诊断不可用");
                        return;
                    }
                    launchAdminActivity();
                }
            }

            @Override
            public void onFaceSdkRequested() {
                if (active && adminFunctionOverlay == chooser) {
                    if (!allowsAdminCapability(AdminCapability.VIEW_DIAGNOSTICS)) {
                        showAdminCapabilityDenied("当前人脸诊断不可用");
                        return;
                    }
                    launchFaceSdkAdminActivity();
                }
            }

            @Override
            public void onEnrollmentRequested() {
                if (active && adminFunctionOverlay == chooser) {
                    beginEnrollment(EnrollmentOrigin.ADMIN);
                }
            }

            @Override
            public void onHomeRequested() {
                if (active && adminFunctionOverlay == chooser) {
                    returnHome("管理员返回首页");
                }
            }
        });
        adminFunctionOverlay = chooser;
        replaceRoot(chooser);
    }

    private boolean allowsAdminCapability(AdminCapability capability) {
        return adminCapabilityPolicy != null
                && adminCapabilityPolicy.allows(capability, isNetworkOnline(), false);
    }

    private void showAdminCapabilityDenied(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void removeAdminPinOverlay() {
        AdminPinOverlay overlay = adminPinOverlay;
        adminPinOverlay = null;
        if (overlay != null) {
            root.removeView(overlay);
        }
    }

    private void launchAdminActivity() {
        if (adminLaunchPending || flow.screen() != KioskFlowModel.Screen.ADMIN_PIN) {
            return;
        }
        adminLaunchPending = true;
        invalidateCustomerWork(true);
        removeAdminPinOverlay();
        long handoffToken = adminHandoffGate.begin(() -> {
            if (!active || !adminLaunchPending) {
                return;
            }
            adminLaunchPending = false;
            startActivity(new Intent(this, AdminSerialActivity.class));
        });
        // Transfer only logical control. The process-owned gateway and descriptor stay alive.
        adminHandoffGate.complete(handoffToken);
    }

    private void launchFaceSdkAdminActivity() {
        if (adminLaunchPending
                || flow.screen() != KioskFlowModel.Screen.ADMIN_PIN) {
            return;
        }
        adminLaunchPending = true;
        startActivity(new Intent(this, FaceSdkAdminActivity.class));
    }

    private void startDiscoveryOperation() {
        if (!customerActionsEnabled()
                || !active
                || flow.screen() != KioskFlowModel.Screen.LOCKER_SELECTION
                || flow.lockerSelection().discoveryState()
                != LockerSelectionModel.DiscoveryState.DETECTING) {
            return;
        }
        SerialWriteAttribution discoveryAttribution =
                SerialWriteAttribution.discovery(1);
        if (customerOperationToken <= 0L
                || customerOperationTokenExhausted
                || !customerSerialTransmitter.beginOperation(
                        customerOperationToken,
                        CustomerSerialPhase.LOCKER_DISCOVERY,
                        null)) {
            appendCustomerLog("[Discovery] operation unavailable");
            if (flow.finishDiscoveryFailure() && resultOverlay != null) {
                resultOverlay.showDeviceConnectionFailure();
            }
            return;
        }
        customerSerialPhase = CustomerSerialPhase.LOCKER_DISCOVERY;
        customerWriteAttribution = discoveryAttribution;
        customerConfirmedTarget = null;
        reserveCustomerOperation(CustomerOperationKind.DISCOVERY);
        long token = customerOperationToken;
        long scanId = customerActionBoundary.call(
                CustomerActionBoundary.Effect.DISCOVERY,
                () -> discoveryCoordinator.start(),
                0L);
        if (customerOperationToken != token
                || customerOperationKind != CustomerOperationKind.DISCOVERY) {
            discoveryCoordinator.cancel();
            return;
        }
        customerProtocolId = scanId;
        if (scanId == 0L) {
            finishActiveCustomerOperation();
            if (flow.finishDiscoveryFailure() && resultOverlay != null) {
                resultOverlay.showDeviceConnectionFailure();
            }
        }
    }

    private void submitLocker(LockerSelectionView source, LockerTarget target) {
        if (!customerActionsEnabled() || !active) {
            return;
        }
        if (source != lockerSelectionView) {
            if (lockerSelectionView != null
                    && flow.screen() == KioskFlowModel.Screen.LOCKER_SELECTION) {
                lockerSelectionView.setSending(false);
            }
            return;
        }
        long confirmationEpochMillis = System.currentTimeMillis();
        KioskFlowModel.ConfirmResult preflight =
                flow.checkLockerConfirmationAt(target, confirmationEpochMillis);
        if (preflight
                == KioskFlowModel.ConfirmResult.FACE_CREDENTIAL_EXPIRED) {
            flow.confirmLockerAt(target, confirmationEpochMillis);
            pendingFaceSuccess = null;
            setFaceDeferredFailure("验证已过期，请重新识别", true);
            invalidateCustomerWork(true);
            renderScreen();
            return;
        }
        if (preflight != KioskFlowModel.ConfirmResult.ACCEPTED) {
            source.showSelectionError("请选择有效柜门");
            return;
        }

        LockerTarget selectedTarget = target;
        FaceRecognitionController.Success faceSuccess = pendingFaceSuccess;
        AuthorizedUnlockRequest request;
        try {
            CustomerUnlockAuthorization authorization = customerActionBoundary.call(
                    CustomerActionBoundary.Effect.AUTHORIZATION,
                    () -> {
                        return customerUnlockAuthorizer.authorize(
                            flow.pendingCredentialMethod(),
                            flow.pendingCredential(),
                            faceSuccess == null ? null : faceSuccess.result(),
                            confirmationEpochMillis,
                            faceSuccess == null ? null : faceSuccess.expectedRequestId(),
                            faceSubsystem == null
                                    ? null : faceSubsystem.deviceBinding(),
                            faceSubsystem == null
                                    ? null : faceSubsystem.processBinding(),
                            faceSubsystem == null
                                    ? null : faceSubsystem.verificationEnvironment(),
                            selectedTarget);
                    },
                    null);
            if (authorization == null) {
                pendingFaceSuccess = null;
                source.showSelectionError("开柜授权失败，请重试");
                return;
            }
            if (!authorization.authorized()) {
                source.showSelectionError(authorization.message());
                return;
            }
            request = authorization.request();
            if (request == null || !samePhysicalTarget(selectedTarget, request.target())) {
                pendingFaceSuccess = null;
                source.showSelectionError("开柜授权失败，请重试");
                return;
            }
        } catch (RuntimeException | LinkageError ignored) {
            pendingFaceSuccess = null;
            source.showSelectionError("开柜授权失败，请重试");
            return;
        }
        pendingFaceSuccess = null;
        KioskFlowModel.ConfirmResult commit =
                flow.confirmLockerAt(target, confirmationEpochMillis);
        if (commit != KioskFlowModel.ConfirmResult.ACCEPTED) {
            source.showSelectionError("开柜授权失败，请重试");
            return;
        }

        invalidateCustomerWork(true);
        renderScreen();
        appendCustomerLog("[Selection] target=" + flow.pendingLockerLabel()
                + " boardAddress=" + technicalAddress(selectedTarget.boardAddress())
                + " localLock=" + selectedTarget.localLock());

        customerConfirmedTarget = target;
        customerWriteAttribution = SerialWriteAttribution.unlock(target);
        if (customerOperationToken <= 0L
                || customerOperationTokenExhausted
                || !customerSerialTransmitter.beginOperation(
                        customerOperationToken,
                        CustomerSerialPhase.LOCKER_CONFIRMED,
                        customerConfirmedTarget)) {
            customerConfirmedTarget = null;
            customerWriteAttribution = null;
            if (flow.finishUnlockFailure() && resultOverlay != null) {
                resultOverlay.showDeviceConnectionFailure();
            }
            return;
        }
        customerSerialPhase = CustomerSerialPhase.LOCKER_CONFIRMED;
        reserveCustomerOperation(CustomerOperationKind.UNLOCK);
        long token = customerOperationToken;
        long attemptId = unlockCoordinator.startAuthorized(request);
        if (customerOperationToken != token
                || customerOperationKind != CustomerOperationKind.UNLOCK) {
            unlockCoordinator.cancel();
            return;
        }
        customerProtocolId = attemptId;
        if (attemptId == 0L) {
            finishActiveCustomerOperation();
            if (flow.finishUnlockFailure() && resultOverlay != null) {
                resultOverlay.showDeviceConnectionFailure();
            }
        }
    }

    private static boolean samePhysicalTarget(
            LockerTarget expected, LockerTarget authorized) {
        return expected != null
                && authorized != null
                && expected.boardAddress() == authorized.boardAddress()
                && expected.localLock() == authorized.localLock()
                && expected.feedbackPolarity() == authorized.feedbackPolarity();
    }

    private void enqueueDiscoveryCompleted(long scanId, List<LockerZone> onlineZones) {
        final List<LockerZone> safeSnapshot = new ArrayList<>(onlineZones);
        final long callbackUiGeneration = customerOperationUiGeneration;
        final long callbackOperationToken = customerOperationToken;
        handler.post(() -> {
            if (!isCurrentOperation(
                    CustomerOperationKind.DISCOVERY,
                    scanId,
                    callbackUiGeneration,
                    callbackOperationToken)) {
                return;
            }
            appendCustomerLog("[Discovery] topology=" + technicalTopology(safeSnapshot));
            finishActiveCustomerOperation();
            if (!flow.applyDiscoverySnapshot(new ArrayList<>(safeSnapshot))) {
                return;
            }
            if (lockerSelectionView != null) {
                lockerSelectionView.refreshFromModel();
            }
        });
    }

    private void enqueueDiscoveryFailed(
            long scanId, BoardDiscoveryCoordinator.Failure failure) {
        final BoardDiscoveryCoordinator.Failure safeFailure = failure;
        final long callbackUiGeneration = customerOperationUiGeneration;
        final long callbackOperationToken = customerOperationToken;
        handler.post(() -> {
            if (!isCurrentOperation(
                    CustomerOperationKind.DISCOVERY,
                    scanId,
                    callbackUiGeneration,
                    callbackOperationToken)) {
                return;
            }
            appendCustomerLog("[Discovery] failure=" + safeFailure);
            finishActiveCustomerOperation();
            if (!flow.finishDiscoveryFailure() || resultOverlay == null) {
                return;
            }
            if (safeFailure == BoardDiscoveryCoordinator.Failure.SEND) {
                resultOverlay.showSendFailure();
            } else {
                resultOverlay.showDeviceConnectionFailure();
            }
        });
    }

    private void enqueueUnlockState(
            long attemptId,
            UnlockCoordinator.State state,
            String detail) {
        final UnlockCoordinator.State safeState = state;
        final String safeDetail = detail == null ? "" : new String(detail);
        final long callbackUiGeneration = customerOperationUiGeneration;
        final long callbackOperationToken = customerOperationToken;
        final CustomerOperationKind callbackKind = customerOperationKind;
        handler.post(() -> {
            if (!isCurrentOperation(
                    callbackKind,
                    attemptId,
                    callbackUiGeneration,
                    callbackOperationToken)) {
                return;
            }
            if (callbackKind == CustomerOperationKind.ONLINE_UNLOCK) {
                OnlineUnlockExecutor.Listener listener = onlineUnlockListener;
                if (safeState == UnlockCoordinator.State.SUCCESS
                        || safeState == UnlockCoordinator.State.FAILURE) {
                    finishActiveCustomerOperation();
                }
                if (listener != null) listener.onState(safeState, safeDetail);
                return;
            }
            renderUnlockState(attemptId, safeState, safeDetail);
        });
    }

    private void renderUnlockState(
            long attemptId,
            UnlockCoordinator.State state,
            String detail) {
        if (resultOverlay == null || flow.screen() != KioskFlowModel.Screen.RESULT) {
            return;
        }
        LockerTarget selectedTarget = flow.pendingTarget();
        String selectedLockerLabel = flow.pendingLockerLabel();
        if (selectedTarget == null || selectedLockerLabel == null) {
            return;
        }
        switch (state) {
            case VERIFYING:
                resultOverlay.showProgress(ResultOverlay.State.VERIFYING);
                break;
            case CONNECTING:
            case QUIETING:
                resultOverlay.showProgress(ResultOverlay.State.CONNECTING);
                break;
            case WAITING_ACK:
                resultOverlay.showProgress(ResultOverlay.State.WAITING);
                break;
            case SUCCESS:
                if (!flow.finishSuccessfulRequest()) {
                    return;
                }
                pendingFaceSuccess = null;
                resultOverlay.showLockerSuccess(selectedLockerLabel);
                appendCustomerLog("[Result] target=" + selectedLockerLabel
                        + " boardAddress=" + technicalAddress(selectedTarget.boardAddress())
                        + " success");
                finishActiveCustomerOperation();
                break;
            case FAILURE:
                if (!flow.finishUnlockFailure()) {
                    return;
                }
                if (selectedLockerFailure(selectedTarget).equals(detail)) {
                    resultOverlay.showLockerFailure(selectedLockerLabel);
                } else if (SEND_FAILURE.equals(detail)) {
                    resultOverlay.showSendFailure();
                } else if (TIMEOUT_FAILURE.equals(detail)) {
                    resultOverlay.showTimeout();
                } else {
                    resultOverlay.showDeviceConnectionFailure();
                }
                appendCustomerLog("[Result] target=" + selectedLockerLabel
                        + " boardAddress=" + technicalAddress(selectedTarget.boardAddress())
                        + " failure=" + safeDetail(detail, OPEN_FAILURE));
                finishActiveCustomerOperation();
                break;
            default:
                throw new IllegalArgumentException("unsupported coordinator state");
        }
    }

    /** Explicit server-approved path; the legacy demo capability gate remains unchanged. */
    private boolean onlineHostCurrent() {
        return active && !isFinishing() && onlineCustomerHost != null
                && onlineCustomerHost.ready() && onlineCustomerHost.showingJourney()
                && !onlineCustomerHost.showingAdmin() && bootstrapRuntime != null
                && onlineCustomerHost.boundTo(bootstrapRuntime.snapshot());
    }

    private boolean onlineSerialAuthorityCurrent() {
        return customerOperationKind == CustomerOperationKind.ONLINE_UNLOCK
                && onlineUnlockPermit != null && onlineUnlockPermit.active()
                && onlineUnlockCancelled != null && !onlineUnlockCancelled.get()
                && onlineUnlockListener != null && onlineUnlockListener.isCurrent() && onlineHostCurrent();
    }

    private boolean serialOperationEnabled() {
        return customerOperationKind == CustomerOperationKind.ONLINE_UNLOCK
                ? onlineSerialAuthorityCurrent() : customerActionsEnabled();
    }

    private <T> T callSerialEffect(CustomerActionBoundary.Effect effect,
            CustomerActionBoundary.Call<T> action, T unavailable) {
        if (customerOperationKind != CustomerOperationKind.ONLINE_UNLOCK) {
            return customerActionBoundary.call(effect, action, unavailable);
        }
        if (effect != CustomerActionBoundary.Effect.SERIAL_CONNECT
                && effect != CustomerActionBoundary.Effect.SERIAL_SEND
                && effect != CustomerActionBoundary.Effect.ASYNC_CALLBACK) return unavailable;
        return onlineSerialAuthorityCurrent() ? action.call() : unavailable;
    }

    private final class OnlinePhysicalExecutor implements OnlineUnlockExecutor {
        @Override public boolean isAvailable() {
            return onlineHostCurrent() && isNetworkOnline();
        }

        @Override public OnlineCustomerCoordinator.Cancellable execute(
                AuthorizedUnlockRequest request, OnlineUnlockExecutor.Listener listener) {
            if (request == null || listener == null) throw new IllegalArgumentException("Online request required");
            AtomicBoolean cancelled = new AtomicBoolean();
            OnlineUnlockDispatchPermit permit = new OnlineUnlockDispatchPermit(request);
            OnlineCustomerCoordinator.Cancellable cancellation = () -> {
                cancelled.set(true);
                // Called outside the coordinator lock; queued output and cancellation share this monitor.
                permit.cancel();
                handler.post(() -> {
                    if (onlineUnlockCancelled == cancelled) invalidateCustomerWork(false);
                });
            };
            // Publish cancellation before the main thread can start a connection or queue a write.
            if (!listener.registerCancellation(cancellation)) {
                cancellation.cancel();
                return cancellation;
            }
            handler.post(() -> {
                if (cancelled.get()) return;
                if (!isAvailable() || !listener.isCurrent() || customerOperationKind != CustomerOperationKind.NONE
                        || SERIAL_GATEWAY_OWNER.currentRole() == ProcessSerialGatewayOwner.Role.ADMIN) {
                    listener.onState(UnlockCoordinator.State.FAILURE, "设备或网络暂不可用，请返回后重新验证");
                    return;
                }
                invalidateCustomerWork(false);
                customerConfirmedTarget = request.target();
                customerWriteAttribution = SerialWriteAttribution.unlock(request.target());
                if (customerOperationToken <= 0L || customerOperationTokenExhausted
                        || !customerSerialTransmitter.beginOperation(customerOperationToken,
                                CustomerSerialPhase.LOCKER_CONFIRMED, request.target())) {
                    invalidateCustomerWork(false);
                    listener.onState(UnlockCoordinator.State.FAILURE, OPEN_FAILURE);
                    return;
                }
                onlineUnlockCancelled = cancelled;
                onlineUnlockPermit = permit;
                onlineUnlockListener = listener;
                customerSerialPhase = CustomerSerialPhase.LOCKER_CONFIRMED;
                reserveCustomerOperation(CustomerOperationKind.ONLINE_UNLOCK);
                long attempt = unlockCoordinator.startAuthorized(request);
                customerProtocolId = attempt;
                if (attempt == 0L) {
                    finishActiveCustomerOperation();
                    listener.onState(UnlockCoordinator.State.FAILURE, OPEN_FAILURE);
                }
            });
            return cancellation;
        }
    }

    private void reserveCustomerOperation(CustomerOperationKind kind) {
        if (kind == null || kind == CustomerOperationKind.NONE) {
            throw new IllegalArgumentException("customer operation kind is required");
        }
        if (customerOperationToken <= 0L || customerOperationTokenExhausted) {
            throw new IllegalStateException("customer operation token unavailable");
        }
        customerOperationUiGeneration = uiGeneration;
        customerOperationKind = kind;
        customerProtocolId = 0L;
        customerProtocolSink = null;
        customerProtocolConnected = false;
    }

    private void requestCustomerBinding(CustomerOperationKind kind, long protocolId) {
        final long callbackUiGeneration = customerOperationUiGeneration;
        final long callbackOperationToken = customerOperationToken;
        handler.post(() -> {
            if (!isCurrentOperation(
                    kind,
                    protocolId,
                    callbackUiGeneration,
                    callbackOperationToken)) {
                return;
            }
            bindCustomerGateway(
                    kind,
                    protocolId,
                    callbackUiGeneration,
                    callbackOperationToken);
        });
    }

    private void bindCustomerGateway(
            CustomerOperationKind kind,
            long protocolId,
            long operationUiGeneration,
            long operationToken) {
        if (!serialOperationEnabled()
                || !isCurrentOperation(
                kind, protocolId, operationUiGeneration, operationToken)) {
            return;
        }
        if (customerGatewayLease != null || customerGatewaySubscription != null) {
            failCurrentProtocolConnection();
            return;
        }

        CustomerProtocolSink sink = createProtocolSink(kind, protocolId);
        ProcessSerialGatewayOwner.Lease<SerialGateway> lease;
        try {
            lease = callSerialEffect(
                    CustomerActionBoundary.Effect.SERIAL_CONNECT,
                    () -> SERIAL_GATEWAY_OWNER.acquire(
                            ProcessSerialGatewayOwner.Role.CUSTOMER,
                            SerialGateway::new),
                    null);
        } catch (RuntimeException failure) {
            sink.onConnectionFailed(OPEN_FAILURE);
            return;
        }
        if (lease == null) {
            return;
        }

        long gatewayGeneration = gatewayGate.activate();
        PersistentSerialConnectionPolicy.Attempt attempt =
                SERIAL_CONNECTION_POLICY.newCustomerAttempt();
        customerGatewayLease = lease;
        customerGatewayGeneration = gatewayGeneration;
        customerConnectionAttempt = attempt;
        customerProtocolSink = sink;
        customerProtocolConnected = false;

        GatewaySnapshot snapshot;
        try {
            snapshot = SERIAL_GATEWAY_OWNER.withGateway(
                    lease,
                    gateway -> {
                        customerGatewaySubscription = gateway.subscribe(
                                new CustomerGatewayListener(
                                        operationUiGeneration,
                                        operationToken,
                                        kind,
                                        protocolId,
                                        gatewayGeneration,
                                        lease,
                                        sink));
                        return new GatewaySnapshot(
                                gateway.phase(), gateway.getActiveConfig());
                    },
                    null);
        } catch (RuntimeException failure) {
            sink.onConnectionFailed(OPEN_FAILURE);
            return;
        }
        if (snapshot == null) {
            CustomerConnectionWakeupPolicy.Action missingSnapshot =
                    CustomerConnectionWakeupPolicy.decide(
                            isCurrentOperation(
                                    kind,
                                    protocolId,
                                    operationUiGeneration,
                                    operationToken),
                            false,
                            null,
                            null,
                            true,
                            "");
            if (missingSnapshot == CustomerConnectionWakeupPolicy.Action.FAIL) {
                sink.onConnectionFailed(OPEN_FAILURE);
            }
            return;
        }
        PersistentSerialConnectionPolicy.Action action =
                attempt.reconcile(snapshot.phase, snapshot.config);
        applyCustomerConnectionAction(
                operationUiGeneration,
                operationToken,
                kind,
                protocolId,
                gatewayGeneration,
                lease,
                sink,
                attempt,
                action);
    }

    private void handleCustomerGatewayWakeup(
            long operationUiGeneration,
            long operationToken,
            CustomerOperationKind kind,
            long protocolId,
            long gatewayGeneration,
            ProcessSerialGatewayOwner.Lease<SerialGateway> expectedLease,
            CustomerProtocolSink expectedSink,
            boolean callbackConnected,
            String callbackDetail) {
        if (!isCurrentGatewayCallback(
                operationUiGeneration,
                operationToken,
                kind,
                protocolId,
                gatewayGeneration,
                expectedLease,
                expectedSink)) {
            return;
        }
        GatewaySnapshot snapshot = currentGatewaySnapshot(expectedLease);
        if (snapshot == null) {
            CustomerConnectionWakeupPolicy.Action missingSnapshot =
                    CustomerConnectionWakeupPolicy.decide(
                            isCurrentOperation(
                                    kind,
                                    protocolId,
                                    operationUiGeneration,
                                    operationToken),
                            customerProtocolConnected,
                            null,
                            null,
                            callbackConnected,
                            callbackDetail);
            if (missingSnapshot == CustomerConnectionWakeupPolicy.Action.FAIL) {
                expectedSink.onConnectionFailed(OPEN_FAILURE);
            }
            return;
        }

        CustomerConnectionWakeupPolicy.Action wakeupAction =
                CustomerConnectionWakeupPolicy.decide(
                        true,
                        customerProtocolConnected,
                        snapshot.phase,
                        snapshot.config,
                        callbackConnected,
                        callbackDetail);
        if (wakeupAction == CustomerConnectionWakeupPolicy.Action.IGNORE) {
            return;
        }
        if (wakeupAction == CustomerConnectionWakeupPolicy.Action.FAIL) {
            expectedSink.onConnectionFailed(OPEN_FAILURE);
            return;
        }

        PersistentSerialConnectionPolicy.Attempt attempt = customerConnectionAttempt;
        if (attempt == null) {
            expectedSink.onConnectionFailed(OPEN_FAILURE);
            return;
        }
        PersistentSerialConnectionPolicy.Action action;
        if (wakeupAction == CustomerConnectionWakeupPolicy.Action.CONNECTION_EVENT) {
            action = attempt.onConnectionEvent(snapshot.phase, snapshot.config);
        } else if (wakeupAction == CustomerConnectionWakeupPolicy.Action.RECONCILE) {
            action = attempt.reconcile(snapshot.phase, snapshot.config);
        } else {
            // All pure policy values are handled above; fail closed on a future value.
            expectedSink.onConnectionFailed(OPEN_FAILURE);
            return;
        }
        applyCustomerConnectionAction(
                operationUiGeneration,
                operationToken,
                kind,
                protocolId,
                gatewayGeneration,
                expectedLease,
                expectedSink,
                attempt,
                action);
    }

    private void applyCustomerConnectionAction(
            long operationUiGeneration,
            long operationToken,
            CustomerOperationKind kind,
            long protocolId,
            long gatewayGeneration,
            ProcessSerialGatewayOwner.Lease<SerialGateway> expectedLease,
            CustomerProtocolSink expectedSink,
            PersistentSerialConnectionPolicy.Attempt attempt,
            PersistentSerialConnectionPolicy.Action action) {
        if (!isCurrentGatewayCallback(
                operationUiGeneration,
                operationToken,
                kind,
                protocolId,
                gatewayGeneration,
                expectedLease,
                expectedSink)) {
            return;
        }
        switch (action) {
            case CLOSE_FOR_DEFAULTS:
                if (kind == CustomerOperationKind.ONLINE_UNLOCK) {
                    // An online customer action must not replace an administrator's connection.
                    expectedSink.onConnectionFailed("串口配置与开柜配置不一致，请联系管理员");
                    return;
                }
                boolean closeAccepted = SERIAL_GATEWAY_OWNER.withGateway(
                        expectedLease, SerialGateway::closePort, false);
                PersistentSerialConnectionPolicy.Action afterCloseRequest =
                        attempt.onCloseRequestResult(closeAccepted);
                applyCustomerConnectionAction(
                        operationUiGeneration,
                        operationToken,
                        kind,
                        protocolId,
                        gatewayGeneration,
                        expectedLease,
                        expectedSink,
                        attempt,
                        afterCloseRequest);
                break;
            case OPEN_DEFAULTS:
                boolean openAccepted = SERIAL_GATEWAY_OWNER.withGateway(
                        expectedLease,
                        gateway -> gateway.open(SerialConfig.defaults()),
                        false);
                if (openAccepted) {
                    appendCustomerLog("[Status] 正在按默认配置打开进程串口");
                    return;
                }
                GatewaySnapshot freshSnapshot = currentGatewaySnapshot(expectedLease);
                if (freshSnapshot == null) {
                    CustomerConnectionWakeupPolicy.Action missingSnapshot =
                            CustomerConnectionWakeupPolicy.decide(
                                    isCurrentOperation(
                                            kind,
                                            protocolId,
                                            operationUiGeneration,
                                            operationToken),
                                    false,
                                    null,
                                    null,
                                    true,
                                    "");
                    if (missingSnapshot
                            == CustomerConnectionWakeupPolicy.Action.FAIL) {
                        expectedSink.onConnectionFailed(OPEN_FAILURE);
                    }
                    return;
                }
                PersistentSerialConnectionPolicy.Action afterRejectedOpen =
                        attempt.reconcile(freshSnapshot.phase, freshSnapshot.config);
                applyCustomerConnectionAction(
                        operationUiGeneration,
                        operationToken,
                        kind,
                        protocolId,
                        gatewayGeneration,
                        expectedLease,
                        expectedSink,
                        attempt,
                        afterRejectedOpen);
                break;
            case REUSE:
                if (!customerProtocolConnected) {
                    customerProtocolConnected = true;
                    appendCustomerLog("[Status] 已连接默认串口配置");
                    expectedSink.onConnected();
                }
                break;
            case WAIT:
            case NONE:
                break;
            case FAIL:
            default:
                expectedSink.onConnectionFailed(OPEN_FAILURE);
                break;
        }
    }

    private GatewaySnapshot currentGatewaySnapshot(
            ProcessSerialGatewayOwner.Lease<SerialGateway> expectedLease) {
        return SERIAL_GATEWAY_OWNER.withGateway(
                expectedLease,
                gateway -> new GatewaySnapshot(
                        gateway.phase(), gateway.getActiveConfig()),
                null);
    }

    private CustomerProtocolSink createProtocolSink(
            CustomerOperationKind kind, long protocolId) {
        if (kind == CustomerOperationKind.DISCOVERY) {
            return new DiscoveryProtocolSink(protocolId);
        }
        if (kind == CustomerOperationKind.UNLOCK || kind == CustomerOperationKind.ONLINE_UNLOCK) {
            return new UnlockProtocolSink(protocolId);
        }
        if (kind == CustomerOperationKind.RETURN) {
            return new ReturnProtocolSink(protocolId);
        }
        throw new IllegalArgumentException("unsupported customer operation");
    }

    private boolean sendForOperation(
            CustomerOperationKind kind, long protocolId, byte[] payload) {
        if (!serialOperationEnabled()
                || !isCurrentOperation(
                kind,
                protocolId,
                customerOperationUiGeneration,
                customerOperationToken)
                || !customerProtocolConnected
                || payload == null
                || payload.length == 0) {
            return false;
        }
        if (kind == CustomerOperationKind.ONLINE_UNLOCK && !isNetworkOnline()) return false;
        final byte[] safePayload = Arrays.copyOf(payload, payload.length);
        SerialWriteAttribution attribution = kind == CustomerOperationKind.DISCOVERY
                ? discoveryAttribution(safePayload)
                : customerWriteAttribution;
        CustomerSerialTransmitter.SendResult result =
                callSerialEffect(
                        CustomerActionBoundary.Effect.SERIAL_SEND,
                        () -> customerSerialTransmitter.send(
                                customerSerialPhase,
                                customerOperationToken,
                                attribution,
                                safePayload),
                        null);
        return result != null && result.accepted();
    }

    private boolean writeAuthorizedCustomerPayload(
            CustomerSerialPhase phase,
            long operationId,
            byte[] payload) {
        if (!serialOperationEnabled()
                || phase == null
                || operationId <= 0L
                || payload == null
                || payload.length == 0
                || customerOperationToken != operationId
                || customerSerialPhase != phase
                || !customerProtocolConnected) {
            return false;
        }
        final byte[] safePayload = Arrays.copyOf(payload, payload.length);
        final ProcessSerialGatewayOwner.Lease<SerialGateway> expectedLease =
                customerGatewayLease;
        final OnlineUnlockDispatchPermit permit = onlineUnlockPermit;
        final AtomicBoolean cancelled = onlineUnlockCancelled;
        final OnlineUnlockExecutor.Listener onlineAuthority = onlineUnlockListener;
        final boolean online = customerOperationKind == CustomerOperationKind.ONLINE_UNLOCK;
        return callSerialEffect(
                CustomerActionBoundary.Effect.SERIAL_SEND,
                () -> SERIAL_GATEWAY_OWNER.withGateway(
                        expectedLease,
                        gateway -> customerGatewayLease == expectedLease
                                && customerOperationToken == operationId
                                && customerSerialPhase == phase
                                && customerProtocolConnected
                                && gatewayGate.accepts(customerGatewayGeneration)
                                && gateway.phase() == SerialSessionState.Phase.OPEN
                                && SerialConfig.defaults().equals(gateway.getActiveConfig())
                                && (online
                                ? gateway.sendAuthorized(safePayload, permit,
                                        () -> permit != null && onlineUnlockPermit == permit
                                                && cancelled != null && !cancelled.get()
                                                && onlineAuthority != null && onlineAuthority.isCurrent()
                                                && SERIAL_GATEWAY_OWNER.isCurrent(expectedLease)
                                                && isNetworkOnline())
                                : gateway.send(Arrays.copyOf(safePayload, safePayload.length))),
                        false),
                false);
    }

    private static SerialWriteAttribution discoveryAttribution(byte[] payload) {
        if (payload == null) {
            return null;
        }
        for (int boardAddress = 1; boardAddress <= 3; boardAddress++) {
            if (Arrays.equals(
                    com.codex.lockertest.protocol.BoardStatusProtocol.query(
                            boardAddress),
                    payload)) {
                return SerialWriteAttribution.discovery(boardAddress);
            }
        }
        return null;
    }

    private void failCurrentProtocolConnection() {
        CustomerProtocolSink sink = customerProtocolSink;
        if (sink != null) {
            sink.onConnectionFailed(OPEN_FAILURE);
        }
    }

    private void invalidateCustomerWork(boolean advanceUiGeneration) {
        if (onlineUnlockCancelled != null) onlineUnlockCancelled.set(true);
        if (onlineUnlockPermit != null) onlineUnlockPermit.cancel();
        onlineUnlockPermit = null;
        onlineUnlockListener = null;
        onlineUnlockCancelled = null;
        long endingOperationToken = customerOperationToken;
        if (advanceUiGeneration) {
            uiGeneration = nextGeneration(uiGeneration);
        }
        gatewayGate.invalidate();
        customerSerialTransmitter.endOperation(endingOperationToken);

        if (discoveryCoordinator != null) {
            discoveryCoordinator.cancel();
        }
        if (unlockCoordinator != null) {
            unlockCoordinator.cancel();
        }
        if (returnUnlockCoordinator != null) {
            returnUnlockCoordinator.cancel();
        }
        clearReturnUnlockDispatchBinding();

        customerOperationKind = CustomerOperationKind.NONE;
        customerOperationUiGeneration = 0L;
        customerProtocolId = 0L;
        customerProtocolSink = null;
        customerProtocolConnected = false;
        customerSerialPhase = CustomerSerialPhase.TERMINAL;
        customerWriteAttribution = null;
        customerConfirmedTarget = null;
        detachCustomerGatewayAfterInvalidation();
        advanceCustomerOperationToken();
    }

    private void finishActiveCustomerOperation() {
        invalidateCustomerWork(false);
    }

    private void detachCustomerGatewayAfterInvalidation() {
        SerialGateway.Subscription subscription = customerGatewaySubscription;
        ProcessSerialGatewayOwner.Lease<SerialGateway> lease = customerGatewayLease;
        PersistentSerialConnectionPolicy.Attempt attempt = customerConnectionAttempt;

        customerGatewaySubscription = null;
        customerGatewayLease = null;
        customerConnectionAttempt = null;
        customerGatewayGeneration = 0L;

        if (attempt != null) {
            attempt.cancel();
        }
        if (subscription != null) {
            subscription.unsubscribe();
        }
        SERIAL_GATEWAY_OWNER.relinquish(lease);
    }

    private boolean isCurrentOperation(
            CustomerOperationKind expectedKind,
            long expectedProtocolId,
            long expectedUiGeneration,
            long expectedOperationToken) {
        return callSerialEffect(
                CustomerActionBoundary.Effect.ASYNC_CALLBACK,
                () -> serialOperationEnabled()
                        && active
                        && uiGeneration == expectedUiGeneration
                        && customerOperationUiGeneration == expectedUiGeneration
                        && customerOperationToken == expectedOperationToken
                        && customerOperationKind == expectedKind
                        && customerProtocolId == expectedProtocolId
                        && expectedFlowState(expectedKind),
                false);
    }

    private boolean isCurrentGatewayCallback(
            long expectedUiGeneration,
            long expectedOperationToken,
            CustomerOperationKind expectedKind,
            long expectedProtocolId,
            long expectedGatewayGeneration,
            ProcessSerialGatewayOwner.Lease<SerialGateway> expectedLease,
            CustomerProtocolSink expectedSink) {
        return isCurrentOperation(
                expectedKind,
                expectedProtocolId,
                expectedUiGeneration,
                expectedOperationToken)
                && gatewayGate.accepts(expectedGatewayGeneration)
                && customerGatewayGeneration == expectedGatewayGeneration
                && customerGatewayLease == expectedLease
                && customerProtocolSink == expectedSink
                && SERIAL_GATEWAY_OWNER.isCurrent(expectedLease);
    }

    private boolean expectedFlowState(CustomerOperationKind kind) {
        if (kind == CustomerOperationKind.ONLINE_UNLOCK) return onlineSerialAuthorityCurrent();
        if (kind == CustomerOperationKind.DISCOVERY) {
            return flow.screen() == KioskFlowModel.Screen.LOCKER_SELECTION
                    && flow.lockerSelection().discoveryState()
                    == LockerSelectionModel.DiscoveryState.DETECTING;
        }
        if (kind == CustomerOperationKind.UNLOCK) {
            return flow.screen() == KioskFlowModel.Screen.RESULT
                    && flow.resultContext() == KioskFlowModel.ResultContext.NONE
                    && flow.pendingTarget() != null;
        }
        if (kind == CustomerOperationKind.RETURN) {
            ReturnFlowModel.State state = returnFlowController.snapshot().state();
            return returnJourneyActive
                    && !returnBackgrounded
                    && returnOperationId > 0L
                    && (state == ReturnFlowModel.State.CONFIRMING
                    || state == ReturnFlowModel.State.OPENING
                    || state == ReturnFlowModel.State.WAITING_FOR_CLOSE);
        }
        return false;
    }

    private void replaceRoot(View view) {
        root.removeAllViews();
        root.addView(view, match());
    }

    private synchronized void appendCustomerLog(String message) {
        runtimeLog.append(timeFormat.format(new Date()) + "  " + message);
    }

    private void configureWindow() {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
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

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
    }

    private static String methodName(UnlockMethod method) {
        return method == null ? "UNKNOWN" : method.name();
    }

    private static String selectedLockerFailure(LockerTarget target) {
        return target.customerLabel() + "号柜门开启失败，请再次尝试。";
    }

    private static String safeDetail(String detail, String fallback) {
        return detail == null || detail.trim().isEmpty() ? fallback : detail;
    }

    private static String technicalAddress(int boardAddress) {
        return String.format(Locale.US, "%02d", boardAddress);
    }

    private static String technicalTopology(List<LockerZone> zones) {
        if (zones == null || zones.isEmpty()) {
            return "none";
        }
        StringBuilder builder = new StringBuilder();
        for (LockerZone zone : zones) {
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(zone.name())
                    .append("(boardAddress=")
                    .append(technicalAddress(zone.boardAddress()))
                    .append(')');
        }
        return builder.toString();
    }

    private static long nextGeneration(long generation) {
        generation++;
        return generation > 0L ? generation : 1L;
    }

    private boolean advanceCustomerOperationToken() {
        if (customerOperationToken == Long.MAX_VALUE) {
            customerOperationTokenExhausted = true;
            return false;
        }
        customerOperationToken++;
        return true;
    }

    private final class DiscoverySerialActions
            implements BoardDiscoveryCoordinator.SerialActions {
        @Override
        public void ensureDefaultConnected(long scanId) {
            requestCustomerBinding(CustomerOperationKind.DISCOVERY, scanId);
        }

        @Override
        public boolean send(long scanId, byte[] bytes) {
            return sendForOperation(CustomerOperationKind.DISCOVERY, scanId, bytes);
        }
    }

    private final class UnlockSerialActions implements UnlockCoordinator.SerialActions {
        @Override
        public void ensureDefaultConnected(long attemptId) {
            requestCustomerBinding(customerOperationKind == CustomerOperationKind.ONLINE_UNLOCK
                    ? CustomerOperationKind.ONLINE_UNLOCK : CustomerOperationKind.UNLOCK, attemptId);
        }

        @Override
        public boolean send(long attemptId, byte[] bytes) {
            return sendForOperation(customerOperationKind == CustomerOperationKind.ONLINE_UNLOCK
                    ? CustomerOperationKind.ONLINE_UNLOCK : CustomerOperationKind.UNLOCK, attemptId, bytes);
        }
    }

    private boolean sendAuthorizedReturnUnlock(long attemptId, byte[] bytes) {
        if (!customerActionsEnabled()) {
            return false;
        }
        AuthorizedUnlockRequest request = returnAuthorizedRequest;
        if (Looper.myLooper() != Looper.getMainLooper()
                || !active
                || !returnJourneyActive
                || returnBackgrounded
                || attemptId <= 0L
                || attemptId != returnUnlockAttemptId
                || returnUnlockDispatchGeneration != returnGeneration
                || returnUnlockDispatchOperationId != returnOperationId
                || returnUnlockDispatchCustomerToken != customerOperationToken
                || returnUnlockDispatchRequest != request
                || request == null
                || request.operationId() != returnOperationId
                || bytes == null
                || !Arrays.equals(request.unlockCommand(), bytes)
                || returnUnlockConsumed
                || customerOperationKind != CustomerOperationKind.RETURN
                || customerSerialPhase != CustomerSerialPhase.RETURN_UNLOCK
                || !customerProtocolConnected
                || !isCurrentOperation(
                        CustomerOperationKind.RETURN,
                        returnSerialProtocolId,
                        customerOperationUiGeneration,
                        customerOperationToken)) {
            return false;
        }

        boolean networkOnline = isNetworkOnline();
        ReturnFlowController.UnlockDispatchValidation validation =
                returnFlowController.validateUnlockDispatch(
                        returnGeneration,
                        returnOperationId,
                        request,
                        networkOnline);
        if (validation != ReturnFlowController.UnlockDispatchValidation.ALLOWED) {
            returnUnlockGateFailureMessage = returnUnlockDispatchFailureMessage(validation);
            if (validation
                    == ReturnFlowController.UnlockDispatchValidation.AUTHORIZATION_EXPIRED) {
                returnFlowController.markUnlockAuthorizationExpiredBeforeWrite(
                        returnGeneration, returnOperationId);
            }
            returnSerialFailure = true;
            return false;
        }

        CustomerSerialTransmitter.SendResult result =
                customerActionBoundary.call(
                        CustomerActionBoundary.Effect.SERIAL_SEND,
                        () -> customerSerialTransmitter.send(
                                CustomerSerialPhase.RETURN_UNLOCK,
                                customerOperationToken,
                                SerialWriteAttribution.returnUnlock(request.target()),
                                Arrays.copyOf(bytes, bytes.length)),
                        null);
        if (result == null) {
            return false;
        }
        if (result.accepted()) {
            // The physical writer accepted the one-shot authority. Never resend it.
            returnUnlockConsumed = true;
        }
        return result.accepted();
    }

    private static String returnUnlockDispatchFailureMessage(
            ReturnFlowController.UnlockDispatchValidation validation) {
        if (validation == ReturnFlowController.UnlockDispatchValidation.NETWORK_OFFLINE) {
            return "网络异常，断网禁止开柜";
        }
        if (validation
                == ReturnFlowController.UnlockDispatchValidation.AUTHORIZATION_EXPIRED) {
            return "本次开柜授权已过期，请重新验证";
        }
        return "本次开柜授权已失效，请重新验证";
    }

    private final class ReturnUnlockSerialActions
            implements UnlockCoordinator.SerialActions {
        @Override
        public void ensureDefaultConnected(long attemptId) {
            final long localToken = customerOperationToken;
            final long protocolId = returnSerialProtocolId;
            handler.post(() -> {
                if (attemptId != returnUnlockAttemptId
                        || localToken != customerOperationToken
                        || !isCurrentOperation(
                                CustomerOperationKind.RETURN,
                                protocolId,
                                customerOperationUiGeneration,
                                localToken)
                        || !customerProtocolConnected
                        || customerSerialPhase != CustomerSerialPhase.RETURN_UNLOCK) {
                    returnUnlockCoordinator.onSerialFailure(attemptId, OPEN_FAILURE);
                    return;
                }
                returnUnlockCoordinator.onSerialConnected(attemptId);
            });
        }

        @Override
        public boolean send(long attemptId, byte[] bytes) {
            return sendAuthorizedReturnUnlock(attemptId, bytes);
        }
    }

    private final class DiscoveryProtocolSink implements CustomerProtocolSink {
        private final long scanId;

        DiscoveryProtocolSink(long scanId) {
            this.scanId = scanId;
        }

        @Override
        public void onConnected() {
            discoveryCoordinator.onSerialConnected(scanId);
        }

        @Override
        public void onConnectionFailed(String detail) {
            discoveryCoordinator.onSerialOpenFailed(scanId, detail);
            discoveryCoordinator.onSerialFailure(scanId, detail);
        }

        @Override
        public void onSent(byte[] payload) {
            discoveryCoordinator.onSerialSent(
                    scanId, Arrays.copyOf(payload, payload.length));
        }

        @Override
        public void onBytes(byte[] bytes) {
            discoveryCoordinator.onSerialBytes(
                    scanId, Arrays.copyOf(bytes, bytes.length));
        }

        @Override
        public void onSendFailed(byte[] payload, String detail) {
            discoveryCoordinator.onSerialSendFailed(
                    scanId,
                    Arrays.copyOf(payload, payload.length),
                    detail);
        }
    }

    private final class UnlockProtocolSink implements CustomerProtocolSink {
        private final long attemptId;

        UnlockProtocolSink(long attemptId) {
            this.attemptId = attemptId;
        }

        @Override
        public void onConnected() {
            unlockCoordinator.onSerialConnected(attemptId);
        }

        @Override
        public void onConnectionFailed(String detail) {
            unlockCoordinator.onSerialFailure(attemptId, detail);
        }

        @Override
        public void onSent(byte[] payload) {
            unlockCoordinator.onSerialSent(
                    attemptId, Arrays.copyOf(payload, payload.length));
        }

        @Override
        public void onBytes(byte[] bytes) {
            unlockCoordinator.onSerialBytes(
                    attemptId, Arrays.copyOf(bytes, bytes.length));
        }

        @Override
        public void onSendFailed(byte[] payload, String detail) {
            unlockCoordinator.onSerialSendFailed(
                    attemptId,
                    Arrays.copyOf(payload, payload.length),
                    detail);
        }
    }

    private final class ReturnProtocolSink implements CustomerProtocolSink {
        private final long operationId;

        ReturnProtocolSink(long operationId) {
            this.operationId = operationId;
        }

        @Override
        public void onConnected() {
            if (operationId == returnOperationId) {
                sendReturnDoorStatusQuery();
            }
        }

        @Override
        public void onConnectionFailed(String detail) {
            ReturnFlowController.Snapshot snapshot = returnFlowController.snapshot();
            returnSerialFailure = true;
            if (returnUnlockAttemptId > 0L
                    && snapshot.state() == ReturnFlowModel.State.OPENING) {
                returnUnlockCoordinator.onSerialFailure(
                        returnUnlockAttemptId, detail);
                return;
            }
            if (returnDoorSession != null) {
                handleReturnDoorEvent(returnDoorSession.onDisconnected(
                        returnGeneration, returnOperationId));
            }
            cancelReturnTimers();
            invalidateCustomerWork(false);
            returnStatusMessage = "锁控设备连接失败，请联系管理员";
            renderReturnSnapshot();
        }

        @Override
        public void onSent(byte[] payload) {
            AuthorizedUnlockRequest request = returnAuthorizedRequest;
            if (request == null || payload == null) {
                return;
            }
            if (Arrays.equals(DoorStateProtocol.query(request.target()), payload)) {
                long pollId = returnActivePollId;
                if (returnDoorSession != null
                        && returnDoorSession.markPollSent(
                                returnGeneration, returnOperationId, pollId)) {
                    armReturnPollTimeout(pollId);
                }
                return;
            }
            if (!Arrays.equals(request.unlockCommand(), payload)
                    || customerSerialPhase != CustomerSerialPhase.RETURN_UNLOCK
                    || returnUnlockAttemptId <= 0L) {
                return;
            }
            ReturnDoorSession.Event sent = returnDoorSession == null
                    ? ReturnDoorSession.Event.REJECTED
                    : returnDoorSession.markUnlockSent(
                            returnGeneration, returnOperationId);
            if (sent != ReturnDoorSession.Event.WAITING_FOR_OPEN) {
                returnUnlockCoordinator.onSerialFailure(
                        returnUnlockAttemptId, SEND_FAILURE);
                return;
            }
            returnUnlockCoordinator.onSerialSent(
                    returnUnlockAttemptId, Arrays.copyOf(payload, payload.length));
            if (!customerSerialTransmitter.transitionAuthorizedReturn(
                    customerOperationToken, CustomerSerialPhase.RETURN_DOOR_STATUS)) {
                returnUnlockCoordinator.onSerialFailure(
                        returnUnlockAttemptId, SEND_FAILURE);
                return;
            }
            customerSerialPhase = CustomerSerialPhase.RETURN_DOOR_STATUS;
            customerWriteAttribution = SerialWriteAttribution.doorStatus(request.target());
            scheduleReturnDoorPoll(RETURN_POLL_INTERVAL_MILLIS);
        }

        @Override
        public void onBytes(byte[] bytes) {
            if (returnUnlockAttemptId > 0L) {
                returnUnlockCoordinator.onSerialBytes(
                        returnUnlockAttemptId, Arrays.copyOf(bytes, bytes.length));
            }
            handleReturnDoorBytes(bytes);
        }

        @Override
        public void onSendFailed(byte[] payload, String detail) {
            AuthorizedUnlockRequest request = returnAuthorizedRequest;
            if (request != null
                    && Arrays.equals(request.unlockCommand(), payload)
                    && returnUnlockAttemptId > 0L) {
                returnUnlockCoordinator.onSerialSendFailed(
                        returnUnlockAttemptId,
                        Arrays.copyOf(payload, payload.length), detail);
                return;
            }
            if (returnDoorSession != null && returnDoorSession.hasPollInFlight()) {
                returnActivePollId = 0L;
                handleReturnDoorEvent(returnDoorSession.onPollTimeout(
                        returnGeneration, returnOperationId));
                scheduleReturnDoorPoll(RETURN_POLL_BACKOFF_MILLIS);
            }
        }
    }

    private final class CustomerGatewayListener implements SerialGateway.Listener {
        private final long operationUiGeneration;
        private final long operationToken;
        private final CustomerOperationKind operationKind;
        private final long protocolId;
        private final long gatewayGeneration;
        private final ProcessSerialGatewayOwner.Lease<SerialGateway> expectedLease;
        private final CustomerProtocolSink expectedSink;

        CustomerGatewayListener(
                long operationUiGeneration,
                long operationToken,
                CustomerOperationKind operationKind,
                long protocolId,
                long gatewayGeneration,
                ProcessSerialGatewayOwner.Lease<SerialGateway> expectedLease,
                CustomerProtocolSink expectedSink) {
            this.operationUiGeneration = operationUiGeneration;
            this.operationToken = operationToken;
            this.operationKind = operationKind;
            this.protocolId = protocolId;
            this.gatewayGeneration = gatewayGeneration;
            this.expectedLease = expectedLease;
            this.expectedSink = expectedSink;
        }

        @Override
        public void onConnectionChanged(boolean connected, String detail) {
            final boolean safeConnected = connected;
            final String safeDetail = detail == null ? "" : new String(detail);
            handler.post(() -> {
                if (!isCurrentGatewayCallback(
                        operationUiGeneration,
                        operationToken,
                        operationKind,
                        protocolId,
                        gatewayGeneration,
                        expectedLease,
                        expectedSink)) {
                    return;
                }
                if (!safeDetail.isEmpty()) {
                    // Raw detail is retained by SerialGateway in the Admin runtime log only.
                }
                handleCustomerGatewayWakeup(
                        operationUiGeneration,
                        operationToken,
                        operationKind,
                        protocolId,
                        gatewayGeneration,
                        expectedLease,
                        expectedSink,
                        safeConnected,
                        safeDetail);
            });
        }

        @Override
        public void onDiagnostic(String detail) {
            final String safeDetail = detail == null ? "" : new String(detail);
            handler.post(() -> {
                if (!isCurrentGatewayCallback(
                        operationUiGeneration,
                        operationToken,
                        operationKind,
                        protocolId,
                        gatewayGeneration,
                        expectedLease,
                        expectedSink)) {
                    return;
                }
                if (safeDetail.isEmpty()) {
                    return;
                }
                // SerialGateway already retained this diagnostic for the Admin log.
            });
        }

        @Override
        public void onSent(byte[] bytes) {
            final byte[] safePayload = Arrays.copyOf(bytes, bytes.length);
            handler.post(() -> {
                if (isCurrentGatewayCallback(
                        operationUiGeneration,
                        operationToken,
                        operationKind,
                        protocolId,
                        gatewayGeneration,
                        expectedLease,
                        expectedSink)) {
                    expectedSink.onSent(Arrays.copyOf(safePayload, safePayload.length));
                }
            });
        }

        @Override
        public void onReceived(byte[] bytes) {
            final byte[] safePayload = Arrays.copyOf(bytes, bytes.length);
            handler.post(() -> {
                if (isCurrentGatewayCallback(
                        operationUiGeneration,
                        operationToken,
                        operationKind,
                        protocolId,
                        gatewayGeneration,
                        expectedLease,
                        expectedSink)) {
                    expectedSink.onBytes(Arrays.copyOf(safePayload, safePayload.length));
                }
            });
        }

        @Override
        public void onSendFailed(byte[] payload, String detail) {
            final byte[] safePayload = Arrays.copyOf(payload, payload.length);
            final String safeDetail = detail == null ? "" : new String(detail);
            handler.post(() -> {
                if (isCurrentGatewayCallback(
                        operationUiGeneration,
                        operationToken,
                        operationKind,
                        protocolId,
                        gatewayGeneration,
                        expectedLease,
                        expectedSink)) {
                    expectedSink.onSendFailed(
                            Arrays.copyOf(safePayload, safePayload.length),
                            safeDetail);
                }
            });
        }
    }
}
