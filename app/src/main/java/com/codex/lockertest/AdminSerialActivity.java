package com.codex.lockertest;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.codex.lockertest.admin.AdminCapability;
import com.codex.lockertest.admin.AdminCapabilityAssembly;
import com.codex.lockertest.admin.AdminCapabilityPolicy;
import com.codex.lockertest.integration.GenerationGate;
import com.codex.lockertest.integration.PersistentSerialConnectionPolicy;
import com.codex.lockertest.integration.ProcessSerialGatewayOwner;
import com.codex.lockertest.model.LockerTarget;
import com.codex.lockertest.model.LockerZone;
import com.codex.lockertest.protocol.FeedbackPolarity;
import com.codex.lockertest.protocol.HexCodec;
import com.codex.lockertest.protocol.LockerResponseDetector;
import com.codex.lockertest.runtime.RuntimeSerialLog;
import com.codex.lockertest.serial.SerialConfig;
import com.codex.lockertest.serial.SerialConfigSelection;
import com.codex.lockertest.serial.SerialDeviceScanner;
import com.codex.lockertest.serial.SerialGateway;
import com.codex.lockertest.serial.SerialSessionState;
import com.codex.lockertest.ui.ZipKioskShell;
import com.codex.lockertest.ui.zip.ZipAdminScreenRouter;
import com.codex.lockertest.ui.zip.ZipScreenAsset;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AdminSerialActivity extends com.codex.lockertest.admin.OnlineMaintenanceActivity {
    @Override protected com.codex.lockertest.admin.OnlineMaintenanceGrant.Target maintenanceTarget() {
        return com.codex.lockertest.admin.OnlineMaintenanceGrant.Target.SERIAL;
    }
    private static final int ADMIN_PANEL_LEFT = 20;
    private static final int ADMIN_PANEL_TOP = 105;
    private static final int ADMIN_PANEL_RIGHT = 1260;
    private static final int ADMIN_PANEL_BOTTOM = 710;
    private static final ProcessSerialGatewayOwner<SerialGateway> SERIAL_GATEWAY_OWNER =
            ProcessSerialGatewayOwner.shared();
    private static final PersistentSerialConnectionPolicy SERIAL_CONNECTION_POLICY =
            PersistentSerialConnectionPolicy.shared();
    private static final ZipAdminScreenRouter.SerialA1RetryFence A1_RETRY_FENCE =
            new ZipAdminScreenRouter.SerialA1RetryFence();
    private static final int NAVY = Color.rgb(9, 42, 83);
    private static final int BLUE = Color.rgb(20, 102, 209);
    private static final int GREEN = Color.rgb(31, 190, 143);
    private static final int DARK_GREEN = Color.rgb(9, 116, 85);
    private static final int RED = Color.rgb(222, 63, 71);
    private static final int AMBER = Color.rgb(226, 134, 27);
    private static final int TEXT = Color.rgb(51, 72, 80);
    private static final int MUTED = Color.rgb(104, 127, 133);
    private static final String UNLOCK_HEX = "8A0101119B";
    private static final LockerTarget ADMIN_A1 = new LockerTarget(
            LockerZone.A, 1, 1, FeedbackPolarity.SHORT_WHEN_LOCKED);

    private static final String[] BAUD_RATES = {
            "1200", "2400", "4800", "9600", "19200", "38400", "57600", "115200"
    };
    private static final String[] DATA_BITS = {"5", "6", "7", "8"};
    private static final String[] STOP_BITS = {"1", "2"};
    private static final String[] PARITY = {"None", "Odd", "Even"};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private AdminCapabilityPolicy adminCapabilityPolicy;
    private final SimpleDateFormat timeFormat =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.CHINA);
    private final RuntimeSerialLog logBuffer = RuntimeSerialLog.shared();
    private final GenerationGate gatewayGate = new GenerationGate();
    private final LockerResponseDetector adminA1Matcher =
            new LockerResponseDetector(ADMIN_A1);
    private final ZipAdminScreenRouter.SerialRequestGate<
            ProcessSerialGatewayOwner.Lease<SerialGateway>> requestGate =
            new ZipAdminScreenRouter.SerialRequestGate<
                    ProcessSerialGatewayOwner.Lease<SerialGateway>>();

    private ProcessSerialGatewayOwner.Lease<SerialGateway> gatewayLease;
    private SerialGateway.Subscription gatewaySubscription;
    private long gatewayGeneration;
    private RuntimeSerialLog.Listener runtimeLogListener;
    private ExecutorService scanExecutor;
    private TextView connectionChip;
    private TextView activeConfigText;
    private Spinner portSpinner;
    private Spinner baudSpinner;
    private Spinner dataSpinner;
    private Spinner stopSpinner;
    private Spinner paritySpinner;
    private Spinner flowSpinner;
    private Button refreshButton;
    private Button connectionButton;
    private Button sendButton;
    private Button unlockButton;
    private Button visibleBackButton;
    private EditText commandInput;
    private TextView resultTitle;
    private TextView resultDetail;
    private TextView logText;
    private ScrollView logScroll;
    private ZipKioskShell pixelShell;
    private FrameLayout serialBody;
    private FrameLayout promptScrim;
    private TextView promptTitle;
    private TextView promptDetail;
    private Button promptPrimary;
    private Button promptBack;
    private SerialSessionState.Phase currentSerialPhase =
            SerialSessionState.Phase.CLOSED;
    private ZipAdminScreenRouter.SerialEvent visibleSerialEvent =
            ZipAdminScreenRouter.SerialEvent.NONE;
    private ZipAdminScreenRouter.SerialRequestGate.Request<
            ProcessSerialGatewayOwner.Lease<SerialGateway>> pendingRequest;
    private Runnable pendingTimeout;
    private boolean manualCloseInFlight;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (isFinishing()) return;
        adminCapabilityPolicy = AdminCapabilityAssembly.create();
        configureWindow();
        setContentView(buildScreen());
        setDefaultSelections();
        renderResult("等待操作", "刷新串口列表，选择参数后点击“打开串口”", MUTED);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (isFinishing()) return;
        adminA1Matcher.reset();
        clearPendingRequest();
        visibleSerialEvent = ZipAdminScreenRouter.SerialEvent.NONE;
        manualCloseInFlight = false;
        gatewayGeneration = gatewayGate.activate();
        final long generation = gatewayGeneration;
        scanExecutor = Executors.newSingleThreadExecutor();
        currentSerialPhase = SerialSessionState.Phase.CLOSED;
        setSerialUiForPhase(currentSerialPhase, null);
        ProcessSerialGatewayOwner.Lease<SerialGateway> expectedLease =
                acquireAdminGateway(generation);
        runtimeLogListener = snapshot -> {
            final String safeSnapshot = snapshot == null ? "" : new String(snapshot);
            handler.post(() -> {
                if (isCurrentAdminCallback(generation, expectedLease)) {
                    renderLogSnapshot(safeSnapshot);
                }
            });
        };
        logBuffer.addListener(runtimeLogListener);
        scanPorts();
    }

    @Override
    protected void onStop() {
        adminA1Matcher.reset();
        clearPendingRequest();
        RuntimeSerialLog.Listener logListener = runtimeLogListener;
        runtimeLogListener = null;
        if (logListener != null) {
            logBuffer.removeListener(logListener);
        }
        gatewayGate.invalidate();
        gatewayGeneration = 0L;
        detachAdminGateway();
        ExecutorService scanner = scanExecutor;
        scanExecutor = null;
        if (scanner != null) {
            scanner.shutdownNow();
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        clearPendingRequest();
        gatewayGate.invalidate();
        gatewayGeneration = 0L;
        detachAdminGateway();
        super.onDestroy();
    }

    private ProcessSerialGatewayOwner.Lease<SerialGateway> acquireAdminGateway(
            long generation) {
        if (!gatewayGate.accepts(generation)) {
            return null;
        }
        ProcessSerialGatewayOwner.Lease<SerialGateway> lease =
                SERIAL_GATEWAY_OWNER.acquire(
                        ProcessSerialGatewayOwner.Role.ADMIN,
                        SerialGateway::new);
        gatewayLease = lease;
        adminA1Matcher.reset();
        requestGate.clear();
        pendingRequest = null;
        AdminGatewaySnapshot snapshot = SERIAL_GATEWAY_OWNER.withGateway(
                lease,
                current -> {
                    gatewaySubscription = current.subscribe(
                            new GatewayListener(generation, lease));
                    return new AdminGatewaySnapshot(
                            current.phase(), current.getActiveConfig());
                },
                null);
        if (snapshot == null) {
            appendLog("[Error] 进程串口会话不可用");
        } else {
            renderPersistentGatewayState(generation, lease, snapshot);
        }
        return lease;
    }

    private void detachAdminGateway() {
        SerialGateway.Subscription subscription = gatewaySubscription;
        ProcessSerialGatewayOwner.Lease<SerialGateway> lease = gatewayLease;
        adminA1Matcher.reset();
        clearPendingRequest();
        gatewaySubscription = null;
        gatewayLease = null;
        if (subscription != null) {
            subscription.unsubscribe();
        }
        SERIAL_GATEWAY_OWNER.relinquish(lease);
    }

    private void renderPersistentGatewayState(
            long generation,
            ProcessSerialGatewayOwner.Lease<SerialGateway> expectedLease,
            AdminGatewaySnapshot snapshot) {
        if (!isCurrentAdminCallback(generation, expectedLease)) {
            return;
        }
        renderSerialPhase(snapshot.phase, snapshot.activeConfig, false);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUi();
        }
    }

    private void onConnectionChanged(
            long generation,
            ProcessSerialGatewayOwner.Lease<SerialGateway> expectedLease,
            boolean connected,
            String detail) {
        if (!isCurrentAdminCallback(generation, expectedLease)) {
            return;
        }
        AdminGatewaySnapshot snapshot = SERIAL_GATEWAY_OWNER.withGateway(
                expectedLease,
                current -> new AdminGatewaySnapshot(
                        current.phase(), current.getActiveConfig()),
                null);
        if (!isCurrentAdminCallback(generation, expectedLease)) {
            return;
        }
        if (snapshot == null) {
            showSerialFailure("串口会话不可用", "无法读取当前串口状态");
            return;
        }
        SerialSessionState.Phase previous = currentSerialPhase;
        boolean expectedManualClose = manualCloseInFlight;
        boolean failedTransition = snapshot.phase == SerialSessionState.Phase.CLOSED
                && (previous == SerialSessionState.Phase.OPENING
                || (previous == SerialSessionState.Phase.OPEN && !expectedManualClose)
                || (previous == SerialSessionState.Phase.CLOSING && !expectedManualClose));
        if (snapshot.phase == SerialSessionState.Phase.OPEN
                || snapshot.phase == SerialSessionState.Phase.CLOSED) {
            manualCloseInFlight = false;
        }
        renderSerialPhase(snapshot.phase, snapshot.activeConfig, failedTransition);
    }

    private void onDiagnostic(
            long generation,
            ProcessSerialGatewayOwner.Lease<SerialGateway> expectedLease,
            String detail) {
        if (!isCurrentAdminCallback(generation, expectedLease)) {
            return;
        }
        // SerialGateway persists diagnostics in the process-wide bounded runtime log.
    }

    private void onSent(
            long generation,
            ProcessSerialGatewayOwner.Lease<SerialGateway> expectedLease,
            byte[] bytes) {
        if (!isCurrentAdminCallback(generation, expectedLease)) {
            return;
        }
        ZipAdminScreenRouter.SerialRequestGate.Request<
                ProcessSerialGatewayOwner.Lease<SerialGateway>> request = pendingRequest;
        if (request == null) return;
        ZipAdminScreenRouter.SerialRequestGate.Completion completion =
                requestGate.onWritten(request, generation, expectedLease,
                        request.kind(), bytes);
        if (completion == ZipAdminScreenRouter.SerialRequestGate.Completion.IGNORED) return;
        if (completion
                == ZipAdminScreenRouter.SerialRequestGate.Completion.WRITE_COMPLETED) {
            finishPendingRequest(request);
            visibleSerialEvent = ZipAdminScreenRouter.SerialEvent.WRITE_ACCEPTED;
            renderSerialRoute(visibleSerialEvent);
            renderResult("发送完成", HexCodec.format(bytes), BLUE);
        } else {
            renderResult("指令已发送", "等待锁控板精确回包", BLUE);
        }
    }

    private void onReceived(
            long generation,
            ProcessSerialGatewayOwner.Lease<SerialGateway> expectedLease,
            byte[] bytes) {
        if (!isCurrentAdminCallback(generation, expectedLease)) {
            return;
        }
        ZipAdminScreenRouter.SerialRequestGate.Request<
                ProcessSerialGatewayOwner.Lease<SerialGateway>> request = pendingRequest;
        if (request == null
                || request.kind()
                != ZipAdminScreenRouter.SerialRequestGate.Kind.A1_TEST) return;
        LockerResponseDetector.Result result = adminA1Matcher.append(bytes, bytes.length);
        if (result == LockerResponseDetector.Result.NONE) return;
        final ZipAdminScreenRouter.SerialRequestGate.MatcherEvent event;
        if (result == LockerResponseDetector.Result.SUCCESS) {
            event = ZipAdminScreenRouter.SerialRequestGate.MatcherEvent.SUCCESS;
        } else if (result == LockerResponseDetector.Result.FAILURE) {
            event = ZipAdminScreenRouter.SerialRequestGate.MatcherEvent.FAILURE;
        } else {
            return;
        }
        ZipAdminScreenRouter.SerialRequestGate.Completion completion =
                requestGate.onMatcher(request, generation, expectedLease,
                        request.kind(), request.command(), event);
        if (completion
                == ZipAdminScreenRouter.SerialRequestGate.Completion.MATCHER_SUCCESS) {
            finishPendingRequest(request);
            showSerialSuccess();
        } else if (completion
                == ZipAdminScreenRouter.SerialRequestGate.Completion.MATCHER_FAILURE) {
            finishPendingRequest(request);
            showSerialFailure(ZipAdminScreenRouter.SerialEvent.MATCHER_FAILURE,
                    "锁控板拒绝开锁", "收到与当前 A1 指令精确匹配的失败回包");
        }
    }

    private void onSendFailed(
            long generation,
            ProcessSerialGatewayOwner.Lease<SerialGateway> expectedLease,
            byte[] payload,
            String detail) {
        if (!isCurrentAdminCallback(generation, expectedLease)) {
            return;
        }
        ZipAdminScreenRouter.SerialRequestGate.Request<
                ProcessSerialGatewayOwner.Lease<SerialGateway>> request = pendingRequest;
        if (request == null) return;
        ZipAdminScreenRouter.SerialRequestGate.Completion completion =
                requestGate.onSendFailed(request, generation, expectedLease,
                        request.kind(), payload);
        if (completion
                != ZipAdminScreenRouter.SerialRequestGate.Completion.TRANSIENT_FAILURE) {
            return;
        }
        if (request.kind() == ZipAdminScreenRouter.SerialRequestGate.Kind.A1_TEST) {
            A1_RETRY_FENCE.onAmbiguousTerminal();
        }
        finishPendingRequest(request);
        showSerialFailure("指令发送失败",
                detail == null || detail.trim().isEmpty() ? "串口写入失败" : detail);
    }

    private boolean isCurrentAdminCallback(
            long generation,
            ProcessSerialGatewayOwner.Lease<SerialGateway> expectedLease) {
        return maintenanceAuthorized() && gatewayGeneration == generation
                && gatewayLease == expectedLease
                && gatewayGate.accepts(generation)
                && SERIAL_GATEWAY_OWNER.isCurrent(expectedLease);
    }

    private void renderSerialPhase(SerialSessionState.Phase phase,
            SerialConfig activeConfig, boolean failedTransition) {
        if (phase == null) {
            showSerialFailure("串口状态异常", "无法确认当前串口阶段");
            return;
        }
        currentSerialPhase = phase;
        if (phase != SerialSessionState.Phase.OPEN) {
            clearPendingRequest();
            adminA1Matcher.reset();
        }
        A1_RETRY_FENCE.observe(phase);
        if (failedTransition) {
            showSerialFailure("串口操作失败", "串口已安全回到未连接状态");
            return;
        }
        if (visibleSerialEvent == ZipAdminScreenRouter.SerialEvent.MATCHER_SUCCESS
                || visibleSerialEvent == ZipAdminScreenRouter.SerialEvent.MATCHER_FAILURE
                || visibleSerialEvent == ZipAdminScreenRouter.SerialEvent.TRANSIENT_FAILURE) {
            setSerialUiForPhase(phase, activeConfig);
            renderSerialRoute(visibleSerialEvent);
            return;
        }
        visibleSerialEvent = ZipAdminScreenRouter.SerialEvent.NONE;
        renderSerialRoute(visibleSerialEvent);
        setSerialUiForPhase(phase, activeConfig);
    }

    private void renderSerialRoute(ZipAdminScreenRouter.SerialEvent event) {
        visibleSerialEvent = event;
        ZipAdminScreenRouter.ActionAvailability actions =
                ZipAdminScreenRouter.actionsForSerial(currentSerialPhase, event);
        if (pixelShell != null) {
            pixelShell.setScreenAsset(
                    ZipAdminScreenRouter.assetForSerial(currentSerialPhase, event));
        }
        boolean success = event == ZipAdminScreenRouter.SerialEvent.MATCHER_SUCCESS;
        boolean failure = event == ZipAdminScreenRouter.SerialEvent.MATCHER_FAILURE
                || event == ZipAdminScreenRouter.SerialEvent.TRANSIENT_FAILURE;
        if (promptScrim != null) {
            promptScrim.setVisibility(success || failure ? View.VISIBLE : View.GONE);
        }
        if (visibleBackButton != null) {
            visibleBackButton.setVisibility(
                    actions.nativeBackVisible() ? View.VISIBLE : View.GONE);
            visibleBackButton.setEnabled(actions.nativeBackVisible());
        }
        if (promptBack != null) {
            promptBack.setVisibility(
                    actions.promptBackVisible() ? View.VISIBLE : View.GONE);
            promptBack.setEnabled(actions.promptBackVisible());
        }
        refreshSerialActionAvailability();
    }

    private void setSerialUiForPhase(SerialSessionState.Phase phase,
            SerialConfig activeConfig) {
        boolean open = phase == SerialSessionState.Phase.OPEN;
        boolean busy = phase == SerialSessionState.Phase.OPENING
                || phase == SerialSessionState.Phase.CLOSING;
        boolean configurable = phase == SerialSessionState.Phase.CLOSED;
        portSpinner.setEnabled(configurable);
        baudSpinner.setEnabled(configurable);
        dataSpinner.setEnabled(configurable);
        stopSpinner.setEnabled(configurable);
        paritySpinner.setEnabled(configurable);
        refreshButton.setEnabled(configurable);
        if (open && activeConfig != null) applyActiveConfig(activeConfig);
        if (open) {
            styleChip(connectionChip, "● 串口已连接", DARK_GREEN,
                    Color.rgb(216, 250, 237));
            activeConfigText.setText(activeConfig == null
                    ? "当前串口已打开" : activeConfig.describe());
            renderResult("串口已打开", "可以发送合法 HEX 或测试 A1 开锁", GREEN);
        } else if (busy) {
            styleChip(connectionChip, "● 串口忙", BLUE, Color.WHITE);
            activeConfigText.setText(phase == SerialSessionState.Phase.OPENING
                    ? "正在打开串口" : "正在关闭串口");
            renderResult("串口操作进行中", "请等待物理串口完成当前操作", BLUE);
        } else {
            styleChip(connectionChip, "● 串口未连接", RED, Color.rgb(255, 232, 232));
            activeConfigText.setText(phase == SerialSessionState.Phase.DISPOSED
                    ? "串口会话不可用" : "当前未打开串口");
            renderResult(phase == SerialSessionState.Phase.DISPOSED
                            ? "串口会话不可用" : "等待操作",
                    phase == SerialSessionState.Phase.DISPOSED
                            ? "请返回首页后重试" : "确认参数后打开串口", MUTED);
        }
        connectionButton.setText(open ? "关闭串口"
                : phase == SerialSessionState.Phase.OPENING ? "正在打开..."
                : phase == SerialSessionState.Phase.CLOSING ? "正在关闭..." : "打开串口");
        connectionButton.setBackground(roundedSolid(open ? RED : BLUE,
                10, Color.TRANSPARENT, 0));
        refreshSerialActionAvailability();
    }

    private void refreshSerialActionAvailability() {
        boolean resultVisible = visibleSerialEvent
                == ZipAdminScreenRouter.SerialEvent.MATCHER_SUCCESS
                || visibleSerialEvent == ZipAdminScreenRouter.SerialEvent.MATCHER_FAILURE
                || visibleSerialEvent == ZipAdminScreenRouter.SerialEvent.TRANSIENT_FAILURE;
        boolean pending = requestGate.hasPending();
        boolean open = currentSerialPhase == SerialSessionState.Phase.OPEN;
        boolean closed = currentSerialPhase == SerialSessionState.Phase.CLOSED;
        connectionButton.setEnabled(!resultVisible && !pending && (open || closed));
        sendButton.setEnabled(!resultVisible && !pending && open);
        unlockButton.setEnabled(!resultVisible && !pending && open
                && A1_RETRY_FENCE.canBeginA1());
    }

    private void showSerialSuccess() {
        visibleSerialEvent = ZipAdminScreenRouter.SerialEvent.MATCHER_SUCCESS;
        promptTitle.setText("开锁成功");
        promptTitle.setTextColor(DARK_GREEN);
        promptDetail.setText("收到当前请求的精确成功回包 8A 01 01 00 8A");
        promptPrimary.setText("确认");
        FrameLayout.LayoutParams primary = (FrameLayout.LayoutParams)
                promptPrimary.getLayoutParams();
        primary.leftMargin = unit(210);
        promptPrimary.setLayoutParams(primary);
        renderSerialRoute(visibleSerialEvent);
    }

    private void showSerialFailure(String title, String detail) {
        showSerialFailure(ZipAdminScreenRouter.SerialEvent.TRANSIENT_FAILURE,
                title, detail);
    }

    private void showSerialFailure(ZipAdminScreenRouter.SerialEvent event,
            String title, String detail) {
        visibleSerialEvent = event;
        if (promptTitle != null) {
            promptTitle.setText(title == null ? "串口操作失败" : title);
            promptTitle.setTextColor(RED);
            promptDetail.setText(detail == null ? "请确认设备后重试" : detail);
            promptPrimary.setText("关闭提示");
            FrameLayout.LayoutParams primary = (FrameLayout.LayoutParams)
                    promptPrimary.getLayoutParams();
            primary.leftMargin = unit(115);
            promptPrimary.setLayoutParams(primary);
        }
        renderSerialRoute(event);
    }

    private void dismissSerialResult() {
        visibleSerialEvent = ZipAdminScreenRouter.SerialEvent.NONE;
        renderSerialRoute(visibleSerialEvent);
        SerialConfig activeConfig = SERIAL_GATEWAY_OWNER.withGateway(
                gatewayLease, SerialGateway::getActiveConfig, null);
        setSerialUiForPhase(currentSerialPhase, activeConfig);
    }

    private void clearPendingRequest() {
        ZipAdminScreenRouter.SerialRequestGate.Request<
                ProcessSerialGatewayOwner.Lease<SerialGateway>> request = pendingRequest;
        if (request != null
                && request.kind() == ZipAdminScreenRouter.SerialRequestGate.Kind.A1_TEST) {
            A1_RETRY_FENCE.onAmbiguousTerminal();
        }
        Runnable timeout = pendingTimeout;
        pendingTimeout = null;
        if (timeout != null) handler.removeCallbacks(timeout);
        requestGate.clear();
        pendingRequest = null;
    }

    private void finishPendingRequest(ZipAdminScreenRouter.SerialRequestGate.Request<
            ProcessSerialGatewayOwner.Lease<SerialGateway>> request) {
        if (pendingRequest != request) return;
        Runnable timeout = pendingTimeout;
        pendingTimeout = null;
        if (timeout != null) handler.removeCallbacks(timeout);
        pendingRequest = null;
        adminA1Matcher.reset();
    }

    private void armRequestTimeout(ZipAdminScreenRouter.SerialRequestGate.Request<
            ProcessSerialGatewayOwner.Lease<SerialGateway>> request) {
        Runnable timeout = () -> {
            if (pendingRequest != request) return;
            ZipAdminScreenRouter.SerialRequestGate.Completion completion =
                    requestGate.onTimeout(request, request.generation(), request.lease(),
                            request.kind(), request.command());
            if (completion
                    != ZipAdminScreenRouter.SerialRequestGate.Completion.TIMEOUT) return;
            if (request.kind() == ZipAdminScreenRouter.SerialRequestGate.Kind.A1_TEST) {
                A1_RETRY_FENCE.onAmbiguousTerminal();
            }
            finishPendingRequest(request);
            showSerialFailure("设备通信超时", "3 秒内未收到当前请求的精确回包");
        };
        pendingTimeout = timeout;
        handler.postDelayed(timeout,
                ZipAdminScreenRouter.SerialRequestGate.TIMEOUT_MILLIS);
    }

    private View buildScreen() {
        pixelShell = new ZipKioskShell(
                this, ZipScreenAsset.ADMIN_SERIAL_DISCONNECTED);
        pixelShell.setOnReturnClickListener(null);
        pixelShell.setReturnEnabled(false);

        serialBody = new FrameLayout(this);
        serialBody.setBackgroundColor(Color.WHITE);
        FrameLayout.LayoutParams bodyParams = new FrameLayout.LayoutParams(
                unit(ADMIN_PANEL_RIGHT - ADMIN_PANEL_LEFT),
                unit(ADMIN_PANEL_BOTTOM - ADMIN_PANEL_TOP));
        bodyParams.leftMargin = unit(ADMIN_PANEL_LEFT);
        bodyParams.topMargin = unit(ADMIN_PANEL_TOP);
        pixelShell.pixelContent().addView(serialBody, bodyParams);

        visibleBackButton = button("返回管理页",
                Color.rgb(132, 147, 150), Color.WHITE);
        visibleBackButton.setContentDescription("返回管理页");
        visibleBackButton.setOnClickListener(view -> finish());
        FrameLayout.LayoutParams visibleBackParams = new FrameLayout.LayoutParams(
                unit(176), unit(38));
        visibleBackParams.leftMargin = unit(1050);
        visibleBackParams.topMargin = unit(110);
        pixelShell.pixelOverlay().addView(visibleBackButton, visibleBackParams);

        LinearLayout controls = buildControlCard();
        FrameLayout.LayoutParams controlParams = new FrameLayout.LayoutParams(
                unit(700), unit(535));
        controlParams.leftMargin = unit(30);
        controlParams.topMargin = unit(60);
        serialBody.addView(controls, controlParams);

        LinearLayout logs = buildLogCard();
        FrameLayout.LayoutParams logParams = new FrameLayout.LayoutParams(
                unit(460), unit(535));
        logParams.leftMargin = unit(750);
        logParams.topMargin = unit(60);
        serialBody.addView(logs, logParams);

        buildSerialPrompt();
        return pixelShell;
    }

    private void buildSerialPrompt() {
        promptScrim = new FrameLayout(this);
        promptScrim.setBackgroundColor(Color.WHITE);
        promptScrim.setVisibility(View.GONE);
        serialBody.addView(promptScrim, match());

        FrameLayout card = new FrameLayout(this);
        card.setBackground(roundedSolid(Color.WHITE, 16,
                Color.rgb(40, 194, 153), 2));
        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                unit(570), unit(330));
        cardParams.leftMargin = unit(335);
        cardParams.topMargin = unit(120);
        promptScrim.addView(card, cardParams);

        promptTitle = text("串口操作结果", 26, NAVY, Typeface.BOLD);
        promptTitle.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams titleParams = new FrameLayout.LayoutParams(
                unit(510), unit(54));
        titleParams.leftMargin = unit(30);
        titleParams.topMargin = unit(55);
        card.addView(promptTitle, titleParams);

        promptDetail = text("", 16, TEXT, Typeface.NORMAL);
        promptDetail.setGravity(Gravity.CENTER);
        promptDetail.setMaxLines(4);
        FrameLayout.LayoutParams detailParams = new FrameLayout.LayoutParams(
                unit(490), unit(92));
        detailParams.leftMargin = unit(40);
        detailParams.topMargin = unit(120);
        card.addView(promptDetail, detailParams);

        promptPrimary = button("确认", GREEN, Color.WHITE);
        promptPrimary.setOnClickListener(view -> dismissSerialResult());
        FrameLayout.LayoutParams primaryParams = new FrameLayout.LayoutParams(
                unit(150), unit(46));
        primaryParams.leftMargin = unit(115);
        primaryParams.topMargin = unit(255);
        card.addView(promptPrimary, primaryParams);

        promptBack = button("返回管理页", Color.rgb(132, 147, 150), Color.WHITE);
        promptBack.setOnClickListener(view -> finish());
        FrameLayout.LayoutParams backParams = new FrameLayout.LayoutParams(
                unit(150), unit(46));
        backParams.leftMargin = unit(305);
        backParams.topMargin = unit(255);
        card.addView(promptBack, backParams);
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(24), 0, dp(24), 0);
        header.setBackground(roundedGradient(
                new int[]{Color.rgb(26, 153, 221), Color.rgb(36, 199, 151)}, 16));

        TextView emblem = text("▣", 34, Color.WHITE, Typeface.BOLD);
        header.addView(emblem, new LinearLayout.LayoutParams(dp(52),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(text("智能更衣柜串口调试", 27, Color.WHITE, Typeface.BOLD));
        titles.addView(text("SMART LOCKER · SERIAL ASSISTANT", 11,
                Color.argb(220, 255, 255, 255), Typeface.NORMAL));
        header.addView(titles, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        connectionChip = text("● 串口未连接", 14, RED, Typeface.BOLD);
        connectionChip.setGravity(Gravity.CENTER);
        connectionChip.setBackground(roundedSolid(Color.WHITE, 24, Color.TRANSPARENT, 0));
        header.addView(connectionChip, new LinearLayout.LayoutParams(dp(168), dp(40)));
        return header;
    }

    private LinearLayout buildControlCard() {
        LinearLayout card = card();

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(sectionTitle("串口配置"), new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        connectionChip = text("● 串口未连接", 12, RED, Typeface.BOLD);
        connectionChip.setGravity(Gravity.CENTER);
        connectionChip.setBackground(roundedSolid(
                Color.rgb(255, 232, 232), 20, Color.TRANSPARENT, 0));
        LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(
                dp(126), dp(38));
        chipParams.rightMargin = dp(8);
        titleRow.addView(connectionChip, chipParams);
        refreshButton = button("刷新串口", Color.rgb(225, 238, 244), TEXT);
        refreshButton.setTextSize(13);
        refreshButton.setOnClickListener(view -> scanPorts());
        titleRow.addView(refreshButton, new LinearLayout.LayoutParams(dp(100), dp(38)));
        card.addView(titleRow);

        TextView warning = text("测试本应用前，请彻底退出原串口调试助手", 13,
                RED, Typeface.BOLD);
        LinearLayout.LayoutParams warningParams = matchWrap();
        warningParams.topMargin = dp(3);
        warningParams.bottomMargin = dp(7);
        card.addView(warning, warningParams);

        portSpinner = spinner();
        card.addView(field("串口设备", portSpinner, 52), matchWrap());

        LinearLayout parameterRow = new LinearLayout(this);
        parameterRow.setOrientation(LinearLayout.HORIZONTAL);
        baudSpinner = spinner(BAUD_RATES);
        dataSpinner = spinner(DATA_BITS);
        stopSpinner = spinner(STOP_BITS);
        paritySpinner = spinner(PARITY);
        flowSpinner = spinner(new String[]{"None"});
        flowSpinner.setEnabled(false);
        addWeightedField(parameterRow, "波特率", baudSpinner);
        addWeightedField(parameterRow, "数据位", dataSpinner);
        addWeightedField(parameterRow, "停止位", stopSpinner);
        addWeightedField(parameterRow, "校验位", paritySpinner);
        addWeightedField(parameterRow, "流控", flowSpinner);
        LinearLayout.LayoutParams parameterParams = matchWrap();
        parameterParams.topMargin = dp(6);
        card.addView(parameterRow, parameterParams);

        connectionButton = button("打开串口", BLUE, Color.WHITE);
        connectionButton.setOnClickListener(view -> toggleConnection());
        LinearLayout.LayoutParams connectionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        connectionParams.topMargin = dp(9);
        card.addView(connectionButton, connectionParams);

        activeConfigText = text("当前未打开串口", 12, MUTED, Typeface.NORMAL);
        activeConfigText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams activeParams = matchWrap();
        activeParams.topMargin = dp(4);
        card.addView(activeConfigText, activeParams);

        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dividerParams.topMargin = dp(7);
        dividerParams.bottomMargin = dp(7);
        View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(221, 233, 236));
        card.addView(divider, dividerParams);

        card.addView(text("HEX 发送", 17, NAVY, Typeface.BOLD));
        commandInput = new EditText(this);
        commandInput.setText(UNLOCK_HEX);
        commandInput.setTextSize(20);
        commandInput.setTextColor(NAVY);
        commandInput.setGravity(Gravity.CENTER);
        commandInput.setSingleLine(true);
        commandInput.setSelectAllOnFocus(true);
        commandInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        commandInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(256)});
        commandInput.setPadding(dp(12), 0, dp(12), 0);
        commandInput.setBackground(roundedSolid(Color.WHITE, 10, GREEN, 2));
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        inputParams.topMargin = dp(5);
        card.addView(commandInput, inputParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        sendButton = button("发送 HEX", Color.rgb(62, 126, 214), Color.WHITE);
        sendButton.setOnClickListener(view -> sendFromInput());
        unlockButton = button("一键开锁", GREEN, Color.WHITE);
        unlockButton.setOnClickListener(view -> sendUnlock());
        LinearLayout.LayoutParams actionLeft = new LinearLayout.LayoutParams(0, dp(48), 1f);
        actionLeft.rightMargin = dp(8);
        actions.addView(sendButton, actionLeft);
        actions.addView(unlockButton, new LinearLayout.LayoutParams(0, dp(48), 1f));
        LinearLayout.LayoutParams actionsParams = matchWrap();
        actionsParams.topMargin = dp(7);
        card.addView(actions, actionsParams);

        LinearLayout result = new LinearLayout(this);
        result.setOrientation(LinearLayout.VERTICAL);
        result.setGravity(Gravity.CENTER);
        result.setPadding(dp(10), dp(6), dp(10), dp(6));
        result.setBackground(roundedSolid(Color.rgb(246, 250, 250), 12,
                Color.rgb(220, 233, 235), 1));
        resultTitle = text("", 21, MUTED, Typeface.BOLD);
        resultTitle.setGravity(Gravity.CENTER);
        resultDetail = text("", 12, MUTED, Typeface.NORMAL);
        resultDetail.setGravity(Gravity.CENTER);
        resultDetail.setMaxLines(3);
        result.addView(resultTitle);
        result.addView(resultDetail);
        LinearLayout.LayoutParams resultParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        resultParams.topMargin = dp(7);
        card.addView(result, resultParams);
        return card;
    }

    private LinearLayout buildLogCard() {
        LinearLayout card = card();
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(sectionTitle("串口收发与诊断"), new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button clear = button("清空日志", Color.rgb(229, 238, 241), TEXT);
        clear.setTextSize(13);
        clear.setOnClickListener(view -> {
            logBuffer.clear();
            logText.setText("");
        });
        titleRow.addView(clear, new LinearLayout.LayoutParams(dp(96), dp(38)));
        card.addView(titleRow);

        TextView legend = text("开锁成功返回：8A 01 01 00 8A", 13,
                DARK_GREEN, Typeface.BOLD);
        LinearLayout.LayoutParams legendParams = matchWrap();
        legendParams.topMargin = dp(4);
        legendParams.bottomMargin = dp(8);
        card.addView(legend, legendParams);

        logScroll = new ScrollView(this);
        logScroll.setFillViewport(true);
        logScroll.setBackground(roundedSolid(Color.rgb(8, 28, 52), 11,
                Color.rgb(35, 102, 145), 1));
        logText = text("", 13, Color.rgb(184, 242, 224), Typeface.NORMAL);
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setPadding(dp(14), dp(12), dp(14), dp(12));
        logText.setTextIsSelectable(true);
        logScroll.addView(logText, match());
        card.addView(logScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return card;
    }

    private void scanPorts() {
        ExecutorService scanner = scanExecutor;
        ProcessSerialGatewayOwner.Lease<SerialGateway> expectedLease = gatewayLease;
        long generation = gatewayGeneration;
        if (scanner == null || scanner.isShutdown() || expectedLease == null) {
            return;
        }
        if (!isCurrentAdminCallback(generation, expectedLease)) {
            return;
        }
        refreshButton.setEnabled(false);
        appendLog("[Status] 正在扫描 /dev 下的串口设备...");
        scanner.execute(() -> {
            List<String> found = SerialDeviceScanner.scanSystemDevices();
            if (found.isEmpty()) {
                found = new ArrayList<>();
                found.add("/dev/ttyS0");
            }
            final List<String> result = new ArrayList<>(found);
            handler.post(() -> {
                if (!isCurrentAdminCallback(generation, expectedLease)) {
                    return;
                }
                if (scanExecutor != scanner) {
                    return;
                }
                SerialConfig activeConfig = SERIAL_GATEWAY_OWNER.withGateway(
                        expectedLease, SerialGateway::getActiveConfig, null);
                if (!isCurrentAdminCallback(generation, expectedLease)) {
                    return;
                }
                List<String> visiblePorts = new ArrayList<>(result);
                if (activeConfig != null && !visiblePorts.contains(activeConfig.getPath())) {
                    visiblePorts.add(0, activeConfig.getPath());
                }
                setSpinnerValues(portSpinner, visiblePorts.toArray(new String[0]));
                if (activeConfig == null) {
                    selectValue(portSpinner, "/dev/ttyS0");
                } else {
                    applyActiveConfig(activeConfig);
                }
                refreshButton.setEnabled(true);
                appendLog("[Status] 扫描完成，共 " + result.size() + " 个候选串口");
            });
        });
    }

    private void toggleConnection() {
        if (!maintenanceAuthorized()) { finish(); return; }
        if (!adminCapabilityPolicy.allows(
                AdminCapability.MANAGE_SERIAL_CONNECTION, isNetworkOnline(), false)) {
            showSerialFailure("串口管理不可用", "当前维护会话不允许管理串口连接");
            return;
        }
        if (requestGate.hasPending()
                || currentSerialPhase == SerialSessionState.Phase.OPENING
                || currentSerialPhase == SerialSessionState.Phase.CLOSING) {
            Toast.makeText(this, "串口正在处理当前操作，请稍候",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        final SerialConfig selected;
        try {
            selected = selectedConfig();
        } catch (IllegalArgumentException exception) {
            renderResult("参数错误", exception.getMessage(), RED);
            Toast.makeText(this, exception.getMessage(), Toast.LENGTH_SHORT).show();
            return;
        }
        ToggleResult result = SERIAL_GATEWAY_OWNER.withGateway(
                gatewayLease,
                current -> {
                    if (current.isConnected()) {
                        return current.closePort()
                                ? ToggleResult.CLOSE_ACCEPTED : ToggleResult.BUSY;
                    }
                    boolean accepted = current.open(selected);
                    ZipAdminScreenRouter.OpenDispatchResult dispatch =
                            ZipAdminScreenRouter.classifyOpenDispatch(
                                    accepted, current.phase());
                    if (dispatch == ZipAdminScreenRouter.OpenDispatchResult.OPEN_ACCEPTED) {
                        return ToggleResult.OPEN_ACCEPTED;
                    }
                    if (dispatch == ZipAdminScreenRouter.OpenDispatchResult.BUSY) {
                        return ToggleResult.BUSY;
                    }
                    if (dispatch
                            == ZipAdminScreenRouter.OpenDispatchResult.OPEN_REJECTED) {
                        return ToggleResult.OPEN_REJECTED;
                    }
                    return ToggleResult.STALE;
                },
                ToggleResult.STALE);
        if (result == ToggleResult.CLOSE_ACCEPTED) {
            manualCloseInFlight = true;
            SERIAL_CONNECTION_POLICY.onManualCloseAccepted();
            renderSerialPhase(SerialSessionState.Phase.CLOSING, null, false);
        } else if (result == ToggleResult.OPEN_ACCEPTED) {
            manualCloseInFlight = false;
            renderSerialPhase(SerialSessionState.Phase.OPENING, selected, false);
        } else if (result == ToggleResult.BUSY) {
            Toast.makeText(this, "串口正在打开或关闭，请稍候",
                    Toast.LENGTH_SHORT).show();
        } else if (result == ToggleResult.OPEN_REJECTED) {
            manualCloseInFlight = false;
            showSerialFailure("串口打开失败", "串口任务未被接受，请确认设备后重试");
        }
    }

    private SerialConfig selectedConfig() {
        Object selected = portSpinner.getSelectedItem();
        if (selected == null) {
            throw new IllegalArgumentException("请先刷新并选择串口设备");
        }
        return new SerialConfig(
                selected.toString(),
                Integer.parseInt(baudSpinner.getSelectedItem().toString()),
                paritySpinner.getSelectedItemPosition(),
                Integer.parseInt(dataSpinner.getSelectedItem().toString()),
                Integer.parseInt(stopSpinner.getSelectedItem().toString()),
                0);
    }

    private void sendFromInput() {
        try {
            byte[] payload = HexCodec.decode(commandInput.getText().toString());
            sendBytes(payload, ZipAdminScreenRouter.classifySerialCommand(payload));
        } catch (IllegalArgumentException exception) {
            renderResult("HEX 格式错误", exception.getMessage(), RED);
            Toast.makeText(this, exception.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void sendUnlock() {
        sendBytes(HexCodec.decode(UNLOCK_HEX),
                ZipAdminScreenRouter.SerialRequestGate.Kind.A1_TEST);
    }

    private void sendBytes(byte[] bytes,
            ZipAdminScreenRouter.SerialRequestGate.Kind kind) {
        if (!adminCapabilityPolicy.allows(
                AdminCapability.OPEN_LOCKER, isNetworkOnline(), false)) {
            showSerialFailure("物理操作不可用", "需要联网并取得服务器管理员授权");
            return;
        }
        ProcessSerialGatewayOwner.Lease<SerialGateway> lease = gatewayLease;
        long generation = gatewayGeneration;
        if (bytes == null || bytes.length == 0 || lease == null
                || kind == null || currentSerialPhase != SerialSessionState.Phase.OPEN
                || !isCurrentAdminCallback(generation, lease)) {
            showSerialFailure("无法发送", "请先成功打开串口");
            return;
        }
        if (kind == ZipAdminScreenRouter.SerialRequestGate.Kind.A1_TEST
                && !A1_RETRY_FENCE.canBeginA1()) {
            showSerialFailure("A1 测试已暂停",
                    "上次请求结果不明确，请手动关闭串口并重新打开后再试");
            return;
        }
        ZipAdminScreenRouter.SerialRequestGate.Request<
                ProcessSerialGatewayOwner.Lease<SerialGateway>> request =
                requestGate.begin(generation, lease, kind, bytes);
        if (request == null) {
            Toast.makeText(this, "已有串口请求正在等待完成",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        boolean accepted = SERIAL_GATEWAY_OWNER.withGateway(
                lease,
                current -> current.phase() == SerialSessionState.Phase.OPEN
                        && current.send(bytes),
                false);
        if (!accepted || !requestGate.accept(
                request, generation, lease, kind, bytes)) {
            requestGate.reject(request, generation, lease, kind, bytes);
            if (kind == ZipAdminScreenRouter.SerialRequestGate.Kind.A1_TEST) {
                A1_RETRY_FENCE.onAmbiguousTerminal();
            }
            showSerialFailure("指令发送失败", "串口未接受当前请求");
            return;
        }
        pendingRequest = request;
        if (kind == ZipAdminScreenRouter.SerialRequestGate.Kind.A1_TEST) {
            adminA1Matcher.reset();
            renderResult("等待锁控板回包", "A1 开锁指令已进入串行发送队列", BLUE);
        } else {
            renderResult("正在发送", HexCodec.format(bytes), BLUE);
        }
        visibleSerialEvent = ZipAdminScreenRouter.SerialEvent.WRITE_ACCEPTED;
        renderSerialRoute(visibleSerialEvent);
        armRequestTimeout(request);
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

    private void setDefaultSelections() {
        selectValue(baudSpinner, "9600");
        selectValue(dataSpinner, "8");
        selectValue(stopSpinner, "1");
        selectValue(paritySpinner, "None");
    }

    private void applyActiveConfig(SerialConfig config) {
        if (config == null) {
            return;
        }
        SerialConfigSelection selection = SerialConfigSelection.from(config);
        if (!containsValue(portSpinner, selection.path())) {
            setSpinnerValues(portSpinner, new String[]{selection.path()});
        }
        selectValue(portSpinner, selection.path());
        selectValue(baudSpinner, selection.baudRate());
        selectValue(dataSpinner, selection.dataBits());
        selectValue(stopSpinner, selection.stopBits());
        selectValue(paritySpinner, selection.parity());
        selectValue(flowSpinner, selection.flowControl());
    }

    private static boolean containsValue(Spinner spinner, String value) {
        for (int index = 0; index < spinner.getCount(); index++) {
            if (value.equals(String.valueOf(spinner.getItemAtPosition(index)))) {
                return true;
            }
        }
        return false;
    }

    private static void selectValue(Spinner spinner, String value) {
        for (int index = 0; index < spinner.getCount(); index++) {
            if (value.equals(String.valueOf(spinner.getItemAtPosition(index)))) {
                spinner.setSelection(index);
                return;
            }
        }
    }

    private Spinner spinner(String[] values) {
        Spinner spinner = spinner();
        setSpinnerValues(spinner, values);
        return spinner;
    }

    private Spinner spinner() {
        Spinner spinner = new Spinner(this, Spinner.MODE_DROPDOWN);
        spinner.setBackground(roundedSolid(Color.WHITE, 8, Color.rgb(159, 193, 203), 1));
        spinner.setPadding(dp(7), 0, dp(7), 0);
        return spinner;
    }

    private void setSpinnerValues(Spinner spinner, String[] values) {
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(
                this, android.R.layout.simple_spinner_item, values) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setTextColor(NAVY);
                view.setTextSize(13);
                view.setGravity(Gravity.CENTER_VERTICAL);
                return view;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
    }

    private LinearLayout field(String label, Spinner spinner, int heightDp) {
        LinearLayout field = new LinearLayout(this);
        field.setOrientation(LinearLayout.VERTICAL);
        field.addView(text(label, 11, MUTED, Typeface.BOLD));
        LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp - 16));
        valueParams.topMargin = dp(2);
        field.addView(spinner, valueParams);
        return field;
    }

    private void addWeightedField(LinearLayout row, String label, Spinner spinner) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(52), 1f);
        params.rightMargin = dp(5);
        row.addView(field(label, spinner, 52), params);
    }

    private void renderResult(String title, String detail, int color) {
        resultTitle.setText(title);
        resultTitle.setTextColor(color);
        resultDetail.setText(detail == null ? "" : detail);
    }

    private void appendLog(String message) {
        if (logText == null) {
            return;
        }
        logBuffer.append(timeFormat.format(new Date()) + "  " + message);
        logText.setText(logBuffer.snapshot());
        logScroll.fullScroll(View.FOCUS_DOWN);
    }

    private void renderLogSnapshot(String snapshot) {
        if (logText == null) {
            return;
        }
        logText.setText(snapshot);
        logScroll.fullScroll(View.FOCUS_DOWN);
    }

    private final class GatewayListener implements SerialGateway.Listener {
        private final long generation;
        private final ProcessSerialGatewayOwner.Lease<SerialGateway> expectedLease;

        GatewayListener(
                long generation,
                ProcessSerialGatewayOwner.Lease<SerialGateway> expectedLease) {
            this.generation = generation;
            this.expectedLease = expectedLease;
        }

        @Override
        public void onConnectionChanged(boolean connected, String detail) {
            final String safeDetail = detail == null ? null : new String(detail);
            handler.post(() -> AdminSerialActivity.this.onConnectionChanged(
                    generation, expectedLease, connected, safeDetail));
        }

        @Override
        public void onDiagnostic(String detail) {
            final String safeDetail = detail == null ? "" : new String(detail);
            handler.post(() -> AdminSerialActivity.this.onDiagnostic(
                    generation, expectedLease, safeDetail));
        }

        @Override
        public void onSent(byte[] bytes) {
            final byte[] safePayload = Arrays.copyOf(bytes, bytes.length);
            handler.post(() -> AdminSerialActivity.this.onSent(
                    generation,
                    expectedLease,
                    Arrays.copyOf(safePayload, safePayload.length)));
        }

        @Override
        public void onReceived(byte[] bytes) {
            final byte[] safePayload = Arrays.copyOf(bytes, bytes.length);
            handler.post(() -> AdminSerialActivity.this.onReceived(
                    generation,
                    expectedLease,
                    Arrays.copyOf(safePayload, safePayload.length)));
        }

        @Override
        public void onSendFailed(byte[] payload, String detail) {
            final byte[] safePayload = Arrays.copyOf(payload, payload.length);
            final String safeDetail = detail == null ? "" : new String(detail);
            handler.post(() -> AdminSerialActivity.this.onSendFailed(
                    generation,
                    expectedLease,
                    Arrays.copyOf(safePayload, safePayload.length),
                    safeDetail));
        }
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

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(15), dp(20), dp(15));
        card.setBackground(roundedSolid(Color.argb(250, 255, 255, 255), 16,
                Color.rgb(121, 206, 226), 1));
        return card;
    }

    private TextView sectionTitle(String value) {
        return text(value, 21, NAVY, Typeface.BOLD);
    }

    private TextView text(String value, float size, int color, int style) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans-serif", style));
        view.setIncludeFontPadding(false);
        return view;
    }

    private Button button(String value, int background, int foreground) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(16);
        button.setTextColor(foreground);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setBackground(roundedSolid(background, 10, Color.TRANSPARENT, 0));
        return button;
    }

    private void styleChip(TextView chip, String value, int foreground, int background) {
        chip.setText(value);
        chip.setTextColor(foreground);
        chip.setBackground(roundedSolid(background, 24, Color.TRANSPARENT, 0));
    }

    private GradientDrawable roundedSolid(int color, int radiusDp, int stroke, int strokeDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) {
            drawable.setStroke(dp(strokeDp), stroke);
        }
        return drawable;
    }

    private GradientDrawable roundedGradient(int[] colors, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, colors);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private int dp(int value) {
        return unit(value);
    }

    private int unit(float designUnits) {
        return ZipKioskShell.unit(this, designUnits);
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private enum ToggleResult {
        OPEN_ACCEPTED,
        CLOSE_ACCEPTED,
        BUSY,
        OPEN_REJECTED,
        STALE
    }

    private static final class AdminGatewaySnapshot {
        final SerialSessionState.Phase phase;
        final SerialConfig activeConfig;

        AdminGatewaySnapshot(
                SerialSessionState.Phase phase,
                SerialConfig activeConfig) {
            this.phase = phase;
            this.activeConfig = activeConfig;
        }
    }

    private static final class TechBackgroundView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        TechBackgroundView(Activity activity) {
            super(activity);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            paint.setShader(new LinearGradient(
                    0, 0, getWidth(), getHeight(),
                    new int[]{Color.rgb(5, 42, 94), Color.rgb(18, 114, 176),
                            Color.rgb(220, 247, 245)},
                    new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
            paint.setShader(null);
            paint.setStrokeWidth(1f);
            paint.setColor(Color.argb(45, 120, 235, 255));
            int grid = Math.max(36, getWidth() / 28);
            for (int x = 0; x < getWidth(); x += grid) {
                canvas.drawLine(x, 0, x, getHeight(), paint);
            }
            for (int y = 0; y < getHeight(); y += grid) {
                canvas.drawLine(0, y, getWidth(), y, paint);
            }
        }
    }
}
