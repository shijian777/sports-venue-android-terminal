package com.codex.lockertest.face;

import android.content.Context;

import com.baidu.idl.main.facesdk.FaceDetect;
import com.baidu.idl.main.facesdk.FaceInfo;
import com.baidu.idl.main.facesdk.FaceLive;
import com.baidu.idl.main.facesdk.FaceQueue;
import com.baidu.idl.main.facesdk.callback.Callback;
import com.baidu.idl.main.facesdk.model.BDFaceDetectListConf;
import com.baidu.idl.main.facesdk.model.BDFaceImageInstance;
import com.baidu.idl.main.facesdk.model.BDFaceInstance;
import com.baidu.idl.main.facesdk.model.BDFaceOcclusion;
import com.baidu.idl.main.facesdk.model.BDFaceSDKCommon.AlignType;
import com.baidu.idl.main.facesdk.model.BDFaceSDKCommon.BDFaceImageType;
import com.baidu.idl.main.facesdk.model.BDFaceSDKCommon.DetectType;
import com.baidu.idl.main.facesdk.model.BDFaceSDKCommon.LiveType;
import com.baidu.idl.main.facesdk.model.BDFaceSDKConfig;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Single-worker owner of the pinned Baidu detection and optional RGB-live runtime. */
public final class BaiduFaceRuntime {
    public static final int SAFE_RUNTIME_ERROR = 3001;
    public static final int SAFE_ANALYSIS_ERROR = 3002;
    private static final String RUNTIME_MESSAGE = "人脸检测组件暂时不可用";
    private static final String ANALYSIS_MESSAGE = "人脸画面检测失败";
    private static final long INIT_TIMEOUT_MILLIS = 15000L;
    private static final long LIVENESS_INIT_TIMEOUT_MILLIS = 15000L;
    private static final float RGB_LIVENESS_THRESHOLD = 0.80f;
    private static final int NOTICE_NONE = 0;
    private static final int NOTICE_READY = 1;
    private static final int NOTICE_FAILURE = 2;

    private static final String DETECT_MODEL =
            "face-sdk-models/detect/detect_rgb-customized-pa-192.model.float32-0.0.18.1";
    private static final String FAST_ALIGN_MODEL =
            "face-sdk-models/align/align_rgb-customized-pa-fast.model.float32-0.7.5.5";
    private static final String ACCURATE_ALIGN_MODEL =
            "face-sdk-models/align/align_rgb-customized-pa-80.model.float32-6.4.14.4";
    private static final String BLUR_MODEL =
            "face-sdk-models/blur/blur-customized-pa-addcloud_quant_e19.model.float32-3.0.13.3";
    private static final String OCCLUSION_MODEL =
            "face-sdk-models/occlusion/occlusion-customized-pa-paddle.model.float32-2.0.7.3";
    private static final String BEST_IMAGE_MODEL =
            "face-sdk-models/best_image/best_image-mobilenet-pa-dcqe449_live_e51_relu_128.model.float32-1.0.3.1";
    private static final String RGB_LIVENESS_MODEL =
            "face-sdk-models/silent_live/liveness_rgb-customized-pa-DCQsdk80.model.float32-1.1.82.1";

    public interface Listener {
        void onReady(long generation);
        void onFailure(long generation, int safeCode, String safeMessage);
    }

    public interface Subscription extends AutoCloseable {
        @Override void close();
    }

    public static final class Analysis {
        private final FaceObservation observation;
        private final PreviewBox previewBox;

        private Analysis(FaceObservation observation, PreviewBox previewBox) {
            if (observation == null) {
                throw new IllegalArgumentException("observation cannot be null");
            }
            this.observation = observation;
            this.previewBox = previewBox;
        }

        public FaceObservation observation() { return observation; }
        public PreviewBox previewBox() { return previewBox; }
    }

    public static final class PreviewBox {
        private final int frameWidth;
        private final int frameHeight;
        private final float centerX;
        private final float centerY;
        private final float width;
        private final float height;

        private PreviewBox(int frameWidth, int frameHeight, float centerX,
                float centerY, float width, float height) {
            this.frameWidth = frameWidth;
            this.frameHeight = frameHeight;
            this.centerX = centerX;
            this.centerY = centerY;
            this.width = width;
            this.height = height;
        }

        public int frameWidth() { return frameWidth; }
        public int frameHeight() { return frameHeight; }
        public float centerX() { return centerX; }
        public float centerY() { return centerY; }
        public float width() { return width; }
        public float height() { return height; }
    }

    public interface AnalysisCallback {
        void onCompleted(long requestId, Analysis analysis);
        void onFailure(long requestId, int safeCode, String safeMessage);
    }

