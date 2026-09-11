package com.codex.lockertest.serial;

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

public final class SerialDiagnosticsTest {
    @Test
    public void basicFactsExposeTheExactNodeAccessAndAbi() throws Exception {
        File root = new File("manual-build/test-fixtures/diagnostics");
        root.mkdirs();
        File node = new File(root, "ttyS0");
        node.createNewFile();
        String value = SerialDiagnostics.basic(
                node, new String[]{"arm64-v8a", "armeabi-v7a"});
        assertTrue(value.contains(node.getAbsolutePath()));
        assertTrue(value.contains("exists=true"));
        assertTrue(value.contains("read="));
        assertTrue(value.contains("write="));
        assertTrue(value.contains("arm64-v8a"));
        assertTrue(value.contains("armeabi-v7a"));
    }

    @Test
    public void rawOpenFailureNamesTheSystemCallAndErrno() {
        assertEquals("raw-open=open: errno=13 (EACCES)",
                SerialDiagnostics.formatRawOpenFailure("open", 13, "EACCES"));
    }
}
