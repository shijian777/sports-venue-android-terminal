package com.codex.lockertest.ui.business;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;

import com.codex.lockertest.bootstrap.BaseSettingSnapshot;
import com.codex.lockertest.bootstrap.BootstrapSnapshot;
import com.codex.lockertest.bootstrap.DeviceRegistration;
import com.codex.lockertest.bootstrap.DeviceSerial;
import com.codex.lockertest.bootstrap.DeviceSerialProvider;
import com.codex.lockertest.business.BusinessService;
import com.codex.lockertest.business.UserInfoRequest;
import com.codex.lockertest.business.journey.OnlineCustomerCoordinator;
import com.codex.lockertest.business.journey.OnlineCustomerSession;
import com.codex.lockertest.business.journey.OnlineCustomerSnapshot;
import com.codex.lockertest.ui.IdCardScanSession;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Android glue for the separate network-only customer journey. No legacy flow or serial access. */
public final class OnlineCustomerHost implements AutoCloseable {
    public interface Ui {
        void show(View view);
        void home();
        void message(String message);
        default void maintenanceRequested(com.codex.lockertest.admin.OnlineMaintenanceGrant.Target target,
                java.util.function.BooleanSupplier authorized, Runnable revokeSession) {
            revokeSession.run();
        }
    }
    public interface ServiceFactory { BusinessService create(DeviceRegistration registration); }
    public interface FaceUploadFactory {
        com.codex.lockertest.business.FaceImageUploadAdapter create(DeviceRegistration registration);
    }
    /** Map an input device to documented userInfo type 2 or 4. Zero means unconfigured. */
    public interface ScanSourceResolver {
        int credentialType(int inputDeviceId);
        default List<ScanInputDevice> devices() { return Collections.emptyList(); }
        default boolean assign(int inputDeviceId, int type) { return false; }
        default boolean assign(ScanInputDevice device, int type) { return assign(device.id, type); }
    }
    public static final class ScanInputDevice {
        public final int id;
        public final String label;
        public final int type;
        public final boolean configurable;
        public final String identity;
        public ScanInputDevice(int id, String label, int type, boolean configurable) {
            this(id, label, type, configurable, null);
        }
        public ScanInputDevice(int id, String label, int type, boolean configurable, String identity) {
            this.id = id; this.label = label; this.type = type; this.configurable = configurable; this.identity = identity;
        }
    }

    private final Context context;
    private final Ui ui;
    private final ServiceFactory factory;
    private final ScanSourceResolver scanSources;
    private final DeviceSerialProvider serialProvider;
    private final FaceUploadFactory faceUploads;
    private com.codex.lockertest.face.verification.OnlineFaceVerificationClient faceVerifier;
    private final com.codex.lockertest.business.journey.OnlineUnlockExecutor unlockExecutor;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AndroidBusinessScheduler scheduler = new AndroidBusinessScheduler(handler);
    private final HidScanFrameDispatcher scans;
    private BusinessService service;
    private OnlineCustomerCoordinator coordinator;
    private OnlineCustomerSession session;
    private OnlineCabinetView panel;
    private OnlineAdminPanel adminPanel;
    private BootstrapSnapshot binding;
    private List<String> recognitionTypes = Collections.emptyList();
    private OnlineCustomerCoordinator.Purpose purpose = OnlineCustomerCoordinator.Purpose.OPEN_CABINET;
    private long epoch;
    private long scanMessageAt;
    private Runnable expiry;
    private boolean showingJourney;
    private boolean confirmationBusy;
    private boolean blockedUnavailable;
    private long panelGeneration;
    private boolean closed;

