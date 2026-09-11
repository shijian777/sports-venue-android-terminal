package com.codex.lockertest.palm;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.codex.lockertest.ui.UiKit;
import com.codex.lockertest.ui.ZipKioskShell;
import com.codex.lockertest.ui.zip.ZipPixelShell;
import com.codex.lockertest.ui.zip.ZipScreenAsset;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local-only test; no identity, persistence, network, or locker action is reachable here. */
public final class PalmHardwareTestPanel extends FrameLayout implements PalmHardwareTestPanelHandle {
    // A single queue also serializes cleanup when a new panel is opened immediately.
    private static final ExecutorService DEVICE_WORKER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "palm-device-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final PalmTestController controller;
    private final JxPalmTestDriver driver;
    private final TextView status;
    private final Button enroll;
    private final Button verify;
    private final Button reconnect;
    private volatile boolean closed;

    public PalmHardwareTestPanel(Context context, Runnable onBack) {
        super(context);
        setContentDescription("掌静脉硬件测试");
        ZipPixelShell shell = new ZipPixelShell(context, ZipScreenAsset.PALM_GUIDE);
        shell.setOnSafeHomeRequested(null);
        addView(shell, new LayoutParams(-1, -1));
        FrameLayout content = shell.contentLayer();
        FrameLayout overlay = shell.overlayLayer();
        FrameLayout panel = new FrameLayout(context);
        panel.setBackgroundColor(Color.rgb(247, 251, 252));
        place(content, panel, 20, 105, 1240, 605);
        TextView heading = text(context, "掌静脉设备测试", 30, UiKit.NAVY);
        place(content, heading, 300, 145, 680, 65);
        TextView notice = text(context,
                "仅本机临时测试 · 不上传、不绑定会员、不执行开柜\n退出或切换到后台即清除测试特征", 19, UiKit.NAVY);
        place(content, notice, 220, 225, 840, 82);
        status = text(context, "正在检查掌静脉设备…", 23, UiKit.NAVY);
        status.setBackground(UiKit.roundedSolid(context, Color.WHITE,
                designDp(context, 12), UiKit.GREEN, 2));
        place(content, status, 280, 330, 720, 145);
        enroll = button(context, "开始录入测试", "掌静脉开始录入测试");
        verify = button(context, "验证刚才的手掌", "掌静脉验证临时模板");
        reconnect = button(context, "重新连接", "掌静脉重新连接设备");
        Button back = button(context, "返回", "退出掌静脉测试并清除数据");
        place(overlay, enroll, 295, 520, 230, 58);
        place(overlay, verify, 555, 520, 230, 58);
        place(overlay, reconnect, 815, 520, 175, 58);
        place(overlay, back, 1050, 110, 176, 38);
        TextView footer = text(context, "请将手掌平放在掌静脉识别器上方，按设备提示保持稳定", 18, UiKit.NAVY);
        place(content, footer, 235, 605, 820, 50);

        driver = new JxPalmTestDriver(context, DEVICE_WORKER);
        controller = new PalmTestController(driver,
                DEVICE_WORKER, (task, delay) -> {
                    if (!ui.postDelayed(task, delay)) return null;
                    return () -> ui.removeCallbacks(task);
                }, (state, code) -> ui.post(() -> render(state, code)));
        enroll.setOnClickListener(view -> { if (!closed) controller.enroll(); });
        verify.setOnClickListener(view -> { if (!closed) controller.verify(); });
        reconnect.setOnClickListener(view -> { if (!closed) controller.connect(); });
        back.setOnClickListener(view -> { close(); onBack.run(); });
        render(PalmTestController.State.IDLE, 0);
        ui.post(() -> { if (!closed) controller.connect(); });
    }

