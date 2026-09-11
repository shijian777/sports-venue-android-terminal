package com.codex.lockertest.palm;

final class PalmCleanupGuard {
    interface Action { void run(); }

    private PalmCleanupGuard() {}

    static boolean attempt(Action action) {
        try {
            action.run();
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }
}
