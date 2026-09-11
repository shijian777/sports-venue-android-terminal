package com.codex.lockertest.review;

import android.app.Instrumentation;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import com.codex.lockertest.business.mqtt.*;
import com.codex.lockertest.server.CallToken;
import com.codex.lockertest.ui.business.OnlineAdminPanel;
import com.codex.lockertest.ui.business.OnlineMqttPanel;
import java.util.concurrent.atomic.AtomicBoolean;

/** Real views and workers; only disk/broker boundaries are replaced, never a lock action. */
final class OnlineMqttRegression {
    static void check(Instrumentation test) throws Exception {
        Store store = new Store(); Probe probe = new Probe();
        OnlineAdminPanel[] admin = new OnlineAdminPanel[1];
        test.runOnMainSync(() -> {
            admin[0] = new OnlineAdminPanel(test.getTargetContext(), new OnlineReaderRegression.Service(),
                    new OnlineReaderRegression.Sources(), "terminal", false, () -> {}, store, probe);
            require(find(admin[0], "MQTT连接设置") == null, "Unauthenticated MQTT access");
            input(admin[0], "服务器管理员账号", "installer");
            input(admin[0], "服务器管理员密码", "test-password");
            find(admin[0], "服务器管理员登录").performClick();
        });
        try {
            OnlineReaderRegression.await(test, () -> find(admin[0], "MQTT连接设置") != null);
            test.runOnMainSync(() -> find(admin[0], "MQTT连接设置").performClick());
            OnlineReaderRegression.await(test, () -> find(admin[0], "保存MQTT设置") != null
                    && find(admin[0], "保存MQTT设置").isEnabled());
            test.runOnMainSync(() -> {
                require(store.saved == null && probe.calls == 0, "Opening form caused mutation or network");
                require(!find(admin[0], "MQTT远程开柜启用").isEnabled(), "Unknown message protocol enabled");
                OnlineBusinessRegression.savePreview(test, admin[0], "online-mqtt-settings.png");
                find(admin[0], "保存MQTT设置").performClick();
                require(store.saved == null, "Invalid form saved");
                fill(admin[0]);
                EditText password = (EditText)find(admin[0], "MQTT密码");
                require(!password.isSaveEnabled() && password.getTransformationMethod() != null, "Credential field not protected");
                find(admin[0], "保存MQTT设置").performClick();
            });
            OnlineReaderRegression.await(test, () -> store.saved != null && find(admin[0], "保存MQTT设置").isEnabled());
            require(store.background && "0001-secret".equals(store.saved.password()), "Saved configuration changed or blocked UI");
            test.runOnMainSync(() -> {
                find(admin[0], "返回设备设置").performClick();
                require(find(admin[0], "扫码读卡来源设置") != null && find(admin[0], "保存MQTT设置") == null,
                        "MQTT return did not restore the maintenance menu");
                find(admin[0], "MQTT连接设置").performClick();
            });
            OnlineReaderRegression.await(test, () -> find(admin[0], "保存MQTT设置").isEnabled());
            test.runOnMainSync(() -> {
                require("0001-secret".equals(((EditText)find(admin[0], "MQTT密码")).getText().toString()), "Configuration not reloaded");
                find(admin[0], "测试MQTT连接").performClick();
                find(admin[0], "测试MQTT连接").performClick();
            });
            OnlineReaderRegression.await(test, () -> status(admin[0]).contains("连接验证通过"));
            require(probe.calls == 1 && probe.background, "Duplicate probe or UI-thread network");
            test.runOnMainSync(() -> {
                probe.outcome = MqttProbe.Outcome.AUTH_REJECTED;
                find(admin[0], "测试MQTT连接").performClick();
            });
            OnlineReaderRegression.await(test, () -> status(admin[0]).contains("账号或权限"));
            test.runOnMainSync(() -> {
                store.fail = true;
                find(admin[0], "保存MQTT设置").performClick();
            });
            OnlineReaderRegression.await(test, () -> status(admin[0]).contains("保存失败"));
            require(!status(admin[0]).contains("sensitive-storage-error"), "Storage detail leaked");
        } finally { test.runOnMainSync(() -> admin[0].close()); }
        checkLoadFailure(test);
        checkCancellation(test);
        checkTimeout(test);
        checkUnauthorized(test);
        checkAndroidKeystore(test);
    }

