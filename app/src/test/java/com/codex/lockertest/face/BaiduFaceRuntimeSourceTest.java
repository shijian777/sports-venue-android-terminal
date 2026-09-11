package com.codex.lockertest.face;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;
import org.junit.Test;

public final class BaiduFaceRuntimeSourceTest {
    @Test
    public void runtimePinsSixCoreModelsAndOneOptionalRgbLivenessModel() throws Exception {
        String runtime = readMain("BaiduFaceRuntime.java");
        assertContains(runtime, "detect_rgb-customized-pa-192.model.float32-0.0.18.1");
        assertContains(runtime, "align_rgb-customized-pa-fast.model.float32-0.7.5.5");
        assertContains(runtime, "align_rgb-customized-pa-80.model.float32-6.4.14.4");
        assertContains(runtime, "blur-customized-pa-addcloud_quant_e19.model.float32-3.0.13.3");
        assertContains(runtime, "occlusion-customized-pa-paddle.model.float32-2.0.7.3");
        assertContains(runtime, "best_image-mobilenet-pa-dcqe449_live_e51_relu_128.model.float32-1.0.3.1");
        assertContains(runtime, "liveness_rgb-customized-pa-DCQsdk80.model.float32-1.1.82.1");
        assertEquals(3, occurrences(runtime, ".initModel("));
        assertEquals(1, occurrences(runtime, ".initQuality("));
        assertEquals(1, occurrences(runtime, ".initBestImage("));
        assertContains(runtime, "new FaceDetect(instance)");
        assertEquals(2, occurrences(runtime, "new FaceDetect(instance)"));
        assertContains(runtime, "BDFACE_ALIGN_TYPE_RGB_FAST");
        assertContains(runtime, "BDFACE_ALIGN_TYPE_RGB_ACCURATE");
        assertContains(runtime, "new FaceLive(attempt.instance)");
        assertContains(runtime, "BDFACE_SILENT_LIVE_TYPE_RGB");
        assertContains(runtime, "RGB_LIVENESS_THRESHOLD = 0.80f");
        assertContains(readMain("FaceLivenessInferencePolicy.java"), "score > threshold");
    }

    @Test
    public void optionalLivenessProbeHasIndependentCapabilityAndOrdinaryFallback()
            throws Exception {
        String runtime = readMain("BaiduFaceRuntime.java");
        String gate = readMain("FaceLivenessProbeGate.java");
        String inference = readMain("FaceLivenessInferencePolicy.java");
        assertContains(runtime, "livenessProbeGate.begin(");
        assertContains(runtime, "livenessProbeGate.complete(");
        assertContains(runtime, "livenessProbeGate.release()");
        assertContains(gate, "control.onProbeStarted()");
        assertContains(gate, "control.onProbeSupported()");
        assertContains(gate, "control.onProbeUnsupported()");
        assertContains(gate, "control.onProbeFailed()");
        assertContains(gate, "code == 10");
        assertContains(inference, "FaceLivenessControl.Mode.RGB_LIVENESS");
        assertContains(runtime, "FaceLivenessInferencePolicy.evaluate(");
        assertContains(runtime, "liveness.capabilityChanged()");
        assertContains(runtime, "accurateFace.landmarks");
        assertContains(runtime, "livenessRequired, livenessPassed");
        assertContains(runtime, "attempt.faceLive.uninitModel()");
        assertContains(runtime, "\"\", \"\", \"\", \"\", \"\"");
    }

    @Test
    public void detectorAndAccurateListExplicitlyEnableRequiredChecks() throws Exception {
        String runtime = readMain("BaiduFaceRuntime.java");
        assertContains(runtime, "isCheckBlur = true");
        assertContains(runtime, "isOcclusion = true");
        assertContains(runtime, "isIllumination = true");
        assertContains(runtime, "isHeadPose = true");
        assertContains(runtime, "isBestImage = true");
        assertContains(runtime, "usingAlign = true");
        assertContains(runtime, "usingQuality = true");
        assertContains(runtime, "usingHeadPose = true");
        assertContains(runtime, "usingBestImage = true");
        assertContains(runtime, "FaceInfo.illum");
        assertContains(runtime, "accurateFace.illum");
    }