    private final Object lock = new Object();
    private final FaceRuntimeStateMachine machine = new FaceRuntimeStateMachine();
    private final ScheduledExecutorService runtimeExecutor;
    private final boolean ownsExecutor;
    private final Runnable stateChangeHook;
    private final FaceLivenessControl livenessControl;
    private final FaceLivenessProbeGate livenessProbeGate;
    private final FaceRuntimeStateMachine.RuntimeVendorDispatcher dispatcher;
    private final FaceRuntimeStateMachine.CallbackEpoch callbackEpoch =
            new FaceRuntimeStateMachine.CallbackEpoch();
    private final FaceRuntimeStateMachine.CallbackDeliveryFence callbackDeliveryFence =
            new FaceRuntimeStateMachine.CallbackDeliveryFence();
    private final IdentityHashMap<Registration, Boolean> registrations =
            new IdentityHashMap<Registration, Boolean>();
    private ScheduledFuture<?> timeoutFuture;
    private ScheduledFuture<?> livenessTimeoutFuture;
    private Context initializationContext;
    private NativeAttempt nativeAttempt;
    private AnalysisTask activeAnalysis;
    private boolean analysisInFlight;
    private boolean releaseCallbacksInvalidated;
    private volatile Thread runtimeThread;

    public BaiduFaceRuntime() {
        this(transientLivenessControl(), null);
    }

    BaiduFaceRuntime(Runnable stateChangeHook) {
        this(transientLivenessControl(), stateChangeHook);
    }

