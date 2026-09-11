package com.codex.lockertest.business;

import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FailClosedScannerSourceStoreTest {
    @Test public void successfulWritePublishesPersistedRole() {
        MutatingStore backing = new MutatingStore();
        ScannerSourceRegistry registry = registry(new FailClosedScannerSourceStore(backing));

        assertTrue(registry.assign(devices(), 7, 4));

        assertEquals(4, registry.resolve(devices(), 7));
    }

    @Test public void falseAfterMemoryMutationQuarantinesPersistedRole() {
        MutatingStore backing = new MutatingStore();
        backing.fail = true;
        ScannerSourceRegistry registry = registry(new FailClosedScannerSourceStore(backing));

        assertFalse(registry.assign(devices(), 7, 4));

        assertEquals(4, backing.read(key()));
        assertEquals(0, registry.resolve(devices(), 7));
    }

    @Test public void exceptionAfterMemoryMutationQuarantinesPersistedRole() {
        MutatingStore backing = new MutatingStore();
        backing.throwAfterMutation = true;
        ScannerSourceRegistry registry = registry(new FailClosedScannerSourceStore(backing));

        assertFalse(registry.assign(devices(), 7, 2));

        assertEquals(2, backing.read(key()));
        assertEquals(0, registry.resolve(devices(), 7));
    }

    @Test public void readDuringBlockedWriteReturnsImmediatelyAndFailsClosed() throws Exception {
        BlockingMutatingStore backing = new BlockingMutatingStore();
        FailClosedScannerSourceStore guarded = new FailClosedScannerSourceStore(backing);
        ScannerSourceRegistry registry = registry(guarded);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> write = executor.submit(() -> registry.assign(devices(), 7, 4));
            assertTrue(backing.writeEntered.await(2, TimeUnit.SECONDS));

            Future<Integer> read = executor.submit(() -> registry.resolve(devices(), 7));
            assertEquals(0, (int) read.get(1, TimeUnit.SECONDS));

            backing.releaseWrite.countDown();
            assertTrue(write.get(2, TimeUnit.SECONDS));
            assertEquals(4, registry.resolve(devices(), 7));
        } finally {
            backing.releaseWrite.countDown();
            executor.shutdownNow();
        }
    }

    @Test public void recreatedRegistryCannotBypassSharedStoreQuarantine() {
        MutatingStore backing = new MutatingStore();
        backing.fail = true;
        FailClosedScannerSourceStore processStore = new FailClosedScannerSourceStore(backing);
        assertFalse(registry(processStore).assign(devices(), 7, 4));

        ScannerSourceRegistry recreated = registry(processStore);

        assertEquals(0, recreated.resolve(devices(), 7));
    }

    @Test public void laterWriteCannotClearProcessLifetimeQuarantine() {
        MutatingStore backing = new MutatingStore();
        backing.fail = true;
        FailClosedScannerSourceStore processStore = new FailClosedScannerSourceStore(backing);
        ScannerSourceRegistry registry = registry(processStore);
        assertFalse(registry.assign(devices(), 7, 4));
        int writesAtFailure = backing.writeCount;
        backing.fail = false;

        assertFalse(registry.assign(devices(), 7, 2));

        assertEquals(writesAtFailure, backing.writeCount);
        assertEquals(0, registry.resolve(devices(), 7));
    }

    private static ScannerSourceRegistry registry(ScannerSourceRegistry.Store store) {
        return new ScannerSourceRegistry(store);
    }

    private static java.util.List<ScannerSourceRegistry.Device> devices() {
        return Collections.singletonList(new ScannerSourceRegistry.Device(7, "reader", 10, 20, true));
    }

    private static String key() {
        return devices().get(0).identityToken();
    }

    private static class MutatingStore implements ScannerSourceRegistry.Store {
        final Map<String, Integer> values = new HashMap<>();
        boolean fail;
        boolean throwAfterMutation;
        int writeCount;

        @Override public int read(String key) {
            Integer value = values.get(key);
            return value == null ? 0 : value;
        }

        @Override public boolean write(String key, int type) {
            writeCount++;
            if (type == 0) values.remove(key); else values.put(key, type);
            if (throwAfterMutation) throw new IllegalStateException("storage failure");
            return !fail;
        }
    }

    private static final class BlockingMutatingStore extends MutatingStore {
        final CountDownLatch writeEntered = new CountDownLatch(1);
        final CountDownLatch releaseWrite = new CountDownLatch(1);

        @Override public boolean write(String key, int type) {
            if (type == 0) values.remove(key); else values.put(key, type);
            writeCount++;
            writeEntered.countDown();
            try {
                if (!releaseWrite.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test write timed out");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("test interrupted", interrupted);
            }
            return true;
        }
    }
}
