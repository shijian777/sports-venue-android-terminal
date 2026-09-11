package com.codex.lockertest.ui.business;

import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;

import com.codex.lockertest.business.mqtt.EncryptedMqttSettingsStore;
import com.codex.lockertest.business.mqtt.MqttSettings;
import com.codex.lockertest.business.mqtt.MqttSettingsStore;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.Arrays;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/** Android Keystore and no-backup atomic-file adapter for MQTT settings. */
public final class AndroidMqttSettingsStore implements MqttSettingsStore {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "smart_locker_mqtt_settings_v1";
    private static final String FILE_NAME = "mqtt-settings-v1.bin";
    private static final int MAX_ENCRYPTED_BYTES = 8230;
    private static final Object KEY_LOCK = new Object();
    private static final Object FILE_LOCK = new Object();

    private final Context applicationContext;

    public AndroidMqttSettingsStore(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("Context is required");
        }
        Context app = context.getApplicationContext();
        this.applicationContext = app == null ? context : app;
    }

    @Override
    public MqttSettings load() throws Exception {
        requireSupportedApi();
        return delegate().load();
    }

    @Override
    public void save(MqttSettings settings) throws Exception {
        requireSupportedApi();
        delegate().save(settings);
    }

    private EncryptedMqttSettingsStore delegate() {
        return new EncryptedMqttSettingsStore(new AndroidKeys(),
                new NoBackupAtomicStorage(applicationContext));
    }

    private static void requireSupportedApi() throws GeneralSecurityException {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            throw new GeneralSecurityException("Secure MQTT settings require Android 6 or newer");
        }
    }

    @TargetApi(Build.VERSION_CODES.M)
    private static final class AndroidKeys implements EncryptedMqttSettingsStore.Keys {
        @Override
        public SecretKey keyForRead() throws Exception {
            requireSupportedApi();
            synchronized (KEY_LOCK) {
                KeyStore store = loadedKeyStore();
                Key key = store.getKey(KEY_ALIAS, null);
                if (!(key instanceof SecretKey)) {
                    throw new GeneralSecurityException("MQTT settings key is unavailable");
                }
                return (SecretKey) key;
            }
        }

        @Override
        public SecretKey keyForWrite() throws Exception {
            requireSupportedApi();
            synchronized (KEY_LOCK) {
                KeyStore store = loadedKeyStore();
                Key existing = store.getKey(KEY_ALIAS, null);
                if (existing != null) {
                    if (!(existing instanceof SecretKey)) {
                        throw new GeneralSecurityException("MQTT settings key is invalid");
                    }
                    return (SecretKey) existing;
                }
                KeyGenerator generator = KeyGenerator.getInstance(
                        KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
                generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build());
                return generator.generateKey();
            }
        }

        private static KeyStore loadedKeyStore() throws Exception {
            KeyStore store = KeyStore.getInstance(KEYSTORE);
            store.load(null);
            return store;
        }
    }

    private static final class NoBackupAtomicStorage
            implements EncryptedMqttSettingsStore.Storage {
        private final AtomicFile file;

        private NoBackupAtomicStorage(Context context) {
            this.file = new AtomicFile(new File(context.getNoBackupFilesDir(), FILE_NAME));
        }

        @Override
        public byte[] read() throws Exception {
            synchronized (FILE_LOCK) {
                return readCurrentOrNull();
            }
        }

        @Override
        public void writeAtomically(byte[] replacement) throws Exception {
            if (replacement == null || replacement.length > MAX_ENCRYPTED_BYTES) {
                throw new IOException("Invalid encrypted MQTT settings size");
            }
            synchronized (FILE_LOCK) {
                byte[] previous = readCurrentOrNull();
                FileOutputStream output = null;
                boolean committed = false;
                try {
                    output = file.startWrite();
                    output.write(replacement);
                    output.flush();
                    output.getFD().sync();
                    file.finishWrite(output);
                    output = null;
                    committed = true;
                    byte[] readback = readCurrentOrNull();
                    try {
                        if (readback == null || !MessageDigest.isEqual(replacement, readback)) {
                            throw new IOException("MQTT settings readback verification failed");
                        }
                    } finally {
                        if (readback != null) {
                            Arrays.fill(readback, (byte) 0);
                        }
                    }
                } catch (Exception failure) {
                    if (output != null) {
                        file.failWrite(output);
                    } else if (committed) {
                        try {
                            restore(previous);
                        } catch (Exception restoreFailure) {
                            failure.addSuppressed(restoreFailure);
                        }
                    }
                    throw failure;
                } finally {
                    if (previous != null) {
                        Arrays.fill(previous, (byte) 0);
                    }
                }
            }
        }

        private byte[] readCurrentOrNull() throws IOException {
            try {
                return readBounded(file.openRead());
            } catch (FileNotFoundException absentOrBroken) {
                File base = file.getBaseFile();
                if (!base.exists()
                        && !new File(base.getPath() + ".bak").exists()
                        && !new File(base.getPath() + ".new").exists()) {
                    return null;
                }
                throw absentOrBroken;
            }
        }

        private void restore(byte[] previous) throws IOException {
            if (previous == null) {
                file.delete();
                return;
            }
            FileOutputStream restore = null;
            try {
                restore = file.startWrite();
                restore.write(previous);
                restore.flush();
                restore.getFD().sync();
                file.finishWrite(restore);
                restore = null;
            } finally {
                if (restore != null) {
                    file.failWrite(restore);
                }
            }
        }

        private static byte[] readBounded(FileInputStream input) throws IOException {
            byte[] buffer = new byte[MAX_ENCRYPTED_BYTES + 1];
            int count = 0;
            try {
                while (count < buffer.length) {
                    int read = input.read(buffer, count, buffer.length - count);
                    if (read < 0) {
                        return Arrays.copyOf(buffer, count);
                    }
                    if (read == 0) {
                        int single = input.read();
                        if (single < 0) {
                            return Arrays.copyOf(buffer, count);
                        }
                        buffer[count++] = (byte) single;
                    } else {
                        count += read;
                    }
                }
                if (input.read() >= 0 || count > MAX_ENCRYPTED_BYTES) {
                    throw new IOException("Encrypted MQTT settings are too large");
                }
                return Arrays.copyOf(buffer, count);
            } finally {
                Arrays.fill(buffer, (byte) 0);
                input.close();
            }
        }
    }
}