    private static void checkLoadFailure(Instrumentation test) throws Exception {
        Store store = new Store(); store.failLoad = true; Probe probe = new Probe();
        OnlineMqttPanel[] panel = new OnlineMqttPanel[1];
        test.runOnMainSync(() -> panel[0] = new OnlineMqttPanel(test.getTargetContext(), store, probe, () -> true, () -> {}));
        try {
            OnlineReaderRegression.await(test, () -> find(panel[0], "保存MQTT设置").isEnabled());
            test.runOnMainSync(() -> require(status(panel[0]).contains("配置读取失败")
                    && !status(panel[0]).contains("重新完整填写并保存")
                    && !status(panel[0]).contains("sensitive-storage-error"), "Load failure promised unsupported recovery or leaked detail"));
            require(probe.calls == 0 && store.saved == null, "Load failure caused network or save");
        } finally { test.runOnMainSync(() -> panel[0].close()); }
    }

    private static void checkCancellation(Instrumentation test) throws Exception {
        Store store = new Store(); store.saved = settings();
        AtomicBoolean allowed = new AtomicBoolean(true); Probe probe = new Probe(); probe.block = true;
        OnlineMqttPanel[] panel = new OnlineMqttPanel[1];
        test.runOnMainSync(() -> panel[0] = new OnlineMqttPanel(test.getTargetContext(), store, probe, allowed::get, () -> {}));
        OnlineReaderRegression.await(test, () -> find(panel[0], "测试MQTT连接").isEnabled());
        test.runOnMainSync(() -> find(panel[0], "测试MQTT连接").performClick());
        OnlineReaderRegression.await(test, () -> probe.token != null);
        test.runOnMainSync(() -> { allowed.set(false); panel[0].close(); });
        require(probe.token.isCancelled(), "Closing admin did not cancel connection");
        test.runOnMainSync(() -> require(((EditText)find(panel[0], "MQTT密码")).getText().length() == 0,
                "Closed form retained password"));
    }

    private static void checkTimeout(Instrumentation test) throws Exception {
        Store store = new Store(); store.saved = settings(); Probe probe = new Probe(); probe.block = true;
        OnlineMqttPanel[] panel = new OnlineMqttPanel[1];
        test.runOnMainSync(() -> panel[0] = new OnlineMqttPanel(test.getTargetContext(), store, probe, () -> true, () -> {}));
        try {
            OnlineReaderRegression.await(test, () -> find(panel[0], "测试MQTT连接").isEnabled());
            test.runOnMainSync(() -> find(panel[0], "测试MQTT连接").performClick());
            long deadline = android.os.SystemClock.elapsedRealtime() + 18000;
            boolean[] timedOut = {false};
            while (!timedOut[0] && android.os.SystemClock.elapsedRealtime() < deadline) {
                test.runOnMainSync(() -> timedOut[0] = status(panel[0]).contains("超时"));
                if (!timedOut[0]) android.os.SystemClock.sleep(50);
            }
            require(timedOut[0] && probe.token.reason() == CallToken.Reason.TIMEOUT, "Probe did not enforce global timeout");
            OnlineReaderRegression.await(test, () -> probe.finished);
            test.runOnMainSync(() -> require(status(panel[0]).contains("超时") && find(panel[0], "测试MQTT连接").isEnabled(),
                    "Late probe reply replaced timeout or left form disabled"));
        } finally { test.runOnMainSync(() -> panel[0].close()); }
    }

