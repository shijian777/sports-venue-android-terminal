package com.codex.lockertest.business.mqtt;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Strict, bounded wire codec for the encrypted settings payload. */
public final class MqttSettingsCodec {
    private static final int MAGIC = 0x4d515454;
    private static final int VERSION = 1;
    private static final int MAX_SERIALIZED_BYTES = 8192;
    private static final ScratchFactory DEFAULT_SCRATCH_FACTORY = new ScratchFactory() {
        @Override
        public ScratchBuffer create(int capacity) {
            return new ScratchBuffer(capacity);
        }
    };

    private final ScratchFactory scratchFactory;

    public MqttSettingsCodec() {
        this(DEFAULT_SCRATCH_FACTORY);
    }

    MqttSettingsCodec(ScratchFactory scratchFactory) {
        if (scratchFactory == null) {
            throw new IllegalArgumentException("Scratch factory is required");
        }
        this.scratchFactory = scratchFactory;
    }

    public byte[] encode(MqttSettings settings) throws IOException {
        if (settings == null) {
            throw malformed();
        }
        ScratchBuffer bytes = scratchFactory.create(MAX_SERIALIZED_BYTES);
        if (bytes == null) {
            throw malformed();
        }
        try {
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeInt(MAGIC);
            output.writeByte(VERSION);
            writeString(output, settings.host());
            output.writeInt(settings.port());
            writeString(output, settings.clientId());
            writeString(output, settings.username());
            writeString(output, settings.password());
            writeString(output, settings.topic());
            output.flush();
            return bytes.copy();
        } finally {
            bytes.wipe();
        }
    }

    public MqttSettings decode(byte[] encoded) throws IOException {
        if (encoded == null || encoded.length > MAX_SERIALIZED_BYTES) {
            throw malformed();
        }
        try {
            ByteArrayInputStream bytes = new ByteArrayInputStream(encoded);
            DataInputStream input = new DataInputStream(bytes);
            if (input.readInt() != MAGIC || input.readUnsignedByte() != VERSION) {
                throw malformed();
            }
            String host = readString(input, 253);
            int port = input.readInt();
            String clientId = readString(input, 128);
            String username = readString(input, 256);
            String password = readString(input, 4096);
            String topic = readString(input, 512);
            if (bytes.available() != 0) {
                throw malformed();
            }
            return new MqttSettings(host, port, clientId, username, password, topic);
        } catch (EOFException | CharacterCodingException | IllegalArgumentException failure) {
            throw malformed(failure);
        }
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        try {
            output.writeInt(utf8.length);
            output.write(utf8);
        } finally {
            Arrays.fill(utf8, (byte) 0);
        }
    }

    private static String readString(DataInputStream input, int maximumBytes) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > maximumBytes) {
            throw malformed();
        }
        byte[] utf8 = new byte[length];
        CharBuffer decoded = null;
        try {
            input.readFully(utf8);
            decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(utf8));
            return decoded.toString();
        } finally {
            if (decoded != null && !decoded.isReadOnly()) {
                for (int i = 0; i < decoded.limit(); i++) {
                    decoded.put(i, '\0');
                }
            }
            Arrays.fill(utf8, (byte) 0);
        }
    }

    interface ScratchFactory {
        ScratchBuffer create(int capacity);
    }

    static final class ScratchBuffer extends OutputStream {
        final byte[] buffer;
        private int count;

        ScratchBuffer(int capacity) {
            if (capacity < 0) {
                throw new IllegalArgumentException("Scratch capacity must not be negative");
            }
            this.buffer = new byte[capacity];
        }

        @Override
        public void write(int value) throws IOException {
            requireAvailable(1);
            buffer[count++] = (byte) value;
        }

        @Override
        public void write(byte[] source, int offset, int length) throws IOException {
            if (source == null) {
                throw new NullPointerException("source");
            }
            if (offset < 0 || length < 0 || offset > source.length - length) {
                throw new IndexOutOfBoundsException();
            }
            requireAvailable(length);
            System.arraycopy(source, offset, buffer, count, length);
            count += length;
        }

        byte[] copy() {
            return Arrays.copyOf(buffer, count);
        }

        void wipe() {
            Arrays.fill(buffer, (byte) 0);
            count = 0;
        }

        private void requireAvailable(int length) throws IOException {
            if (length > buffer.length - count) {
                throw malformed();
            }
        }
    }

    private static IOException malformed() {
        return new IOException("Invalid persisted MQTT settings");
    }

    private static IOException malformed(Exception cause) {
        return new IOException("Invalid persisted MQTT settings", cause);
    }
}
