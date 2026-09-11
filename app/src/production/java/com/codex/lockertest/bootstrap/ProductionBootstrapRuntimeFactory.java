package com.codex.lockertest.bootstrap;

import com.codex.lockertest.server.ProductionSecretProvider;
import com.codex.lockertest.server.ProductionBootstrapService;
import com.codex.lockertest.ui.TerminalReadiness;

import java.util.Arrays;

/** Builds production bootstrap only through fixed, reviewable trust gates. */
public final class ProductionBootstrapRuntimeFactory {
    private ProductionBootstrapRuntimeFactory() { }

    public static BootstrapRuntime create() {
        char[] sysCode = ProductionSecretProvider.copyOrEmpty();
        if (sysCode == null) sysCode = new char[0];
        ProductionBootstrapService service = null;
        ProductionBootstrapScheduler scheduler = null;
        boolean ownershipTransferred = false;
        try {
            if (sysCode.length == 0) {
                return ProductionBootstrapRuntime.blocked(
                        TerminalReadiness.serverNotConfigured(),
                        BootstrapSnapshot.FailureReason.KEY_MISSING);
            }
            if (!ProductionBootstrapContractGate.isLiveApproved()) {
                return ProductionBootstrapRuntime.blocked(
                        TerminalReadiness.serverContractUnapproved(),
                        BootstrapSnapshot.FailureReason.CONTRACT);
            }
            service = ProductionBootstrapService.createLive(sysCode);
            scheduler = new ProductionBootstrapScheduler();
            BootstrapRuntime runtime = new ProductionBootstrapRuntime(
                    new Rk3288DeviceSerialProvider(),
                    service,
                    scheduler,
                    service,
                    scheduler);
            ownershipTransferred = true;
            return runtime;
        } catch (RuntimeException configurationFailure) {
            return ProductionBootstrapRuntime.blocked(
                    TerminalReadiness.serverNotConfigured(),
                    BootstrapSnapshot.FailureReason.CONFIGURATION);
        } finally {
            if (!ownershipTransferred) {
                closeQuietly(scheduler);
                closeQuietly(service);
            }
            Arrays.fill(sysCode, '\0');
        }
    }

    static BootstrapRuntime createForTest(
            BootstrapService service,
            DeviceSerialProvider serialProvider,
            BootstrapScheduler scheduler) {
        if (service == null || serialProvider == null || scheduler == null) {
            throw new IllegalArgumentException("Bootstrap dependencies are required");
        }
        return new ProductionBootstrapRuntime(serialProvider, service, scheduler);
    }

    private static void closeQuietly(AutoCloseable resource) {
        if (resource == null) return;
        try {
            resource.close();
        } catch (Exception ignored) {
            // Construction already failed and the returned runtime remains blocked.
        }
    }
}
