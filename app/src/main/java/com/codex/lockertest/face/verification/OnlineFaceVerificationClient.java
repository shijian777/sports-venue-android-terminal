package com.codex.lockertest.face.verification;

import com.codex.lockertest.business.AuthenticatedUser;
import com.codex.lockertest.business.FaceAuthenticationPipeline;
import com.codex.lockertest.server.ApiResult;
import com.codex.lockertest.server.CallToken;
import com.codex.lockertest.server.ServerFailure;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongSupplier;

/** Bridges the camera controller to the server-owned customer identity flow. */
public final class OnlineFaceVerificationClient implements FaceVerificationClient, AutoCloseable {
    private final Object gate = new Object();
    private final FaceAuthenticationPipeline pipeline;
    private final Executor worker;
    private final ExecutorService ownedWorker;
    private final LongSupplier clock;
    private final LongSupplier monotonicNanos;
    private Operation current;
    private AuthenticatedUser verifiedUser;
    private FaceVerificationResult issuedResult;
    private long issuedNanos;
    private boolean closed;

    public OnlineFaceVerificationClient(FaceAuthenticationPipeline pipeline) {
        this(pipeline, Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "online-face-upload"); thread.setDaemon(true); return thread;
        }), System::currentTimeMillis, System::nanoTime, true);
    }
    OnlineFaceVerificationClient(FaceAuthenticationPipeline pipeline, Executor worker, LongSupplier clock) {
        this(pipeline, worker, clock, System::nanoTime, false);
    }
    OnlineFaceVerificationClient(FaceAuthenticationPipeline pipeline, Executor worker,
            LongSupplier clock, LongSupplier monotonicNanos) {
        this(pipeline, worker, clock, monotonicNanos, false);
    }
    private OnlineFaceVerificationClient(FaceAuthenticationPipeline pipeline, Executor worker,
            LongSupplier clock, LongSupplier monotonicNanos, boolean ownsWorker) {
        if(pipeline == null || worker == null || clock == null || monotonicNanos == null)
            throw new IllegalArgumentException("Face dependencies required");
        this.pipeline=pipeline; this.worker=worker; this.clock=clock; this.monotonicNanos=monotonicNanos;
        this.ownedWorker=ownsWorker?(ExecutorService)worker:null;
    }
    public Cancellable verify(FaceVerificationRequest request, Callback callback) {
        if(request == null || callback == null) throw new IllegalArgumentException("Face request and callback required");
        final Operation operation;
        synchronized(gate) {
            if(closed || current != null) {
                request.close();
                callback.onCompleted(FaceVerificationResult.terminalFailure(FaceVerificationStatus.CANCELLED,request.requestId()));
                return () -> {};
            }
            verifiedUser=null; issuedResult=null;
            operation=new Operation(request,callback,request.takeOwnedJpeg());
            current=operation;
        }
        try {worker.execute(() -> run(operation));}
        catch(RuntimeException rejected) {
            cancel(operation);
            callback.onCompleted(FaceVerificationResult.terminalFailure(FaceVerificationStatus.SERVER_ERROR,request.requestId()));
        }
        return () -> cancel(operation);
    }
    private void run(Operation operation) {
        byte[] jpeg;
        synchronized(gate) {
            if(closed || current!=operation || operation.token.isCancelled()) return;
            jpeg=operation.jpeg; operation.jpeg=null;
        }
        ApiResult<AuthenticatedUser> result;
        try {result=pipeline.authenticate(jpeg,operation.token);}
        catch(RuntimeException failure) {result=ApiResult.failure(ServerFailure.of(ServerFailure.Kind.CONFIGURATION));}
        finally {if(jpeg!=null) Arrays.fill(jpeg,(byte)0);}
        synchronized(gate) {
            if(closed || current!=operation || operation.token.isCancelled()) {
                if(current==operation) current=null;
                return;
            }
            current=null; operation.completed=true;
            long now=clock.getAsLong();
            FaceVerificationResult response;
            if(result!=null && result.isSuccess() && result.value().isCustomerReady()
                    && now>0 && now<Long.MAX_VALUE-60_000) {
                // This is only a one-use handoff to the online journey, never a legacy physical-unlock permit.
                response=FaceVerificationResult.passed(FaceVerificationSource.REMOTE_SERVER,
                        operation.request.requestId(),UUID.randomUUID().toString().replace("-",""),UUID.randomUUID().toString().replace("-",""),
                        now+60_000,operation.request.deviceBinding(),operation.request.processBinding(),0);
                verifiedUser=result.value(); issuedResult=response;
                issuedNanos=monotonicNanos.getAsLong();
            } else {
                response=FaceVerificationResult.terminalFailure(FaceVerificationStatus.SERVER_ERROR,operation.request.requestId());
            }
            operation.callback.onCompleted(response);
        }
    }
    /** Requires the exact in-memory completion; intent extras and reconstructed results cannot grant access. */
    public AuthenticatedUser consume(FaceVerificationResult result) {
        synchronized(gate) {
            if(closed || result==null || result!=issuedResult || verifiedUser==null) return null;
            long now=clock.getAsLong();
            long elapsed=monotonicNanos.getAsLong()-issuedNanos;
            AuthenticatedUser user=now>=result.expiresAtEpochMillis()-60_000
                    && now<result.expiresAtEpochMillis() && elapsed>=0 && elapsed<60_000_000_000L
                    ?verifiedUser:null;
            verifiedUser=null; issuedResult=null;
            return user;
        }
    }
    private void cancel(Operation operation) {
        byte[] wipe;
        synchronized(gate) {
            if(operation.completed) return;
            if(current==operation) current=null;
            wipe=operation.jpeg; operation.jpeg=null;
        }
        operation.token.cancel(CallToken.Reason.CANCELLED);
        if(wipe!=null) Arrays.fill(wipe,(byte)0);
    }
    public void close() {
        Operation operation;
        synchronized(gate) {
            if(closed)return;
            closed=true; verifiedUser=null; issuedResult=null; operation=current; current=null;
        }
        if(operation!=null)cancel(operation);
        if(ownedWorker!=null)ownedWorker.shutdownNow();
    }
    private static final class Operation {
        final FaceVerificationRequest request;
        final Callback callback;
        final CallToken token=new CallToken();
        byte[] jpeg;
        boolean completed;
        Operation(FaceVerificationRequest request,Callback callback,byte[] jpeg) {
            this.request=request;this.callback=callback;this.jpeg=jpeg;
        }
    }
}
