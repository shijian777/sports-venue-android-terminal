package com.codex.lockertest.business.mqtt;

import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

public final class MqttSettingsCodecTest {
    private final MqttSettingsCodec codec = new MqttSettingsCodec();

    @Test
    public void encodeWipesFixedScratchBufferAfterSuccess() throws Exception {
        CapturingScratchFactory scratchFactory = new CapturingScratchFactory(8192);
        MqttSettingsCodec observedCodec = new MqttSettingsCodec(scratchFactory);

        byte[] encoded = observedCodec.encode(valid());

        assertFalse(encoded.length == 0);
        assertAllZero(scratchFactory.created.buffer);
    }

    @Test
    public void encodeWipesFixedScratchBufferAfterFailure() throws Exception {
        CapturingScratchFactory scratchFactory = new CapturingScratchFactory(32);
        MqttSettingsCodec observedCodec = new MqttSettingsCodec(scratchFactory);

        try {
            observedCodec.encode(valid());
            fail("expected deliberately undersized scratch buffer to fail");
        } catch (IOException expected) {
            assertAllZero(scratchFactory.created.buffer);
        }
    }

    @Test
    public void versionedRoundTripPreservesOpaqueSettingsExactly() throws Exception {
        MqttSettings input = new MqttSettings("broker.example", 8883, "007-client",
                " user 007 ", " password 007 ", " terminal/007 ");

        MqttSettings output = codec.decode(codec.encode(input));

        assertEquals(input.host(), output.host());
        assertEquals(input.port(), output.port());
        assertEquals(input.clientId(), output.clientId());
        assertEquals(input.username(), output.username());
        assertEquals(input.password(), output.password());
        assertEquals(input.topic(), output.topic());
    }

    @Test
    public void rejectsEveryTruncationAndTrailingData() throws Exception {
        byte[] complete = codec.encode(valid());
        for (int length = 0; length < complete.length; length++) {
            reject(Arrays.copyOf(complete, length));
        }
        byte[] trailing = Arrays.copyOf(complete, complete.length + 1);
        trailing[trailing.length - 1] = 42;
        reject(trailing);
    }

    @Test
    public void rejectsUnknownVersionAndMalformedUtf8() throws Exception {
        byte[] unknownVersion = codec.encode(valid());
        unknownVersion[4] = 2;
        reject(unknownVersion);

        byte[] malformedUtf8 = codec.encode(valid());
        malformedUtf8[9] = (byte) 0xc3;
        malformedUtf8[10] = 0x28;
        reject(malformedUtf8);
    }

    @Test
    public void rejectsOversizedInputBeforeParsingLengths() {
        reject(new byte[8193]);
    }

    @Test
    public void decodeFailureDoesNotPutCredentialsInTheException() throws Exception {
        byte[] bytes = codec.encode(new MqttSettings("broker.example", 8883, "id",
                "user", "not-for-errors", "topic"));
        bytes[bytes.length - 1] = '#';
        try {
            codec.decode(bytes);
            fail("expected invalid settings");
        } catch (IOException expected) {
            assertFalse(String.valueOf(expected.getMessage()).contains("not-for-errors"));
        }
    }

    private void reject(byte[] bytes) {
        try {
            codec.decode(bytes);
            fail("expected malformed codec input to be rejected");
        } catch (IOException expected) {
            // Expected: persisted bytes are untrusted.
        }
    }

    private static MqttSettings valid() {
        return new MqttSettings("broker.example", 8883, "terminal-1",
                "user", "password", "locker/terminal-1");
    }

    private static void assertAllZero(byte[] bytes) {
        for (byte value : bytes) {
            assertEquals(0, value);
        }
    }

    private static final class CapturingScratchFactory
            implements MqttSettingsCodec.ScratchFactory {
        private final int actualCapacity;
        private MqttSettingsCodec.ScratchBuffer created;

        private CapturingScratchFactory(int actualCapacity) {
            this.actualCapacity = actualCapacity;
        }

        @Override
        public MqttSettingsCodec.ScratchBuffer create(int requestedCapacity) {
            created = new MqttSettingsCodec.ScratchBuffer(actualCapacity);
            return created;
        }
    }
}
