package com.codex.lockertest.serial;

final class ReaderGeneration<T> {
    static final class Lease<T> {
        private final long generation;
        private final T resource;

        private Lease(long generation, T resource) {
            this.generation = generation;
            this.resource = resource;
        }

        T resource() {
            return resource;
        }
    }

    private long generation;
    private Lease<T> current;

    synchronized Lease<T> activate(T resource) {
        if (resource == null) {
            throw new IllegalArgumentException("resource cannot be null");
        }
        if (current != null) {
            throw new IllegalStateException("current reader must be detached first");
        }
        generation = next(generation);
        current = new Lease<>(generation, resource);
        return current;
    }

    synchronized boolean isCurrent(Lease<T> candidate) {
        return candidate != null
                && current == candidate
                && current.generation == candidate.generation;
    }

    synchronized boolean runIfCurrent(Lease<T> candidate, Runnable action) {
        if (action == null) {
            throw new IllegalArgumentException("action cannot be null");
        }
        if (!isCurrent(candidate)) {
            return false;
        }
        action.run();
        return true;
    }

    synchronized boolean clear(Lease<T> candidate) {
        if (!isCurrent(candidate)) {
            return false;
        }
        current = null;
        return true;
    }

    private static long next(long value) {
        value++;
        return value > 0L ? value : 1L;
    }
}
