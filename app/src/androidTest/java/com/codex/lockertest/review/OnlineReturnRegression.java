package com.codex.lockertest.review;

import android.app.Instrumentation;
import android.view.View;

import com.codex.lockertest.bootstrap.BootstrapSnapshot;
import com.codex.lockertest.business.AssignedCabinet;
import com.codex.lockertest.business.AuthenticatedUser;
import com.codex.lockertest.business.BoardAction;
import com.codex.lockertest.business.BusinessService;
import com.codex.lockertest.business.ControlPanelPreview;
import com.codex.lockertest.business.EmptyBusinessResult;
import com.codex.lockertest.business.SessionToken;
import com.codex.lockertest.business.UsedCabinet;
import com.codex.lockertest.business.UsedCabinetList;
import com.codex.lockertest.business.UserInfoRequest;
import com.codex.lockertest.business.UserType;
import com.codex.lockertest.server.ApiResult;
import com.codex.lockertest.server.CallToken;
import com.codex.lockertest.ui.business.OnlineCustomerHost;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Real host/coordinator/view return confirmation with a deterministic blocked server boundary. */
final class OnlineReturnRegression {
    static void check(Instrumentation test) throws Exception {
        Fixture service = new Fixture();
        AtomicReference<View> current = new AtomicReference<>();
        OnlineCustomerHost host = new OnlineCustomerHost(
                test.getTargetContext(),
                registration -> service,
                inputDeviceId -> 0,
                new OnlineCustomerHost.Ui() {
                    @Override public void show(View view) { current.set(view); }
                    @Override public void home() { current.set(null); }
                    @Override public void message(String message) { }
                });
        try {
            Method ready = OnlineBusinessRegression.class.getDeclaredMethod("ready");
            ready.setAccessible(true);
            BootstrapSnapshot snapshot = (BootstrapSnapshot) ready.invoke(null);
            test.runOnMainSync(() -> {
                host.bind(snapshot);
                host.prepareReturn();
                host.submitPhone("13800000001", "001234");
            });

            OnlineReaderRegression.await(test, () -> OnlineReaderRegression.find(
                    current.get(), "本人柜门 A01，选择还柜") != null);
            test.runOnMainSync(() -> {
                View cabinet = OnlineReaderRegression.find(
                        current.get(), "本人柜门 A01，选择还柜");
                require(cabinet != null && cabinet.isEnabled(),
                        "Owned cabinet was not selectable");
                cabinet.performClick();
                View confirm = OnlineReaderRegression.find(
                        current.get(), "确认还柜所选柜门");
                require(confirm != null && confirm.isEnabled(),
                        "Return confirmation did not enable after selection");
                require(confirm.performClick(), "First return confirmation was ignored");
                require(!confirm.isEnabled(), "Return confirmation stayed enabled while pending");
                confirm.performClick();
            });

            require(service.returnEntered.await(3, TimeUnit.SECONDS),
                    "Return request did not reach the fake server boundary");
            OnlineReaderRegression.await(test, () -> OnlineReaderRegression.find(
                    current.get(), "服务器操作进行中") != null);
            test.runOnMainSync(() -> {
                View progress = OnlineReaderRegression.find(current.get(), "服务器操作进行中");
                OnlineBusinessRegression.savePreview(test, current.get(), "detail-return-busy.png");
                require(progress != null && progress.getVisibility() == View.VISIBLE
                                && progress.getWidth() > 0 && progress.getHeight() > 0,
                        "Return busy state did not show a visible loading indicator");
                require(OnlineReaderRegression.find(current.get(), "还柜成功") == null,
                        "Return succeeded before the server response");
            });
            require(service.openBoardCalls.get() == 1,
                    "Duplicate confirmation sent more than one mutation");
            require(service.userBoardCalls.get() == 0,
                    "Return confirmation requested physical cabinet allocation");
            require(service.action == BoardAction.RETURN && service.fcId == 501L,
                    "Return mutation used the wrong type or cabinet id");
            require("TEST_RETURN_TOKEN".equals(service.token),
                    "Return mutation did not use the authenticated customer token");

            service.releaseReturn.countDown();
            OnlineReaderRegression.await(test, () -> OnlineReaderRegression.find(
                    current.get(), "还柜成功") != null);
            require(service.openBoardCalls.get() == 1 && service.userBoardCalls.get() == 0,
                    "Return completion issued an extra server or physical action");
        } finally {
            service.releaseReturn.countDown();
            test.runOnMainSync(host::close);
        }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static final class Fixture implements BusinessService {
        final AtomicInteger openBoardCalls = new AtomicInteger();
        final AtomicInteger userBoardCalls = new AtomicInteger();
        final CountDownLatch returnEntered = new CountDownLatch(1);
        final CountDownLatch releaseReturn = new CountDownLatch(1);
        volatile BoardAction action;
        volatile long fcId;
        volatile String token;

        @Override public ApiResult<AuthenticatedUser> authenticate(
                UserInfoRequest request, CallToken callToken) {
            require(request.type() == 1
                            && "13800000001".equals(request.keyword())
                            && "001234".equals(request.userCode()),
                    "Host changed the member-login request");
            return ApiResult.success(new AuthenticatedUser(
                    SessionToken.of("TEST_RETURN_TOKEN"), UserType.USER, 0, 81L));
        }

        @Override public ApiResult<UsedCabinetList> useCabinetList(
                AuthenticatedUser user, CallToken callToken) {
            require("TEST_RETURN_TOKEN".equals(user.token().value()),
                    "Owned-list query lost the customer token");
            return ApiResult.success(new UsedCabinetList(
                    "TEST_USER", "TEST_PHONE", Collections.singletonList(
                    new UsedCabinet(91L, 501L, "A01",
                            "2026-09-09 10:00:00", 12, "TEST_NOTICE"))));
        }

        @Override public ApiResult<EmptyBusinessResult> openBoard(
                AuthenticatedUser user, BoardAction action, long fcId, CallToken callToken) {
            openBoardCalls.incrementAndGet();
            this.action = action;
            this.fcId = fcId;
            token = user.token().value();
            returnEntered.countDown();
            try {
                require(releaseReturn.await(3, TimeUnit.SECONDS),
                        "Test did not release the blocked return response");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Blocked return response was interrupted", interrupted);
            }
            return ApiResult.success(EmptyBusinessResult.INSTANCE);
        }

        @Override public ApiResult<AssignedCabinet> userBoard(
                AuthenticatedUser user, long areaId, long fcId, CallToken callToken) {
            userBoardCalls.incrementAndGet();
            throw new AssertionError("Return journey requested a physical cabinet allocation");
        }

        @Override public ApiResult<AuthenticatedUser> verifyMemberDynamicCode(
                AuthenticatedUser user, String code, CallToken callToken) {
            throw new AssertionError("Return journey entered administrator verification");
        }

        @Override public ApiResult<ControlPanelPreview> controlPanelPreview(
                AuthenticatedUser user, int type, long areaId, int page, CallToken callToken) {
            throw new AssertionError("Return journey queried free-cabinet preview");
        }
    }
}