    public OnlineCustomerHost(Context context, ServiceFactory factory, ScanSourceResolver scanSources, Ui ui) {
        this(context, factory, scanSources, ui, null);
    }
    public OnlineCustomerHost(Context context, ServiceFactory factory, ScanSourceResolver scanSources, Ui ui,
            DeviceSerialProvider serialProvider) {
        this(context, factory, scanSources, ui, serialProvider, null);
    }
    public OnlineCustomerHost(Context context, ServiceFactory factory, ScanSourceResolver scanSources, Ui ui,
            DeviceSerialProvider serialProvider,
            com.codex.lockertest.business.journey.OnlineUnlockExecutor unlockExecutor) {
        this(context, factory, scanSources, ui, serialProvider, unlockExecutor, null);
    }
    public OnlineCustomerHost(Context context, ServiceFactory factory, ScanSourceResolver scanSources, Ui ui,
            DeviceSerialProvider serialProvider,
            com.codex.lockertest.business.journey.OnlineUnlockExecutor unlockExecutor,
            FaceUploadFactory faceUploads) {
        if (context == null || factory == null || scanSources == null || ui == null)
            throw new IllegalArgumentException("Online customer dependencies required");
        this.context = context;
        this.factory = factory;
        this.scanSources = scanSources;
        this.ui = ui;
        this.serialProvider = serialProvider;
        this.unlockExecutor = unlockExecutor;
        this.faceUploads = faceUploads;
        this.scans = new HidScanFrameDispatcher(4096, new HidScanFrameDispatcher.Scheduler() {
            @Override public void postDelayed(Runnable task, long delayMillis) {
                handler.postDelayed(task, delayMillis);
            }
            @Override public void removeCallbacks(Runnable task) {
                handler.removeCallbacks(task);
            }
        }, OnlineCustomerHost.this::finishScan);
    }

    public void bind(BootstrapSnapshot snapshot) {
        if (closed || snapshot == binding) return;
        unbind();
        if (snapshot == null || snapshot.phase() != BootstrapSnapshot.Phase.READY_READ_ONLY) return;
        try {
            DeviceRegistration registration = snapshot.registration();
            List<OnlineCustomerSession.Region> regions = new ArrayList<>();
            for (BaseSettingSnapshot.Area area : snapshot.baseSetting().areas())
                regions.add(new OnlineCustomerSession.Region(area.areaId(), area.areaName()));
            session = new OnlineCustomerSession(snapshot.generation(), registration.merchantCode(),
                    registration.deviceNo(), regions);
            service = factory.create(registration);
            if (service == null) { unbind(); return; }
            final long capturedEpoch = epoch;
            coordinator = new OnlineCustomerCoordinator(service, scheduler, SystemClock::elapsedRealtime,
                    next -> handler.post(() -> deliver(capturedEpoch, next)), unlockExecutor);
            recognitionTypes = new ArrayList<>(snapshot.baseSetting().recognitionTypes());
            binding = snapshot;
        } catch (RuntimeException | LinkageError failure) {
            unbind();
        }
    }

    public boolean ready() { return !closed && coordinator != null && session != null; }
    public boolean boundTo(BootstrapSnapshot snapshot) { return ready() && binding == snapshot; }
    public boolean hasJourney() {
        return showingJourney || faceVerifier != null || adminPanel != null || purpose == OnlineCustomerCoordinator.Purpose.RETURN_CABINET;
    }
    public boolean showingJourney() { return showingJourney || faceVerifier != null || adminPanel != null; }
    public boolean showingAdmin() { return adminPanel != null; }
    public boolean returning() { return purpose == OnlineCustomerCoordinator.Purpose.RETURN_CABINET; }
    public String homeStatus() {
        return purpose == OnlineCustomerCoordinator.Purpose.RETURN_CABINET
                ? "还柜查询：请输入手机号和取柜码，或刷卡/扫码验证"
                : "请输入手机号和取柜码；已配置的扫码器/读卡器可直接识别";
    }

