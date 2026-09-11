package com.codex.lockertest.ui;

/** Supplies the latest immutable terminal availability decision. */
public interface TerminalReadinessSource {
    TerminalReadiness current();
}
