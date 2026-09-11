package com.codex.lockertest.face.verification;

import com.codex.lockertest.business.*;
import com.codex.lockertest.server.*;
import java.util.*;
import java.util.concurrent.Executor;
import org.junit.Test;
import static org.junit.Assert.*;

public class OnlineFaceVerificationClientTest {
    @Test public void uploadThenServerAuthenticationIsRequiredForOneUseHandoff() {
        Fixture f = new Fixture();
        FaceVerificationRequest request = f.request();
        FaceVerificationClient.Cancellable handle = f.client.verify(request, f.results::add);
        request.commitOwnedJpegTransfer();
        assertTrue(f.results.isEmpty());
        f.run();
        assertEquals(Arrays.asList("upload", "authenticate"), f.events);
        assertEquals(5, f.identity.type());
        assertEquals("https://image.invalid/processed.jpg", f.identity.keyword());
        FaceVerificationResult result = f.results.get(0);
        assertTrue(result.isPassed());
        assertNotEquals(f.member.token().value(), result.credential());
        assertEquals(FaceVerificationSource.REMOTE_SERVER, result.source());
        handle.cancel(); // Capture-session terminal cleanup must not revoke its completed handoff.
        assertSame(f.member, f.client.consume(result));
        assertNull(f.client.consume(result));
    }
    @Test public void uploadSuccessWithAuthenticationFailureCannotPass() {
        Fixture f = new Fixture(); f.login = ApiResult.failure(ServerFailure.of(ServerFailure.Kind.HTTP));
        f.client.verify(f.request(), f.results::add); f.run();
        assertFalse(f.results.get(0).isPassed());
        assertNull(f.client.consume(f.results.get(0)));
    }
    @Test public void administratorIsNotACustomerFaceIdentity() {
        Fixture f = new Fixture();
        f.login = ApiResult.success(new AuthenticatedUser(SessionToken.of("admin-test"), UserType.ADMIN, 0, 7));
        f.client.verify(f.request(), f.results::add); f.run();
        assertFalse(f.results.get(0).isPassed());
    }
    @Test public void cancellationBeforeWorkerStartsDoesNotUpload() {
        Fixture f = new Fixture(); FaceVerificationRequest request = f.request();
        f.client.verify(request, f.results::add).cancel(); f.run();
        assertTrue(f.events.isEmpty()); assertTrue(f.results.isEmpty());
    }
    @Test public void cancellationDuringUploadStopsAuthenticationAndCallbacks() {
        Fixture f = new Fixture(); f.cancelDuringUpload = true;
        f.client.verify(f.request(), f.results::add); f.run();
        assertEquals(Collections.singletonList("upload"), f.events); assertTrue(f.results.isEmpty());
    }
    @Test public void closingRevokesSuccessfulHandoff() {
        Fixture f = new Fixture(); f.client.verify(f.request(), f.results::add); f.run();
        f.client.close(); assertNull(f.client.consume(f.results.get(0)));
    }
    @Test public void expiryOrForgedResultCannotReuseMemberToken() {
        Fixture f = new Fixture(); f.client.verify(f.request(), f.results::add); f.run();
        FaceVerificationResult r = f.results.get(0);
        FaceVerificationResult forged = FaceVerificationResult.passed(r.source(),r.requestId(),r.credential(),
                r.ticketId(),r.expiresAtEpochMillis(),r.deviceBinding(),r.processBinding(),0);
        assertNull(f.client.consume(forged));
        f.now = r.expiresAtEpochMillis(); assertNull(f.client.consume(r));
    }
    @Test public void duplicateVerificationWhileBusyDoesNotStartSecondUpload() {
        Fixture f = new Fixture();
        f.client.verify(f.request(), f.results::add);
        f.client.verify(f.request(), ignored -> {});
        assertEquals(1, f.tasks.size()); f.run();
        assertEquals(Arrays.asList("upload", "authenticate"), f.events);
    }
    @Test public void clockRollbackCannotExtendHandoffLifetime() {
        Fixture f = new Fixture(); f.client.verify(f.request(), f.results::add); f.run();
        f.now -= 1;
        assertNull(f.client.consume(f.results.get(0)));
    }
    @Test public void elapsedDeadlineAppliesEvenWhenWallClockIsUnchanged() {
        Fixture f = new Fixture(); f.client.verify(f.request(), f.results::add); f.run();
        f.nanos += 60_000_000_000L;
        assertNull(f.client.consume(f.results.get(0)));
    }
    private static final class Fixture implements BusinessService {
        final List<Runnable> tasks = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        final List<FaceVerificationResult> results = new ArrayList<>();
        final AuthenticatedUser member = new AuthenticatedUser(SessionToken.of("member-test-token"), UserType.USER, 0, 0);
        ApiResult<AuthenticatedUser> login = ApiResult.success(member);
        UserInfoRequest identity; boolean cancelDuringUpload; long now = 100_000; long nanos = 1;
        final OnlineFaceVerificationClient client = new OnlineFaceVerificationClient(
            new FaceAuthenticationPipeline(this, new FaceImageUploadAdapter() {
                public ApiResult<String> upload(byte[] jpeg, CallToken token) {
                    events.add("upload"); assertArrayEquals(new byte[]{(byte)255,(byte)216,(byte)255,(byte)217}, jpeg);
                    if(cancelDuringUpload) token.cancel(CallToken.Reason.CANCELLED);
                    return ApiResult.success("https://image.invalid/processed.jpg");
                }
                public String unavailableReason(){return "";}
            }), tasks::add, () -> now, () -> nanos);
        FaceVerificationRequest request(){return new FaceVerificationRequest("request-1",new byte[]{(byte)255,(byte)216,(byte)255,(byte)217},now,"device-1","process-1");}
        void run(){tasks.remove(0).run();}
        public ApiResult<AuthenticatedUser> authenticate(UserInfoRequest r,CallToken t){events.add("authenticate");identity=r;return login;}
        public ApiResult<AuthenticatedUser> verifyMemberDynamicCode(AuthenticatedUser u,String c,CallToken t){throw new AssertionError();}
        public ApiResult<ControlPanelPreview> controlPanelPreview(AuthenticatedUser u,int type,long a,int p,CallToken t){throw new AssertionError();}
        public ApiResult<UsedCabinetList> useCabinetList(AuthenticatedUser u,CallToken t){throw new AssertionError();}
        public ApiResult<AssignedCabinet> userBoard(AuthenticatedUser u,long a,long f,CallToken t){throw new AssertionError();}
        public ApiResult<EmptyBusinessResult> openBoard(AuthenticatedUser u,BoardAction a,long f,CallToken t){throw new AssertionError();}
    }
}
