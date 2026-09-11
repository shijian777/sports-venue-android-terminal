package com.codex.lockertest.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import com.codex.lockertest.face.FaceLivenessControl;
import com.codex.lockertest.face.FaceLicenseStateMachine;
import com.codex.lockertest.face.FaceRuntimeStateMachine;
import com.codex.lockertest.ui.zip.ZipAdminScreenRouter;
import com.codex.lockertest.ui.zip.ZipScreenAsset;

import java.util.Arrays;

/** Administrator-only view for process authorization, models and local-demo policy. */
public final class FaceSdkAdminOverlay extends FrameLayout {
    private static final int ADMIN_PANEL_LEFT = 20;
    private static final int ADMIN_PANEL_TOP = 105;
    private static final int ADMIN_PANEL_RIGHT = 1260;
    private static final int ADMIN_PANEL_BOTTOM = 710;
    public interface Listener {
        void onActivateRequested(char[] ownedActivationCode);
        void onDemoEnabledChanged(boolean enabled);
        void onLivenessEnabledChanged(boolean enabled);
        void onInitializeRuntimeRequested();
        void onCloseRequested();
    }

    private final TextView licenseStateView;
    private final TextView runtimeStateView;
    private final TextView operationMessage;
    private final EditText activationInput;
    private final Button activationButton;
    private final Button initializeButton;
    private final Button visibleBackButton;
    private final Button retryButton;
    private final Button errorBackButton;
    private final TextView livenessStateView;
    private final Switch livenessSwitch;
    private final Switch demoSwitch;
    private final ZipKioskShell shell;
    private final LinearLayout stateCard;
    private final LinearLayout demoCard;
    private Listener listener;
    private boolean activationInFlight;
    private boolean renderingDemoState;
    private boolean renderingLivenessState;
    private FaceLivenessControl.Snapshot lastLivenessSnapshot;
    private boolean activationAllowedByState = true;
    private boolean licenseReady;

