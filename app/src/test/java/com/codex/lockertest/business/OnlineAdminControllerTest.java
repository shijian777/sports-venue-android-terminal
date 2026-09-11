package com.codex.lockertest.business;

import com.codex.lockertest.business.journey.OnlineCustomerCoordinator;
import com.codex.lockertest.server.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class OnlineAdminControllerTest {
    @Test public void successfulServerLoginAloneEnablesConfiguration() {
        Fixture f = new Fixture();
        assertFalse(f.controller.authorized());
        assertTrue(f.controller.login("installer", "password", ""));
        assertFalse(f.controller.authorized());
        f.scheduler.work.remove(0).run();
        assertTrue(f.controller.authorized());
        assertEquals("installer|password|", f.service.received);
        assertEquals(1, f.service.calls);
    }
    @Test public void rejectionAndMismatchedDeviceCannotGrantAccess() {
        Fixture f = new Fixture();
        f.service.result = ApiResult.failure(ServerFailure.of(ServerFailure.Kind.REMOTE_REJECTED));
        f.controller.login("user", "wrong", ""); f.scheduler.work.remove(0).run();
        assertEquals(OnlineAdminController.State.FAILED, f.controller.state());
        assertFalse(f.controller.authorized());
        f.service.result = ApiResult.success(admin("other-terminal"));
        f.controller.login("user", "correct", ""); f.scheduler.work.remove(0).run();
        assertFalse(f.controller.authorized());
    }
    @Test public void cancellationPreventsLateLoginAndAvoidsDuplicateCalls() {
        Fixture f = new Fixture();
        assertTrue(f.controller.login("user", "password", ""));
        assertFalse(f.controller.login("another", "password", ""));
        f.service.duringCall = () -> f.controller.cancel();
        f.scheduler.work.remove(0).run();
        assertEquals(1, f.service.calls);
        assertTrue(f.service.token.isCancelled());
        assertFalse(f.controller.authorized());
        assertEquals(OnlineAdminController.State.IDLE, f.controller.state());
    }
    @Test public void expiredRequestOrSessionNeverRemainsAuthorized() {
        Fixture f = new Fixture();
        f.controller.login("user", "password", "");
        f.scheduler.time = 15_001;
        f.scheduler.work.remove(0).run();
        assertFalse(f.controller.authorized());
        assertEquals(0, f.service.calls);
        f.controller.login("user", "password", "");
        f.scheduler.work.remove(0).run();
        assertTrue(f.controller.authorized());
        f.scheduler.time += 60_001;
        assertFalse(f.controller.authorized());
    }
    @Test public void cancelledQueuedCredentialsAreNeverSent() {
        Fixture f = new Fixture();
        f.controller.login("user", "password", "");
        f.controller.cancel();
        f.scheduler.work.remove(0).run();
        assertEquals(0, f.service.calls);
        assertFalse(f.controller.authorized());
    }
    @Test public void invalidInputAndUnavailableSchedulerFailWithoutAuthentication() {
        Fixture f = new Fixture();
        assertFalse(f.controller.login("", "password", ""));
        f.scheduler.reject = true;
        assertFalse(f.controller.login("user", "password", ""));
        assertFalse(f.controller.authorized());
        assertEquals(0, f.service.calls);
    }
    private static AdminLogin admin(String serial) {
        return new AdminLogin(SessionToken.of("test-token"), "venue", "device", serial, "area", "user", "name");
    }
    private static class Fixture {
        final StubService service = new StubService();
        final QueueScheduler scheduler = new QueueScheduler();
        final OnlineAdminController controller = new OnlineAdminController(service, "terminal", scheduler, () -> scheduler.time, state -> { });
    }
    private static class QueueScheduler implements OnlineCustomerCoordinator.Scheduler {
        final List<Runnable> work = new ArrayList<>();
        final List<Runnable> timers = new ArrayList<>();
        long time;
        boolean reject;
        public OnlineCustomerCoordinator.Cancellable submit(Runnable r) {
            if (reject) throw new IllegalStateException(); work.add(r); return () -> { };
        }
        public OnlineCustomerCoordinator.Cancellable schedule(Runnable r, long delay) {
            if (reject) throw new IllegalStateException(); timers.add(r); return () -> { };
        }
    }
    private static class StubService implements BusinessService {
        ApiResult<AdminLogin> result = ApiResult.success(admin("terminal"));
        int calls; String received; CallToken token; Runnable duringCall;
        public ApiResult<AdminLogin> adminLogin(String name, String password, String code, CallToken token) {
            calls++; this.token = token; received = name + "|" + password + "|";
            if (duringCall != null) duringCall.run(); return result;
        }
        public ApiResult<AuthenticatedUser> authenticate(UserInfoRequest r, CallToken t) { return BusinessService.unavailable(); }
        public ApiResult<AuthenticatedUser> verifyMemberDynamicCode(AuthenticatedUser u, String c, CallToken t) { return BusinessService.unavailable(); }
        public ApiResult<ControlPanelPreview> controlPanelPreview(AuthenticatedUser u, int a, long b, int c, CallToken t) { return BusinessService.unavailable(); }
        public ApiResult<UsedCabinetList> useCabinetList(AuthenticatedUser u, CallToken t) { return BusinessService.unavailable(); }
        public ApiResult<AssignedCabinet> userBoard(AuthenticatedUser u, long a, long c, CallToken t) { return BusinessService.unavailable(); }
        public ApiResult<EmptyBusinessResult> openBoard(AuthenticatedUser u, BoardAction a, long c, CallToken t) { return BusinessService.unavailable(); }
    }
}
