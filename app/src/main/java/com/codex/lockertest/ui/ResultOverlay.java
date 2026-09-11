package com.codex.lockertest.ui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.codex.lockertest.model.LockerTarget;
import com.codex.lockertest.ui.zip.ZipLockerScreenRouter;
import com.codex.lockertest.ui.zip.ZipLockerScreenRouter.ResultActions;
import com.codex.lockertest.ui.zip.ZipLockerScreenRouter.ResultState;
import com.codex.lockertest.ui.zip.ZipPixelShell;
import com.codex.lockertest.ui.zip.ZipScreenAsset;

/** Customer-only result layer using ZIP assets 19-26 and typed visual routing. */
public final class ResultOverlay extends FrameLayout {
    public enum State {
        VERIFYING,
        CONNECTING,
        WAITING,
        SUCCESS,
        FAILURE,
        TIMEOUT
    }

    public interface Listener {
        void onRetry();

        void onReturnHome();
    }

    private static final long SUCCESS_RETURN_MILLIS = 3_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable successReturn = this::returnHome;
    private final ZipPixelShell pixelShell;

    private Listener listener;
    private ZipPromptOverlay activePrompt;
    private boolean actionTaken;

    public ResultOverlay(Context context) {
        super(context);
        setClickable(true);
        setFocusable(true);
        pixelShell = new ZipPixelShell(context, ZipScreenAsset.UNLOCK_VALIDATING);
        addView(pixelShell, match());
        setVisibility(GONE);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Compatibility entry point; active selected-locker integration uses explicit methods. */
    public void show(State state) {
        if (state == null) {
            throw new IllegalArgumentException("state cannot be null");
        }
        switch (state) {
            case VERIFYING:
            case CONNECTING:
            case WAITING:
                showProgress(state);
                break;
            case SUCCESS:
                showLockerSuccess(1);
                break;
            case FAILURE:
                showLockerFailure(1);
                break;
            case TIMEOUT:
                showTimeout();
                break;
            default:
                throw new IllegalArgumentException("unsupported state: " + state);
        }
    }

    public void showProgress(State state) {
        if (state == State.VERIFYING) {
            render(ResultState.VALIDATING, null, false);
        } else if (state == State.CONNECTING) {
            render(ResultState.CONNECTING, null, false);
        } else if (state == State.WAITING) {
            render(ResultState.WAITING_RESPONSE, null, false);
        } else {
            throw new IllegalArgumentException("state must be a progress state");
        }
    }

    public void showLockerSuccess(String displayLabel) {
        render(ResultState.SUCCESS, displayLabel, true);
    }

    public void showLockerFailure(String displayLabel) {
        render(ResultState.BOARD_REJECTED, displayLabel, false);
    }

    /** @deprecated Display compatibility only; active flow supplies the server label directly. */
    @Deprecated
    public void showLockerSuccess(LockerTarget target) {
        showLockerSuccess(requireTarget(target).customerLabel());
    }

    /** @deprecated Display compatibility only; active flow supplies the server label directly. */
    @Deprecated
    public void showLockerFailure(LockerTarget target) {
        showLockerFailure(requireTarget(target).customerLabel());
    }

    /** @deprecated Temporary A-zone display bridge for pre-zone callers. */
    @Deprecated
    public void showLockerSuccess(int lockerNumber) {
        showLockerSuccess(legacyDisplayLabel(lockerNumber));
    }

    /** @deprecated Temporary A-zone display bridge for pre-zone callers. */
    @Deprecated
    public void showLockerFailure(int lockerNumber) {
        showLockerFailure(legacyDisplayLabel(lockerNumber));
    }

    public void showDeviceConnectionFailure() {
        render(ResultState.DEVICE_CONNECTION_FAILED, null, false);
    }

    public void showSendFailure() {
        render(ResultState.SEND_FAILED, null, false);
    }

    public void showTimeout() {
        render(ResultState.COMMUNICATION_TIMEOUT, null, false);
    }

    public void showAuthorizationFailure(String message) {
        if (message == null || message.trim().isEmpty()) {
            throw new IllegalArgumentException("authorization message cannot be empty");
        }
        renderCopy(ResultState.DEVICE_CONNECTION_FAILED,
                "开柜失败", message,
                supportingFor(ResultState.DEVICE_CONNECTION_FAILED), false);
    }

    /** Compatibility bridge that still uses pixel assets and never stacks the legacy card. */
    public void showPrompt(ZipPromptModel model, boolean autoReturnHome) {
        if (model == null) {
            throw new IllegalArgumentException("model cannot be null");
        }
        ResultState state = autoReturnHome
                ? ResultState.SUCCESS : ResultState.DEVICE_CONNECTION_FAILED;
        renderCopy(state, model.title(), model.message(), supportingFor(state),
                autoReturnHome && state == ResultState.SUCCESS);
    }

    /** Compatibility bridge for an unmigrated customer failure presentation. */
    public void showFailure(CustomerFailurePresentation presentation) {
        if (presentation == null) {
            throw new IllegalArgumentException("presentation cannot be null");
        }
        renderCopy(ResultState.DEVICE_CONNECTION_FAILED,
                presentation.title(), presentation.detail(),
                supportingFor(ResultState.DEVICE_CONNECTION_FAILED), false);
    }

    public void hide() {
        handler.removeCallbacks(successReturn);
        actionTaken = true;
        removeActivePrompt();
        pixelShell.setOnSafeHomeRequested(null);
        setVisibility(GONE);
    }

    @Override
    protected void onDetachedFromWindow() {
        handler.removeCallbacks(successReturn);
        actionTaken = true;
        super.onDetachedFromWindow();
    }

    private void render(ResultState state, String displayLabel, boolean autoReturnHome) {
        renderCopy(state, titleFor(state),
                ZipLockerScreenRouter.messageFor(state, displayLabel),
                supportingFor(state), autoReturnHome);
    }

    private void renderCopy(ResultState state, CharSequence title,
            CharSequence detail, CharSequence supporting, boolean autoReturnHome) {
        if (state == null) {
            throw new IllegalArgumentException("result state cannot be null");
        }
        handler.removeCallbacks(successReturn);
        actionTaken = false;
        removeActivePrompt();

        ResultActions actions = ZipLockerScreenRouter.actionsFor(state);
        ZipPromptOverlay prompt = ZipPromptOverlay.pixelPrompt(
                getContext(), title, detail, supporting, actions,
                role -> retry(), role -> returnHome());
        activePrompt = prompt;
        pixelShell.overlayLayer().addView(prompt, match());
        pixelShell.setOnSafeHomeRequested(actions == ResultActions.NONE
                ? null : this::returnHome);
        pixelShell.setScreenAsset(ZipLockerScreenRouter.assetFor(state));

        setContentDescription(title + "，" + detail + "，" + supporting);
        setVisibility(VISIBLE);
        bringToFront();
        if (autoReturnHome) {
            handler.postDelayed(successReturn, SUCCESS_RETURN_MILLIS);
        }
    }

    private void removeActivePrompt() {
        ZipPromptOverlay prompt = activePrompt;
        activePrompt = null;
        if (prompt != null) {
            pixelShell.overlayLayer().removeView(prompt);
        }
    }

    private void retry() {
        if (actionTaken) {
            return;
        }
        actionTaken = true;
        handler.removeCallbacks(successReturn);
        Listener current = listener;
        if (current != null) {
            current.onRetry();
        }
    }

    private void returnHome() {
        if (actionTaken) {
            return;
        }
        actionTaken = true;
        handler.removeCallbacks(successReturn);
        Listener current = listener;
        if (current != null) {
            current.onReturnHome();
        }
    }

    private static String titleFor(ResultState state) {
        switch (state) {
            case VALIDATING:
                return "正在验证";
            case CONNECTING:
                return "正在连接设备";
            case WAITING_RESPONSE:
                return "正在开启柜门";
            case SUCCESS:
                return "开柜成功";
            case BOARD_REJECTED:
                return "柜门开启失败";
            case DEVICE_CONNECTION_FAILED:
                return "设备连接失败";
            case SEND_FAILED:
                return "指令发送失败";
            case COMMUNICATION_TIMEOUT:
                return "设备无响应";
            default:
                throw new IllegalArgumentException("unsupported result state: " + state);
        }
    }

    private static String supportingFor(ResultState state) {
        switch (ZipLockerScreenRouter.actionsFor(state)) {
            case NONE:
                return "操作进行中，请勿重复提交";
            case HOME:
                return "3 秒后自动返回首页";
            case RETRY_AND_HOME:
                return "可再次尝试或返回首页";
            default:
                throw new IllegalArgumentException("unsupported result state: " + state);
        }
    }

    private static LockerTarget requireTarget(LockerTarget target) {
        if (target == null) {
            throw new IllegalArgumentException("target cannot be null");
        }
        return target;
    }

    private static String legacyDisplayLabel(int lockerNumber) {
        if (lockerNumber < 1 || lockerNumber > 12) {
            throw new IllegalArgumentException("lockerNumber must be between 1 and 12");
        }
        return "A" + lockerNumber;
    }

    private static LayoutParams match() {
        return new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }
}
