package com.codex.lockertest.integration;

/**
 * One process-lifetime physical gateway with replaceable identity-bound client leases.
 */
public final class ProcessSerialGatewayOwner<T> {
    public enum Role {
        CUSTOMER,
        ADMIN
    }

    public interface Factory<T> {
        T create();
    }

    public interface Operation<T, R> {
        R run(T gateway);
    }

    private static final ProcessSerialGatewayOwner<Object> SHARED =
            new ProcessSerialGatewayOwner<>();

    private ProcessSerialGatewayOwner() {
    }

    @SuppressWarnings("unchecked")
    public static <T> ProcessSerialGatewayOwner<T> shared() {
        return (ProcessSerialGatewayOwner<T>) SHARED;
    }

    public static final class Lease<T> {
        private final ProcessSerialGatewayOwner<T> parent;
        private final Role role;
        private final long id;

        private Lease(ProcessSerialGatewayOwner<T> parent, Role role, long id) {
            this.parent = parent;
            this.role = role;
            this.id = id;
        }

        public Role role() {
            return role;
        }

        public long id() {
            return id;
        }
    }

    private T owner;
    private Role activeRole;
    private long leaseGeneration;
    private Lease<T> activeLease;

    /**
     * Creates the physical gateway only once, then replaces only the logical client.
     */
    public synchronized Lease<T> acquire(Role role, Factory<T> factory) {
        if (role == null) {
            throw new IllegalArgumentException("role cannot be null");
        }
        if (factory == null) {
            throw new IllegalArgumentException("factory cannot be null");
        }
        if (owner == null) {
            T candidate = factory.create();
            if (candidate == null) {
                throw new IllegalStateException("factory returned null");
            }
            owner = candidate;
        }
        leaseGeneration = nextGeneration(leaseGeneration);
        activeLease = new Lease<>(this, role, leaseGeneration);
        activeRole = role;
        return activeLease;
    }

    /**
     * Validates the lease and performs the complete gateway operation under one owner lock.
     * A replacement cannot become current between validation and subscribe/open/send/close.
     */
    public synchronized <R> R withGateway(
            Lease<T> expected,
            Operation<T, R> operation,
            R staleResult) {
        if (operation == null) {
            throw new IllegalArgumentException("operation cannot be null");
        }
        if (!isCurrentLocked(expected)) {
            return staleResult;
        }
        return operation.run(owner);
    }

    public synchronized boolean isCurrent(Lease<T> expected) {
        return isCurrentLocked(expected);
    }

    /**
     * Relinquishes logical access only. The physical gateway remains for process lifetime.
     */
    public synchronized boolean relinquish(Lease<T> expected) {
        if (!isCurrentLocked(expected)) {
            return false;
        }
        activeLease = null;
        activeRole = null;
        return true;
    }

    public synchronized Role currentRole() {
        return activeRole;
    }

    private boolean isCurrentLocked(Lease<T> expected) {
        return expected != null
                && expected.parent == this
                && expected == activeLease
                && expected.role == activeRole
                && owner != null;
    }

    private static long nextGeneration(long generation) {
        generation++;
        return generation > 0L ? generation : 1L;
    }
}
