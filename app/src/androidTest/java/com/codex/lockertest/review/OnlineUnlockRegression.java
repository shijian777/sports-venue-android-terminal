package com.codex.lockertest.review;

import android.app.Instrumentation;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import com.codex.lockertest.business.*;
import com.codex.lockertest.business.journey.*;
import com.codex.lockertest.server.*;
import com.codex.lockertest.ui.business.OnlineCustomerHost;
import com.codex.lockertest.unlock.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Real Android UI + real asynchronous coordinator; no network or physical lock writes. */
final class OnlineUnlockRegression {
    static void check(Instrumentation test) throws Exception {
        Fixture service = new Fixture();
        FakeExecutor executor = new FakeExecutor();
        OnlineCustomerHost[] host = new OnlineCustomerHost[1];
        View[] current = new View[1];
        com.codex.lockertest.bootstrap.BootstrapSnapshot binding = OnlineBusinessRegression.ready();
        main(test, () -> {
            host[0] = new OnlineCustomerHost(test.getTargetContext(), r -> service, id -> 0,
                    new OnlineCustomerHost.Ui() {
                        public void show(View view) { current[0] = view; }
                        public void home() { current[0] = null; }
                        public void message(String message) { }
                    }, null, executor);
            host[0].bind(binding);
            host[0].submitPhone("13800000001", "001234");
        });
        try {
            await(test, () -> find(current[0], "柜门 自定义001", true) != null);
            main(test, () -> {
                View confirm = find(current[0], "确认使用所选柜门", false);
                require(confirm != null && !confirm.isEnabled(), "Online confirm missing or enabled before selection");
                View used = find(current[0], "柜门 已占用", true);
                require(used != null && !used.isEnabled(), "Occupied cabinet should not be selectable");
                View random = find(current[0], "随机选择本页空闲柜门，确认后开柜", false);
                require(random != null && random.isEnabled(), "Missing random cabinet selection");
                random.performClick();
                require(service.allocations.get() == 0 && executor.calls.get() == 0,
                        "Random selection must not allocate or dispatch before confirmation");
                View selected = find(current[0], "柜门 自定义001", true);
                require(selected instanceof android.widget.Button
                                && ((android.widget.Button) selected).getText().toString().contains("已选"),
                        "Selected cabinet still appears free instead of selected");
                find(current[0], "柜门 自定义001", true).performClick();
                confirm = find(current[0], "确认使用所选柜门", false);
                require(confirm != null && confirm.isEnabled(), "Selecting free cabinet did not enable confirmation");
                OnlineBusinessRegression.savePreview(test, current[0], "online-unlock-selected.png");
                confirm.performClick();
                confirm.performClick(); // Stale view must not duplicate a server mutation.
            });
            await(test, () -> executor.calls.get() == 1);
            require(service.allocations.get() == 1, "Duplicate server allocation");
            require(Arrays.equals(executor.request.unlockCommand(), new byte[]{(byte) 0x8A,2,2,0x11,(byte) 0x9B}),
                    "UI label/region changed board02 channel02 command");
            main(test, () -> executor.listener.onState(UnlockCoordinator.State.WAITING_ACK, "正在开启柜门，请稍候"));
            await(test, () -> find(current[0], "等待锁板返回", true) != null);
            require(findOnMain(test, current, "开柜成功") == null, "API approval displayed physical success before ACK");
            main(test, () -> OnlineBusinessRegression.savePreview(test, current[0], "detail-opening-busy.png"));
            main(test, () -> executor.listener.onState(UnlockCoordinator.State.SUCCESS, "B2号柜门已打开"));
            await(test, () -> find(current[0], "开柜成功", false) != null);
            main(test, () -> {
                require(find(current[0], "重新尝试服务器查询", false) == null, "Successful open offers unsafe retry");
                OnlineBusinessRegression.savePreview(test, current[0], "online-unlock-success.png");
                find(current[0], "返回首页并清除认证会话", false).performClick();
                require(!host[0].hasJourney(), "Back retained authenticated opening session");
            });
            require(service.opens.get() == 0, "Undocumented openBoard call after userBoard");
        } finally { main(test, () -> host[0].close()); }
        checkUnavailable(test);
        checkFailureRecovery(test);
        checkNoFreeCabinet(test);
        checkLongSelectionNotice(test);
    }