    private static void checkUnauthorized(Instrumentation test) throws Exception {
        Store store = new Store(); Probe probe = new Probe(); int[] exits = {0};
        OnlineMqttPanel[] panel = new OnlineMqttPanel[1];
        test.runOnMainSync(() -> panel[0] = new OnlineMqttPanel(test.getTargetContext(), store, probe, () -> false, () -> exits[0]++));
        OnlineReaderRegression.await(test, () -> exits[0] == 1);
        require(store.loads == 0 && store.saved == null && probe.calls == 0, "Expired admin reached storage/network");
        test.runOnMainSync(() -> panel[0].close());
    }

    private static void checkAndroidKeystore(Instrumentation test) throws Exception {
        // Runner is installed only on the disposable API25 emulator. No network consumer exists.
        MqttSettingsStore first = new com.codex.lockertest.ui.business.AndroidMqttSettingsStore(test.getTargetContext());
        first.save(settings());
        MqttSettingsStore reopened = new com.codex.lockertest.ui.business.AndroidMqttSettingsStore(test.getTargetContext());
        MqttSettings restored = reopened.load();
        require(restored != null && "mqtt.example.test".equals(restored.host())
                && "0001-secret".equals(restored.password()) && "locker/0001".equals(restored.topic()),
                "Android Keystore persistence did not round-trip across adapters");
        java.io.File base = new java.io.File(test.getTargetContext().getNoBackupFilesDir(), "mqtt-settings-v1.bin");
        java.io.File backup = new java.io.File(base.getPath() + ".bak");
        require(!backup.exists() && base.renameTo(backup), "Could not arrange interrupted atomic-write fixture");
        restored = reopened.load();
        require(restored != null && "0001-secret".equals(restored.password()), "Interrupted-write backup was not recovered");
    }

    private static MqttSettings settings() { return new MqttSettings("mqtt.example.test", 8883, "terminal-0001", "test-user", "0001-secret", "locker/0001"); }
    private static void fill(View view) {
        input(view, "MQTT服务器地址", "mqtt.example.test"); input(view, "MQTT端口", "8883");
        input(view, "MQTT客户端标识", "terminal-0001"); input(view, "MQTT订阅主题", "locker/0001");
        input(view, "MQTT账号", "test-user"); input(view, "MQTT密码", "0001-secret");
    }
    private static View find(View view, String key) { return OnlineReaderRegression.find(view, key); }
    private static void input(View view, String key, String text) { ((EditText)find(view, key)).setText(text); }
    private static String status(View view) { return ((TextView)find(view, "MQTT操作状态")).getText().toString(); }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static class Store implements MqttSettingsStore {
        volatile MqttSettings saved; volatile boolean background; volatile boolean fail; volatile boolean failLoad; volatile int loads;
        public MqttSettings load() throws Exception {
            loads++;
            if (failLoad) throw new java.io.IOException("sensitive-storage-error");
            return saved;
        }
        public void save(MqttSettings settings) throws Exception {
            if (fail) throw new java.io.IOException("sensitive-storage-error");
            background = Looper.myLooper() != Looper.getMainLooper(); saved = settings;
        }
    }
    private static class Probe implements MqttProbe {
        volatile int calls; volatile boolean background; volatile CallToken token; volatile boolean block; volatile boolean finished;
        volatile Outcome outcome = Outcome.CONNECTED;
        public Outcome test(MqttSettings settings, CallToken token) {
            calls++; background = Looper.myLooper() != Looper.getMainLooper(); this.token = token;
            if (block) {
                java.util.concurrent.CountDownLatch cancelled = new java.util.concurrent.CountDownLatch(1);
                CallToken.Registration hook = token.onCancel(cancelled::countDown);
                try { cancelled.await(20, java.util.concurrent.TimeUnit.SECONDS); }
                catch (InterruptedException expected) { Thread.currentThread().interrupt(); }
                finally { hook.unregister(); }
            }
            finished = true; return outcome;
        }
    }
}
