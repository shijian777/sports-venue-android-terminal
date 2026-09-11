package com.codex.lockertest.ui.business;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import com.codex.lockertest.business.journey.OnlineCustomerCoordinator;
import com.codex.lockertest.business.mqtt.*;
import com.codex.lockertest.server.CallToken;
import com.codex.lockertest.ui.UiKit;
import com.codex.lockertest.ui.ZipKioskShell;

/** Authenticated configuration only. This view has no lock or MQTT message consumer. */
public final class OnlineMqttPanel extends FrameLayout implements AutoCloseable {
    public interface Authorization { boolean allowed(); }
    private enum Kind { LOAD, SAVE, PROBE }
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AndroidBusinessScheduler scheduler = new AndroidBusinessScheduler(handler);
    private final MqttSettingsStore store;
    private final MqttProbe probe;
    private final Authorization authorization;
    private final Runnable back;
    private final EditText host, port, client, topic, username, password;
    private final Button save, test;
    private final TextView status;
    private volatile boolean closed;
    private Operation current;

    public OnlineMqttPanel(Context context, MqttSettingsStore store, MqttProbe probe,
            Authorization authorization, Runnable back) {
        super(context);
        if (store == null || probe == null || authorization == null || back == null)
            throw new IllegalArgumentException("MQTT settings dependencies required");
        this.store = store; this.probe = probe; this.authorization = authorization; this.back = back;
        View cover = new View(context); cover.setBackgroundColor(Color.WHITE);
        place(cover, 20, 105, 1240, 605);
        place(text("MQTT 连接设置", 27), 350, 128, 580, 44);
        place(text("管理员登录后 60 秒退出；消息协议接入前仅支持保存与连接测试", 18), 115, 174, 1050, 35);
        host = field("服务器地址（域名或 IPv4）", "MQTT服务器地址", "不含协议、端口或路径", 120, 214, false, 253);
        port = field("TLS 端口", "MQTT端口", "例如 8883", 680, 214, false, 5);
        port.setInputType(InputType.TYPE_CLASS_NUMBER);
        client = field("客户端标识 Client ID", "MQTT客户端标识", "填写后台分配的终端标识", 120, 304, false, 128);
        topic = field("订阅主题 Topic", "MQTT订阅主题", "填写本终端的完整主题，不含通配符", 680, 304, false, 512);
        username = field("MQTT 账号（可留空）", "MQTT账号", "使用 MQTT 账号，不是后台登录账号", 120, 394, false, 256);
        password = field("MQTT 密码（加密保存）", "MQTT密码", "匿名服务可留空", 680, 394, true, 4096);
        Switch enable = new Switch(context); enable.setText("远程开柜：消息协议未接入");
        enable.setTextSize(TypedValue.COMPLEX_UNIT_PX, unit(19)); enable.setChecked(false); enable.setEnabled(false);
        enable.setContentDescription("MQTT远程开柜启用"); place(enable, 120, 493, 475, 40);
        place(text("测试使用临时标识，不验证正式标识或主题权限", 17), 635, 493, 560, 40);
        status = text("", 18); status.setMaxLines(2); status.setContentDescription("MQTT操作状态");
        place(status, 110, 543, 1060, 64);
        save = button("保存配置", "保存MQTT设置"); save.setOnClickListener(v -> submit(Kind.SAVE)); place(save, 145, 636, 245, 54);
        test = button("测试连接", "测试MQTT连接"); test.setOnClickListener(v -> submit(Kind.PROBE)); place(test, 515, 636, 245, 54);
        Button exit = button("返回设备设置", "返回设备设置"); exit.setOnClickListener(v -> leave()); place(exit, 885, 636, 250, 54);
        busy(true);
        if (!handler.post(() -> execute(Kind.LOAD, null))) {
            busy(false); status.setText("配置读取未开始，请返回后重试");
        }
    }

    private void submit(Kind kind) {
        if (closed || current != null) return;
        if (!allowed()) { leave(); return; }
        try {
            MqttSettings settings = new MqttSettings(host.getText().toString(), Integer.parseInt(port.getText().toString()),
                    client.getText().toString(), username.getText().toString(), password.getText().toString(), topic.getText().toString());
            execute(kind, settings);
        } catch (IllegalArgumentException invalid) {
            status.setText("请检查地址、端口、客户端标识和主题；密码不为空时也需填写账号");
        }
    }

    private void execute(Kind kind, MqttSettings settings) {
        if (closed || current != null) return;
        if (!allowed()) { leave(); return; }
        Operation operation = new Operation(kind); current = operation; busy(true);
        status.setText(kind == Kind.LOAD ? "正在读取已保存配置…" : kind == Kind.SAVE ? "正在加密保存配置…" : "正在验证 TLS 和 MQTT 登录，最长 15 秒…");
        try {
            if (kind == Kind.PROBE) {
                operation.timeout = () -> {
                    if (closed || current != operation) return;
                    cancelCurrent(CallToken.Reason.TIMEOUT); busy(false);
                    if (!allowed()) { leave(); return; }
                    status.setText("连接测试超时，请检查网络、地址和端口后重试");
                };
                if (!handler.postDelayed(operation.timeout, 15_000)) throw new IllegalStateException("Timer unavailable");
            }
            operation.worker = scheduler.submit(() -> {
                MqttSettings loaded = null; MqttProbe.Outcome outcome = null; boolean succeeded = false;
                try {
                    if (allowed() && !operation.token.isCancelled() && !closed) {
                        if (kind == Kind.LOAD) loaded = store.load();
                        else if (kind == Kind.SAVE) store.save(settings);
                        else outcome = probe.test(settings, operation.token);
                        succeeded = true;
                    }
                } catch (Exception | LinkageError failure) { /* Only fixed, non-sensitive messages reach the screen. */ }
                final MqttSettings result = loaded;
                final MqttProbe.Outcome resultOutcome = outcome;
                final boolean success = succeeded;
                handler.post(() -> complete(operation, success, result, resultOutcome));
            });
        } catch (RuntimeException unavailable) {
            cancelCurrent(CallToken.Reason.CANCELLED); busy(false); status.setText("操作未开始，请稍后重试");
        }
    }

