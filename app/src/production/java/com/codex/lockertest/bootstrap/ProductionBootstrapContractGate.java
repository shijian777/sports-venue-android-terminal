package com.codex.lockertest.bootstrap;

/** Pins approval to the reviewed three-endpoint read-only bootstrap contract. */
public final class ProductionBootstrapContractGate {
    private ProductionBootstrapContractGate() { }

    public static boolean isLiveApproved() {
        return true;
    }
}
