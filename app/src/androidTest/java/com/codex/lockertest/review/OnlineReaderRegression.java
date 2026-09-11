package com.codex.lockertest.review;

import android.app.Instrumentation;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Spinner;
import com.codex.lockertest.business.*;
import com.codex.lockertest.server.*;
import com.codex.lockertest.ui.business.OnlineAdminPanel;
import com.codex.lockertest.ui.business.OnlineCustomerHost;
import java.util.*;
import java.util.function.BooleanSupplier;

final class OnlineReaderRegression {
    static void check(Instrumentation test) throws Exception {
        Service service = new Service(); Sources sources = new Sources();
        Maintenance maintenance = new Maintenance();
        OnlineAdminPanel[] panel = new OnlineAdminPanel[1];
        int[] homes = {0};
        test.runOnMainSync(() -> {
            panel[0] = new OnlineAdminPanel(test.getTargetContext(), service, sources, "terminal", false,
                    () -> homes[0]++, maintenance);
            OnlineBusinessRegression.savePreview(test, panel[0], "online-admin-login.png");
            require(find(panel[0], "保存识别来源 9") == null, "Source settings visible before authentication");
            require(find(panel[0], "串口设置") == null && find(panel[0], "百度人脸SDK设置") == null,
                    "Maintenance entry visible before server authentication");
            require(maintenance.serialCalls == 0 && maintenance.faceCalls == 0,
                    "Maintenance opened before server authentication");
            ((EditText)find(panel[0], "服务器管理员账号")).setText("installer");
            ((EditText)find(panel[0], "服务器管理员密码")).setText("test-password");
            find(panel[0], "服务器管理员登录").performClick();
        });
        try {
            await(test, () -> find(panel[0], "扫码读卡来源设置") != null);
            require(service.calls == 1 && service.background, "Admin login missing or blocked UI");
            require(service.omittedCode, "Optional dynamic code was not omitted");
            test.runOnMainSync(() -> {
                require(find(panel[0], "MQTT连接设置") != null, "MQTT settings entry missing after administrator login");
                require(find(panel[0], "保存识别来源 9") == null, "Server login skipped the maintenance menu");
                OnlineBusinessRegression.savePreview(test, panel[0], "online-admin-menu.png");
                View serial = find(panel[0], "串口设置");
                serial.performClick();
                require(maintenance.serialCalls == 1 && maintenance.faceCalls == 0
                        && maintenance.serialAuthorization.getAsBoolean(), "Serial entry did not receive live server authorization");
                serial.performClick();
                require(maintenance.serialCalls == 1, "Stale maintenance click launched twice");
                panel[0].resumeMenu();
                find(panel[0], "百度人脸SDK设置").performClick();
                require(maintenance.serialCalls == 1 && maintenance.faceCalls == 1
                        && maintenance.faceAuthorization.getAsBoolean(), "Face entry did not receive live server authorization");
                panel[0].resumeMenu();
                require(service.calls == 1 && sources.assignments == 0,
                        "Maintenance navigation repeated login or changed device settings");
                find(panel[0], "扫码读卡来源设置").performClick();
                OnlineBusinessRegression.savePreview(test, panel[0], "online-reader-sources.png");
                ((Spinner)find(panel[0], "识别来源 9")).setSelection(2);
                find(panel[0], "保存识别来源 9").performClick();
            });
            await(test, () -> sources.type == 4);
            test.runOnMainSync(() -> {
                find(panel[0], "退出管理员设置").performClick();
                require(homes[0] == 1, "Admin exit not routed home");
                require(!maintenance.serialAuthorization.getAsBoolean() && !maintenance.faceAuthorization.getAsBoolean(),
                        "Exiting administrator preserved a maintenance authorization");
                panel[0].resumeMenu();
                require(homes[0] == 1, "Closed administrator session reopened or exited twice");
            });
        } finally { test.runOnMainSync(() -> panel[0].close()); }
        checkRequiredCode(test);
        checkMaintenanceRevocation(test);
        checkKnownScans(test);
    }
    private static void checkMaintenanceRevocation(Instrumentation test) throws Exception {
        Maintenance maintenance = new Maintenance();
        Service service = new Service();
        OnlineAdminPanel[] panel = new OnlineAdminPanel[1];
        int[] homes = {0};
        test.runOnMainSync(() -> {
            panel[0] = new OnlineAdminPanel(test.getTargetContext(), service, new Sources(), "terminal", false,
                    () -> homes[0]++, maintenance);
            ((EditText)find(panel[0], "服务器管理员账号")).setText("installer");
            ((EditText)find(panel[0], "服务器管理员密码")).setText("test-password");
            find(panel[0], "服务器管理员登录").performClick();
        });
        try {
            await(test, () -> find(panel[0], "串口设置") != null);
            test.runOnMainSync(() -> {
                find(panel[0], "串口设置").performClick();
                maintenance.revoke.run();
                require(homes[0] == 1 && !maintenance.serialAuthorization.getAsBoolean(),
                        "Maintenance revocation did not close its server session");
                maintenance.revoke.run();
                panel[0].resumeMenu();
                require(homes[0] == 1 && maintenance.serialCalls == 1 && service.calls == 1,
                        "A revoked maintenance session was reused");
            });
        } finally { test.runOnMainSync(() -> panel[0].close()); }
    }
    private static void checkRequiredCode(Instrumentation test) throws Exception {
        Service service = new Service(); service.reject = true;
        OnlineAdminPanel[] panel = new OnlineAdminPanel[1];
        test.runOnMainSync(() -> {
            panel[0] = new OnlineAdminPanel(test.getTargetContext(), service, new Sources(), "terminal", true, () -> { });
            ((EditText)find(panel[0], "服务器管理员账号")).setText("installer");
            ((EditText)find(panel[0], "服务器管理员密码")).setText("test-password");
            find(panel[0], "服务器管理员登录").performClick();
            require(service.calls == 0, "Required dynamic code was omitted");
            ((EditText)find(panel[0], "管理员动态验证码")).setText("verify-test");
            find(panel[0], "服务器管理员登录").performClick();
        });
        try {
            await(test, () -> service.calls == 1 && find(panel[0], "服务器管理员登录").isEnabled());
            require("verify-test".equals(service.code), "Required dynamic code lost");
            test.runOnMainSync(() -> require(find(panel[0], "保存识别来源 9") == null
                    && find(panel[0], "串口设置") == null && find(panel[0], "百度人脸SDK设置") == null,
                    "Rejected admin entered configuration"));
        } finally { test.runOnMainSync(() -> panel[0].close()); }
    }
    private static void checkKnownScans(Instrumentation test) throws Exception {
        Service service = new Service(); Sources sources = new Sources(); sources.type = 4;
        com.codex.lockertest.bootstrap.BootstrapSnapshot ready = OnlineBusinessRegression.ready();
        OnlineCustomerHost[] host = new OnlineCustomerHost[1];
        test.runOnMainSync(() -> {
            host[0] = new OnlineCustomerHost(test.getTargetContext(), r -> service, sources, new OnlineCustomerHost.Ui() {
                public void show(View view) { }
                public void home() { }
                public void message(String message) { }
            });
            host[0].bind(ready);
            scan(host[0], "0012345678");
        });
        try {
            await(test, () -> service.customerCalls == 1);
            require(service.credential.type() == 4 && "0012345678".equals(service.credential.keyword()), "Card source or leading zeros changed");
            test.runOnMainSync(() -> { host[0].cancel(); sources.type = 2; scan(host[0], "qr-test~1~234567890"); });
            await(test, () -> service.customerCalls == 2);
            require(service.credential.type() == 2 && "qr-test~1~234567890".equals(service.credential.keyword()), "Variable length QR payload changed");
        } finally { test.runOnMainSync(() -> host[0].close()); }
    }
    private static void scan(OnlineCustomerHost host, String value) {
        for (int i = 0; i < value.length(); i++) host.scannerKey(9, value.charAt(i), false, 0, 0);
        host.scannerKey(9, -1, true, 0, 0);
    }
    static View find(View view, String description) {
        if (view == null) return null;
        if (description.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) {
            View found = find(((ViewGroup)view).getChildAt(i), description); if (found != null) return found;
        }
        return null;
    }
    interface Condition { boolean get(); }
    static void await(Instrumentation test, Condition condition) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 4000;
        while (SystemClock.elapsedRealtime() < deadline) {
            boolean[] ready = {false}; test.runOnMainSync(() -> ready[0] = condition.get());
            if (ready[0]) return; SystemClock.sleep(30);
        }
        throw new AssertionError("Reader setup operation timed out");
    }
    private static void require(boolean value, String error) { if (!value) throw new AssertionError(error); }
    static class Sources implements OnlineCustomerHost.ScanSourceResolver {
        volatile int type;
        volatile int assignments;
        public int credentialType(int id) { return id == 9 ? type : 0; }
        public List<OnlineCustomerHost.ScanInputDevice> devices() {
            return Collections.singletonList(new OnlineCustomerHost.ScanInputDevice(9, "Test USB reader", type, true));
        }
        public boolean assign(int id, int role) { if (id != 9) return false; assignments++; type = role; return true; }
    }
    private static final class Maintenance implements OnlineAdminPanel.MaintenanceActions {
        int serialCalls, faceCalls;
        BooleanSupplier serialAuthorization, faceAuthorization;
        Runnable revoke;
        public void onSerialRequested(BooleanSupplier authorized, Runnable revokeSession) {
            serialCalls++; serialAuthorization = authorized; revoke = revokeSession;
        }
        public void onFaceSdkRequested(BooleanSupplier authorized, Runnable revokeSession) {
            faceCalls++; faceAuthorization = authorized; revoke = revokeSession;
        }
    }
    static class Service implements BusinessService {
        volatile int calls; volatile boolean background; volatile boolean omittedCode;
        volatile boolean reject; volatile String code;
        volatile int customerCalls; volatile UserInfoRequest credential;
        public ApiResult<AdminLogin> adminLogin(String u, String p, String d, CallToken t) {
            calls++; background = android.os.Looper.myLooper() != android.os.Looper.getMainLooper(); omittedCode = d == null;
            code = d;
            if (reject) return ApiResult.failure(ServerFailure.of(ServerFailure.Kind.REMOTE_REJECTED));
            return ApiResult.success(new AdminLogin(SessionToken.of("test-token"), "venue", "device", "terminal", "area", "user", "name"));
        }
        public ApiResult<AuthenticatedUser> authenticate(UserInfoRequest r, CallToken t) {
            credential = r; customerCalls++;
            return ApiResult.success(new AuthenticatedUser(SessionToken.of("test-token"), UserType.USER, 0, 1));
        }
        public ApiResult<AuthenticatedUser> verifyMemberDynamicCode(AuthenticatedUser u, String c, CallToken t) { return BusinessService.unavailable(); }
        public ApiResult<ControlPanelPreview> controlPanelPreview(AuthenticatedUser u, int a, long b, int c, CallToken t) { return BusinessService.unavailable(); }
        public ApiResult<UsedCabinetList> useCabinetList(AuthenticatedUser u, CallToken t) { return BusinessService.unavailable(); }
        public ApiResult<AssignedCabinet> userBoard(AuthenticatedUser u, long a, long c, CallToken t) { throw new AssertionError("Physical assignment during setup"); }
        public ApiResult<EmptyBusinessResult> openBoard(AuthenticatedUser u, BoardAction a, long c, CallToken t) { throw new AssertionError("Physical open during setup"); }
    }
}