    @Test
    public void analysisUsesOwnedNv21RotationMirrorAndDestroysBeforeCallback() throws Exception {
        String runtime = readMain("BaiduFaceRuntime.java");
        assertContains(runtime, "BDFACE_IMAGE_TYPE_YUV_NV21");
        assertContains(runtime, "ownedBorrow.sdkRotationDegrees()");
        assertContains(runtime, "ownedBorrow.sdkMirror()");
        assertContains(runtime, "tracker.track(");
        assertContains(runtime, "detector.detect(");
        assertContains(runtime, "image.destory()");
        assertContains(runtime, "finally");
        assertContains(runtime, "ownedBorrow.close()");
        String analysisRun = methodSlice(runtime,
                "public void run() {", "public void cancel() {");
        assertTrue(analysisRun.indexOf("closeBorrowQuietly(ownedBorrow)")
                < analysisRun.indexOf("notifyAnalysisCompleted(callback, requestId, analysis)"));
        String completedDelivery = methodSlice(runtime,
                "private void notifyAnalysisCompleted(",
                "private void notifyAnalysisFailure(");
        assertContains(completedDelivery, "callbackDeliveryFence.deliver(");
        assertContains(completedDelivery, "callback.onCompleted(requestId, analysis)");
        assertFalse(publicAnalysisSlice(runtime).contains("FaceInfo"));
        assertFalse(publicAnalysisSlice(runtime).contains("BDFace"));
        assertFalse(publicAnalysisSlice(runtime).contains("byte[]"));
        assertFalse(publicAnalysisSlice(runtime).contains("landmark"));
    }

    @Test
    public void runtimeSerializesCallbacksAndCleansEachPhysicalInstanceOnce() throws Exception {
        String runtime = readMain("BaiduFaceRuntime.java");
        assertContains(runtime, "newSingleThreadScheduledExecutor");
        assertContains(runtime, "FaceQueue.getInstance().execute");
        assertContains(runtime, "runtimeExecutor.execute");
        assertContains(runtime, "generation");
        assertContains(runtime, "analysisInFlight");
        assertEquals(2, occurrences(runtime, ".uninitModel()"));
        assertEquals(1, occurrences(runtime, "image.destory()"));
        assertEquals(1, occurrences(runtime, "attempt.instance.destory()"));
        assertContains(runtime, "cleanupThenInitialize");
        assertContains(runtime, "ownsExecutor");
        assertContains(runtime, "QUEUE_VENDOR_BARRIER_THEN_CLEAN");
        assertContains(runtime, "START_AFTER_CLEANUP");
        assertContains(runtime, "RuntimeVendorDispatcher");
        assertContains(runtime, "executeVendorAfterRuntime");
        assertContains(runtime, "CallbackEpoch");
        assertContains(runtime, "AnalysisGate");
        assertContains(runtime, "cleanupBarrierSubmissionRejected");
        assertContains(runtime, "claimCleanupBarrierSubmission");
        assertContains(runtime, "cleanupAttemptFailed");
        assertContains(runtime, "RuntimeException | LinkageError");
    }

    @Test
    public void licenseUsesTheOfficialFaceAuthApiAndSdkManagedCache() throws Exception {
        String auth = readLicense("BdFaceAuth.java");
        assertContains(auth, "getLocalInfo(application)");
        assertContains(auth, ".licenseKey");
        assertContains(auth, "initLicenseOnLine(application, licenseKey");
        assertContains(auth, "BDFACE_LITE_POWER_NO_BIND");
        assertContains(auth, "setCoreConfigure");
        assertContains(auth, ", 2)");
        assertContains(auth, "OfficialFaceAuthOperation");
        assertContains(auth, "CommitGate");
        assertContains(auth, "tryCommit()");
        assertContains(auth, "RuntimeException | LinkageError");
        assertFalse(auth.contains("AndroidLicenser"));
        assertFalse(auth.contains("authFromMemory"));
        assertFalse(auth.contains("authFromFile"));
        assertFalse(auth.contains("BdHttpUtils"));
        assertFalse(auth.contains("BdFileUtils"));
        assertFalse(auth.contains("FaceQueue"));
        assertFalse(auth.contains("createInstance"));
        assertFalse(auth.contains("android.util.Log"));
        assertFalse(auth.contains("System.out"));
        assertFalse(auth.contains("System.err"));
        assertFalse(auth.contains("printStackTrace"));
        assertFalse(auth.contains("private final FaceAuth"));
        assertContains(auth, "new FaceAuth()");
        Path licenseRoot = projectRoot().resolve(
                "app/src/main/java/com/codex/lockertest/face/baidu/license");
        assertFalse(Files.exists(licenseRoot.resolve("util/BdHttpUtils.java")));
        assertFalse(Files.exists(licenseRoot.resolve("util/BdFileUtils.java")));
        assertFalse(Files.exists(licenseRoot.resolve("data/SituationData.java")));
    }

