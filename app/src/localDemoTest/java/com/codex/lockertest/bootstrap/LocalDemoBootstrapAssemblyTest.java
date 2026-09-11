package com.codex.lockertest.bootstrap;

import com.codex.lockertest.ui.TerminalReadiness;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class LocalDemoBootstrapAssemblyTest {
    @Test
    public void localDemoRuntimeIsReadyWithoutThreadsOrNetwork() {
        BootstrapRuntime runtime = BootstrapAssembly.create(null);
        List<BootstrapSnapshot> snapshots = new ArrayList<>();

        long first = runtime.start(snapshots::add);
        long second = runtime.restartBootstrap();

        assertTrue(second > first);
        assertEquals(TerminalReadiness.State.READY_LOCAL_DEMO,
                runtime.readinessSource().current().state());
        assertTrue(runtime.readinessSource().current().customerActionsEnabled());
        assertEquals(2, snapshots.size());
        runtime.cancel();
        runtime.close();
        runtime.close();
    }
}