    private static void checkLongSelectionNotice(Instrumentation test) throws Exception {
        Scenario scenario = new Scenario(test);
        scenario.service.label = "自定义001" + String.join("", Collections.nCopies(30, "运动场馆东侧柜门"));
        try {
            scenario.start();
            main(test, () -> {
                find(scenario.current[0], "柜门 自定义001", true).performClick();
                OnlineBusinessRegression.savePreview(test, scenario.current[0], "detail-cabinet-long-selected.png");
                View full = find(scenario.current[0], "已选择", true);
                require(full instanceof android.widget.TextView && ((android.widget.TextView) full).getText().toString().contains(scenario.service.label),
                        "Selected server cabinet name was lost");
                require(full.getParent() instanceof android.widget.HorizontalScrollView,
                        "Long selected label has no horizontal viewport");
                android.widget.HorizontalScrollView scroll = (android.widget.HorizontalScrollView) full.getParent();
                require(full.getMeasuredWidth() > scroll.getWidth(), "Long selected label is truncated to viewport width");
                scroll.setSmoothScrollingEnabled(false);
                scroll.fullScroll(View.FOCUS_RIGHT);
                require(scroll.getScrollX() > 0, "Long selected name cannot be scrolled");
                require(scenario.service.allocations.get() == 0 && scenario.executor.calls.get() == 0,
                        "Scrolling selection text triggered a mutation");
            });
        } finally { scenario.close(); }
    }

    private static void checkNoFreeCabinet(Instrumentation test) throws Exception {
        Scenario scenario = new Scenario(test);
        scenario.service.allOccupied = true;
        try {
            scenario.start();
            main(test, () -> {
                View random = find(scenario.current[0], "随机选择本页空闲柜门，确认后开柜", false);
                require(random != null && !random.isEnabled(), "Random selection enabled with no free cabinets");
                random.performClick();
                View confirm = find(scenario.current[0], "确认使用所选柜门", false);
                require(confirm != null && !confirm.isEnabled(), "Occupied-only page permitted confirmation");
                require(scenario.service.allocations.get() == 0 && scenario.executor.calls.get() == 0,
                        "Occupied-only page initiated an opening");
            });
        } finally { scenario.close(); }
    }

    private static void checkUnavailable(Instrumentation test) throws Exception {
        Scenario scenario = new Scenario(test);
        try {
            scenario.start();
            main(test, () -> {
                find(scenario.current[0], "柜门 自定义001", true).performClick();
                scenario.executor.available = false;
                find(scenario.current[0], "确认使用所选柜门", false).performClick();
            });
            await(test, () -> find(scenario.current[0], "网络或设备暂不可用", false) != null);
            require(scenario.service.allocations.get() == 0 && scenario.executor.calls.get() == 0,
                    "Unavailable confirmation allocated a cabinet or started physical work");
            main(test, () -> OnlineBusinessRegression.savePreview(test, scenario.current[0], "online-unlock-unavailable.png"));
            await(test, () -> scenario.current[0] == null, 10_000);
            main(test, () -> require(!scenario.host[0].hasJourney(), "Unavailable timeout retained session"));
        } finally { scenario.close(); }
    }

    private static void checkFailureRecovery(Instrumentation test) throws Exception {
        Scenario scenario = new Scenario(test);
        try {
            scenario.start();
            main(test, () -> {
                find(scenario.current[0], "柜门 自定义001", true).performClick();
                find(scenario.current[0], "确认使用所选柜门", false).performClick();
            });
            await(test, () -> scenario.executor.calls.get() == 1);
            main(test, () -> scenario.executor.listener.onState(UnlockCoordinator.State.FAILURE, "private raw transport detail"));
            await(test, () -> find(scenario.current[0], "未确认开柜结果", false) != null);
            main(test, () -> {
                require(find(scenario.current[0], "重新尝试服务器查询", false) == null, "Physical failure offers unsafe retry");
                require(find(scenario.current[0], "private raw transport detail", true) == null, "Raw executor detail leaked into UI");
                OnlineBusinessRegression.savePreview(test, scenario.current[0], "online-unlock-failed.png");
            });
            await(test, () -> scenario.current[0] == null, 10_000);
            require(scenario.service.allocations.get() == 1 && scenario.executor.calls.get() == 1,
                    "Failure timeout automatically retried mutation or dispatch");
        } finally { scenario.close(); }
    }