    private void render(PalmTestController.State state, int code) {
        if (closed || state != controller.state()) return;
        boolean ready = state == PalmTestController.State.READY;
        boolean sample = state == PalmTestController.State.ENROLLED
                || state == PalmTestController.State.MATCHED || state == PalmTestController.State.NOT_MATCHED;
        enroll.setEnabled(ready || sample);
        verify.setEnabled(sample);
        reconnect.setEnabled(state == PalmTestController.State.IDLE || state == PalmTestController.State.FAILED);
        String message;
        switch (state) {
            case CONNECTING: message = "正在检查设备与授权，请稍候…"; break;
            case READY: message = "掌静脉设备已连接\n请点击“开始录入测试”"; break;
            case ENROLLING: message = "正在录入临时测试特征\n请保持手掌稳定（最长 30 秒）"; break;
            case ENROLLED: message = "临时特征采集完成\n可以验证刚才的手掌；尚未向服务器登记"; break;
            case VERIFYING: message = "正在进行本机单模板比对\n请再次放入手掌（最长 10 秒）"; break;
            case MATCHED: message = "本机比对通过\n此结果不代表服务器授权"; break;
            case NOT_MATCHED: message = "本机比对未通过\n请重新放置同一只手掌后再试"; break;
            case FAILED: message = failureMessage(code); break;
            default: message = "正在准备掌静脉设备测试…";
        }
        status.setText(message);
        status.setContentDescription(message);
    }

    private static String failureMessage(int code) {
        switch (code) {
            case PalmTestController.ERROR_TIMEOUT: return "本次测试等待超时\n请重新连接，按设备提示再次放置手掌";
            case PalmTestController.ERROR_DISCONNECTED: return "掌静脉设备已断开，测试数据已清除\n请检查连接后重试";
            case PalmTestController.ERROR_INVALID_FEATURE: return "未获得有效的掌静脉特征\n请重新连接后再试";
            case -2001: return "未发现掌静脉设备\n请检查连接后点击“重新连接”";
            case -2002: return "未获得掌静脉设备使用权限\n请重新连接并允许访问";
            case -2003: return "发现多个掌静脉设备\n测试时请只连接一台";
            case -2004: return "掌静脉组件暂时不可用\n请检查终端与 SDK 兼容性";
            case -2005: return "掌静脉设备仍在清理上一任务\n请稍候重试";
            case -2006: return "掌静脉设备清理未完成\n请重新启动应用再测试";
            default:
                if (code >= 10001 && code <= 10009) {
                    return "掌静脉设备授权未通过（状态 " + code + "）\n请联系设备厂家确认授权";
                }
                return "本次测试未完成（状态 " + code + "）\n临时数据已清除，请重新连接后再试";
        }
    }

    @Override public View view() { return this; }
    @Override public void close() {
        if (closed) return;
        closed = true;
        driver.invalidate();
        controller.close();
        enroll.setEnabled(false);
        verify.setEnabled(false);
        reconnect.setEnabled(false);
    }
    @Override protected void onDetachedFromWindow() { close(); super.onDetachedFromWindow(); }
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != View.VISIBLE && controller != null) close();
    }

    private static TextView text(Context context, String label, int size, int color) {
        TextView text = UiKit.text(context, label, size, color, Typeface.BOLD);
        text.setTextSize(TypedValue.COMPLEX_UNIT_PX, ZipKioskShell.unit(context, size));
        text.setGravity(Gravity.CENTER);
        return text;
    }
    private static Button button(Context context, String label, String description) {
        Button result = UiKit.button(context, label, 17, UiKit.GREEN, Color.WHITE,
                designDp(context, 22));
        result.setTextSize(TypedValue.COMPLEX_UNIT_PX, ZipKioskShell.unit(context, 17));
        int padding = ZipKioskShell.unit(context, 8);
        result.setPadding(padding, 0, padding, 0);
        result.setContentDescription(description);
        return result;
    }
    private static float designDp(Context context, float units) {
        return ZipKioskShell.unit(context, units)
                / Math.max(0.01f, context.getResources().getDisplayMetrics().density);
    }
    private static void place(FrameLayout parent, View child, int x, int y, int width, int height) {
        LayoutParams params = new LayoutParams(ZipKioskShell.unit(parent.getContext(), width),
                ZipKioskShell.unit(parent.getContext(), height));
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.leftMargin = ZipKioskShell.unit(parent.getContext(), x);
        params.topMargin = ZipKioskShell.unit(parent.getContext(), y);
        parent.addView(child, params);
    }
}