    public void beginAdmin() {
        if (!ready()) { ui.message("设备尚未连接服务器，暂不能在线登录管理员"); return; }
        cancel();
        try {
            DeviceSerial serial = serialProvider == null ? null : serialProvider.read();
            if (serial == null || !serial.isAvailable()) { ui.message("无法读取本机序列号，暂不能进入在线管理"); return; }
            adminPanel = new OnlineAdminPanel(context, service, scanSources, serial.value(),
                    "1".equals(binding.basicData().dynamicVerificationCode()), () -> {
                        adminPanel = null; ui.home();
                    }, new OnlineAdminPanel.MaintenanceActions() {
                        @Override public void onSerialRequested(java.util.function.BooleanSupplier authorized, Runnable revoke) {
                            ui.maintenanceRequested(com.codex.lockertest.admin.OnlineMaintenanceGrant.Target.SERIAL, authorized, revoke);
                        }
                        @Override public void onFaceSdkRequested(java.util.function.BooleanSupplier authorized, Runnable revoke) {
                            ui.maintenanceRequested(com.codex.lockertest.admin.OnlineMaintenanceGrant.Target.FACE_SDK, authorized, revoke);
                        }
                    });
            ui.show(adminPanel);
        } catch (RuntimeException | LinkageError failure) {
            if (adminPanel != null) adminPanel.close();
            adminPanel = null;
            ui.message("管理员配置页面暂时不可用，请重试");
        }
    }

    /** Only a retained, still-authorized panel can be resumed after an internal activity. */
    public boolean resumeAdmin() {
        if (closed || adminPanel == null) return false;
        OnlineAdminPanel expected = adminPanel;
        expected.resumeMenu();
        if (adminPanel != expected) return false;
        ui.show(expected);
        return true;
    }

    public void prepareReturn() {
        if (!ready()) return;
        cancel();
        purpose = OnlineCustomerCoordinator.Purpose.RETURN_CABINET;
        ui.home();
    }

    /** Reserves the shared authentication lane; camera remains owned by Main's SDK controller. */
    public com.codex.lockertest.face.verification.OnlineFaceVerificationClient beginFace() {
        if (!ready() || showingJourney || adminPanel != null || faceVerifier != null) return null;
        if (!recognitionTypes.contains("5")) { ui.message("服务器未开放人脸识别"); return null; }
        if (faceUploads == null) { ui.message("人脸图片上传组件尚未配置"); return null; }
        try {
            faceVerifier = new com.codex.lockertest.face.verification.OnlineFaceVerificationClient(
                    new com.codex.lockertest.business.FaceAuthenticationPipeline(service,
                            faceUploads.create(binding.registration())));
            clearScan();
            return faceVerifier;
        } catch (RuntimeException | LinkageError failure) {
            ui.message("人脸上传组件暂时不可用，请重试");
            return null;
        }
    }
    public boolean isCurrentFace(com.codex.lockertest.face.verification.OnlineFaceVerificationClient client) {
        return ready() && client != null && faceVerifier == client;
    }
    public boolean finishFace(com.codex.lockertest.face.verification.OnlineFaceVerificationClient client,
            com.codex.lockertest.face.verification.FaceVerificationResult result) {
        if (!isCurrentFace(client)) return false;
        com.codex.lockertest.business.AuthenticatedUser user = client.consume(result);
        closeFace();
        if (user == null || !user.isCustomerReady()) return false;
        start(null, user);
        return showingJourney;
    }
    private void closeFace() {
        if (faceVerifier != null) faceVerifier.close();
        faceVerifier = null;
    }

    public void submitPhone(String phone, String code) {
        if (!ready() || showingJourney || faceVerifier != null || adminPanel != null) return;
        if (!recognitionTypes.contains("1")) { ui.message("服务器未开放手机号验证"); return; }
        try { start(UserInfoRequest.phone(phone, code)); }
        catch (IllegalArgumentException invalid) { ui.message("请完整输入手机号和取柜码"); }
    }

    /** A source-aware scanner entry. Unknown reader types never send credentials to the server. */
    public boolean scannerKey(int deviceId, int character, boolean terminator, int action, int repeat) {
        if (!ready()) return false;
        if (adminPanel != null) return false;
        if (showingJourney || faceVerifier != null || action != KeyEvent.ACTION_DOWN || repeat != 0) return true;
        int type;
        try { type = scanSources.credentialType(deviceId); }
        catch (RuntimeException error) { type = 0; }
        if ((type != 2 && type != 4) || !recognitionTypes.contains(Integer.toString(type))) {
            clearScan();
            long now = SystemClock.elapsedRealtime();
            if (scanMessageAt == 0 || now - scanMessageAt > 1500) {
                scanMessageAt = now;
                ui.message(type == 2 || type == 4 ? "服务器未开放此识别方式"
                        : "尚未配置读卡器/扫码器类型，请先使用手机号和取柜码");
            }
            return true;
        }
        final int knownType = type;
        scans.key(deviceId, character, terminator, knownType);
        return true;
    }

