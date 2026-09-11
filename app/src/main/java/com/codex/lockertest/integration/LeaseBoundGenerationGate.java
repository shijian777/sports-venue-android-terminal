package com.codex.lockertest.integration;

/**
 * Runs a queued callback only while both its Activity generation and exact
 * process-owner lease are still current.
 */
public final class LeaseBoundGenerationGate<T> {
    public interface Action<T> {
        void run(T gateway);
    }

    private final GenerationGate generations;
    private final ProcessSerialGatewayOwner<T> owner;

    public LeaseBoundGenerationGate(
            GenerationGate generations,
            ProcessSerialGatewayOwner<T> owner) {
        if (generations == null) {
            throw new IllegalArgumentException("generations cannot be null");
        }
        if (owner == null) {
            throw new IllegalArgumentException("owner cannot be null");
        }
        this.generations = generations;
        this.owner = owner;
    }

    public boolean runIfCurrent(
            long generation,
            ProcessSerialGatewayOwner.Lease<T> expectedLease,
            Action<T> action) {
        if (action == null) {
            throw new IllegalArgumentException("action cannot be null");
        }
        if (!generations.accepts(generation)) {
            return false;
        }
        return owner.withGateway(
                expectedLease,
                gateway -> {
                    if (!generations.accepts(generation)) {
                        return false;
                    }
                    action.run(gateway);
                    return true;
                },
                false);
    }
}
