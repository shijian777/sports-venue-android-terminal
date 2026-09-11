package com.codex.lockertest.business.mqtt;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** AES-GCM persistence independent of its key and atomic-storage adapters. */
public final class EncryptedMqttSettingsStore implements MqttSettingsStore {
    public interface Keys {
        SecretKey keyForRead() throws Exception;

        SecretKey keyForWrite() throws Exception;
    }

    public interface Storage {
        byte[] read() throws Exception;

        void writeAtomically(byte[] replacement) throws Exception;
    }

    private static final int MAGIC = 0x4d514553;
    private static final byte VERSION = 1;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int HEADER_BYTES = 10;
    private static final int MAX_CIPHERTEXT_BYTES = 8208;
    private static final int MAX_ENCRYPTED_BYTES = HEADER_BYTES + IV_BYTES + MAX_CIPHERTEXT_BYTES;
    private static final byte[] AUTHENTICATED_FORMAT = {0x4d, 0x51, 0x45, 0x53, VERSION, IV_BYTES};

    private final Keys keys;
    private final Storage storage;
    private final MqttSettingsCodec codec;

    public EncryptedMqttSettingsStore(Keys keys, Storage storage) {
        if (keys == null || storage == null) {
            throw new IllegalArgumentException("MQTT settings dependencies are required");
        }
        this.keys = keys;
        this.storage = storage;
        this.codec = new MqttSettingsCodec();
    }

    @Override
    public MqttSettings load() throws Exception {
        byte[] envelope = storage.read();
        if (envelope == null) {
            return null;
        }
        ParsedEnvelope parsed = parseEnvelope(envelope);
        SecretKey key = keys.keyForRead();
        if (key == null) {
            throw new GeneralSecurityException("MQTT settings key is unavailable");
        }
        byte[] serialized = null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, parsed.iv));
            cipher.updateAAD(AUTHENTICATED_FORMAT);
            serialized = cipher.doFinal(parsed.ciphertext);
            return codec.decode(serialized);
        } finally {
            Arrays.fill(parsed.iv, (byte) 0);
            Arrays.fill(parsed.ciphertext, (byte) 0);
            if (serialized != null) {
                Arrays.fill(serialized, (byte) 0);
            }
        }
    }

    @Override
    public void save(MqttSettings settings) throws Exception {
        byte[] serialized = codec.encode(settings);
        byte[] iv = null;
        byte[] ciphertext = null;
        try {
            SecretKey key = keys.keyForWrite();
            if (key == null) {
                throw new GeneralSecurityException("MQTT settings key is unavailable");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            iv = cipher.getIV();
            if (iv == null || iv.length != IV_BYTES) {
                throw new GeneralSecurityException("MQTT settings cipher returned an invalid IV");
            }
            cipher.updateAAD(AUTHENTICATED_FORMAT);
            ciphertext = cipher.doFinal(serialized);
            if (ciphertext.length > MAX_CIPHERTEXT_BYTES) {
                throw malformed();
            }
            ByteBuffer output = ByteBuffer.allocate(HEADER_BYTES + iv.length + ciphertext.length);
            output.putInt(MAGIC);
            output.put(VERSION);
            output.put((byte) iv.length);
            output.putInt(ciphertext.length);
            output.put(iv);
            output.put(ciphertext);
            storage.writeAtomically(output.array());
        } finally {
            Arrays.fill(serialized, (byte) 0);
            if (iv != null) {
                Arrays.fill(iv, (byte) 0);
            }
            if (ciphertext != null) {
                Arrays.fill(ciphertext, (byte) 0);
            }
        }
    }

    private static ParsedEnvelope parseEnvelope(byte[] envelope) throws IOException {
        if (envelope.length < HEADER_BYTES || envelope.length > MAX_ENCRYPTED_BYTES) {
            throw malformed();
        }
        ByteBuffer input = ByteBuffer.wrap(envelope);
        int magic = input.getInt();
        int version = input.get() & 0xff;
        int ivLength = input.get() & 0xff;
        int ciphertextLength = input.getInt();
        if (magic != MAGIC || version != VERSION || ivLength != IV_BYTES
                || ciphertextLength < 16 || ciphertextLength > MAX_CIPHERTEXT_BYTES
                || input.remaining() != ivLength + ciphertextLength) {
            throw malformed();
        }
        byte[] iv = new byte[ivLength];
        byte[] ciphertext = new byte[ciphertextLength];
        input.get(iv);
        input.get(ciphertext);
        return new ParsedEnvelope(iv, ciphertext);
    }

    private static IOException malformed() {
        return new IOException("Invalid encrypted MQTT settings");
    }

    private static final class ParsedEnvelope {
        private final byte[] iv;
        private final byte[] ciphertext;

        private ParsedEnvelope(byte[] iv, byte[] ciphertext) {
            this.iv = iv;
            this.ciphertext = ciphertext;
        }
    }
}
