package com.codex.lockertest.returnflow;

/** Production wiring deliberately fails closed until a real return service is integrated. */
public final class ReturnServiceAssembly {
    private ReturnServiceAssembly() {
    }

    public static ReturnServiceClient create() {
        return new FailClosedReturnServiceClient();
    }

    public static boolean isLocalDemo() {
        return false;
    }
}