    private static final class Scenario {
        final Instrumentation test;
        final Fixture service = new Fixture();
        final FakeExecutor executor = new FakeExecutor();
        final OnlineCustomerHost[] host = new OnlineCustomerHost[1];
        final View[] current = new View[1];
        Scenario(Instrumentation test) { this.test = test; }
        void start() throws Exception {
            com.codex.lockertest.bootstrap.BootstrapSnapshot binding = OnlineBusinessRegression.ready();
            main(test, () -> {
                host[0] = new OnlineCustomerHost(test.getTargetContext(), r -> service, id -> 0,
                        new OnlineCustomerHost.Ui() {
                            public void show(View view) { current[0] = view; }
                            public void home() { current[0] = null; }
                            public void message(String message) { }
                        }, null, executor);
                host[0].bind(binding);
                host[0].submitPhone("13800000001", "001234");
            });
            await(test, () -> find(current[0], "柜门 自定义001", true) != null);
        }
        void close() { main(test, () -> { if (host[0] != null) host[0].close(); }); }
    }

    private static final class FakeExecutor implements OnlineUnlockExecutor {
        final AtomicInteger calls = new AtomicInteger();
        volatile AuthorizedUnlockRequest request;
        volatile Listener listener;
        volatile boolean available = true;
        public boolean isAvailable() { return available; }
        public OnlineCustomerCoordinator.Cancellable execute(AuthorizedUnlockRequest request, Listener listener) {
            this.request = request; this.listener = listener; calls.incrementAndGet();
            return () -> { };
        }
    }
    private static final class Fixture implements BusinessService {
        String label = "自定义001";
        boolean allOccupied;
        final AtomicInteger allocations = new AtomicInteger();
        final AtomicInteger opens = new AtomicInteger();
        public ApiResult<AuthenticatedUser> authenticate(UserInfoRequest r, CallToken t) {
            return ApiResult.success(new AuthenticatedUser(SessionToken.of("UI_TEST_ONLY"), UserType.USER, 0, 1));
        }
        public ApiResult<ControlPanelPreview> controlPanelPreview(AuthenticatedUser u, int type, long area, int page, CallToken t) {
            List<ControlPanelPreview.Cabinet> row = Arrays.asList(
                    new ControlPanelPreview.Cabinet(31, 93, label, allOccupied ? 1 : 0, area, "02", "02", "8A 02 02 11 9B", 0),
                    new ControlPanelPreview.Cabinet(32, 94, "已占用", 1, area, "02", "03", "8A 02 03 11 9A", 0));
            return ApiResult.success(new ControlPanelPreview(Collections.singletonList(1),
                    Collections.emptyList(), Collections.singletonMap(1, row)));
        }
        public ApiResult<AssignedCabinet> userBoard(AuthenticatedUser u, long area, long fc, CallToken t) {
            require(area == 1 && fc == 31, "Wrong server selection identity");
            allocations.incrementAndGet(); return ApiResult.success(new AssignedCabinet(fc));
        }
        public ApiResult<EmptyBusinessResult> openBoard(AuthenticatedUser u, BoardAction a, long f, CallToken t) {
            opens.incrementAndGet(); throw new AssertionError("Unexpected additional mutation");
        }
        public ApiResult<UsedCabinetList> useCabinetList(AuthenticatedUser u, CallToken t) { throw new AssertionError("Unexpected return"); }
        public ApiResult<AuthenticatedUser> verifyMemberDynamicCode(AuthenticatedUser u, String c, CallToken t) { throw new AssertionError("Unexpected admin"); }
    }
    private interface Condition { boolean get(); }
    private static void await(Instrumentation test, Condition condition) {
        await(test, condition, 5_000);
    }
    private static void await(Instrumentation test, Condition condition, long timeout) {
        long end = SystemClock.elapsedRealtime() + timeout;
        boolean[] ready = {false};
        do {
            main(test, () -> ready[0] = condition.get());
            if (ready[0]) return;
            SystemClock.sleep(15);
        } while (SystemClock.elapsedRealtime() < end);
        throw new AssertionError("Online unlock UI timed out");
    }
    private static View findOnMain(Instrumentation test, View[] current, String description) {
        View[] result = new View[1]; main(test, () -> result[0] = find(current[0], description, false)); return result[0];
    }
    private static void main(Instrumentation test, Runnable task) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        test.runOnMainSync(() -> { try { task.run(); } catch (Throwable e) { failure.set(e); } });
        if (failure.get() != null) throw new AssertionError(failure.get());
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static View find(View view, String description, boolean prefix) {
        if (view == null) return null;
        CharSequence value = view.getContentDescription();
        if (value != null && (prefix ? value.toString().startsWith(description) : value.toString().equals(description))) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = find(((ViewGroup) view).getChildAt(i), description, prefix); if (found != null) return found;
        }
        return null;
    }
}
