package com.codex.lockertest.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ScrollView;

import com.codex.lockertest.model.UnlockMethod;
import com.codex.lockertest.model.FeatureAvailability;
import com.codex.lockertest.runtime.CredentialAdmissionPolicy;
import com.codex.lockertest.ui.zip.ZipBrand;
import com.codex.lockertest.ui.zip.ZipCustomerScreenRouter;
import com.codex.lockertest.ui.zip.ZipPixelShell;
import com.codex.lockertest.ui.zip.ZipScreenAsset;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Native interaction layer for the pixel-backed ZIP customer home screens 1-4. */
public final class ZipHomeView extends FrameLayout {
    public interface PairedCredentialListener {
        void onSubmit(String phone, String pickupCode);
    }

    public interface Listener {
        void onCredentialSubmit(UnlockMethod method, String rawValue);

        default void onFaceRequested() { }

        void onUnavailableSelected(UnlockMethod method);

        default void onReturnRequested() { }

        default void onEnrollmentRequested() { }

        default void onBootstrapRetryRequested() { }

        void onAdminRequested();
    }

    private enum PassiveCredentialState {
        WAITING,
        READING,
        INVALID_COUNTDOWN
    }

    private static final String[][] KEYS = {
            {"1", "2", "3"},
            {"4", "5", "6"},
            {"7", "8", "9"},
            {"清空", "0", "删除"}
    };
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat clockFormat =
            new SimpleDateFormat("yyyy/MM/dd  HH:mm", Locale.CHINA);
    private final BootstrapHomePresentation bootstrapPresentation;
    private final List<View> customerActionViews = new ArrayList<>();
    private final HomeCredentialModel credentialModel;
    private final ClockAdminTapGate clockAdminTapGate = new ClockAdminTapGate();
    private final FinalHomeActions finalHomeActions;
    private final ZipPixelShell pixelShell;
    private final FrameLayout underlyingInteractionLayer;
    private final Button phoneField;
    private final Button passwordField;
    private final Button confirmButton;
    private TextView passiveScanStatus;
    private final FrameLayout modalInterceptionLayer;
    private final FrameLayout readingErrorModal;
    private final FrameLayout unregisteredModal;
    private final FrameLayout terminalUnavailableModal;
    private final TextView readingErrorMessage;
    private final TextView countdownView;
    private final Button modalActionButton;
    private final Button credentialRecoveryButton;
    private final Button recoveryActionButton;
    private final Button bootstrapRetryButton;
    private final TextView title;
    private final TextView clock;
    private final Runnable clockTicker;
    private TextView warmTipsTitle;
    private TextView warmTipsBody;

    private PassiveCredentialState passiveCredentialState =
            PassiveCredentialState.WAITING;
    private boolean validationErrorVisible;
    private String validationErrorMessage = "";
    private int secondsRemaining;
    private Listener listener;
    private PairedCredentialListener pairedCredentialListener;
    private Runnable credentialRecoveryListener;
    private String runtimeStatusText = "请直接刷手环或扫描二维码";
    private boolean returnMode;

