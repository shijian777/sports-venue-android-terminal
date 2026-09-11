package com.codex.lockertest.review;

import android.app.Instrumentation;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import com.codex.lockertest.bootstrap.*;
import com.codex.lockertest.business.*;
import com.codex.lockertest.server.*;
import com.codex.lockertest.ui.business.OnlineCustomerHost;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Exercises actual Android host, async scheduler, callbacks, paging and teardown without network. */
final class OnlineBusinessRegression {
    static void check(Instrumentation test) throws Exception {
        Fixture fixture = new Fixture();
        BootstrapSnapshot ready = ready();
        OnlineCustomerHost[] owned = new OnlineCustomerHost[1];
        View[] current = {null};
        AtomicInteger homes = new AtomicInteger();
        AtomicInteger messages = new AtomicInteger();
        test.runOnMainSync(() -> {
            owned[0] = new OnlineCustomerHost(test.getTargetContext(), registration -> fixture,
                    id -> 0, new OnlineCustomerHost.Ui() {
                @Override public void show(View view) {
                    require(Looper.myLooper() == Looper.getMainLooper(), "UI callback left main thread");
                    current[0] = view;
                }
                @Override public void home() { homes.incrementAndGet(); current[0] = null; }
                @Override public void message(String message) { messages.incrementAndGet(); }
            });
            owned[0].bind(ready);
            require(owned[0].ready(), "Ready bootstrap did not configure online host");
            owned[0].scannerKey(9001, '1', false, 0, 0);
            require(messages.get() == 1 && fixture.authCalls.get() == 0,
                    "Unknown keyboard reader guessed credential type or sent request");
            owned[0].submitPhone("13800000001", "001234");
            owned[0].submitPhone("13800000001", "001234");
        });
        try {
            await(test, () -> current[0] != null && find(current[0], "柜门 A1", true) != null);
            require(fixture.authCalls.get() == 1, "Duplicate authentication request");
            require(fixture.notOnMain, "Authentication blocked UI thread");
            test.runOnMainSync(() -> {
                View confirm = find(current[0], "服务器开柜/还柜事务闭环尚未确认", true);
                require(confirm != null && !confirm.isEnabled() && !confirm.isClickable(),
                        "Physical confirmation enabled on read-only page");
                View random = find(current[0], "随机选择本页空闲柜门，确认后开柜", false);
                require(random != null && !random.isEnabled(), "Read-only page enabled random opening");
                require(countPrefix(current[0], "柜门 A") == 32, "Grid did not display 4 x 8 server cabinets");
                click(current[0], "柜门 A1，使用中，仅可查看");
                View occupiedSelection = find(current[0], "柜门 A1，", true);
                require(occupiedSelection instanceof android.widget.Button
                                && ((android.widget.Button) occupiedSelection).getText().toString().contains("使用中")
                                && occupiedSelection.getContentDescription().toString().contains("使用中"),
                        "Read-only selection erased the server occupied status");
                savePreview(test, current[0], "online-cabinets.png");
                confirm = find(current[0], "服务器开柜/还柜事务闭环尚未确认", true);
                View region = find(current[0], "柜区 A区", false);
                View firstCabinet = find(current[0], "柜门 A1，", true);
                View lastCabinet = find(current[0], "柜门 A32，", true);
                require(region != null && firstCabinet != null && region.getBottom() <= firstCabinet.getTop(),
                        "PDF layout: region selection must precede the cabinet grid");
                require(lastCabinet != null && confirm.getTop() >= lastCabinet.getBottom(),
                        "PDF layout: confirmation overlaps the final cabinet row");
                click(current[0], "下一页柜门");
            });
            await(test, () -> fixture.lastPage == 2 && find(current[0], "柜门 B1", true) != null);
            test.runOnMainSync(() -> click(current[0], "下一组区域"));
            test.runOnMainSync(() -> click(current[0], "柜区 E区"));
            await(test, () -> fixture.lastArea == 5 && find(current[0], "柜门 A1", true) != null);
            test.runOnMainSync(() -> click(current[0], "返回首页并清除认证会话"));
            require(homes.get() == 1 && !owned[0].hasJourney(), "Back retained online journey");
            test.runOnMainSync(() -> {
                owned[0].prepareReturn();
                owned[0].submitPhone("13800000001", "001234");
            });
            await(test, () -> current[0] != null && find(current[0], "本人正在使用的柜门", false) != null);
            require(fixture.usedCalls.get() == 1, "Return journey did not query owned cabinets");
            test.runOnMainSync(() -> savePreview(test, current[0], "online-owned-cabinets.png"));
            require(fixture.mutations.get() == 0, "Read-only UI invoked server mutation");
            int homesBeforeFailure = homes.get();
            test.runOnMainSync(() -> {
                owned[0].cancel();
                fixture.rejectNext = true;
                owned[0].submitPhone("13800000001", "001234");
            });
            await(test, () -> find(current[0], "8 秒后自动返回首页", false) != null);
            test.runOnMainSync(() -> {
                View backdropPhone = find(current[0], "手机号输入框", true);
                require(backdropPhone != null && !backdropPhone.isEnabled(),
                        "Identity failure must remain over a non-interactive identity form");
                require(find(current[0], "服务器柜门列表", false) == null,
                        "Identity failure incorrectly claims to have reached the cabinet list");
                savePreview(test, current[0], "online-identity-failed.png");
            });
            long failureShownAt = SystemClock.elapsedRealtime();
            await(test, () -> homes.get() == homesBeforeFailure + 1, 10_000);
            require(SystemClock.elapsedRealtime() - failureShownAt >= 7_500,
                    "Failure page returned before the eight-second deadline");
            require(!owned[0].hasJourney() && current[0] == null,
                    "Failure auto-return retained authenticated page");
            test.runOnMainSync(() -> {
                fixture.twelveInOneLayer = true;
                owned[0].submitPhone("13800000001", "001234");
            });
            await(test, () -> find(current[0], "柜门 C12", true) != null);
            test.runOnMainSync(() -> {
                require(countPrefix(current[0], "柜门 C") == 12,
                        "Single physical layer lost server cabinets");
                View first = find(current[0], "柜门 C1，", true);
                View eighth = find(current[0], "柜门 C8，", true);
                View ninth = find(current[0], "柜门 C9，", true);
                savePreview(test, current[0], "online-cabinets-12.png");
                require(first.getTop() == eighth.getTop() && ninth.getTop() > eighth.getTop(),
                        "Twelve physical cabinets were not projected into 8 + 4 display slots");
                click(current[0], "柜门 C12，空闲，仅可查看");
                require(fixture.mutations.get() == 0, "Selecting a projected cabinet issued a mutation");
            });
            test.runOnMainSync(() -> {
                owned[0].cancel();
                fixture.twelveInOneLayer = false;
                fixture.longLabels = true;
                owned[0].submitPhone("13800000001", "001234");
            });
            await(test, () -> find(current[0], "柜门 001号柜，", true) != null);
            test.runOnMainSync(() -> {
                savePreview(test, current[0], "detail-cabinet-long-labels.png");
                for (String name : fixture.longNames()) {
                    android.widget.Button cell = (android.widget.Button) find(current[0], "柜门 " + name + "，", true);
                    android.text.Layout layout = cell.getLayout();
                    require(layout.getLineCount() == 2, "Cabinet name wrapped over the status: " + name.length());
                    String line2 = cell.getText().subSequence(layout.getLineStart(1), layout.getLineEnd(1)).toString();
                    require(line2.contains("空闲"), "Cabinet status hidden by long server name");
                }
                View previous = find(current[0], "上一页柜门", false);
                View random = find(current[0], "随机选择本页空闲柜门，确认后开柜", false);
                require(!previous.isEnabled() && previous.getBackground().isStateful(), "Disabled page control lacks state feedback");
                require(!random.isEnabled() && random.getBackground().isStateful(), "Disabled random control lacks state feedback");
                click(current[0], "柜门 " + fixture.longNames()[2] + "，空闲，仅可查看");
                savePreview(test, current[0], "detail-cabinet-long-selected.png");
            });
            test.runOnMainSync(() -> {
                owned[0].cancel();
                fixture.blockNext = true;
                owned[0].submitPhone("13800000001", "001234");
            });
            require(fixture.entered.await(3, TimeUnit.SECONDS), "Blocked request never started");
            final View[] beforeCancel = {null};
            test.runOnMainSync(() -> { beforeCancel[0] = current[0]; owned[0].unbind(); });
            fixture.release.countDown();
            test.waitForIdleSync();
            SystemClock.sleep(150);
            test.waitForIdleSync();
            require(!owned[0].ready() && !owned[0].hasJourney(), "Unbind retained authentication state");
            require(current[0] == beforeCancel[0], "Late response changed UI after unbind");
        } finally {
            fixture.release.countDown();
            test.runOnMainSync(() -> owned[0].close());
        }
    }

