package com.codex.lockertest.bootstrap;

import android.content.Context;

/** Production composition root. */
public final class BootstrapAssembly {
    private BootstrapAssembly() { }

    public static BootstrapRuntime create(Context context) {
        return ProductionBootstrapRuntimeFactory.create();
    }
}