    @Test
    public void managerOwnsCharArrayTimerAndIdentitySubscriptions() throws Exception {
        String manager = readMain("BaiduFaceLicenseManager.java");
        assertContains(manager, "char[] ownedLicenseId");
        assertContains(manager, "Arrays.fill(ownedLicenseId");
        assertContains(manager, "15000L");
        assertContains(manager, "IdentityHashMap");
        assertContains(manager, "Subscription");
        assertContains(manager, "cancelUiWait()");
        assertContains(manager, "stateChangeHook");
        assertContains(manager, "notifyReady");
        assertContains(manager, "MAX_ACTIVATION_CHARACTERS");
        assertContains(manager, "tryBeginCommit");
        assertTrue(manager.indexOf("ownedLicenseId.length > MAX_ACTIVATION_CHARACTERS")
                < manager.indexOf("new String(ownedLicenseId)"));
        assertFalse(manager.contains("onReady(String"));
        assertFalse(manager.contains("deviceId"));
        assertFalse(manager.contains("error_msg"));
        assertFalse(manager.contains("android.util.Log"));
    }

    @Test
    public void bindingsAreOpaqueAndSubsystemIsApplicationScoped() throws Exception {
        String device = readMain("FaceDeviceBindingProvider.java");
        String process = readMain("FaceProcessBinding.java");
        String subsystem = readMain("FaceSubsystem.java");
        assertContains(device, "face-device-binding-v1");
        assertContains(device, "messageDigest.update((byte) 0)");
        assertEquals(2, occurrences(device, "messageDigest.update((byte) 0)"));
        assertFalse(device.contains("ByteArrayOutputStream"));
        assertContains(device, "SHA-256");
        assertContains(device, "Settings.Secure.ANDROID_ID");
        assertContains(device, "getApplicationContext()");
        assertContains(process, "new byte[16]");
        assertContains(process, "Arrays.fill");
        assertContains(subsystem, "FaceVerificationAssembly.create");
        assertContains(subsystem, "getApplicationContext()");
        assertContains(subsystem, "IdentityHashMap");
        assertContains(subsystem, "OrderedDrainQueue");
        assertContains(subsystem, "enqueueLocked");
        assertContains(subsystem, "drainNotifications");
        assertContains(subsystem, "pending.registration.active");
        assertContains(subsystem, "currentPolicyEpoch()");
        assertContains(subsystem, "public static final class Snapshot");
        assertContains(subsystem, "public static FaceSubsystem shared(Context context)");
        assertContains(subsystem, "public Snapshot snapshot()");
        assertContains(subsystem, "public BaiduFaceRuntime runtime()");
        assertFalse(subsystem.contains("Activity"));
        assertFalse(subsystem.contains("View"));
        assertFalse(subsystem.contains("Camera"));
        assertFalse(subsystem.contains("Serial"));
        assertFalse(subsystem.contains("ANDROID_ID"));
    }

    @Test
    public void subsystemRejectsRuntimeInitializationUntilLicenseIsReady()
            throws Exception {
        String subsystem = readMain("FaceSubsystem.java");
        String initialize = methodSlice(subsystem,
                "public BaiduFaceRuntime.Subscription initializeRuntime(",
                "public BaiduFaceRuntime runtime()");

        assertContains(initialize, "listener cannot be null");
        assertContains(initialize, "licenseManager.state()");
        assertContains(initialize, "FaceLicenseStateMachine.State.READY");
        assertContains(initialize, "notifyRuntimeLicenseUnavailableImmediately(listener)");
        assertContains(initialize, "return closedRuntimeSubscription()");
        assertContains(initialize, "faceRuntime.initialize(applicationContext, listener)");
        assertTrue(initialize.indexOf("licenseManager.state()")
                < initialize.indexOf("faceRuntime.initialize(applicationContext, listener)"));

        String notify = methodSlice(subsystem,
                "private static void notifyRuntimeLicenseUnavailableImmediately(",
                "private static BaiduFaceRuntime.Subscription closedRuntimeSubscription()");
        assertContains(notify, "listener.onFailure(0L");
        assertContains(notify, "BaiduFaceRuntime.SAFE_RUNTIME_ERROR");
        assertContains(notify, "人脸检测组件暂时不可用");
        assertContains(notify, "catch (RuntimeException | LinkageError ignored)");

        String closed = methodSlice(subsystem,
                "private static BaiduFaceRuntime.Subscription closedRuntimeSubscription()",
                "public BaiduFaceRuntime runtime()");
        assertContains(closed, "return () -> { }");
    }