    BaiduFaceRuntime(FaceLivenessControl livenessControl, Runnable stateChangeHook) {
        this(Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "baidu-face-runtime");
            thread.setDaemon(true);
            return thread;
        }), true, livenessControl, stateChangeHook);
    }

    BaiduFaceRuntime(ScheduledExecutorService runtimeExecutor,
            boolean ownsExecutor, Runnable stateChangeHook) {
        this(runtimeExecutor, ownsExecutor, transientLivenessControl(), stateChangeHook);
    }

    BaiduFaceRuntime(ScheduledExecutorService runtimeExecutor,
            boolean ownsExecutor, FaceLivenessControl livenessControl,
            Runnable stateChangeHook) {
        if (runtimeExecutor == null) {
            throw new IllegalArgumentException("runtimeExecutor cannot be null");
        }
        if (livenessControl == null) {
            throw new IllegalArgumentException("livenessControl cannot be null");
        }
        this.runtimeExecutor = runtimeExecutor;
        this.ownsExecutor = ownsExecutor;
        this.livenessControl = livenessControl;
        this.livenessProbeGate = new FaceLivenessProbeGate(livenessControl);
        this.stateChangeHook = stateChangeHook == null ? () -> { } : stateChangeHook;
        this.dispatcher = new FaceRuntimeStateMachine.RuntimeVendorDispatcher(
                task -> this.runtimeExecutor.execute(() -> runOnRuntime(task)),
                task -> FaceQueue.getInstance().execute(task));
    }

    private static FaceLivenessControl transientLivenessControl() {
        return new FaceLivenessControl(new FaceLivenessControl.BooleanStore() {
            private boolean value;

            @Override public boolean read() { return value; }
            @Override public boolean write(boolean enabled) {
                value = enabled;
                return true;
            }
        });
    }

    public Subscription initialize(Context context, Listener listener) {
        if (listener == null) throw new IllegalArgumentException("listener cannot be null");
        Context application = null;
        try {
            application = context == null ? null : context.getApplicationContext();
        } catch (RuntimeException | LinkageError ignored) { }
        FaceRuntimeStateMachine.StartResult start;
        long registrationGeneration;
        Registration registration;
        FaceRuntimeStateMachine.State state;
        synchronized (lock) {
            start = machine.startInitialize();
            registrationGeneration = start.generation() > 0L
                    ? start.generation() : machine.currentGeneration();
            registration = new Registration(this, listener, registrationGeneration,
                    callbackEpoch.capture());
            registrations.put(registration, Boolean.TRUE);
            if (application != null) initializationContext = application;
            state = machine.state();
        }
        if (state == FaceRuntimeStateMachine.State.READY) {
            postReady(registration, registrationGeneration);
            return registration;
        }
        if (state == FaceRuntimeStateMachine.State.RELEASED) {
            postFailure(registration, registrationGeneration);
            return registration;
        }
        if (!start.shouldStart()) {
            if (start.generation() <= 0L
                    && state == FaceRuntimeStateMachine.State.FAILED) {
                stateChanged();
                postFailure(registration, registrationGeneration);
            }
            return registration;
        }
        stateChanged();
        if (application == null) {
            abortInitializationStart(start.generation(), start.physicalAttemptId());
            return registration;
        }
        if (!armTimeout(start.generation())) {
            abortInitializationStart(start.generation(), start.physicalAttemptId());
            return registration;
        }
        followStartDirective(application, start);
        return registration;
    }

    public FaceCaptureSession.Cancellable analyze(long requestId,
            FaceFrame.Borrow ownedBorrow, AnalysisCallback callback) {
        if (ownedBorrow == null) throw new IllegalArgumentException("borrow cannot be null");
        if (callback == null || requestId <= 0L) {
            closeBorrowQuietly(ownedBorrow);
            throw new IllegalArgumentException("request and callback must be valid");
        }
        AnalysisTask task = null;
        boolean reject;
        long rejectedCallbackEpoch = 0L;
        synchronized (lock) {
            reject = !machine.mayAnalyze() || analysisInFlight;
            if (reject) {
                rejectedCallbackEpoch = callbackEpoch.capture();
            } else {
                analysisInFlight = true;
                task = new AnalysisTask(requestId, ownedBorrow, callback,
                        callbackEpoch.capture());
                activeAnalysis = task;
            }
        }
        if (reject) {
            closeBorrowQuietly(ownedBorrow);
            if (claimImmediateAnalysisNotification(rejectedCallbackEpoch)) {
                notifyAnalysisFailure(callback, requestId);
            }
            return () -> { };
        }
        if (!dispatcher.executeRuntime(task)) {
            task.reject();
        }
        return task;
    }

    public FaceRuntimeStateMachine.State state() {
        return machine.state();
    }

    public void release() {
        FaceRuntimeStateMachine.ReleaseResult release;
        List<Registration> suppressed;
        AnalysisTask analysis;
        synchronized (lock) {
            release = machine.release();
            cancelTimeoutLocked();
            cancelLivenessTimeoutLocked();
            if (!releaseCallbacksInvalidated) {
                callbackEpoch.invalidate();
                releaseCallbacksInvalidated = true;
            }
            callbackDeliveryFence.beginClose();
            livenessProbeGate.release();
            suppressed = new ArrayList<Registration>(registrations.keySet());
            registrations.clear();
            for (Registration registration : suppressed) registration.active = false;
            analysis = activeAnalysis;
        }
        if (analysis != null) analysis.suppress();
        callbackDeliveryFence.awaitDrained();
        stateChanged();
        if (release.directive()
                == FaceRuntimeStateMachine.Directive.QUEUE_VENDOR_BARRIER_THEN_CLEAN) {
            queueVendorBarrier(release.physicalAttemptId());
        } else if (release.directive() == FaceRuntimeStateMachine.Directive.NO_OP
                && machine.currentPhysicalAttemptId() == 0L) {
            shutdownOwnedExecutor();
        }
    }

    private void followStartDirective(Context context,
            FaceRuntimeStateMachine.StartResult start) {
        if (start.directive() == FaceRuntimeStateMachine.Directive.START_NOW) {
            dispatchStart(context, start.generation(), start.physicalAttemptId());
        } else if (start.directive()
                == FaceRuntimeStateMachine.Directive.QUEUE_VENDOR_BARRIER_THEN_CLEAN) {
            queueVendorBarrier(start.physicalAttemptId());
        }
    }

    private void dispatchStart(Context context, long generation, long physicalAttemptId) {
        if (!dispatcher.executeRuntime(
                () -> startModels(context, generation, physicalAttemptId))) {
            if (machine.startDispatchRejected(generation, physicalAttemptId)) {
                finishInitializationFailure(generation);
            }
        }
    }

    private void startModels(Context context, long generation, long physicalAttemptId) {
        if (machine.state() != FaceRuntimeStateMachine.State.INITIALIZING
                || machine.currentPhysicalAttemptId() != physicalAttemptId) return;
        try {
            verifyCorePinnedPaths();
            BDFaceInstance instance = new BDFaceInstance();
            NativeAttempt attempt = new NativeAttempt(
                    generation, physicalAttemptId, instance);
            nativeAttempt = attempt;
            instance.creatInstance();
            FaceDetect tracker = new FaceDetect(instance);
            FaceDetect detector = new FaceDetect(instance);
            attempt.tracker = tracker;
            attempt.detector = detector;

            BDFaceSDKConfig trackerConfig = new BDFaceSDKConfig();
            tracker.loadConfig(trackerConfig);
            BDFaceSDKConfig detectorConfig = new BDFaceSDKConfig();
            detectorConfig.isCheckBlur = true;
            detectorConfig.isOcclusion = true;
            detectorConfig.isIllumination = true;
            detectorConfig.isHeadPose = true;
            detectorConfig.isBestImage = true;
            detector.loadConfig(detectorConfig);

            tracker.initModel(context, DETECT_MODEL, FAST_ALIGN_MODEL,
                    DetectType.DETECT_VIS, AlignType.BDFACE_ALIGN_TYPE_RGB_FAST,
                    modelCallback(generation,
                            FaceRuntimeStateMachine.ModelStage.TRACKER_MODEL));
            detector.initModel(context, DETECT_MODEL, ACCURATE_ALIGN_MODEL,
                    DetectType.DETECT_VIS, AlignType.BDFACE_ALIGN_TYPE_RGB_ACCURATE,
                    modelCallback(generation,
                            FaceRuntimeStateMachine.ModelStage.DETECTOR_MODEL));
            detector.initQuality(context, BLUR_MODEL, OCCLUSION_MODEL,
                    modelCallback(generation,
                            FaceRuntimeStateMachine.ModelStage.QUALITY_MODELS));
            detector.initBestImage(context, BEST_IMAGE_MODEL,
                    modelCallback(generation,
                            FaceRuntimeStateMachine.ModelStage.BEST_IMAGE_MODEL));
            startOptionalLiveness(context, generation, physicalAttemptId, attempt);
        } catch (RuntimeException | LinkageError failure) {
            failLivenessProbe(generation, physicalAttemptId);
            if (machine.failAttempt(generation)) {
                finishInitializationFailure(generation);
            }
        }
    }

    private void startOptionalLiveness(Context context, long generation,
            long physicalAttemptId, NativeAttempt attempt) {
        synchronized (lock) {
            if (machine.state() != FaceRuntimeStateMachine.State.INITIALIZING
                    || machine.currentPhysicalAttemptId() != physicalAttemptId
                    || nativeAttempt != attempt
                    || !livenessProbeGate.begin(generation, physicalAttemptId)) return;
        }
        stateChanged();
        try {
            verifyOptionalLivenessPinnedPath();
            FaceLive faceLive = new FaceLive(attempt.instance);
            attempt.faceLive = faceLive;
            if (!armLivenessTimeout(generation, physicalAttemptId)) {
                failLivenessProbe(generation, physicalAttemptId);
                return;
            }
            faceLive.initModel(context, RGB_LIVENESS_MODEL,
                    "", "", "", "", "",
                    livenessCallback(generation, physicalAttemptId));
        } catch (RuntimeException | LinkageError ignored) {
            failLivenessProbe(generation, physicalAttemptId);
        }
    }

    private Callback livenessCallback(long generation, long physicalAttemptId) {
        return (code, ignoredNativeResponse) -> {
            if (!dispatcher.executeRuntime(() -> livenessCallbackOnRuntime(
                    generation, physicalAttemptId, code))) {
                failLivenessProbe(generation, physicalAttemptId);
            }
        };
    }

    private void livenessCallbackOnRuntime(long generation,
            long physicalAttemptId, int code) {
        if (!livenessProbeGate.complete(generation, physicalAttemptId, code)) return;
        synchronized (lock) { cancelLivenessTimeoutLocked(); }
        stateChanged();
    }

    private boolean isCurrentLivenessProbe(long generation, long physicalAttemptId) {
        FaceRuntimeStateMachine.State state = machine.state();
        return state != FaceRuntimeStateMachine.State.RELEASED
                && state != FaceRuntimeStateMachine.State.FAILED
                && livenessProbeGate.isActive(generation, physicalAttemptId);
    }

    private void failLivenessProbe(long generation, long physicalAttemptId) {
        if (!livenessProbeGate.fail(generation, physicalAttemptId)) return;
        synchronized (lock) { cancelLivenessTimeoutLocked(); }
        stateChanged();
    }

    private Callback modelCallback(long generation,
            FaceRuntimeStateMachine.ModelStage stage) {
        return (code, ignoredNativeResponse) -> {
            if (!dispatcher.executeRuntime(() -> modelCallbackOnRuntime(
                    generation, stage, code == 0))) {
                if (machine.stageFailed(generation, stage)) {
                    finishInitializationFailure(generation);
                }
            }
        };
    }

    private void modelCallbackOnRuntime(long generation,
            FaceRuntimeStateMachine.ModelStage stage, boolean success) {
        if (!success) {
            if (machine.stageFailed(generation, stage)) {
                finishInitializationFailure(generation);
            }
            return;
        }
        if (!machine.stageSucceeded(generation, stage)) return;
        if (machine.state() == FaceRuntimeStateMachine.State.READY) {
            List<Registration> listeners;
            synchronized (lock) {
                cancelTimeoutLocked();
                listeners = markGenerationLocked(generation, NOTICE_READY);
            }
            stateChanged();
            for (Registration registration : listeners) {
                deliverRegistration(registration, NOTICE_READY);
            }
        }
    }

    private void initializationFailed(long generation, long physicalAttemptId,
            FaceRuntimeStateMachine.ModelStage stage) {
        boolean changed = stage == null
                ? machine.startDispatchRejected(generation, physicalAttemptId)
                : machine.stageFailed(generation, stage);
        if (changed) finishInitializationFailure(generation);
    }

    private void abortInitializationStart(long generation, long physicalAttemptId) {
        if (machine.abortStart(generation, physicalAttemptId)) {
            finishInitializationFailure(generation);
        }
    }

    private void finishInitializationFailure(long generation) {
        NativeAttempt attempt = nativeAttempt;
        if (attempt != null) failLivenessForRuntimeFailure(generation, attempt.id);
        List<Registration> listeners;
        synchronized (lock) {
            cancelTimeoutLocked();
            listeners = markGenerationLocked(generation, NOTICE_FAILURE);
        }
        stateChanged();
        for (Registration registration : listeners) {
            deliverRegistration(registration, NOTICE_FAILURE);
        }
    }

    private void failLivenessForRuntimeFailure(long generation, long physicalAttemptId) {
        if (!livenessProbeGate.runtimeUnavailable(generation, physicalAttemptId)) return;
        synchronized (lock) { cancelLivenessTimeoutLocked(); }
        stateChanged();
    }

    private boolean armTimeout(long generation) {
        try {
            ScheduledFuture<?> scheduled = runtimeExecutor.schedule(() -> {
                runOnRuntime(() -> {
                    if (machine.timeout(generation)) {
                        finishInitializationFailure(generation);
                    }
                });
            }, INIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            synchronized (lock) {
                if (machine.state() == FaceRuntimeStateMachine.State.RELEASED) {
                    scheduled.cancel(false);
                    return false;
                }
                timeoutFuture = scheduled;
            }
            return true;
        } catch (RuntimeException | LinkageError rejected) {
            return false;
        }
    }

    private boolean armLivenessTimeout(long generation, long physicalAttemptId) {
        try {
            ScheduledFuture<?> scheduled = runtimeExecutor.schedule(() ->
                    runOnRuntime(() -> failLivenessProbe(generation, physicalAttemptId)),
                    LIVENESS_INIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            synchronized (lock) {
                if (!isCurrentLivenessProbe(generation, physicalAttemptId)) {
                    scheduled.cancel(false);
                    return false;
                }
                livenessTimeoutFuture = scheduled;
            }
            return true;
        } catch (RuntimeException | LinkageError rejected) {
            return false;
        }
    }

    private void queueVendorBarrier(long physicalAttemptId) {
        if (!machine.claimCleanupBarrierSubmission(physicalAttemptId)) return;
        boolean accepted = dispatcher.executeVendorAfterRuntime(() -> {
            if (!dispatcher.executeRuntime(
                    () -> cleanupThenInitialize(physicalAttemptId))) {
                barrierSubmissionRejected(physicalAttemptId);
            }
        }, () -> barrierSubmissionRejected(physicalAttemptId));
        if (!accepted) {
            barrierSubmissionRejected(physicalAttemptId);
        }
    }

    private void barrierSubmissionRejected(long physicalAttemptId) {
        if (!machine.cleanupBarrierSubmissionRejected(physicalAttemptId)) return;
        long generation = machine.currentGeneration();
        if (machine.state() == FaceRuntimeStateMachine.State.FAILED) {
            finishInitializationFailure(generation);
        } else {
            stateChanged();
            if (machine.state() == FaceRuntimeStateMachine.State.RELEASED) {
                shutdownOwnedExecutor();
            }
        }
    }

    private void cleanupThenInitialize(long physicalAttemptId) {
        if (!cleanupNativeAttempt(physicalAttemptId)) {
            nativeCleanupFailed(physicalAttemptId);
            return;
        }
        FaceRuntimeStateMachine.CleanupResult after =
                machine.cleanupCompleted(physicalAttemptId);
        stateChanged();
        if (after.directive()
                == FaceRuntimeStateMachine.Directive.START_AFTER_CLEANUP) {
            Context context;
            synchronized (lock) { context = initializationContext; }
            if (context == null) {
                abortInitializationStart(
                        after.generation(), after.physicalAttemptId());
            } else {
                dispatchStart(context, after.generation(), after.physicalAttemptId());
            }
        } else if (machine.state() == FaceRuntimeStateMachine.State.RELEASED) {
            shutdownOwnedExecutor();
        }
    }

    private void nativeCleanupFailed(long physicalAttemptId) {
        if (!machine.cleanupAttemptFailed(physicalAttemptId)) return;
        long generation = machine.currentGeneration();
        if (machine.state() == FaceRuntimeStateMachine.State.FAILED) {
            finishInitializationFailure(generation);
        } else {
            stateChanged();
            if (machine.state() == FaceRuntimeStateMachine.State.RELEASED) {
                shutdownOwnedExecutor();
            }
        }
    }

    private boolean cleanupNativeAttempt(long physicalAttemptId) {
        NativeAttempt attempt = nativeAttempt;
        if (attempt == null) return true;
        if (attempt.id != physicalAttemptId || attempt.cleaned) return false;
        attempt.cleaned = true;
        boolean successful = true;
        synchronized (lock) { cancelLivenessTimeoutLocked(); }
        try {
            if (attempt.faceLive != null) attempt.faceLive.uninitModel();
        } catch (RuntimeException | LinkageError ignored) {
            // Optional liveness cleanup never changes the ordinary detector state.
        }
        try {
            if (attempt.detector != null && attempt.detector.uninitModel() != 0) {
                successful = false;
            }
        } catch (RuntimeException | LinkageError ignored) { successful = false; }
        try {
            if (attempt.instance != null && attempt.instance.destory() != 0) {
                successful = false;
            }
        } catch (RuntimeException | LinkageError ignored) { successful = false; }
        nativeAttempt = null;
        return successful;
    }

    private void shutdownOwnedExecutor() {
        if (ownsExecutor) {
            try { runtimeExecutor.shutdown(); }
            catch (RuntimeException | LinkageError ignored) { }
        }
    }

    private void runOnRuntime(Runnable task) {
        runtimeThread = Thread.currentThread();
        task.run();
    }

    private Analysis analyzeOnWorker(FaceFrame.Borrow ownedBorrow) {
        BDFaceImageInstance image = null;
        try {
            NativeAttempt attempt = nativeAttempt;
            if (attempt == null || attempt.tracker == null || attempt.detector == null) {
                throw new AnalysisFailure();
            }
            int visibleWidth = isQuarterTurn(ownedBorrow.sdkRotationDegrees())
                    ? ownedBorrow.height() : ownedBorrow.width();
            int visibleHeight = isQuarterTurn(ownedBorrow.sdkRotationDegrees())
                    ? ownedBorrow.width() : ownedBorrow.height();
            image = new BDFaceImageInstance(ownedBorrow.nv21(),
                    ownedBorrow.width(), ownedBorrow.height(),
                    BDFaceImageType.BDFACE_IMAGE_TYPE_YUV_NV21,
                    ownedBorrow.sdkRotationDegrees(), ownedBorrow.sdkMirror());
            FaceInfo[] fast = attempt.tracker.track(DetectType.DETECT_VIS,
                    AlignType.BDFACE_ALIGN_TYPE_RGB_FAST, image);
            int faceCount = fast == null ? 0 : fast.length;
            PreviewBox preview = null;
            if (faceCount > 0) {
                FaceInfo tracked = fast[0];
                requireGeometry(tracked.centerX, tracked.centerY,
                        tracked.width, tracked.height);
                preview = new PreviewBox(visibleWidth, visibleHeight,
                        tracked.centerX, tracked.centerY, tracked.width, tracked.height);
            }
            if (faceCount == 0) {
                return new Analysis(zeroObservation(visibleWidth, visibleHeight, 0), null);
            }
            BDFaceDetectListConf list = new BDFaceDetectListConf();
            list.usingAlign = true;
            list.usingQuality = true;
            list.usingHeadPose = true;
            list.usingBestImage = true;
            FaceInfo[] accurate = attempt.detector.detect(DetectType.DETECT_VIS,
                    AlignType.BDFACE_ALIGN_TYPE_RGB_ACCURATE, image, fast, list);
            if (accurate == null || accurate.length == 0 || accurate[0] == null) {
                return new Analysis(zeroObservation(visibleWidth, visibleHeight, faceCount),
                        preview);
            }
            FaceInfo accurateFace = accurate[0];
            BDFaceOcclusion occlusion = accurateFace.occlusion;
            if (occlusion == null) throw new AnalysisFailure();
            // FaceInfo.illum is always copied; no SDK object escapes this worker.
            requireGeometry(accurateFace.centerX, accurateFace.centerY,
                    accurateFace.width, accurateFace.height);
            requireUnit(accurateFace.score);
            requireFinite(accurateFace.yaw);
            requireFinite(accurateFace.pitch);
            requireFinite(accurateFace.roll);
            requireUnit(accurateFace.bluriness);
            requireUnit(accurateFace.illum);
            requireUnit(accurateFace.bestImageScore);
            requireUnit(occlusion.leftEye);
            requireUnit(occlusion.rightEye);
            requireUnit(occlusion.nose);
            requireUnit(occlusion.mouth);
            requireUnit(occlusion.leftCheek);
            requireUnit(occlusion.rightCheek);
            requireUnit(occlusion.chin);
            float completeness = fullyInside(accurateFace, visibleWidth, visibleHeight)
                    ? 1.0f : 0.0f;
            final FaceLive currentFaceLive = attempt.faceLive;
            final float[] currentLandmarks = accurateFace.landmarks;
            final BDFaceImageInstance currentImage = image;
            FaceLivenessInferencePolicy.Decision liveness =
                    FaceLivenessInferencePolicy.evaluate(
                            livenessControl, RGB_LIVENESS_THRESHOLD, () -> {
                                if (currentLandmarks == null
                                        || currentLandmarks.length == 0) return 0.0f;
                                if (currentFaceLive == null) {
                                    throw new IllegalStateException(
                                            "optional liveness runtime unavailable");
                                }
                                return currentFaceLive.silentLive(
                                        LiveType.BDFACE_SILENT_LIVE_TYPE_RGB,
                                        currentImage, currentLandmarks,
                                        RGB_LIVENESS_THRESHOLD);
                            }, () -> livenessProbeGate.inferenceUnavailable(
                                    attempt.generation, attempt.id));
            if (liveness.capabilityChanged()) stateChanged();
            boolean livenessRequired = liveness.livenessRequired();
            boolean livenessPassed = liveness.livenessPassed();
            FaceObservation observation = new FaceObservation(
                    visibleWidth, visibleHeight, faceCount,
                    accurateFace.score, accurateFace.centerX, accurateFace.centerY,
                    accurateFace.width, accurateFace.height,
                    accurateFace.yaw, accurateFace.pitch, accurateFace.roll,
                    accurateFace.bluriness, accurateFace.illum,
                    occlusion.leftEye, occlusion.rightEye, occlusion.nose,
                    occlusion.mouth, occlusion.leftCheek, occlusion.rightCheek,
                    occlusion.chin, completeness, accurateFace.bestImageScore,
                    livenessRequired, livenessPassed);
            return new Analysis(observation, preview);
        } catch (AnalysisFailure failure) {
            throw failure;
        } catch (RuntimeException | LinkageError failure) {
            throw new AnalysisFailure();
        } finally {
            if (image != null) {
                try {
                    if (image.destory() != 0) throw new AnalysisFailure();
                } catch (AnalysisFailure failure) {
                    throw failure;
                } catch (RuntimeException | LinkageError failure) {
                    throw new AnalysisFailure();
                }
            }
        }
    }

    private static FaceObservation zeroObservation(int width, int height, int count) {
        return new FaceObservation(width, height, count,
                0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
                0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
                0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
                0.0f, 0.0f);
    }

    private static boolean fullyInside(FaceInfo face, int width, int height) {
        float halfWidth = face.width / 2.0f;
        float halfHeight = face.height / 2.0f;
        return face.centerX - halfWidth >= 0.0f
                && face.centerY - halfHeight >= 0.0f
                && face.centerX + halfWidth <= width
                && face.centerY + halfHeight <= height;
    }

    private static void requireGeometry(float centerX, float centerY,
            float width, float height) {
        requireFinite(centerX);
        requireFinite(centerY);
        requireFinite(width);
        requireFinite(height);
        if (centerX < 0.0f || centerY < 0.0f || width <= 0.0f || height <= 0.0f) {
            throw new AnalysisFailure();
        }
    }

    private static void requireUnit(float value) {
        requireFinite(value);
        if (value < 0.0f || value > 1.0f) throw new AnalysisFailure();
    }

    private static void requireFinite(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) throw new AnalysisFailure();
    }

    private static boolean isQuarterTurn(int rotation) {
        return rotation == 90 || rotation == 270;
    }

    private static void verifyCorePinnedPaths() {
        String[] paths = new String[] { DETECT_MODEL, FAST_ALIGN_MODEL,
                ACCURATE_ALIGN_MODEL, BLUR_MODEL, OCCLUSION_MODEL, BEST_IMAGE_MODEL };
        for (String path : paths) {
            verifyPinnedPath(path, BaiduFaceArtifactManifest.coreRuntimeModels());
        }
    }

    private static void verifyOptionalLivenessPinnedPath() {
        BaiduFaceArtifactManifest.Artifact artifact =
                BaiduFaceArtifactManifest.optionalLivenessModel();
        if (artifact.kind() != BaiduFaceArtifactManifest.Kind.MODEL
                || !artifact.relativePath().equals(
                        "app/src/main/assets/" + RGB_LIVENESS_MODEL)) {
            throw new IllegalStateException("optional liveness model unavailable");
        }
    }

    private static void verifyPinnedPath(String path,
            List<BaiduFaceArtifactManifest.Artifact> candidates) {
        for (BaiduFaceArtifactManifest.Artifact artifact : candidates) {
            if (artifact.kind() == BaiduFaceArtifactManifest.Kind.MODEL
                    && artifact.relativePath().equals("app/src/main/assets/" + path)) {
                return;
            }
        }
        throw new IllegalStateException("pinned core model unavailable");
    }

    private List<Registration> markGenerationLocked(long generation, int notice) {
        List<Registration> marked = new ArrayList<Registration>();
        for (Registration registration
                : new ArrayList<Registration>(registrations.keySet())) {
            if (registration.generation == generation && registration.active) {
                registration.pendingNotice = notice;
                marked.add(registration);
            }
        }
        return marked;
    }

    private boolean claimRegistration(Registration registration, int notice) {
        synchronized (lock) {
            if (!registration.active || registration.pendingNotice != notice
                    || !registrations.containsKey(registration)
                    || !callbackEpoch.tryClaim(registration.callbackEpoch)) return false;
            registration.active = false;
            registrations.remove(registration);
            return true;
        }
    }

    private void postReady(Registration registration, long generation) {
        synchronized (lock) {
            if (!registration.active || !registrations.containsKey(registration)) return;
            registration.pendingNotice = NOTICE_READY;
        }
        if (dispatcher.executeRuntime(
                () -> deliverRegistration(registration, NOTICE_READY))) return;
        synchronized (lock) {
            if (registration.active && registrations.containsKey(registration)) {
                registration.pendingNotice = NOTICE_FAILURE;
            }
        }
        deliverRegistration(registration, NOTICE_FAILURE);
    }

    private void postFailure(Registration registration, long generation) {
        synchronized (lock) {
            if (!registration.active || !registrations.containsKey(registration)) return;
            registration.pendingNotice = NOTICE_FAILURE;
        }
        if (!dispatcher.executeRuntime(
                () -> deliverRegistration(registration, NOTICE_FAILURE))) {
            deliverRegistration(registration, NOTICE_FAILURE);
        }
    }

    private void deliverRegistration(Registration registration, int notice) {
        if (!claimRegistration(registration, notice)) return;
        if (notice == NOTICE_READY) {
            notifyReady(registration, registration.generation);
        } else {
            notifyFailure(registration, registration.generation);
        }
    }

    private void notifyReady(Registration registration, long generation) {
        callbackDeliveryFence.deliver(() -> {
            try { registration.listener.onReady(generation); }
            catch (RuntimeException | LinkageError ignored) { }
        });
    }

    private void notifyFailure(Registration registration, long generation) {
        callbackDeliveryFence.deliver(() -> {
            try {
                registration.listener.onFailure(
                        generation, SAFE_RUNTIME_ERROR, RUNTIME_MESSAGE);
            } catch (RuntimeException | LinkageError ignored) { }
        });
    }

    private void notifyAnalysisCompleted(AnalysisCallback callback,
            long requestId, Analysis analysis) {
        callbackDeliveryFence.deliver(() -> {
            try { callback.onCompleted(requestId, analysis); }
            catch (RuntimeException | LinkageError ignored) { }
        });
    }

    private void notifyAnalysisFailure(AnalysisCallback callback, long requestId) {
        callbackDeliveryFence.deliver(() -> {
            try { callback.onFailure(requestId, SAFE_ANALYSIS_ERROR, ANALYSIS_MESSAGE); }
            catch (RuntimeException | LinkageError ignored) { }
        });
    }

    private static boolean closeBorrowQuietly(FaceFrame.Borrow ownedBorrow) {
        try {
            ownedBorrow.close();
            return true;
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private void close(Registration registration) {
        synchronized (lock) {
            registration.active = false;
            registrations.remove(registration);
        }
    }

    private void finishAnalysisResources(AnalysisTask task) {
        synchronized (lock) {
            if (activeAnalysis == task) activeAnalysis = null;
            analysisInFlight = false;
        }
        task.gate.completeCleanup();
    }

    private boolean claimAnalysisNotification(AnalysisTask task) {
        synchronized (lock) {
            return machine.mayAnalyze()
                    && task.gate.tryClaimNotification()
                    && callbackEpoch.tryClaim(task.callbackEpoch);
        }
    }

    private boolean claimImmediateAnalysisNotification(long capturedEpoch) {
        synchronized (lock) {
            return callbackEpoch.tryClaim(capturedEpoch);
        }
    }

    private void cancelTimeoutLocked() {
        ScheduledFuture<?> claimed = timeoutFuture;
        timeoutFuture = null;
        if (claimed != null) claimed.cancel(false);
    }

    private void cancelLivenessTimeoutLocked() {
        ScheduledFuture<?> claimed = livenessTimeoutFuture;
        livenessTimeoutFuture = null;
        if (claimed != null) claimed.cancel(false);
    }

    private void stateChanged() {
        try { stateChangeHook.run(); }
        catch (RuntimeException | LinkageError ignored) { }
    }

    private static final class NativeAttempt {
        final long generation;
        final long id;
        final BDFaceInstance instance;
        FaceDetect tracker;
        FaceDetect detector;
        FaceLive faceLive;
        boolean cleaned;

        NativeAttempt(long generation, long id, BDFaceInstance instance) {
            this.generation = generation;
            this.id = id;
            this.instance = instance;
        }
    }

    private static final class AnalysisFailure extends RuntimeException { }

    private final class AnalysisTask implements Runnable, FaceCaptureSession.Cancellable {
        private final long requestId;
        private final FaceFrame.Borrow ownedBorrow;
        private final AnalysisCallback callback;
        private final long callbackEpoch;
        private final FaceRuntimeStateMachine.AnalysisGate gate =
                new FaceRuntimeStateMachine.AnalysisGate();

        AnalysisTask(long requestId, FaceFrame.Borrow ownedBorrow,
                AnalysisCallback callback, long callbackEpoch) {
            this.requestId = requestId;
            this.ownedBorrow = ownedBorrow;
            this.callback = callback;
            this.callbackEpoch = callbackEpoch;
        }

        @Override
        public void run() {
            FaceRuntimeStateMachine.AnalysisStart start = gate.tryStart();
            if (start == FaceRuntimeStateMachine.AnalysisStart.NO_OP) return;
            Analysis analysis = null;
            boolean failed = start != FaceRuntimeStateMachine.AnalysisStart.RUN_NATIVE;
            try {
                if (!failed) analysis = analyzeOnWorker(ownedBorrow);
            } catch (RuntimeException | LinkageError failure) {
                failed = true;
            } finally {
                if (!closeBorrowQuietly(ownedBorrow)) failed = true;
                finishAnalysisResources(this);
            }
            if (!claimAnalysisNotification(this)) return;
            if (failed || analysis == null) {
                notifyAnalysisFailure(callback, requestId);
            } else {
                notifyAnalysisCompleted(callback, requestId, analysis);
            }
        }

        @Override
        public void cancel() {
            gate.cancel();
            if (Thread.currentThread() != runtimeThread) gate.awaitCleanup();
        }

        void suppress() {
            gate.cancel();
        }

        void reject() {
            FaceRuntimeStateMachine.AnalysisStart start = gate.tryStart();
            if (start == FaceRuntimeStateMachine.AnalysisStart.NO_OP) return;
            closeBorrowQuietly(ownedBorrow);
            finishAnalysisResources(this);
            if (claimAnalysisNotification(this)) {
                notifyAnalysisFailure(callback, requestId);
            }
        }
    }

    private static final class Registration implements Subscription {
        private final BaiduFaceRuntime owner;
        private final Listener listener;
        private final long generation;
        private final long callbackEpoch;
        private boolean active = true;
        private int pendingNotice = NOTICE_NONE;

        Registration(BaiduFaceRuntime owner, Listener listener,
                long generation, long callbackEpoch) {
            this.owner = owner;
            this.listener = listener;
            this.generation = generation;
            this.callbackEpoch = callbackEpoch;
        }

        @Override public void close() { owner.close(this); }
    }
}
