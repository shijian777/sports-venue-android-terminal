package com.codex.lockertest.ui.business;

import com.codex.lockertest.ui.IdCardScanSession;

/** Owns one device-bound HID frame and its quiet-period completion callback. */
final class HidScanFrameDispatcher {
    interface Scheduler {
        void postDelayed(Runnable task, long delayMillis);
        void removeCallbacks(Runnable task);
    }

    interface Listener {
        void onFrame(IdCardScanSession.Completion completion, int credentialType);
    }

    private static final long IDLE_MILLIS = 350L;

    private final IdCardScanSession session;
    private final Scheduler scheduler;
    private final Listener listener;
    private Runnable pendingIdle;

    HidScanFrameDispatcher(int maximumLength, Scheduler scheduler, Listener listener) {
        if (scheduler == null || listener == null) {
            throw new IllegalArgumentException("HID scan dependencies required");
        }
        this.session = new IdCardScanSession(maximumLength);
        this.scheduler = scheduler;
        this.listener = listener;
    }

    void key(int deviceId, int character, boolean terminator, int credentialType) {
        if (terminator) {
            IdCardScanSession.Completion completion = session.finish(deviceId);
            if (completion == null) return;
            cancelIdle();
            listener.onFrame(completion, credentialType);
            return;
        }
        if (character < 0 || character > Character.MAX_VALUE) return;
        IdCardScanSession.AppendResult appended = session.appendCharacter(deviceId, (char) character);
        if (appended == IdCardScanSession.AppendResult.IGNORED_OTHER_DEVICE) return;
        cancelIdle();
        final long generation = session.generation();
        pendingIdle = new Runnable() {
            @Override public void run() {
                if (pendingIdle != this) return;
                pendingIdle = null;
                IdCardScanSession.Completion completion = session.finishIfCurrent(generation);
                if (completion != null) listener.onFrame(completion, credentialType);
            }
        };
        scheduler.postDelayed(pendingIdle, IDLE_MILLIS);
    }

    void reset() {
        cancelIdle();
        session.reset();
    }

    private void cancelIdle() {
        if (pendingIdle != null) scheduler.removeCallbacks(pendingIdle);
        pendingIdle = null;
    }
}