    private void finishScan(IdCardScanSession.Completion result, int type) {
        if (!ready() || showingJourney || faceVerifier != null || result == null) return;
        if (!result.isValidFrame()) { ui.message("读取凭证失败，请重新识别"); return; }
        try { start(type == 2 ? UserInfoRequest.qr(result.value()) : UserInfoRequest.card(result.value())); }
        catch (IllegalArgumentException invalid) { ui.message("读取凭证失败，请重新识别"); }
    }

    private void start(UserInfoRequest request) {
        start(request, null);
    }
    private void start(UserInfoRequest request, com.codex.lockertest.business.AuthenticatedUser verifiedUser) {
        if (!ready() || showingJourney || faceVerifier != null) return;
        confirmationBusy = false;
        blockedUnavailable = false;
        final long capturedPanelGeneration = ++panelGeneration;
        panel = new OnlineCabinetView(context, new OnlineCabinetView.Listener() {
            @Override public void onRegion(long areaId) {
                if (isCurrentPanel(capturedPanelGeneration) && !blockedUnavailable && ready()) {
                    coordinator.changeRegion(areaId);
                }
            }
            @Override public void onPage(int page) {
                if (isCurrentPanel(capturedPanelGeneration) && !blockedUnavailable && ready()) {
                    coordinator.changePage(page);
                }
            }
            @Override public void onRetry() {
                if (!isCurrentPanel(capturedPanelGeneration)) return;
                if (!ready() || !coordinator.retry()) { cancel(); ui.home(); }
            }
            @Override public void onHome() {
                if (!isCurrentPanel(capturedPanelGeneration)) return;
                cancel();
                ui.home();
            }
            @Override public void onConfirmOpen(long fcId) {
                confirmOpen(capturedPanelGeneration, fcId);
            }
            @Override public void onConfirmReturn(long fcId) {
                if (!isCurrentPanel(capturedPanelGeneration) || blockedUnavailable || !ready()
                        || coordinator.snapshot().state() != OnlineCustomerSnapshot.State.BROWSING_USED) return;
                coordinator.confirmReturn(fcId);
                if (isCurrentPanel(capturedPanelGeneration) && coordinator != null) {
                    panel.render(coordinator.snapshot());
                    scheduleExpiry(coordinator.snapshot());
                }
            }
            @Override public void onInteraction() {
                if (isCurrentPanel(capturedPanelGeneration) && !blockedUnavailable
                        && coordinator != null
                        && (coordinator.snapshot().state() == OnlineCustomerSnapshot.State.BROWSING_OPEN
                        || coordinator.snapshot().state() == OnlineCustomerSnapshot.State.BROWSING_USED))
                    scheduleExpiry(coordinator.snapshot());
            }
        }, unlockExecutor != null, identityBackdrop());
        showingJourney = true;
        boolean started = verifiedUser == null ? coordinator.start(session, purpose, request)
                : coordinator.startAuthenticated(session, purpose, verifiedUser);
        if (!started) {
            showingJourney = false;
            panel = null;
            panelGeneration++;
            ui.message("当前操作未结束，请稍候重试");
            return;
        }
        clearScan();
        panel.render(coordinator.snapshot());
        scheduleExpiry(coordinator.snapshot());
        ui.show(panel);
    }

    private View identityBackdrop() {
        com.codex.lockertest.ui.BootstrapHomePresentation presentation =
                com.codex.lockertest.ui.BootstrapHomePresentation.createForOnlineBrowsing(
                        new com.codex.lockertest.ui.BootstrapReadinessMapper().map(binding), binding, true);
        com.codex.lockertest.ui.ZipHomeView backdrop = new com.codex.lockertest.ui.ZipHomeView(context,
                (method, value) -> com.codex.lockertest.runtime.CredentialAdmission.rejected("显示背景不接受凭证"),
                method -> true, presentation);
        backdrop.showOnlineStatus(homeStatus());
        backdrop.setReturnMode(returning());
        return backdrop;
    }