    private interface Condition { boolean get(); }
    static void savePreview(Instrumentation test, View view, String name) {
        savePreview(test.getTargetContext(), view, name);
    }
    static void savePreview(android.content.Context context, View view, String name) {
        view.measure(View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 1280, 800);
        // Complete the off-window pre-draw phase before checking and capturing native controls.
        view.getViewTreeObserver().dispatchOnPreDraw();
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(1280, 800,
                android.graphics.Bitmap.Config.ARGB_8888);
        view.draw(new android.graphics.Canvas(bitmap));
        java.io.File directory = context.getExternalFilesDir(null);
        if (directory == null) throw new AssertionError("No emulator screenshot output directory");
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(new java.io.File(directory, name))) {
            require(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output), "Screenshot failed");
        } catch (java.io.IOException error) { throw new AssertionError(error); }
        finally { bitmap.recycle(); }
        verifyButtonLayouts(view);
    }
    private static void verifyButtonLayouts(View view) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view instanceof android.widget.Button) {
            android.widget.Button button = (android.widget.Button) view;
            if (button.getText().length() > 0) {
                android.text.Layout layout = button.getLayout();
                require(layout != null, "Button text layout missing");
                require(button.getCurrentTextColor() != android.graphics.Color.TRANSPARENT,
                        "Button text transparent");
                require(layout.getHeight() <= button.getHeight(), "Button text clipped: size="
                        + button.getTextSize() + " layout=" + layout.getHeight() + " height=" + button.getHeight()
                        + " control=" + button.getContentDescription() + " text=" + button.getText());
                require(layout.getLineLeft(0) - button.getScrollX() < button.getWidth(),
                        "Button text horizontally offscreen: layoutWidth=" + layout.getWidth()
                        + " lineLeft=" + layout.getLineLeft(0) + " scroll=" + button.getScrollX()
                        + " width=" + button.getWidth());
            }
        }
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            verifyButtonLayouts(((ViewGroup) view).getChildAt(i));
    }
    private static void await(Instrumentation test, Condition condition) {
        await(test, condition, 4000);
    }
    private static void await(Instrumentation test, Condition condition, long timeoutMillis) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMillis;
        boolean[] result = {false};
        do {
            test.runOnMainSync(() -> result[0] = condition.get());
            if (result[0]) return;
            SystemClock.sleep(15);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("Online host UI response timed out");
    }
    private static void require(boolean passed, String reason) {
        if (!passed) throw new AssertionError(reason);
    }
    private static void click(View root, String description) {
        View target = find(root, description, false);
        require(target != null && target.isEnabled(), "Missing enabled control: " + description);
        target.performClick();
    }
    private static int countPrefix(View view, String prefix) {
        int count = view.getContentDescription() != null
                && view.getContentDescription().toString().startsWith(prefix) ? 1 : 0;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            count += countPrefix(((ViewGroup) view).getChildAt(i), prefix);
        return count;
    }
    private static View find(View view, String text, boolean prefix) {
        if (view == null) return null;
        CharSequence desc = view.getContentDescription();
        if (desc != null && (prefix ? desc.toString().startsWith(text) : desc.toString().equals(text))) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = find(((ViewGroup) view).getChildAt(i), text, prefix);
            if (found != null) return found;
        }
        return null;
    }
    static BootstrapSnapshot ready() throws Exception {
        DeviceRegistration registration = new CheckDeviceResponseParser().parse(
                "{\"code\":200,\"message\":\"ok\",\"data\":{\"merchant_code\":\"TEST_MERCHANT\",\"device_no\":\"TEST_DEVICE\"}}").value();
        BaseSettingSnapshot base = new BaseSettingResponseParser().parse(
                "{\"code\":200,\"message\":\"ok\",\"data\":{\"venue_name\":\"TEST\",\"version\":\"1\","
                + "\"recognition_type\":[\"1\",\"2\",\"4\"],\"use_notice\":\"\",\"return_notice\":\"\",\"logo\":\"\","
                + "\"company\":\"TEST\",\"cozy_tips\":\"\",\"advertisement_type\":0,\"advertisement_list\":[],"
                + "\"background_img\":\"\",\"palm_print_img\":\"\",\"locker_check_status\":\"0\","
                + "\"area_list\":[{\"area_id\":1,\"name\":\"A区\"},{\"area_id\":2,\"name\":\"B区\"},"
                + "{\"area_id\":3,\"name\":\"C区\"},{\"area_id\":4,\"name\":\"D区\"},{\"area_id\":5,\"name\":\"E区\"}]}}").value();
        BasicDataSnapshot basic = new BasicDataResponseParser().parse(
                "{\"code\":200,\"message\":\"ok\",\"data\":{\"num\":64,\"surplus_num\":32,\"dynamic_verification_code\":\"0\"}}").value();
        java.lang.reflect.Method method = BootstrapSnapshot.class.getDeclaredMethod("ready", long.class,
                String.class, DeviceRegistration.class, BaseSettingSnapshot.class, BasicDataSnapshot.class);
        method.setAccessible(true);
        return (BootstrapSnapshot) method.invoke(null, 10L, "***", registration, base, basic);
    }

    private static final class Fixture implements BusinessService {
        final AtomicInteger authCalls = new AtomicInteger();
        final AtomicInteger usedCalls = new AtomicInteger();
        final AtomicInteger mutations = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        volatile int lastPage;
        volatile long lastArea;
        volatile boolean notOnMain;
        volatile boolean blockNext;
        volatile boolean rejectNext;
        volatile boolean twelveInOneLayer;
        volatile boolean longLabels;
        String[] longNames() {
            return new String[]{"001号柜", "运动场馆东侧更衣柜", String.join("", Collections.nCopies(256, "柜"))};
        }
        @Override public ApiResult<AuthenticatedUser> authenticate(UserInfoRequest request, CallToken token) {
            authCalls.incrementAndGet();
            notOnMain = Looper.myLooper() != Looper.getMainLooper();
            require(request.type() == 1 && "13800000001".equals(request.keyword())
                    && "001234".equals(request.userCode()), "Wrong paired identity request");
            if (rejectNext) {
                rejectNext = false;
                return ApiResult.failure(ServerFailure.of(ServerFailure.Kind.REMOTE_REJECTED));
            }
            if (blockNext) {
                entered.countDown();
                try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            }
            return ApiResult.success(new AuthenticatedUser(SessionToken.of("TEST_ONLY_TOKEN"), UserType.USER, 0, 1));
        }
        @Override public ApiResult<ControlPanelPreview> controlPanelPreview(AuthenticatedUser user,
                int type, long areaId, int page, CallToken token) {
            require(type == 2, "Customer request entered installation/use validation mode");
            lastArea = areaId; lastPage = page;
            Map<Integer, List<ControlPanelPreview.Cabinet>> rows = new LinkedHashMap<>();
            if (longLabels) {
                List<ControlPanelPreview.Cabinet> cabinets = new ArrayList<>();
                int id = 1;
                for (String name : longNames()) cabinets.add(new ControlPanelPreview.Cabinet(id, id++, name,
                        0, areaId, "01", "01", "8A 01 01 11 9B", 0));
                rows.put(1, cabinets);
                return ApiResult.success(new ControlPanelPreview(Collections.singletonList(1), Collections.emptyList(), rows));
            }
            if (twelveInOneLayer) {
                List<ControlPanelPreview.Cabinet> cabinets = new ArrayList<>();
                for (int id = 1; id <= 12; id++) {
                    cabinets.add(new ControlPanelPreview.Cabinet(id, id, "C" + id,
                            0, areaId, "01", "01", "8A 01 01 11 9B", 0));
                }
                rows.put(1, cabinets);
                return ApiResult.success(new ControlPanelPreview(
                        Collections.singletonList(1), Collections.emptyList(), rows));
            }
            for (int row = 1; row <= 4; row++) {
                List<ControlPanelPreview.Cabinet> cabinets = new ArrayList<>();
                for (int column = 1; column <= 8; column++) {
                    int id = (row - 1) * 8 + column;
                    cabinets.add(new ControlPanelPreview.Cabinet(id, id, (page == 1 ? "A" : "B") + id,
                            id == 1 ? 1 : 0, areaId, "01", "01", "8A 01 01 11 9B", 0));
                }
                rows.put(row, cabinets);
            }
            return ApiResult.success(new ControlPanelPreview(Arrays.asList(1, 2), Collections.emptyList(), rows));
        }
        @Override public ApiResult<UsedCabinetList> useCabinetList(AuthenticatedUser user, CallToken token) {
            usedCalls.incrementAndGet();
            return ApiResult.success(new UsedCabinetList("TEST_USER", "TEST_MOBILE", Collections.singletonList(
                    new UsedCabinet(1, 1, "本人柜门01", "TEST_TIME", 5, ""))));
        }
        @Override public ApiResult<AuthenticatedUser> verifyMemberDynamicCode(AuthenticatedUser u, String c, CallToken t) {
            throw new AssertionError("Unexpected administrator flow");
        }
        @Override public ApiResult<AssignedCabinet> userBoard(AuthenticatedUser u, long a, long f, CallToken t) {
            mutations.incrementAndGet(); throw new AssertionError("Mutation from read-only UI");
        }
        @Override public ApiResult<EmptyBusinessResult> openBoard(AuthenticatedUser u, BoardAction a, long f, CallToken t) {
            mutations.incrementAndGet(); throw new AssertionError("Mutation from read-only UI");
        }
    }
}
