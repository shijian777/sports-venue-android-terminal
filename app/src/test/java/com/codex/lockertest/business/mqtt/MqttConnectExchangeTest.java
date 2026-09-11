package com.codex.lockertest.business.mqtt;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import com.codex.lockertest.server.CallToken;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.Test;

public final class MqttConnectExchangeTest {
    private static final String TEMPORARY_ID = "qgtest0123456789abcdef";

    @Test
    public void writesLiteralMqtt311ConnectAndDisconnectWithoutApplicationPackets() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        MqttProbe.Outcome outcome = new MqttConnectExchange().connect(
                new ByteArrayInputStream(bytes(0x20, 0x02, 0x00, 0x00)), output,
                TEMPORARY_ID, "", "", new CallToken());

        assertEquals(MqttProbe.Outcome.CONNECTED, outcome);
        assertArrayEquals(bytes(
                0x10, 0x22,
                0x00, 0x04, 'M', 'Q', 'T', 'T',
                0x04, 0x02, 0x00, 0x00,
                0x00, 0x16,
                'q', 'g', 't', 'e', 's', 't', '0', '1', '2', '3', '4', '5',
                '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f',
                0xe0, 0x00), output.toByteArray());
    }

    @Test
    public void setsUsernameAndPasswordFlagsAndWritesBothUtf8Fields() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        MqttProbe.Outcome outcome = new MqttConnectExchange().connect(
                new ByteArrayInputStream(bytes(0x20, 0x02, 0x00, 0x00)), output,
                "qgtesta", "u", "pw", new CallToken());

        assertEquals(MqttProbe.Outcome.CONNECTED, outcome);
        assertArrayEquals(bytes(
                0x10, 0x1a,
                0x00, 0x04, 'M', 'Q', 'T', 'T',
                0x04, 0xc2, 0x00, 0x00,
                0x00, 0x07, 'q', 'g', 't', 'e', 's', 't', 'a',
                0x00, 0x01, 'u',
                0x00, 0x02, 'p', 'w',
                0xe0, 0x00), output.toByteArray());
    }

    @Test
    public void acceptsConnackFragmentedAcrossSingleByteReads() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        MqttProbe.Outcome outcome = new MqttConnectExchange().connect(
                new FragmentedInputStream(bytes(0x20, 0x02, 0x00, 0x00)), output,
                TEMPORARY_ID, "", "", new CallToken());

        assertEquals(MqttProbe.Outcome.CONNECTED, outcome);
        assertArrayEquals(bytes(0xe0, 0x00),
                Arrays.copyOfRange(output.toByteArray(), output.size() - 2, output.size()));
    }

    @Test
    public void mapsBrokerConnackReturnCodesWithoutReturningBrokerText() throws Exception {
        assertConnack(MqttProbe.Outcome.UNSUPPORTED, 0x01);
        assertConnack(MqttProbe.Outcome.AUTH_REJECTED, 0x02);
        assertConnack(MqttProbe.Outcome.SERVER_UNAVAILABLE, 0x03);
        assertConnack(MqttProbe.Outcome.AUTH_REJECTED, 0x04);
        assertConnack(MqttProbe.Outcome.AUTH_REJECTED, 0x05);
    }

    @Test
    public void rejectsMalformedAndTruncatedFirstPacket() throws Exception {
        byte[][] invalidPackets = {
                bytes(),
                bytes(0x20),
                bytes(0x20, 0x02),
                bytes(0x20, 0x02, 0x00),
                bytes(0x21, 0x02, 0x00, 0x00),
                bytes(0x20, 0x03, 0x00, 0x00),
                bytes(0x20, 0x02, 0x02, 0x00),
                bytes(0x20, 0x02, 0x00, 0x06)
        };

        for (byte[] packet : invalidPackets) {
            assertEquals(MqttProbe.Outcome.INVALID_RESPONSE, connect(packet, new CallToken()));
        }
    }

    @Test
    public void rejectsSessionPresentForCleanSession() throws Exception {
        assertEquals(MqttProbe.Outcome.INVALID_RESPONSE,
                connect(bytes(0x20, 0x02, 0x01, 0x00), new CallToken()));
    }

    @Test
    public void reportsCancellationBeforeWritingCredentials() throws Exception {
        CallToken cancelled = new CallToken();
        cancelled.cancel(CallToken.Reason.CANCELLED);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        MqttProbe.Outcome outcome = new MqttConnectExchange().connect(
                new ByteArrayInputStream(bytes(0x20, 0x02, 0x00, 0x00)), output,
                TEMPORARY_ID, "private-user", "private-password", cancelled);

        assertEquals(MqttProbe.Outcome.CANCELLED, outcome);
        assertEquals(0, output.size());
    }

    @Test
    public void observesCancellationBetweenFragmentedResponseBytes() throws Exception {
        CallToken token = new CallToken();
        InputStream input = new CancellingInputStream(
                bytes(0x20, 0x02, 0x00, 0x00), token, 2, CallToken.Reason.TIMEOUT);

        MqttProbe.Outcome outcome = new MqttConnectExchange().connect(
                input, new ByteArrayOutputStream(), TEMPORARY_ID,
                "private-user", "private-password", token);

        assertEquals(MqttProbe.Outcome.TIMEOUT, outcome);
    }

    @Test
    public void malformedResponseCannotEchoCredentials() throws Exception {
        MqttProbe.Outcome outcome = new MqttConnectExchange().connect(
                new ByteArrayInputStream("private-password".getBytes(StandardCharsets.UTF_8)),
                new ByteArrayOutputStream(), TEMPORARY_ID,
                "private-user", "private-password", new CallToken());

        assertEquals(MqttProbe.Outcome.INVALID_RESPONSE, outcome);
        org.junit.Assert.assertFalse(outcome.name().contains("private"));
    }

    private static void assertConnack(MqttProbe.Outcome expected, int returnCode) throws Exception {
        assertEquals(expected, connect(bytes(0x20, 0x02, 0x00, returnCode), new CallToken()));
    }

    private static MqttProbe.Outcome connect(byte[] response, CallToken token) throws Exception {
        return new MqttConnectExchange().connect(
                new ByteArrayInputStream(response), new ByteArrayOutputStream(),
                TEMPORARY_ID, "", "", token);
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int index = 0; index < values.length; index++) result[index] = (byte) values[index];
        return result;
    }

    private static final class FragmentedInputStream extends InputStream {
        private final byte[] bytes;
        private int index;

        private FragmentedInputStream(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override public int read() {
            return index == bytes.length ? -1 : bytes[index++] & 0xff;
        }

        @Override public int read(byte[] destination, int offset, int length) {
            if (index == bytes.length) return -1;
            destination[offset] = bytes[index++];
            return 1;
        }
    }

    private static final class CancellingInputStream extends InputStream {
        private final byte[] bytes;
        private final CallToken token;
        private final int cancelAfterReads;
        private final CallToken.Reason reason;
        private int index;

        private CancellingInputStream(byte[] bytes, CallToken token, int cancelAfterReads,
                CallToken.Reason reason) {
            this.bytes = bytes;
            this.token = token;
            this.cancelAfterReads = cancelAfterReads;
            this.reason = reason;
        }

        @Override public int read() throws IOException {
            if (index == bytes.length) return -1;
            int value = bytes[index++] & 0xff;
            if (index == cancelAfterReads) token.cancel(reason);
            return value;
        }
    }
}