    public FaceSdkAdminOverlay(Context context) {
        super(context);
        shell = new ZipKioskShell(
                context, ZipScreenAsset.FACE_SDK_CHECKING_LICENSE);
        addView(shell, match());
        shell.setOnReturnClickListener(null);
        shell.setReturnEnabled(false);

        FrameLayout nativeAdminPanel = new FrameLayout(context);
        nativeAdminPanel.setBackgroundColor(Color.WHITE);
        LayoutParams nativePanelParams = new LayoutParams(
                ZipKioskShell.unit(context, ADMIN_PANEL_RIGHT - ADMIN_PANEL_LEFT),
                ZipKioskShell.unit(context, ADMIN_PANEL_BOTTOM - ADMIN_PANEL_TOP));
        nativePanelParams.leftMargin = ZipKioskShell.unit(context, ADMIN_PANEL_LEFT);
        nativePanelParams.topMargin = ZipKioskShell.unit(context, ADMIN_PANEL_TOP);
        shell.pixelContent().addView(nativeAdminPanel, nativePanelParams);

        visibleBackButton = action(context, "返回管理页", Color.rgb(132, 147, 150));
        visibleBackButton.setContentDescription("返回管理页");
        visibleBackButton.setOnClickListener(view -> notifyCloseRequested());
        LayoutParams visibleBackParams = new LayoutParams(
                ZipKioskShell.unit(context, 176), ZipKioskShell.unit(context, 38));
        visibleBackParams.leftMargin = ZipKioskShell.unit(context, 1050);
        visibleBackParams.topMargin = ZipKioskShell.unit(context, 110);
        shell.pixelOverlay().addView(visibleBackButton, visibleBackParams);

        retryButton = action(context, "重试", UiKit.GREEN);
        retryButton.setContentDescription("重试人脸组件");
        retryButton.setOnClickListener(view -> requestCoreRetry());
        placeOnDesignOverlay(retryButton, context, 470, 480, 150, 46);

        errorBackButton = action(context, "返回管理页", Color.rgb(132, 147, 150));
        errorBackButton.setContentDescription("从人脸组件异常页返回管理页");
        errorBackButton.setOnClickListener(view -> notifyCloseRequested());
        placeOnDesignOverlay(errorBackButton, context, 660, 480, 150, 46);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setGravity(Gravity.CENTER);
        int gap = ZipKioskShell.unit(context, 18);

        stateCard = card(context);
        licenseStateView = stateLine(context, "授权状态：未检查");
        runtimeStateView = stateLine(context, "模型状态：未初始化");
        stateCard.addView(licenseStateView, row(context, 54));
        stateCard.addView(runtimeStateView, row(context, 54));

        activationInput = new EditText(context);
        activationInput.setHint("请输入在线激活信息");
        activationInput.setSingleLine(true);
        activationInput.setSaveEnabled(false);
        activationInput.setSaveFromParentEnabled(false);
        activationInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD
                | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        activationInput.setTransformationMethod(
                PasswordTransformationMethod.getInstance());
        activationInput.setFilters(new InputFilter[] {
                new InputFilter.AllCaps(),
                new InputFilter.LengthFilter(4096)
        });
        activationInput.setTextColor(UiKit.TEXT);
        activationInput.setHintTextColor(UiKit.MUTED);
        activationInput.setTextSize(17);
        activationInput.setPadding(gap, 0, gap, 0);
        activationInput.setContentDescription("在线激活信息输入框，内容已隐藏");
        activationInput.setBackground(UiKit.roundedSolid(
                context, Color.WHITE, 10, Color.rgb(145, 195, 204), 1));
        placeOnDesignOverlay(activationInput, context, 481, 332, 320, 48);

        activationButton = action(context, "在线激活", UiKit.GREEN);
        activationButton.setContentDescription("提交在线激活");
        activationButton.setOnClickListener(view -> submitActivation());
        placeOnDesignOverlay(activationButton, context, 551, 398, 180, 42);

        initializeButton = action(context, "初始化模型", UiKit.BLUE);
        initializeButton.setContentDescription("初始化或重试人脸模型");
        initializeButton.setVisibility(View.GONE);
        initializeButton.setOnClickListener(view -> {
            Listener current = listener;
            if (current != null) {
                try { current.onInitializeRuntimeRequested(); }
                catch (RuntimeException | LinkageError ignored) {
                    showOperationMessage("模型初始化请求失败，请重试", true);
                }
            }
        });
        LinearLayout.LayoutParams initializeParams = row(context, 58);
        initializeParams.topMargin = gap;
        stateCard.addView(initializeButton, initializeParams);

        operationMessage = UiKit.text(
                context, "等待管理员操作", 15, UiKit.MUTED, Typeface.NORMAL);
        operationMessage.setGravity(Gravity.CENTER);
        operationMessage.setMaxLines(2);
        operationMessage.setContentDescription("操作状态");
        LinearLayout.LayoutParams messageParams = row(context, 58);
        messageParams.topMargin = gap;
        stateCard.addView(operationMessage, messageParams);

        demoCard = card(context);
        int compactGap = ZipKioskShell.unit(context, 10);

        livenessStateView = UiKit.text(context,
                "等待完成在线授权后检测活体能力",
                15, UiKit.MUTED, Typeface.BOLD);
        livenessStateView.setGravity(Gravity.CENTER);
        livenessStateView.setMaxLines(3);
        livenessStateView.setContentDescription("活体检测状态");
        livenessStateView.setBackground(UiKit.roundedSolid(
                context, Color.rgb(241, 249, 250), 10,
                Color.rgb(202, 228, 232), 1));
        placeOnDesignOverlay(livenessStateView, context, 402, 342, 370, 60);

        livenessSwitch = new Switch(context);
        livenessSwitch.setText("");
        livenessSwitch.setTextColor(UiKit.TEXT);
        livenessSwitch.setTextSize(17);
        livenessSwitch.setGravity(Gravity.CENTER);
        livenessSwitch.setPadding(0, 0, 0, 0);
        livenessSwitch.setContentDescription("RGB 活体检测开关");
        livenessSwitch.setEnabled(false);
        livenessSwitch.setAlpha(0.55f);
        livenessSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (renderingLivenessState) return;
            requestLivenessState(checked);
        });
        placeOnDesignOverlay(livenessSwitch, context, 797, 347, 72, 42);

        TextView demoTitle = UiKit.text(
                context, "本机模拟校验", 20, UiKit.NAVY, Typeface.BOLD);
        demoTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams demoTitleParams = row(context, 44);
        demoTitleParams.topMargin = compactGap;
        demoCard.addView(demoTitle, demoTitleParams);

        TextView demoExplanation = UiKit.text(context,
                "仅用于本机流程联调，不进行身份验证；正式环境始终关闭。",
                14, UiKit.AMBER, Typeface.BOLD);
        demoExplanation.setGravity(Gravity.CENTER);
        demoExplanation.setMaxLines(4);
        demoExplanation.setContentDescription("本机模拟安全说明");
        LinearLayout.LayoutParams explanationParams = row(context, 92);
        explanationParams.topMargin = compactGap;
        demoCard.addView(demoExplanation, explanationParams);

        demoSwitch = new Switch(context);
        demoSwitch.setText("启用本机模拟校验");
        demoSwitch.setTextColor(UiKit.TEXT);
        demoSwitch.setTextSize(17);
        demoSwitch.setGravity(Gravity.CENTER);
        demoSwitch.setContentDescription("本机模拟校验开关");
        demoSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (renderingDemoState) return;
            requestDemoState(checked);
        });
        LinearLayout.LayoutParams switchParams = row(context, 56);
        switchParams.topMargin = compactGap;
        demoCard.addView(demoSwitch, switchParams);

        LinearLayout.LayoutParams stateParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1.25f);
        stateParams.rightMargin = gap;
        content.addView(stateCard, stateParams);
        content.addView(demoCard, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 0.75f));
        LayoutParams contentParams = new LayoutParams(
                ZipKioskShell.unit(context, 1160),
                ZipKioskShell.unit(context, 505));
        contentParams.leftMargin = ZipKioskShell.unit(context, 40);
        contentParams.topMargin = ZipKioskShell.unit(context, 55);
        nativeAdminPanel.addView(content, contentParams);
        renderScreen(ZipScreenAsset.FACE_SDK_CHECKING_LICENSE);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void renderScreen(ZipScreenAsset asset) {
        renderScreen(asset, asset == ZipScreenAsset.FACE_SDK_ERROR);
    }

    public void renderScreen(ZipScreenAsset asset, boolean retryAvailable) {
        if (asset == null || asset.id() < 48 || asset.id() > 54) {
            throw new IllegalArgumentException("face SDK administrator asset is required");
        }
        ZipAdminScreenRouter.ActionAvailability actions =
                ZipAdminScreenRouter.actionsForFaceAsset(asset, retryAvailable);
        shell.setScreenAsset(asset);
        boolean activation = asset == ZipScreenAsset.FACE_SDK_AWAITING_ACTIVATION;
        boolean ready = asset == ZipScreenAsset.FACE_SDK_READY;
        boolean error = asset == ZipScreenAsset.FACE_SDK_ERROR;
        activationInput.setVisibility(activation ? View.VISIBLE : View.GONE);
        activationButton.setVisibility(activation ? View.VISIBLE : View.GONE);
        livenessStateView.setVisibility(ready ? View.VISIBLE : View.GONE);
        livenessSwitch.setVisibility(ready ? View.VISIBLE : View.GONE);
        demoCard.setVisibility(ready ? View.VISIBLE : View.GONE);
        stateCard.setVisibility(View.VISIBLE);
        initializeButton.setVisibility(View.GONE);
        visibleBackButton.setVisibility(
                actions.nativeBackVisible() ? View.VISIBLE : View.GONE);
        visibleBackButton.setEnabled(actions.nativeBackVisible());
        retryButton.setVisibility(actions.retryVisible() ? View.VISIBLE : View.GONE);
        retryButton.setEnabled(actions.retryVisible());
        errorBackButton.setVisibility(
                actions.promptBackVisible() ? View.VISIBLE : View.GONE);
        errorBackButton.setEnabled(actions.promptBackVisible());
    }

    private void requestCoreRetry() {
        Listener current = listener;
        if (current == null) {
            showOperationMessage("当前页面不可用，请关闭后重试", true);
            return;
        }
        try { current.onInitializeRuntimeRequested(); }
        catch (RuntimeException | LinkageError ignored) {
            showOperationMessage("人脸组件重试请求失败，请重试", true);
        }
    }

    private void notifyCloseRequested() {
        Listener current = listener;
        if (current == null) return;
        try { current.onCloseRequested(); }
        catch (RuntimeException | LinkageError ignored) { }
    }

    private char[] takeAndClearActivationCode() {
        Editable editable = activationInput.getText();
        char[] owned = new char[editable.length()];
        editable.getChars(0, editable.length(), owned, 0);
        editable.clear();
        return owned;
    }

    private void submitActivation() {
        if (activationInFlight) return;
        char[] owned = takeAndClearActivationCode();
        if (!containsVisibleCharacter(owned)) {
            Arrays.fill(owned, '\0');
            showOperationMessage("请输入有效的在线激活信息", true);
            return;
        }
        Listener current = listener;
        if (current == null) {
            Arrays.fill(owned, '\0');
            showOperationMessage("当前页面不可用，请关闭后重试", true);
            return;
        }
        activationInFlight = true;
        setActivationInFlight(true);
        boolean transferred = false;
        try {
            current.onActivateRequested(owned);
            transferred = true;
        } catch (RuntimeException | LinkageError ignored) {
            setActivationInFlight(false);
            showOperationMessage("在线激活请求失败，请重试", true);
        } finally {
            if (!transferred) {
                Arrays.fill(owned, '\0');
                setActivationInFlight(false);
            }
        }
    }

    private void requestDemoState(boolean requested) {
        Listener current = listener;
        if (current == null) {
            renderDemoState(demoSwitch.getVisibility() == View.VISIBLE, false);
            return;
        }
        try { current.onDemoEnabledChanged(requested); }
        catch (RuntimeException | LinkageError ignored) {
            renderDemoState(demoSwitch.getVisibility() == View.VISIBLE, false);
            showOperationMessage("无法更新本机模拟状态", true);
        }
    }

    private void requestLivenessState(boolean requested) {
        Listener current = listener;
        if (current == null) {
            renderLivenessState(lastLivenessSnapshot);
            return;
        }
        try { current.onLivenessEnabledChanged(requested); }
        catch (RuntimeException | LinkageError ignored) {
            renderLivenessState(lastLivenessSnapshot);
            showOperationMessage("无法更新活体检测状态", true);
        }
    }

    public void setActivationInFlight(boolean inFlight) {
        activationInFlight = inFlight;
        activationInput.setEnabled(!inFlight);
        activationButton.setEnabled(!inFlight);
        if (!activationAllowedByState || licenseReady) {
            activationInput.setEnabled(false);
            activationButton.setEnabled(false);
        }
    }

    public void renderStates(FaceLicenseStateMachine.State licenseState,
            FaceRuntimeStateMachine.State runtimeState) {
        FaceLicenseStateMachine.State safeLicense = licenseState == null
                ? FaceLicenseStateMachine.State.UNKNOWN : licenseState;
        FaceRuntimeStateMachine.State safeRuntime = runtimeState == null
                ? FaceRuntimeStateMachine.State.UNINITIALIZED : runtimeState;
        licenseStateView.setText("授权状态：" + mapLicenseState(safeLicense));
        runtimeStateView.setText("模型状态：" + runtimeText(safeRuntime));

        licenseReady = safeLicense == FaceLicenseStateMachine.State.READY;
        activationAllowedByState =
                safeLicense == FaceLicenseStateMachine.State.UNKNOWN
                        || safeLicense == FaceLicenseStateMachine.State.INVALID
                        || safeLicense == FaceLicenseStateMachine.State.FAILED;
        if (licenseReady) setActivationInFlight(false);
        else setActivationInFlight(activationInFlight);

        initializeButton.setVisibility(View.GONE);
    }

    public void renderDemoState(boolean supported, boolean enabled) {
        renderingDemoState = true;
        try {
            demoSwitch.setChecked(enabled);
        } finally {
            renderingDemoState = false;
        }
        demoSwitch.setVisibility(supported ? View.VISIBLE : View.GONE);
        demoSwitch.setEnabled(supported);
    }

    public void renderLivenessState(FaceLivenessControl.Snapshot snapshot) {
        lastLivenessSnapshot = snapshot;
        FaceLivenessControl.Capability capability = snapshot == null
                ? FaceLivenessControl.Capability.WAITING_FOR_LICENSE
                : snapshot.capability();
        boolean checked = snapshot != null && snapshot.requestedEnabled();
        renderingLivenessState = true;
        try {
            livenessSwitch.setChecked(checked);
        } finally {
            renderingLivenessState = false;
        }
        boolean enabled = snapshot != null && snapshot.switchEnabled();
        if (snapshot == null) {
            livenessSwitch.setEnabled(false);
        } else {
            livenessSwitch.setEnabled(snapshot.switchEnabled());
        }
        livenessSwitch.setAlpha(enabled ? 1.0f : 0.55f);
        switch (capability) {
            case SUPPORTED:
                livenessStateView.setText(checked
                        ? "活体检测已开启"
                        : "活体检测可用，当前未开启");
                livenessStateView.setTextColor(UiKit.DARK_GREEN);
                break;
            case UNSUPPORTED:
                livenessStateView.setText(
                        "当前终端未授权活体检测，普通人脸抓拍仍可使用");
                livenessStateView.setTextColor(UiKit.AMBER);
                break;
            case FAILED:
                livenessStateView.setText(
                        "活体模型初始化失败，普通人脸抓拍仍可使用");
                livenessStateView.setTextColor(UiKit.AMBER);
                break;
            case PROBING:
                livenessStateView.setText("正在检测活体模型支持状态");
                livenessStateView.setTextColor(UiKit.MUTED);
                break;
            case WAITING_FOR_LICENSE:
            default:
                livenessStateView.setText("等待完成在线授权后检测活体能力");
                livenessStateView.setTextColor(UiKit.MUTED);
                break;
        }
    }

    public void clearActivationInput() {
        activationInput.getText().clear();
    }

    public void showOperationMessage(CharSequence message, boolean failure) {
        CharSequence safe = isBlank(message) ? "等待管理员操作" : message;
        operationMessage.setText(safe);
        operationMessage.setTextColor(failure ? UiKit.RED : UiKit.DARK_GREEN);
    }

    private static boolean containsVisibleCharacter(char[] value) {
        for (char item : value) {
            if (!Character.isWhitespace(item) && !Character.isSpaceChar(item)) return true;
        }
        return false;
    }

    private static boolean isBlank(CharSequence value) {
        if (value == null || value.length() == 0) return true;
        for (int index = 0; index < value.length(); index++) {
            char item = value.charAt(index);
            if (!Character.isWhitespace(item) && !Character.isSpaceChar(item)) return false;
        }
        return true;
    }

    private static String mapLicenseState(FaceLicenseStateMachine.State state) {
        switch (state) {
            case CHECKING_LOCAL: return "正在检查";
            case ACTIVATING_ONLINE: return "正在激活";
            case READY: return "已授权";
            case INVALID: return "授权无效";
            case FAILED: return "授权暂不可用";
            case UNKNOWN:
            default: return "未检查";
        }
    }

    private static String runtimeText(FaceRuntimeStateMachine.State state) {
        switch (state) {
            case INITIALIZING: return "正在初始化";
            case READY: return "已就绪";
            case FAILED: return "初始化失败";
            case RELEASED: return "已停止";
            case UNINITIALIZED:
            default: return "未初始化";
        }
    }

    private static LinearLayout card(Context context) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        int padding = ZipKioskShell.unit(context, 24);
        card.setPadding(padding, padding, padding, padding);
        card.setBackground(UiKit.roundedSolid(
                context, Color.WHITE, 16, Color.rgb(166, 221, 230), 1));
        return card;
    }

    private static TextView stateLine(Context context, CharSequence text) {
        TextView view = UiKit.text(context, text, 20, UiKit.NAVY, Typeface.BOLD);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPadding(ZipKioskShell.unit(context, 16), 0, 0, 0);
        view.setBackground(UiKit.roundedSolid(
                context, Color.rgb(241, 249, 250), 10,
                Color.rgb(202, 228, 232), 1));
        return view;
    }

    private static Button action(Context context, CharSequence text, int color) {
        return ZipKioskShell.scaleButton(UiKit.button(
                context, text, 17, color, Color.WHITE,
                ZipKioskShell.designDp(context, 10)), 17);
    }

    private static LinearLayout.LayoutParams row(Context context, int height) {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ZipKioskShell.unit(context, height));
    }

    private void placeOnDesignOverlay(View view, Context context,
            int left, int top, int width, int height) {
        LayoutParams params = new LayoutParams(
                ZipKioskShell.unit(context, width),
                ZipKioskShell.unit(context, height));
        params.leftMargin = ZipKioskShell.unit(context, left);
        params.topMargin = ZipKioskShell.unit(context, top);
        shell.pixelOverlay().addView(view, params);
    }

    private static LayoutParams match() {
        return new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }
}
