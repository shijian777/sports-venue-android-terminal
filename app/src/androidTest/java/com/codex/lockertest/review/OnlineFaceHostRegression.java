package com.codex.lockertest.review;

import android.app.Instrumentation;
import android.view.View;
import com.codex.lockertest.bootstrap.*;
import com.codex.lockertest.business.*;
import com.codex.lockertest.business.journey.OnlineUnlockExecutor;
import com.codex.lockertest.face.verification.*;
import com.codex.lockertest.server.*;
import com.codex.lockertest.ui.business.OnlineCustomerHost;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Real Android host + real face pipeline; external upload/auth responses are deterministic test fixtures. */
final class OnlineFaceHostRegression {
    static void check(Instrumentation test) throws Exception {
        Class<?> uploadFactory = Class.forName("com.codex.lockertest.ui.business.OnlineCustomerHost$FaceUploadFactory");
        Class<?> verifierType = Class.forName("com.codex.lockertest.face.verification.OnlineFaceVerificationClient");
        Method begin = OnlineCustomerHost.class.getMethod("beginFace");
        Method finish = OnlineCustomerHost.class.getMethod("finishFace",verifierType,FaceVerificationResult.class);
        Fixture fixture = new Fixture();
        AtomicReference<View> current = new AtomicReference<>();
        Object uploads = Proxy.newProxyInstance(uploadFactory.getClassLoader(), new Class<?>[]{uploadFactory},
                (p,m,a) -> new FaceImageUploadAdapter() {
                    public ApiResult<String> upload(byte[] jpeg,CallToken token) {
                        fixture.uploads.incrementAndGet();
                        return ApiResult.success("https://images.invalid/fixture.jpg");
                    }
                    public String unavailableReason(){return "";}
                });
        Constructor<?> ctor = OnlineCustomerHost.class.getConstructor(android.content.Context.class,
                OnlineCustomerHost.ServiceFactory.class,OnlineCustomerHost.ScanSourceResolver.class,OnlineCustomerHost.Ui.class,
                DeviceSerialProvider.class,OnlineUnlockExecutor.class,uploadFactory);
        OnlineCustomerHost host = (OnlineCustomerHost)ctor.newInstance(test.getTargetContext(),
                (OnlineCustomerHost.ServiceFactory)(registration -> fixture),
                (OnlineCustomerHost.ScanSourceResolver)(id -> 2),new OnlineCustomerHost.Ui(){
                    public void show(View view){current.set(view);}
                    public void home(){current.set(null);}
                    public void message(String message){}
                },null,null,uploads);
        AtomicReference<Object> verifier = new AtomicReference<>();
        AtomicReference<FaceVerificationResult> result = new AtomicReference<>();
        CountDownLatch complete = new CountDownLatch(1);
        try {
            BootstrapSnapshot snapshot = ready();
            test.runOnMainSync(() -> {
                host.bind(snapshot); host.prepareReturn();
                try {
                    verifier.set(begin.invoke(host));
                    require(verifier.get()!=null,"Face lane did not reserve");
                    require(begin.invoke(host)==null,"Duplicate face button created a second owner");
                } catch(Exception e){throw new AssertionError(e);}
                require(host.showingJourney(),"Face capture not included in exclusive journey");
                host.submitPhone("13800000001","1234");
                host.scannerKey(42,'x',false,0,0); host.scannerKey(42,0,true,0,0);
                require(fixture.auth.get()==0,"Phone/scanner interrupted face authentication");
            });
            FaceVerificationRequest request = new FaceVerificationRequest("face-ui-1",
                    new byte[]{(byte)255,(byte)216,(byte)255,(byte)217},System.currentTimeMillis(),"device-test","process-test");
            FaceVerificationClient.Cancellable handle = ((FaceVerificationClient)verifier.get()).verify(request,r->{result.set(r);complete.countDown();});
            request.commitOwnedJpegTransfer();
            require(complete.await(3,TimeUnit.SECONDS),"Face authentication did not complete");
            handle.cancel();
            require(result.get()!=null && result.get().isPassed(),"Server-approved face was not accepted");
            test.runOnMainSync(() -> {
                try { require((Boolean)finish.invoke(host,verifier.get(),result.get()),"Face result did not enter owned-cabinet flow"); }
                catch(Exception e){throw new AssertionError(e);}
            });
            require(fixture.queried.await(3,TimeUnit.SECONDS),"Face return purpose was lost");
            require(fixture.auth.get()==1 && fixture.uploads.get()==1,"Duplicate server authentication after face success");
            require(fixture.mutations.get()==0,"Face success physically opened/returned a cabinet without user action");
            test.runOnMainSync(() -> {
                try {require(!(Boolean)finish.invoke(host,verifier.get(),result.get()),"Face result replay accepted");}
                catch(Exception e){throw new AssertionError(e);}
                host.unbind(); require(!host.hasJourney(),"Unbind retained face or member state");
            });
        } finally {test.runOnMainSync(host::close);}
    }
    private static BootstrapSnapshot ready() throws Exception {
        BootstrapSnapshot base = OnlineBusinessRegression.ready();
        java.lang.reflect.Field types = BaseSettingSnapshot.class.getDeclaredField("recognitionTypes");
        types.setAccessible(true);
        // Test-fixture-only option enablement; production settings still come exclusively from the server.
        types.set(base.baseSetting(),Collections.unmodifiableList(Arrays.asList("1","2","4","5")));
        return base;
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static final class Fixture implements BusinessService {
        final AtomicInteger auth=new AtomicInteger(),uploads=new AtomicInteger(),mutations=new AtomicInteger();
        final CountDownLatch queried=new CountDownLatch(1);
        public ApiResult<AuthenticatedUser> authenticate(UserInfoRequest r,CallToken t){
            require(r.type()==5 && "https://images.invalid/fixture.jpg".equals(r.keyword()),"Wrong processed face image");
            auth.incrementAndGet();return ApiResult.success(new AuthenticatedUser(SessionToken.of("TEST_FACE_TOKEN"),UserType.USER,0,0));
        }
        public ApiResult<UsedCabinetList> useCabinetList(AuthenticatedUser u,CallToken t){queried.countDown();return ApiResult.success(new UsedCabinetList("TEST_USER","TEST_PHONE",Collections.emptyList()));}
        public ApiResult<ControlPanelPreview> controlPanelPreview(AuthenticatedUser u,int type,long a,int p,CallToken t){throw new AssertionError("Return face became a new cabinet selection");}
        public ApiResult<AuthenticatedUser> verifyMemberDynamicCode(AuthenticatedUser u,String c,CallToken t){throw new AssertionError();}
        public ApiResult<AssignedCabinet> userBoard(AuthenticatedUser u,long a,long f,CallToken t){mutations.incrementAndGet();throw new AssertionError();}
        public ApiResult<EmptyBusinessResult> openBoard(AuthenticatedUser u,BoardAction a,long f,CallToken t){mutations.incrementAndGet();throw new AssertionError();}
    }
}