    private void complete(Operation operation, boolean success, MqttSettings loaded, MqttProbe.Outcome outcome) {
        if (closed || current != operation) return;
        current = null;
        if (operation.timeout != null) handler.removeCallbacks(operation.timeout);
        if (!allowed()) { leave(); return; }
        busy(false);
        if (!success) {
            status.setText(operation.kind == Kind.SAVE ? "保存失败，原有配置未确认更新；请检查设备存储后重试"
                    : operation.kind == Kind.LOAD ? "配置读取失败，请检查设备配置或存储后重试；不会自动启用"
                    : "连接测试失败，请检查网络与配置后重试");
            return;
        }
        switch (operation.kind) {
            case LOAD:
                if (loaded != null) {
                    host.setText(loaded.host()); port.setText(Integer.toString(loaded.port())); client.setText(loaded.clientId());
                    topic.setText(loaded.topic()); username.setText(loaded.username()); password.setText(loaded.password());
                }
                status.setText(loaded == null ? "尚未配置。仅支持 TLS 加密连接，不会自动连接或开柜" : "已读取保存配置；远程开柜仍未启用");
                break;
            case SAVE: status.setText("配置已加密保存；远程开柜仍未启用"); break;
            case PROBE: status.setText(probeMessage(outcome)); break;
        }
    }

    private static String probeMessage(MqttProbe.Outcome outcome) {
        if (outcome == null) return "连接测试未完成，请稍后重试";
        switch (outcome) {
            case CONNECTED: return "连接验证通过，测试连接已结束；未订阅主题，未启用远程开柜";
            case AUTH_REJECTED: return "账号或权限未通过，请确认 MQTT 凭据以及临时客户端是否被允许";
            case TLS_FAILED: return "安全连接验证失败，请检查服务器证书、域名和终端时间";
            case UNSUPPORTED: return "当前设备或服务器不支持本测试使用的 TLS／MQTT 3.1.1 配置";
            case SERVER_UNAVAILABLE: return "MQTT 服务暂不可用，请稍后重试";
            case TIMEOUT: return "连接测试超时，请检查网络、地址和端口后重试";
            case CANCELLED: return "连接测试已取消";
            case INVALID_RESPONSE: return "服务器响应不符合 MQTT 连接协议，请检查端口与服务类型";
            default: return "网络连接失败，请检查网络、地址和端口";
        }
    }

    private boolean allowed() { try { return authorization.allowed(); } catch (RuntimeException failure) { return false; } }
    private void busy(boolean value) {
        for (EditText field : new EditText[]{host, port, client, topic, username, password}) field.setEnabled(!value);
        save.setEnabled(!value); test.setEnabled(!value);
    }
    private void cancelCurrent(CallToken.Reason reason) {
        Operation operation = current; current = null;
        if (operation == null) return;
        operation.token.cancel(reason);
        if (operation.timeout != null) handler.removeCallbacks(operation.timeout);
        if (operation.worker != null) operation.worker.cancel();
    }
    private void leave() { if (closed) return; close(); back.run(); }
    @Override public void close() {
        if (closed) return; closed = true; cancelCurrent(CallToken.Reason.CANCELLED); scheduler.close();
        handler.removeCallbacksAndMessages(null);
        for (EditText field : new EditText[]{host, port, client, topic, username, password}) field.setText("");
    }
    private EditText field(String label, String description, String hint, int x, int y, boolean secret, int max) {
        TextView caption = text(label, 18); caption.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL); place(caption, x, y, 475, 30);
        EditText edit = new EditText(getContext()); edit.setSingleLine(true); edit.setSaveEnabled(false);
        edit.setContentDescription(description); edit.setHint(hint); edit.setFilters(new InputFilter[]{new InputFilter.LengthFilter(max)});
        edit.setInputType(InputType.TYPE_CLASS_TEXT | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
        edit.setTextSize(TypedValue.COMPLEX_UNIT_PX, unit(19)); edit.setPadding(unit(10), 0, unit(10), 0);
        edit.setBackgroundColor(Color.rgb(241, 247, 245)); place(edit, x, y + 32, 475, 50); return edit;
    }
    private TextView text(String value, int size) {
        TextView view = UiKit.text(getContext(), value, 18, UiKit.TEXT, Typeface.NORMAL);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, unit(size)); view.setGravity(Gravity.CENTER); return view;
    }
    private Button button(String value, String description) {
        Button view = UiKit.button(getContext(), value, 18, UiKit.GREEN, Color.WHITE, unit(14) / getResources().getDisplayMetrics().density);
        view.setContentDescription(description); view.setTextSize(TypedValue.COMPLEX_UNIT_PX, unit(21)); return view;
    }
    private int unit(int value) { return ZipKioskShell.unit(getContext(), value); }
    private void place(View view, int x, int y, int width, int height) {
        LayoutParams params = new LayoutParams(unit(width), unit(height)); params.leftMargin = unit(x); params.topMargin = unit(y); addView(view, params);
    }
    private static final class Operation {
        final Kind kind; final CallToken token = new CallToken();
        OnlineCustomerCoordinator.Cancellable worker; Runnable timeout;
        Operation(Kind kind) { this.kind = kind; }
    }
}
