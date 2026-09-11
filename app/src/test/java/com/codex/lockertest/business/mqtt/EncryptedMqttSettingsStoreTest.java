package com.codex.lockertest.business.mqtt;

import org.junit.Test;

import java.security.GeneralSecurityException;
import java.util.Arrays;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class EncryptedMqttSettingsStoreTest {
    private static final SecretKey KEY_A = key((byte) 0x11);
    private static final SecretKey KEY_B = key((byte) 0x22);

    @Test
    public void absentDataReturnsNullWithoutRequestingOrCreatingAKey() throws Exception {
        FakeKeys keys = new FakeKeys(KEY_A);
        EncryptedMqttSettingsStore store = store(keys, new MemoryStorage());

        assertNull(store.load());
        assertEquals(0, keys.reads);
        assertEquals(0, keys.writes);
    }

    @Test
    public void saveThenLoadUsesAuthenticatedEncryptionAndPreservesFields() throws Exception {
        MemoryStorage storage = new MemoryStorage();
        EncryptedMqttSettingsStore store = store(new FakeKeys(KEY_A), storage);
        MqttSettings input = settings("opaque-password");

        store.save(input);
        MqttSettings output = store.load();

        assertNotNull(storage.bytes);
        assertFalse(new String(storage.bytes, "UTF-8").contains("opaque-password"));
        assertEquals(input.host(), output.host());
        assertEquals(input.port(), output.port());
        assertEquals(input.clientId(), output.clientId());
        assertEquals(input.username(), output.username());
        assertEquals(input.password(), output.password());
        assertEquals(input.topic(), output.topic());
    }

    @Test
    public void eachSaveUsesAFreshIv() throws Exception {
        MemoryStorage storage = new MemoryStorage();
        EncryptedMqttSettingsStore store = store(new FakeKeys(KEY_A), storage);
        store.save(settings("secret"));
        byte[] first = storage.bytes.clone();

        store.save(settings("secret"));

        assertFalse(Arrays.equals(first, storage.bytes));
    }

    @Test
    public void rejectsCorruptTruncatedWrongKeyAndOversizedCiphertext() throws Exception {
        MemoryStorage storage = new MemoryStorage();
        store(new FakeKeys(KEY_A), storage).save(settings("secret"));
        byte[] good = storage.bytes.clone();

        storage.bytes[storage.bytes.length - 1] ^= 1;
        rejectLoad(store(new FakeKeys(KEY_A), storage));

        for (int length = 0; length < good.length; length++) {
            storage.bytes = Arrays.copyOf(good, length);
            rejectLoad(store(new FakeKeys(KEY_A), storage));
        }

        storage.bytes = good;
        rejectLoad(store(new FakeKeys(KEY_B), storage));

        storage.bytes = new byte[8251];
        rejectLoad(store(new FakeKeys(KEY_A), storage));
    }

    @Test
    public void existingCiphertextWithMissingKeyFailsClosed() throws Exception {
        MemoryStorage storage = new MemoryStorage();
        store(new FakeKeys(KEY_A), storage).save(settings("secret"));
        FakeKeys missing = new FakeKeys(null);

        rejectLoad(store(missing, storage));

        assertEquals(1, missing.reads);
        assertEquals(0, missing.writes);
    }

    @Test
    public void failedAtomicWriteThrowsAndDoesNotExposeUnsavedSettings() throws Exception {
        MemoryStorage storage = new MemoryStorage();
        EncryptedMqttSettingsStore store = store(new FakeKeys(KEY_A), storage);
        store.save(settings("old-secret"));
        storage.failWrites = true;

        try {
            store.save(settings("new-secret"));
            fail("failed persistence must not report success");
        } catch (Exception expected) {
            assertFalse(String.valueOf(expected.getMessage()).contains("new-secret"));
        }

        storage.failWrites = false;
        assertEquals("old-secret", store.load().password());
    }

    private static EncryptedMqttSettingsStore store(FakeKeys keys, MemoryStorage storage) {
        return new EncryptedMqttSettingsStore(keys, storage);
    }

    private static void rejectLoad(EncryptedMqttSettingsStore store) {
        try {
            store.load();
            fail("expected encrypted settings load to fail closed");
        } catch (Exception expected) {
            assertTrue(expected instanceof GeneralSecurityException
                    || expected instanceof java.io.IOException);
        }
    }

    private static MqttSettings settings(String password) {
        return new MqttSettings("broker.example", 8883, "terminal-007",
                " user-007 ", password, "locker/007");
    }

    private static SecretKey key(byte value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, value);
        return new SecretKeySpec(bytes, "AES");
    }

    private static final class FakeKeys implements EncryptedMqttSettingsStore.Keys {
        private final SecretKey key;
        private int reads;
        private int writes;

        private FakeKeys(SecretKey key) {
            this.key = key;
        }

        @Override
        public SecretKey keyForRead() {
            reads++;
            return key;
        }

        @Override
        public SecretKey keyForWrite() {
            writes++;
            return key;
        }
    }

    private static final class MemoryStorage implements EncryptedMqttSettingsStore.Storage {
        private byte[] bytes;
        private boolean failWrites;

        @Override
        public byte[] read() {
            return bytes == null ? null : bytes.clone();
        }

        @Override
        public void writeAtomically(byte[] replacement) throws Exception {
            if (failWrites) {
                throw new java.io.IOException("simulated atomic write failure");
            }
            bytes = replacement.clone();
        }
    }
}
