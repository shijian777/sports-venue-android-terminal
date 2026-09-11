package com.codex.lockertest.runtime;

import com.codex.lockertest.protocol.BoundedLogBuffer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class RuntimeSerialLog {
    public interface Listener {
        void onLogChanged(String snapshot);
    }

    private static final int MAX_CHARACTERS = 48_000;
    private static final int MAX_LINES = 600;
    private static final RuntimeSerialLog SHARED = new RuntimeSerialLog();

    private final BoundedLogBuffer buffer =
            new BoundedLogBuffer(MAX_CHARACTERS, MAX_LINES);
    private final Set<Listener> listeners = new LinkedHashSet<>();

    public static RuntimeSerialLog shared() {
        return SHARED;
    }

    public synchronized void append(String line) {
        buffer.append(line);
        notifyListeners(buffer.snapshot());
    }

    public synchronized String snapshot() {
        return buffer.snapshot();
    }

    public synchronized void clear() {
        buffer.clear();
        notifyListeners("");
    }

    public synchronized void addListener(Listener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener cannot be null");
        }
        if (listeners.add(listener)) {
            listener.onLogChanged(buffer.snapshot());
        }
    }

    public synchronized void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private void notifyListeners(String snapshot) {
        List<Listener> current = new ArrayList<>(listeners);
        for (Listener listener : current) {
            listener.onLogChanged(snapshot);
        }
    }
}
