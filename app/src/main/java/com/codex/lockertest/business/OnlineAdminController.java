package com.codex.lockertest.business;

import com.codex.lockertest.business.journey.OnlineCustomerCoordinator;
import com.codex.lockertest.server.ApiResult;
import com.codex.lockertest.server.CallToken;

/** A short-lived server administrator login; never a local-password bypass. */
public final class OnlineAdminController {
    public enum State { IDLE, AUTHENTICATING, READY, FAILED }
    public interface Listener { void changed(State state); }
    private final BusinessService service;
    private final String deviceSerial;
    private final OnlineCustomerCoordinator.Scheduler scheduler;
    private final OnlineCustomerCoordinator.Clock clock;
    private final Listener listener;
    private State state = State.IDLE;
    private Attempt current;
    private long generation;
    private long authorizedUntil;
    private OnlineCustomerCoordinator.Cancellable expiry;
    public OnlineAdminController(BusinessService service, String deviceSerial,
            OnlineCustomerCoordinator.Scheduler scheduler, OnlineCustomerCoordinator.Clock clock, Listener listener) {
        if (service == null || !valid(deviceSerial, 256) || scheduler == null || clock == null || listener == null)
            throw new IllegalArgumentException("Administrator dependencies required");
        this.service = service; this.deviceSerial = deviceSerial;
        this.scheduler = scheduler; this.clock = clock; this.listener = listener;
    }
    public boolean login(String username, String password, String dynamicCode) {
        if (!valid(username, 256) || !valid(password, 4096)
                || dynamicCode != null && !dynamicCode.isEmpty() && !valid(dynamicCode, 256)) return false;
        final Attempt attempt;
        synchronized (this) {
            if (state == State.AUTHENTICATING || state == State.READY) return false;
            attempt = new Attempt(++generation, clock.nowMillis() + 15_000, username, password,
                    dynamicCode == null || dynamicCode.isEmpty() ? null : dynamicCode);
            current = attempt; state = State.AUTHENTICATING;
        }
        publish();
        try {
            attach(attempt, scheduler.schedule(() -> finish(attempt, null), 15_000), true);
            attach(attempt, scheduler.submit(() -> authenticate(attempt)), false);
            return true;
        } catch (RuntimeException failure) { finish(attempt, null); return false; }
    }
    public synchronized boolean authorized() {
        return state == State.READY && clock.nowMillis() < authorizedUntil;
    }
    public synchronized State state() { return state; }
    public void cancel() {
        Attempt old;
        OnlineCustomerCoordinator.Cancellable oldExpiry;
        synchronized (this) {
            generation++; old = current; current = null;
            oldExpiry = expiry; expiry = null; authorizedUntil = 0; state = State.IDLE;
        }
        if (old != null) old.cancel();
        if (oldExpiry != null) oldExpiry.cancel();
        publish();
    }
    private void authenticate(Attempt attempt) {
        String[] values;
        synchronized (this) {
            if (current != attempt || attempt.token.isCancelled()) return;
            values = attempt.take();
        }
        if (clock.nowMillis() >= attempt.deadline) { finish(attempt, null); return; }
        ApiResult<AdminLogin> result = null;
        try { result = service.adminLogin(values[0], values[1], values[2], attempt.token); }
        catch (RuntimeException | LinkageError failure) { /* No vendor or credential text escapes. */ }
        finally { java.util.Arrays.fill(values, null); }
        finish(attempt, result);
    }
    private void finish(Attempt attempt, ApiResult<AdminLogin> result) {
        boolean ready;
        synchronized (this) {
            if (current != attempt) return;
            current = null;
            ready = !attempt.token.isCancelled() && clock.nowMillis() < attempt.deadline
                    && result != null && result.isSuccess()
                    && deviceSerial.equals(result.value().deviceSerial());
            state = ready ? State.READY : State.FAILED;
            authorizedUntil = ready ? clock.nowMillis() + 60_000 : 0;
        }
        attempt.cancel();
        if (ready) {
            try {
                OnlineCustomerCoordinator.Cancellable timer = scheduler.schedule(() -> expire(attempt.generation), 60_000);
                if (timer == null) throw new IllegalStateException();
                synchronized (this) {
                    if (generation == attempt.generation && state == State.READY) expiry = timer;
                    else timer.cancel();
                }
            } catch (RuntimeException failure) { cancel(); }
        }
        publish();
    }
    private void expire(long expected) {
        synchronized (this) { if (generation != expected || state != State.READY) return; }
        cancel();
    }
    private void attach(Attempt attempt, OnlineCustomerCoordinator.Cancellable handle, boolean timer) {
        if (handle == null) throw new IllegalStateException("Administrator scheduler unavailable");
        synchronized (this) {
            if (current != attempt) { handle.cancel(); return; }
            if (timer) attempt.timer = handle; else attempt.worker = handle;
        }
    }
    private void publish() { listener.changed(state()); }
    private static boolean valid(String value, int max) {
        if (value == null || value.isEmpty() || value.length() > max) return false;
        for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i))) return false;
        return true;
    }
    private static final class Attempt {
        final long generation;
        final long deadline;
        final CallToken token = new CallToken();
        private String[] credentials;
        OnlineCustomerCoordinator.Cancellable worker;
        OnlineCustomerCoordinator.Cancellable timer;
        Attempt(long generation, long deadline, String username, String password, String code) {
            this.generation = generation; this.deadline = deadline;
            credentials = new String[]{username, password, code};
        }
        synchronized String[] take() { String[] owned = credentials; credentials = null; return owned; }
        synchronized void cancel() {
            if (credentials != null) java.util.Arrays.fill(credentials, null);
            credentials = null;
            token.cancel(CallToken.Reason.CANCELLED);
            if (timer != null) timer.cancel();
            if (worker != null) worker.cancel();
        }
    }
}
