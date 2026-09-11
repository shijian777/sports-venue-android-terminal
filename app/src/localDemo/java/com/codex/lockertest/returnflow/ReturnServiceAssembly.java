package com.codex.lockertest.returnflow;

/** localDemo source-set wiring for deterministic, in-process return fixtures. */
public final class ReturnServiceAssembly {
    private ReturnServiceAssembly() {
    }

    public static ReturnServiceClient create() {
        return new LocalDemoReturnServiceClient();
    }

    public static boolean isLocalDemo() {
        return true;
    }
}