    public ZipHomeView(
            Context context,
            CredentialAdmissionPolicy credentialPolicy,
            FeatureAvailability featureAvailability,
            BootstrapHomePresentation bootstrapPresentation) {
        super(context);
        if (featureAvailability == null) {
            throw new IllegalArgumentException("Feature availability is required");
        }
        if (bootstrapPresentation == null) {
            throw new IllegalArgumentException("Bootstrap presentation is required");
        }
        this.bootstrapPresentation = bootstrapPresentation;
        credentialModel = new HomeCredentialModel(credentialPolicy);
        finalHomeActions = FinalHomeActions.create(
                bootstrapPresentation.serverCapabilitiesKnown(),
                bootstrapPresentation.recognitionTypes(),
                featureAvailability);

        pixelShell = new ZipPixelShell(context, ZipScreenAsset.HOME_WAITING);
        pixelShell.setOnSafeHomeRequested(this::requestCredentialRecovery);
        addView(pixelShell, match());
        // This screen owns a complete native backdrop. When its canvas is dimmed,
        // use an opaque backing so stale text in the legacy bitmap cannot bleed through.
        View nativeBacking = new View(context);
        nativeBacking.setBackgroundColor(UiKit.DEEP_BLUE);
        nativeBacking.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        pixelShell.addView(nativeBacking, 1, match());
        FrameLayout content = pixelShell.contentLayer();
        FrameLayout overlay = pixelShell.overlayLayer();
        KioskPolish.addBackdrop(content, true);

        title = nativeText(
                context, ZipBrand.HOME_TITLE, 30, Color.WHITE, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setContentDescription(ZipBrand.HOME_TITLE);
        place(content, title, 385, 0, 510, 58);

        clock = nativeText(context, "", 25, Color.WHITE, Typeface.BOLD);
        clock.setText(clockFormat.format(new Date()));
        clock.setGravity(Gravity.CENTER);
        clock.setBackgroundColor(Color.rgb(24, 102, 205));
        clock.setContentDescription("当前日期时间，连续点击五次进入管理员登录");
        clock.setOnClickListener(view -> {
            if (!clockAdminTapGate.tap(SystemClock.elapsedRealtime())) return;
            Listener current = listener;
            if (current != null) current.onAdminRequested();
        });
        clockTicker = new Runnable() {
            @Override
            public void run() {
                String now = clockFormat.format(new Date());
                clock.setText(now);
                clock.setContentDescription(
                        "当前日期时间 " + now + "，连续点击五次进入管理员登录");
                handler.postDelayed(this, 1_000L);
            }
        };

        // Cover all sample venue/count/progress pixels: none are live server data.
        FrameLayout venueInfo = new FrameLayout(context);
        venueInfo.setBackgroundColor(Color.TRANSPARENT);
        place(content, venueInfo, 44, 102, 354, 120);
        String venueName = bootstrapPresentation.venueName();
        TextView venue = nativeText(context,
                venueName.isEmpty() ? "场馆信息待同步" : venueName,
                32, Color.WHITE, Typeface.BOLD);
        venue.setGravity(Gravity.CENTER);
        venue.setSingleLine(true);
        venue.setHorizontallyScrolling(false);
        venue.setEllipsize(TextUtils.TruncateAt.END);
        venue.setContentDescription(venue.getText());
        place(venueInfo, venue, 6, 0, 342, 64);

        TextView usage = nativeText(
                context, bootstrapPresentation.usageText(),
                22, Color.WHITE, Typeface.NORMAL);
        usage.setGravity(Gravity.CENTER);
        usage.setMaxLines(2);
        usage.setEllipsize(TextUtils.TruncateAt.END);
        usage.setPadding(0, 0, 0, 0);
        usage.setContentDescription(bootstrapPresentation.usageText());
        place(venueInfo, usage, 6, 65, 342, 45);

        underlyingInteractionLayer = new FrameLayout(context);
        underlyingInteractionLayer.setClipChildren(false);
        underlyingInteractionLayer.setClipToPadding(false);
        underlyingInteractionLayer.setImportantForAccessibility(
                IMPORTANT_FOR_ACCESSIBILITY_YES);
        content.addView(underlyingInteractionLayer, match());

        phoneField = createInputField(context, UnlockMethod.PHONE);
        passwordField = createInputField(context, UnlockMethod.PASSWORD);
        registerCustomerAction(phoneField);
        registerCustomerAction(passwordField);
        place(content, phoneField, 435, 101, 439, 63);
        moveToUnderlyingLayer(phoneField);
        place(content, passwordField, 435, 197, 439, 63);
        moveToUnderlyingLayer(passwordField);

        LinearLayout keypad = buildKeypad(context);
        registerCustomerAction(keypad);
        place(content, keypad, 436, 291, 436, 334);
        moveToUnderlyingLayer(keypad);

        confirmButton = UiKit.button(
                context, "确 认", 30, KioskPolish.GREEN, Color.WHITE, designDp(context, 4));
        ZipKioskShell.scaleButton(confirmButton, 30);
        confirmButton.setTypeface(KioskPolish.typeface(context, true));
        confirmButton.setIncludeFontPadding(false);
        confirmButton.setContentDescription("确认提交当前凭据");
        confirmButton.setOnClickListener(view -> submitCurrentCredential());
        registerCustomerAction(confirmButton);
        place(content, confirmButton, 436, 657, 438, 60);
        moveToUnderlyingLayer(confirmButton);

        FrameLayout rightInteractions = buildRightInteractions(context);
        place(content, rightInteractions, 900, 99, 336, 621);
        moveToUnderlyingLayer(rightInteractions);
        place(content, passiveScanStatus, 58, 218, 326, 36);

        modalInterceptionLayer = new FrameLayout(context);
        overlay.setClipChildren(false);
        modalInterceptionLayer.setClickable(true);
        modalInterceptionLayer.setFocusable(true);
        modalInterceptionLayer.setBackgroundColor(Color.TRANSPARENT);
        modalInterceptionLayer.setImportantForAccessibility(
                IMPORTANT_FOR_ACCESSIBILITY_NO);
        place(overlay, modalInterceptionLayer, 0, 0, 1280, 800);

        readingErrorModal = modalCard(context, UiKit.GREEN);
        readingErrorMessage = nativeText(
                context, "正在读取凭证\n正在读取并核验您的凭证",
                22, UiKit.TEXT, Typeface.BOLD);
        readingErrorMessage.setGravity(Gravity.CENTER);
        addModalCopy(readingErrorModal, readingErrorMessage);
        place(overlay, readingErrorModal, 330, 225, 620, 400);

        unregisteredModal = modalCard(context, UiKit.RED);
        TextView unregisteredMessage = nativeText(
                context, "凭证未登记\n该凭证尚未登记，请重新识别",
                23, UiKit.RED, Typeface.BOLD);
        unregisteredMessage.setGravity(Gravity.CENTER);
        place(unregisteredModal, unregisteredMessage, 40, 123, 540, 73);
        place(overlay, unregisteredModal, 330, 225, 620, 400);

        terminalUnavailableModal = modalCard(context, UiKit.RED);
        TextView terminalUnavailableMessage = nativeText(
                context, bootstrapPresentation.statusMessage(),
                24, UiKit.RED, Typeface.BOLD);
        terminalUnavailableMessage.setGravity(Gravity.CENTER);
        terminalUnavailableMessage.setContentDescription(
                bootstrapPresentation.statusMessage());
        addModalCopy(terminalUnavailableModal, terminalUnavailableMessage);
        place(overlay, terminalUnavailableModal, 330, 225, 620, 400);

        bootstrapRetryButton = UiKit.button(
                context, "重新连接服务器", 18, UiKit.GREEN,
                Color.WHITE, designDp(context, 28));
        bootstrapRetryButton.setContentDescription("重新连接服务器");
        bootstrapRetryButton.setOnClickListener(
                view -> notifyBootstrapRetryRequested());
        ZipKioskShell.scaleButton(bootstrapRetryButton, 23);
        place(overlay, bootstrapRetryButton, 485, 538, 310, 48);

        countdownView = nativeText(context, "8s", 44,
                Color.rgb(32, 148, 199), Typeface.BOLD);
        countdownView.setGravity(Gravity.CENTER);
        countdownView.setBackgroundColor(Color.WHITE);
        place(overlay, countdownView, 540, 427, 200, 63);

        modalActionButton = UiKit.button(
                context, "取消", 17, Color.rgb(252, 139, 54),
                Color.WHITE, designDp(context, 20));
        // This short button lives on the design-pixel canvas, not the device's sp scale.
        ZipKioskShell.scaleButton(modalActionButton, 22);
        modalActionButton.setContentDescription("取消凭证读取");
        modalActionButton.setOnClickListener(view -> {
            if (validationErrorVisible) {
                showValidationError(null);
            } else {
                requestCredentialRecovery();
            }
        });
        place(overlay, modalActionButton, 525, 538, 230, 48);

        credentialRecoveryButton = UiKit.button(
                context, "重新识别（8s）", 18, UiKit.GREEN,
                Color.WHITE, designDp(context, 28));
        credentialRecoveryButton.setContentDescription("重新识别，点击立即恢复等待识别");
        credentialRecoveryButton.setOnClickListener(view -> requestCredentialRecovery());
        recoveryActionButton = credentialRecoveryButton;
        ZipKioskShell.scaleButton(credentialRecoveryButton, 24);
        place(overlay, recoveryActionButton, 450, 538, 380, 48);

        // The clock is the only administrator entry. Keep its existing position,
        // above modal interception so errors never block the five-tap login route.
        place(overlay, clock, 970, 5, 275, 55);

        refreshFields();
        refreshModeCopy();
        applyHomePresentation();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Opt-in online contract; localDemo keeps its original focused-field callback. */
    public void setPairedCredentialListener(PairedCredentialListener listener) {
        pairedCredentialListener = listener;
    }

    public void setCredentialRecoveryListener(Runnable listener) {
        credentialRecoveryListener = listener;
    }

    /** Displays controller/model validation without changing passive countdown state. */
    public void showValidationError(String validationError) {
        validationErrorVisible = !isBlank(validationError);
        validationErrorMessage = validationErrorVisible ? validationError : "";
        applyHomePresentation();
    }

    public void clearInputs() {
        credentialModel.clearAll();
        credentialModel.select(UnlockMethod.PHONE);
        showValidationError(null);
        refreshFields();
    }

    public void showOnlineStatus(String status) {
        if (passiveScanStatus == null || status == null) return;
        runtimeStatusText = status;
        renderRuntimeStatus(status);
    }

    /** Visual-only mode label for return authentication; callbacks and inputs stay unchanged. */
    public void setReturnMode(boolean returnMode) {
        this.returnMode = returnMode;
        refreshModeCopy();
        renderRuntimeStatus(runtimeStatusText);
    }

    public void showCredentialWaiting() {
        passiveCredentialState = PassiveCredentialState.WAITING;
        secondsRemaining = 0;
        applyHomePresentation();
    }

    public void showCredentialReading() {
        passiveCredentialState = PassiveCredentialState.READING;
        secondsRemaining = 0;
        applyHomePresentation();
    }

    public void showInvalidCredential(int secondsRemaining) {
        if (secondsRemaining <= 0) {
            showCredentialWaiting();
            return;
        }
        passiveCredentialState = PassiveCredentialState.INVALID_COUNTDOWN;
        this.secondsRemaining = secondsRemaining;
        applyHomePresentation();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        handler.removeCallbacks(clockTicker);
        clockTicker.run();
    }

    @Override
    protected void onDetachedFromWindow() {
        handler.removeCallbacks(clockTicker);
        clockAdminTapGate.reset();
        super.onDetachedFromWindow();
    }

    private void applyHomePresentation() {
        // Keep one home canvas. Only the native modal changes, never the baked button layout.
        pixelShell.setScreenAsset(ZipScreenAsset.HOME_WAITING);
        pixelShell.setPromptDimmed(false);
        clock.bringToFront();
        if (!bootstrapPresentation.customerActionsEnabled()) {
            clockAdminTapGate.reset();
            setCustomerInteractionsEnabled(false);
            pixelShell.setPromptDimmed(true);
            modalInterceptionLayer.setVisibility(View.VISIBLE);
            modalInterceptionLayer.bringToFront();
            readingErrorModal.setVisibility(View.GONE);
            unregisteredModal.setVisibility(View.GONE);
            countdownView.setVisibility(View.GONE);
            modalActionButton.setVisibility(View.GONE);
            modalActionButton.setEnabled(false);
            recoveryActionButton.setVisibility(View.GONE);
            recoveryActionButton.setEnabled(false);
            terminalUnavailableModal.setVisibility(View.VISIBLE);
            terminalUnavailableModal.bringToFront();
            bootstrapRetryButton.setVisibility(bootstrapPresentation.retryAvailable()
                    ? View.VISIBLE : View.GONE);
            bootstrapRetryButton.setEnabled(bootstrapPresentation.retryAvailable());
            bootstrapRetryButton.setClickable(bootstrapPresentation.retryAvailable());
            bootstrapRetryButton.bringToFront();
            renderRuntimeStatus(bootstrapPresentation.statusMessage());
            clock.bringToFront();
            return;
        }

        setCustomerInteractionsEnabled(true);
        terminalUnavailableModal.setVisibility(View.GONE);
        bootstrapRetryButton.setVisibility(View.GONE);
        bootstrapRetryButton.setEnabled(false);
        ZipCustomerScreenRouter.State route;
        if (validationErrorVisible) {
            route = ZipCustomerScreenRouter.State.HOME_CREDENTIAL_ERROR;
        } else if (passiveCredentialState == PassiveCredentialState.READING) {
            route = ZipCustomerScreenRouter.State.HOME_READING_CREDENTIAL;
        } else if (passiveCredentialState == PassiveCredentialState.INVALID_COUNTDOWN) {
            route = ZipCustomerScreenRouter.State.HOME_UNREGISTERED_COUNTDOWN;
        } else {
            route = ZipCustomerScreenRouter.State.HOME_WAITING;
        }
        boolean modal = route != ZipCustomerScreenRouter.State.HOME_WAITING;
        if (modal) clockAdminTapGate.reset();
        pixelShell.setPromptDimmed(modal);
        setUnderlyingInteractionsEnabled(!modal);
        modalInterceptionLayer.setVisibility(modal ? View.VISIBLE : View.GONE);
        readingErrorModal.setVisibility(View.GONE);
        unregisteredModal.setVisibility(View.GONE);
        countdownView.setVisibility(View.GONE);
        modalActionButton.setVisibility(View.GONE);
        modalActionButton.setEnabled(false);
        recoveryActionButton.setVisibility(View.GONE);
        recoveryActionButton.setEnabled(false);

        if (!modal) {
            renderRuntimeStatus(runtimeStatusText);
            return;
        }

        modalInterceptionLayer.bringToFront();
        clock.bringToFront();
        if (validationErrorVisible) {
            readingErrorMessage.setText("操作提示\n" + validationErrorMessage);
            readingErrorMessage.setTextColor(UiKit.RED);
            readingErrorMessage.setContentDescription("操作提示，" + validationErrorMessage);
            readingErrorModal.setVisibility(View.VISIBLE);
            readingErrorModal.bringToFront();
            modalActionButton.setText("确认");
            modalActionButton.setContentDescription("确认提示并返回输入");
            modalActionButton.setBackground(UiKit.pressableSolid(
                    getContext(), UiKit.RED, designDp(getContext(), 20)));
            modalActionButton.setVisibility(View.VISIBLE);
            modalActionButton.setEnabled(true);
            modalActionButton.bringToFront();
            return;
        }

        if (passiveCredentialState == PassiveCredentialState.READING) {
            readingErrorMessage.setText("正在读取凭证\n正在读取并核验您的凭证");
            readingErrorMessage.setTextColor(Color.rgb(247, 133, 52));
            readingErrorMessage.setContentDescription("正在读取并核验您的凭证");
            readingErrorModal.setVisibility(View.VISIBLE);
            readingErrorModal.bringToFront();
            modalActionButton.setText("取消");
            modalActionButton.setContentDescription("取消凭证读取");
            modalActionButton.setBackground(UiKit.pressableSolid(
                    getContext(), Color.rgb(252, 139, 54), designDp(getContext(), 20)));
            modalActionButton.setVisibility(View.VISIBLE);
            modalActionButton.setEnabled(true);
            modalActionButton.bringToFront();
            return;
        }

        String seconds = secondsRemaining + "s";
        countdownView.setText(seconds);
        countdownView.setContentDescription("剩余 " + secondsRemaining + " 秒");
        String recoveryCopy = "重新识别（" + seconds + "）";
        credentialRecoveryButton.setText(recoveryCopy);
        credentialRecoveryButton.setContentDescription(
                recoveryCopy + "，点击可立即恢复等待识别");
        unregisteredModal.setVisibility(View.VISIBLE);
        unregisteredModal.bringToFront();
        countdownView.setVisibility(View.VISIBLE);
        countdownView.bringToFront();
        recoveryActionButton.setVisibility(View.VISIBLE);
        recoveryActionButton.setEnabled(true);
        recoveryActionButton.bringToFront();
    }

    private void setUnderlyingInteractionsEnabled(boolean enabled) {
        underlyingInteractionLayer.setVisibility(View.VISIBLE);
        setCustomerInteractionsEnabled(enabled);
        underlyingInteractionLayer.setImportantForAccessibility(enabled
                ? IMPORTANT_FOR_ACCESSIBILITY_YES
                : IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        if (!enabled) underlyingInteractionLayer.clearFocus();
    }

    private FrameLayout buildRightInteractions(Context context) {
        FrameLayout panel = new FrameLayout(context);
        panel.setBackgroundColor(Color.TRANSPARENT);

        List<Button> actions = new ArrayList<>();
        if (finalHomeActions.palmEnrollmentVisible()) {
            Button enrollmentButton = UiKit.button(
                    context, "掌纹录入", 18,
                    Color.rgb(54, 112, 220), Color.WHITE, designDp(context, 28));
            enrollmentButton.setContentDescription("掌纹录入入口");
            KioskPolish.action(enrollmentButton, "掌纹录入", "录入或更新掌纹信息",
                    Color.rgb(19, 127, 207), Color.rgb(53, 77, 200));
            enrollmentButton.setTag(0);
            enrollmentButton.setOnClickListener(view -> {
                Listener current = listener;
                if (current != null) current.onEnrollmentRequested();
            });
            registerCustomerAction(enrollmentButton);
            actions.add(enrollmentButton);
        }

        if (finalHomeActions.faceVisible()) {
            Button faceButton = UiKit.button(
                    context, "人脸识别", 18,
                    Color.rgb(237, 101, 88), Color.WHITE, designDp(context, 28));
            faceButton.setContentDescription("人脸识别入口");
            KioskPolish.action(faceButton, "人脸识别", "点击后启动摄像头",
                    Color.rgb(223, 101, 94), Color.rgb(211, 83, 48));
            faceButton.setTag(1);
            faceButton.setOnClickListener(view -> {
                Listener current = listener;
                if (current != null) current.onFaceRequested();
            });
            registerCustomerAction(faceButton);
            actions.add(faceButton);
        }

        Button returnButton = UiKit.button(
                context, "离场还柜", 18, UiKit.GREEN, Color.WHITE,
                designDp(context, 28));
        returnButton.setContentDescription("离场还柜，进入身份验证");
        KioskPolish.action(returnButton, "离场还柜", "离场前归还您的柜子",
                Color.rgb(16, 168, 149), Color.rgb(5, 143, 157));
        returnButton.setTag(2);
        returnButton.setOnClickListener(view -> notifyReturnRequested());
        registerCustomerAction(returnButton);
        actions.add(returnButton);

        distributeFinalActions(panel, actions);

        FrameLayout tipsCard = new FrameLayout(context);
        tipsCard.setBackground(UiKit.roundedSolid(
                context, Color.rgb(255, 249, 219), designDp(context, 12),
                Color.rgb(238, 206, 112), designDp(context, 1)));
        tipsCard.setBackground(KioskPolish.surface(context, Color.rgb(232,231,194), Color.rgb(214,221,166), 5));
        place(panel, tipsCard, 2, 363, 331, 256);

        warmTipsTitle = nativeText(
                context, "温馨提示", 23, Color.rgb(183,120,43), Typeface.BOLD);
        warmTipsTitle.setGravity(Gravity.CENTER);
        place(tipsCard, warmTipsTitle, 18, 10, 295, 38);

        warmTipsBody = nativeText(
                context, bootstrapPresentation.warmTipsText(), 20,
                Color.rgb(76, 85, 82), Typeface.NORMAL);
        warmTipsBody.setGravity(Gravity.TOP | Gravity.LEFT);
        warmTipsBody.setLineSpacing(unit(context, 7), 1f);
        warmTipsBody.setPadding(0,0,0,unit(context,8));
        warmTipsBody.setEllipsize(null);
        ScrollView tipsScroll = new ScrollView(context);
        tipsScroll.setFillViewport(true);
        tipsScroll.addView(warmTipsBody, new ScrollView.LayoutParams(-1,-2));
        place(tipsCard, tipsScroll, 22, 60, 287, 184);

        passiveScanStatus = nativeText(
                context, "", 15,
                Color.WHITE, Typeface.NORMAL);
        passiveScanStatus.setGravity(Gravity.CENTER);
        passiveScanStatus.setPadding(unit(context, 6), 0, unit(context, 6), 0);
        passiveScanStatus.setMaxLines(2);
        passiveScanStatus.setEllipsize(TextUtils.TruncateAt.END);
        passiveScanStatus.setBackground(KioskPolish.surface(context,
                Color.argb(100,0,101,149), Color.TRANSPARENT, 12));

        return panel;
    }

    private static void distributeFinalActions(
            FrameLayout panel, List<Button> actions) {
        final int actionAreaHeight = 340;
        final int buttonHeight = 100;
        int gap = (actionAreaHeight - actions.size() * buttonHeight)
                / Math.max(1, actions.size() - 1);
        int top = actions.size() == 1 ? (actionAreaHeight - buttonHeight) / 2 : 0;
        for (Button action : actions) {
            place(panel, action, 0, top, 336, buttonHeight);
            top += buttonHeight + gap;
        }
    }

    private void refreshModeCopy() {
        if (returnMode) {
            title.setAlpha(1f);
            title.setText("离场还柜 · 身份验证");
            title.setTextColor(Color.WHITE);
            title.setBackgroundColor(Color.rgb(24, 102, 205));
            title.setContentDescription("离场还柜 · 身份验证");
            warmTipsTitle.setText("还柜提示");
            warmTipsBody.setText(bootstrapPresentation.returnTipsText());
            warmTipsBody.setContentDescription(
                    "还柜提示，" + bootstrapPresentation.returnTipsText());
            return;
        }
        title.setText(ZipBrand.HOME_TITLE);
        title.setAlpha(0f); // Supplied brand artwork remains visible; retain accessible label.
        title.setTextColor(Color.WHITE);
        title.setBackgroundColor(Color.TRANSPARENT);
        title.setContentDescription(ZipBrand.HOME_TITLE);
        warmTipsTitle.setText("温馨提示");
        warmTipsBody.setText(bootstrapPresentation.warmTipsText());
        warmTipsBody.setContentDescription(
                "温馨提示，" + bootstrapPresentation.warmTipsText());
    }

    private void renderRuntimeStatus(String status) {
        if (passiveScanStatus == null) return;
        String mode = returnMode ? "还柜身份验证" : "首页";
        passiveScanStatus.setText(returnMode ? "还柜验证 · " + status : status);
        passiveScanStatus.setContentDescription(
                "当前模式：" + mode + "；运行状态：" + status);
    }

    private void notifyReturnRequested() {
        Listener current = listener;
        if (current != null) current.onReturnRequested();
    }

    private void notifyBootstrapRetryRequested() {
        Listener current = listener;
        if (current != null) current.onBootstrapRetryRequested();
    }

    private Button createInputField(Context context, UnlockMethod method) {
        Button field = UiKit.button(
                context, "", 22, Color.WHITE, UiKit.TEXT, designDp(context, 8));
        field.setGravity(Gravity.CENTER);
        field.setPadding(unit(context, 22), 0, unit(context, 22), 0);
        field.setIncludeFontPadding(false);
        field.setTypeface(KioskPolish.typeface(context, false));
        field.setSingleLine(true);
        field.setHorizontallyScrolling(false);
        field.setEllipsize(TextUtils.TruncateAt.END);
        ZipKioskShell.scaledText(field, 28);
        field.setContentDescription(method == UnlockMethod.PHONE
                ? "手机号输入框" : "取柜码输入框");
        field.setOnClickListener(view -> {
            credentialModel.select(method);
            showValidationError(null);
            refreshFields();
        });
        return field;
    }

    private LinearLayout buildKeypad(Context context) {
        LinearLayout keypad = new LinearLayout(context);
        keypad.setOrientation(LinearLayout.VERTICAL);
        keypad.setPadding(unit(context, 24), unit(context, 18),
                unit(context, 24), unit(context, 11));
        keypad.setBackground(UiKit.roundedSolid(
                context, Color.rgb(252, 253, 251), designDp(context, 5),
                Color.TRANSPARENT, 0));
        for (String[] rowLabels : KEYS) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int index = 0; index < rowLabels.length; index++) {
                String label = rowLabels[index];
                boolean action = "清空".equals(label) || "删除".equals(label);
                Button key = UiKit.button(
                        context, label, action ? 17 : 25, Color.WHITE,
                        action ? UiKit.GREEN : UiKit.TEXT, designDp(context, 6));
                ZipKioskShell.scaleButton(key, action ? 24 : 43);
                key.setTypeface(KioskPolish.typeface(context, !action));
                key.setIncludeFontPadding(false);
                android.graphics.drawable.StateListDrawable keyState = new android.graphics.drawable.StateListDrawable();
                keyState.addState(new int[]{android.R.attr.state_pressed}, KioskPolish.surface(context,Color.rgb(227,243,234),Color.TRANSPARENT,4));
                keyState.addState(new int[0],KioskPolish.surface(context,Color.TRANSPARENT,Color.TRANSPARENT,4));
                key.setBackground(keyState);
                key.setTextColor("清空".equals(label) ? KioskPolish.GREEN : Color.rgb(92,108,101));
                key.setContentDescription("数字键盘" + label);
                key.setOnClickListener(view -> handleKey(label));
                LinearLayout.LayoutParams keyParams = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                row.addView(key, keyParams);
            }
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            keypad.addView(row, rowParams);
        }
        return keypad;
    }

    private void handleKey(String label) {
        if ("清空".equals(label)) credentialModel.clear();
        else if ("删除".equals(label)) credentialModel.delete();
        else if (label.length() == 1) credentialModel.pressDigit(label.charAt(0));
        showValidationError(null);
        refreshFields();
    }

    private void submitCurrentCredential() {
        PairedCredentialListener paired = pairedCredentialListener;
        if (paired != null) {
            paired.onSubmit(credentialModel.rawValue(UnlockMethod.PHONE),
                    credentialModel.rawValue(UnlockMethod.PASSWORD));
            return;
        }
        UnlockMethod method = credentialModel.activeMethod();
        String rawValue = credentialModel.rawValue(method);
        Listener current = listener;
        if (current != null) current.onCredentialSubmit(method, rawValue);
    }

    private void refreshFields() {
        updateField(phoneField, UnlockMethod.PHONE, "请输入手机号");
        updateField(passwordField, UnlockMethod.PASSWORD, "请输入取柜码");
    }

    private void updateField(Button field, UnlockMethod method, String hint) {
        String display = credentialModel.displayValue(method);
        boolean active = credentialModel.activeMethod() == method;
        field.setText(display.length() == 0 ? hint : display);
        field.setTextColor(display.length() == 0 ? Color.rgb(112,127,125) : UiKit.TEXT);
        field.setBackground(UiKit.roundedSolid(
                getContext(), Color.rgb(237,243,249), designDp(getContext(), 5),
                active && display.length() > 0 ? UiKit.GREEN : Color.TRANSPARENT,
                designDp(getContext(), active && display.length() > 0 ? 2 : 0)));
        field.setContentDescription((method == UnlockMethod.PHONE
                ? "手机号输入框" : "取柜码输入框") + (active ? "，已选中" : ""));
    }

    private void requestCredentialRecovery() {
        Runnable current = credentialRecoveryListener;
        if (current != null) current.run();
        else showCredentialWaiting();
    }

    private void registerCustomerAction(View view) {
        customerActionViews.add(view);
    }

    private void setCustomerInteractionsEnabled(boolean enabled) {
        for (View view : customerActionViews) {
            setCustomerActionEnabled(view, enabled);
        }
    }

    private static void setCustomerActionEnabled(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof Button) {
            view.setClickable(enabled);
        }
        if (!enabled) {
            view.clearFocus();
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                setCustomerActionEnabled(group.getChildAt(index), enabled);
            }
        }
    }

    private void moveToUnderlyingLayer(View view) {
        ViewGroup parent = (ViewGroup) view.getParent();
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (parent != null) parent.removeView(view);
        underlyingInteractionLayer.addView(view, params);
    }

    private static FrameLayout modalCard(Context context, int borderColor) {
        FrameLayout card = new FrameLayout(context);
        card.setBackground(KioskPolish.promptSurface());
        KioskPolish.decoratePrompt(card);
        TextView heading=nativeText(context,"温馨提示",35,KioskPolish.GREEN,Typeface.BOLD);
        heading.setGravity(Gravity.CENTER);
        place(card,heading,180,54,262,62);
        card.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        return card;
    }

    private static void addModalCopy(FrameLayout card, TextView text) {
        Context context=card.getContext();
        text.setTypeface(KioskPolish.typeface(context,false));
        text.setLineSpacing(unit(context,6),1f);
        text.setPadding(unit(context,10),unit(context,8),unit(context,10),unit(context,8));
        text.setEllipsize(null);
        ScrollView scroll=new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.addView(text,new ScrollView.LayoutParams(-1,-2));
        place(card,scroll,28,132,564,157);
    }

    private static TextView nativeText(Context context, String text,
            float designTextSize, int color, int style) {
        TextView view = ZipKioskShell.scaledText(
                UiKit.text(context, text, designTextSize, color, style),
                designTextSize);
        view.setTypeface(KioskPolish.typeface(context, style==Typeface.BOLD));
        return view;
    }

    private static void place(FrameLayout parent, View child,
            int x, int y, int width, int height) {
        LayoutParams params = new LayoutParams(
                unit(parent.getContext(), width), unit(parent.getContext(), height));
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.leftMargin = unit(parent.getContext(), x);
        params.topMargin = unit(parent.getContext(), y);
        parent.addView(child, params);
    }

    private static boolean isBlank(CharSequence value) {
        if (value == null || value.length() == 0) return true;
        for (int index = 0; index < value.length(); index++) {
            if (!Character.isWhitespace(value.charAt(index))) return false;
        }
        return true;
    }

    private static String methodDescription(UnlockMethod method) {
        if (method == UnlockMethod.FACE) return "人脸识别入口";
        if (method == UnlockMethod.PALM) return "掌纹识别入口";
        return "识别入口";
    }

    private static int unit(Context context, float designUnits) {
        return ZipKioskShell.unit(context, designUnits);
    }

    private static float designDp(Context context, float designUnits) {
        return ZipKioskShell.designDp(context, designUnits);
    }

    private static LayoutParams match() {
        return new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }
}
