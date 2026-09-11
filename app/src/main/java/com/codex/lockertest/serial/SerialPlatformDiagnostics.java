package com.codex.lockertest.serial;

import android.os.Build;

import java.io.File;
import java.io.FileDescriptor;
import java.io.RandomAccessFile;

public final class SerialPlatformDiagnostics {
    private SerialPlatformDiagnostics() {
    }

    public static String inspect(File device) {
        return SerialDiagnostics.basic(device, supportedAbis()) + ", " + probeRawOpen(device);
    }

    private static String[] supportedAbis() {
        if (Build.VERSION.SDK_INT >= 21) {
            return Build.SUPPORTED_ABIS;
        }
        if (Build.CPU_ABI2 == null || Build.CPU_ABI2.isEmpty()) {
            return new String[]{Build.CPU_ABI};
        }
        return new String[]{Build.CPU_ABI, Build.CPU_ABI2};
    }

    private static String probeRawOpen(File device) {
        if (device == null || !device.exists()) {
            return "raw-open=skipped (device missing)";
        }
        if (Build.VERSION.SDK_INT >= 21) {
            return Api21.probe(device.getAbsolutePath());
        }
        RandomAccessFile file = null;
        try {
            file = new RandomAccessFile(device, "rw");
            return "raw-open=ok";
        } catch (Throwable throwable) {
            return "raw-open=failed: " + message(throwable);
        } finally {
            if (file != null) {
                try {
                    file.close();
                } catch (Throwable ignored) {
                    // Diagnostic descriptors must never remain open.
                }
            }
        }
    }

    private static String message(Throwable throwable) {
        String value = throwable.getMessage();
        return value == null || value.trim().isEmpty()
                ? throwable.getClass().getSimpleName() : value;
    }

    private static final class Api21 {
        private Api21() {
        }

        static String probe(String path) {
            FileDescriptor descriptor = null;
            try {
                descriptor = android.system.Os.open(
                        path,
                        android.system.OsConstants.O_RDWR
                                | android.system.OsConstants.O_NOCTTY,
                        0);
                return "raw-open=ok";
            } catch (android.system.ErrnoException exception) {
                return SerialDiagnostics.formatRawOpenFailure(
                        "open",
                        exception.errno,
                        android.system.OsConstants.errnoName(exception.errno));
            } catch (Throwable throwable) {
                return "raw-open=failed: " + message(throwable);
            } finally {
                if (descriptor != null) {
                    try {
                        android.system.Os.close(descriptor);
                    } catch (Throwable ignored) {
                        // Diagnostic descriptors must never remain open.
                    }
                }
            }
        }
    }
}