    private void confirmOpen(long capturedPanelGeneration, long fcId) {
        if (!isCurrentPanel(capturedPanelGeneration) || blockedUnavailable
                || confirmationBusy || coordinator == null
                || coordinator.snapshot().state() != OnlineCustomerSnapshot.State.BROWSING_OPEN) {
            return;
        }
        confirmationBusy = true;
        boolean launched = coordinator.confirmOpen(fcId);
        if (!isCurrentPanel(capturedPanelGeneration) || coordinator == null) return;
        OnlineCustomerSnapshot current = coordinator.snapshot();
        if (!launched && current.state() == OnlineCustomerSnapshot.State.BROWSING_OPEN) {
            blockedUnavailable = true;
            panel.showPhysicalUnavailableModal();
            scheduleBlockedExpiry(capturedPanelGeneration);
        }
    }

    private boolean isCurrentPanel(long capturedPanelGeneration) {
        return showingJourney && panel != null && capturedPanelGeneration == panelGeneration;
    }

    private void deliver(long capturedEpoch, OnlineCustomerSnapshot next) {
        if (closed || capturedEpoch != epoch || !showingJourney || panel == null
                || coordinator == null || next != coordinator.snapshot()
                || session == null || next.bootstrapGeneration() != session.bootstrapGeneration()) return;
        panel.render(next);
        scheduleExpiry(next);
    }

    private void scheduleExpiry(OnlineCustomerSnapshot next) {
        if (blockedUnavailable) return;
        if (expiry != null) handler.removeCallbacks(expiry);
        expiry = null;
        if (next.isBusy()) return;
        final long expectedEpoch = epoch;
        expiry = () -> {
            expiry = null;
            if (expectedEpoch != epoch || !showingJourney || coordinator == null
                    || next != coordinator.snapshot()) return;
            cancel();
            ui.home();
        };
        boolean shortExpiry = next.state() == OnlineCustomerSnapshot.State.FAILED
                || next.state() == OnlineCustomerSnapshot.State.OPEN_SUCCESS
                || next.state() == OnlineCustomerSnapshot.State.OPEN_FAILED
                || next.state() == OnlineCustomerSnapshot.State.RETURN_SUCCEEDED
                || next.state() == OnlineCustomerSnapshot.State.RETURN_FAILED;
        handler.postDelayed(expiry, shortExpiry ? 8_000 : 60_000);
    }

    private void scheduleBlockedExpiry(long capturedPanelGeneration) {
        if (expiry != null) handler.removeCallbacks(expiry);
        expiry = () -> {
            expiry = null;
            if (!isCurrentPanel(capturedPanelGeneration) || !blockedUnavailable) return;
            cancel();
            ui.home();
        };
        handler.postDelayed(expiry, 8_000);
    }

    public void cancel() {
        closeFace();
        if (adminPanel != null) adminPanel.close();
        adminPanel = null;
        if (expiry != null) handler.removeCallbacks(expiry);
        expiry = null;
        panelGeneration++;
        confirmationBusy = false;
        blockedUnavailable = false;
        showingJourney = false;
        panel = null;
        purpose = OnlineCustomerCoordinator.Purpose.OPEN_CABINET;
        clearScan();
        if (coordinator != null) coordinator.cancel();
    }

    public void unbind() {
        epoch++;
        cancel();
        if (coordinator != null) coordinator.close();
        coordinator = null;
        if (service instanceof AutoCloseable) {
            try { ((AutoCloseable) service).close(); }
            catch (Exception ignored) { /* No credential payload may escape teardown. */ }
        }
        service = null;
        session = null;
        binding = null;
        recognitionTypes = Collections.emptyList();
    }

    private void clearScan() {
        scans.reset();
    }

    @Override public void close() {
        if (closed) return;
        unbind();
        closed = true;
        scheduler.close();
    }
}
