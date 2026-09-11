package com.codex.lockertest.serial;

/**
 * A newest-subscriber-wins callback slot with exact-identity removal.
 * Delivery is linearized with replacement/removal, so a replacement cannot become
 * current while an older callback is still running.
 */
public final class IdentityListenerRegistry<T> {
    public interface Delivery<T> {
        void deliver(T listener);
    }

    public static final class Subscription<T> {
        private final IdentityListenerRegistry<T> parent;
        private final T listener;
        private final long id;

        private Subscription(IdentityListenerRegistry<T> parent, T listener, long id) {
            this.parent = parent;
            this.listener = listener;
            this.id = id;
        }

        public long id() {
            return id;
        }
    }

    private long generation;
    private Subscription<T> current;

    public synchronized Subscription<T> replace(T listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener cannot be null");
        }
        generation = nextGeneration(generation);
        current = new Subscription<>(this, listener, generation);
        return current;
    }

    public synchronized boolean remove(Subscription<T> expected) {
        if (!isCurrentLocked(expected)) {
            return false;
        }
        current = null;
        return true;
    }

    public synchronized boolean isCurrent(Subscription<T> expected) {
        return isCurrentLocked(expected);
    }

    public synchronized boolean deliver(Delivery<T> delivery) {
        if (delivery == null) {
            throw new IllegalArgumentException("delivery cannot be null");
        }
        T listener = current == null ? null : current.listener;
        if (listener == null) {
            return false;
        }
        delivery.deliver(listener);
        return true;
    }

    private boolean isCurrentLocked(Subscription<T> expected) {
        return expected != null
                && expected.parent == this
                && expected == current;
    }

    private static long nextGeneration(long generation) {
        generation++;
        return generation > 0L ? generation : 1L;
    }
}