    @Test
    public void runtimeExposesFrozenCallbacksAndFailsInvalidAnalysisExactlyOnce()
            throws Exception {
        String runtime = readMain("BaiduFaceRuntime.java");
        assertContains(runtime, "void onReady(long generation)");
        assertContains(runtime,
                "void onFailure(long generation, int safeCode, String safeMessage)");
        assertContains(runtime, "void onCompleted(long requestId, Analysis analysis)");
        assertContains(runtime,
                "void onFailure(long requestId, int safeCode, String safeMessage)");
        assertContains(runtime, "SAFE_ANALYSIS_ERROR");
        assertContains(runtime, "callback.onFailure(requestId");
        assertContains(runtime, "Float.isNaN");
        assertContains(runtime, "Float.isInfinite");
        assertContains(runtime, "stateChangeHook");
        assertContains(runtime, "notifyReady");
        assertContains(runtime,
                "public Subscription initialize(Context context, Listener listener)");
        assertContains(runtime,
                "public FaceCaptureSession.Cancellable analyze(long requestId");
        assertContains(runtime, "public FaceRuntimeStateMachine.State state()");
        assertContains(runtime, "public void release()");
    }

    @Test
    public void runtimeLinearizesInitializeRegistrationAndImmediateRejectWithRelease()
            throws Exception {
        String runtime = readMain("BaiduFaceRuntime.java");
        String initialize = methodSlice(runtime,
                "public Subscription initialize(Context context, Listener listener)",
                "public FaceCaptureSession.Cancellable analyze(long requestId");
        int initializeLock = initialize.indexOf("synchronized (lock)");
        int start = initialize.indexOf("machine.startInitialize()");
        int capture = initialize.indexOf("callbackEpoch.capture()");
        int registration = initialize.indexOf("registrations.put(");
        int stateSnapshot = initialize.indexOf("state = machine.state()");
        assertTrue(initializeLock >= 0);
        assertTrue(initializeLock < start);
        assertTrue(start < capture);
        assertTrue(capture < registration);
        assertTrue(registration < stateSnapshot);

        String analyze = methodSlice(runtime,
                "public FaceCaptureSession.Cancellable analyze(long requestId",
                "public FaceRuntimeStateMachine.State state()");
        assertContains(analyze, "rejectedCallbackEpoch = callbackEpoch.capture()");
        assertContains(analyze, "claimImmediateAnalysisNotification(");
        int close = analyze.indexOf("closeBorrowQuietly(ownedBorrow)",
                analyze.indexOf("if (reject)"));
        int claim = analyze.indexOf("claimImmediateAnalysisNotification(", close);
        int notify = analyze.indexOf("notifyAnalysisFailure(callback, requestId)", claim);
        assertTrue(close >= 0 && close < claim);
        assertTrue(claim < notify);
    }

    @Test
    public void taskSourcesContainNoCredentialShapedLiteralOrLogging() throws Exception {
        StringBuilder all = new StringBuilder();
        Path main = projectRoot().resolve("app/src/main/java/com/codex/lockertest/face");
        try (java.util.stream.Stream<Path> paths = Files.walk(main)) {
            paths.filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> {
                        try {
                            all.append(new String(Files.readAllBytes(path),
                                    StandardCharsets.UTF_8));
                        } catch (IOException failure) {
                            throw new RuntimeException(failure);
                        }
                    });
        }
        assertFalse(Pattern.compile(
                "(?<![A-Z0-9])[A-Z0-9]{4}(?:-[A-Z0-9]{4}){3}(?![A-Z0-9])")
                .matcher(all).find());
        assertFalse(all.toString().contains("setActiveLog"));
    }

    private static String publicAnalysisSlice(String source) {
        int start = source.indexOf("public static final class Analysis");
        int end = source.indexOf("public interface AnalysisCallback", start);
        assertTrue(start >= 0 && end > start);
        return source.substring(start, end);
    }

    private static String methodSlice(String source, String startToken, String endToken) {
        int start = source.indexOf(startToken);
        int end = source.indexOf(endToken, start);
        assertTrue(start >= 0 && end > start);
        return source.substring(start, end);
    }

    private static String readMain(String file) throws IOException {
        return read("app/src/main/java/com/codex/lockertest/face/" + file);
    }

    private static String readLicense(String file) throws IOException {
        return read("app/src/main/java/com/codex/lockertest/face/baidu/license/" + file);
    }

    private static String read(String relativePath) throws IOException {
        return new String(Files.readAllBytes(projectRoot().resolve(relativePath)),
                StandardCharsets.UTF_8);
    }

    private static Path projectRoot() {
        Path candidate = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        for (int depth = 0; depth < 8 && candidate != null; depth++) {
            if (Files.isRegularFile(candidate.resolve("app/build.gradle"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Cannot locate project root");
    }

    private static int occurrences(String source, String value) {
        int count = 0;
        int from = 0;
        while ((from = source.indexOf(value, from)) >= 0) {
            count++;
            from += value.length();
        }
        return count;
    }

    private static void assertContains(String source, String value) {
        assertTrue("missing source token: " + value, source.contains(value));
    }
}
