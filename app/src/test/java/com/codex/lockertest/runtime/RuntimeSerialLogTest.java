package com.codex.lockertest.runtime;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public final class RuntimeSerialLogTest {
    @Test
    public void listenerReceivesExistingHistoryAndEveryLaterMutation() {
        RuntimeSerialLog log = new RuntimeSerialLog();
        log.append("existing");
        List<String> snapshots = new ArrayList<>();
        RuntimeSerialLog.Listener listener = snapshots::add;

        log.addListener(listener);
        log.append("live");
        log.clear();
        log.removeListener(listener);
        log.append("not observed");

        assertEquals(3, snapshots.size());
        assertEquals("existing", snapshots.get(0));
        assertEquals("existing\nlive", snapshots.get(1));
        assertEquals("", snapshots.get(2));
    }

    @Test
    public void sharedInstanceIsProcessWideButFreshInstancesHaveNoHistory() {
        RuntimeSerialLog first = new RuntimeSerialLog();
        first.append("runtime only");

        RuntimeSerialLog second = new RuntimeSerialLog();

        assertTrue(second.snapshot().isEmpty());
        assertSame(RuntimeSerialLog.shared(), RuntimeSerialLog.shared());
    }

    @Test
    public void appendThroughOneSharedHandleIsVisibleThroughEveryOtherHandle() {
        RuntimeSerialLog first = RuntimeSerialLog.shared();
        RuntimeSerialLog second = RuntimeSerialLog.shared();
        String marker = "shared-marker-" + System.nanoTime();

        first.append(marker);

        assertTrue(second.snapshot().contains(marker));
    }

    @Test
    public void historyNeverExceedsSixHundredLinesOrFortyEightThousandCharacters() {
        RuntimeSerialLog log = new RuntimeSerialLog();
        for (int index = 0; index < 605; index++) {
            log.append(String.format("line-%03d", index));
        }

        String lineBounded = log.snapshot();
        assertFalse(lineBounded.contains("line-004"));
        assertTrue(lineBounded.contains("line-604"));
        assertTrue(lineBounded.split("\\n", -1).length <= 600);

        StringBuilder huge = new StringBuilder();
        for (int index = 0; index < 50_123; index++) {
            huge.append((char) ('A' + (index % 26)));
        }
        log.clear();
        log.append(huge.toString());

        String characterBounded = log.snapshot();
        assertTrue(characterBounded.length() <= 48_000);
        assertTrue(huge.toString().endsWith(characterBounded));
    }
}
