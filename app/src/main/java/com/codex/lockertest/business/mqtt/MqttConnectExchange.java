package com.codex.lockertest.business.mqtt;

import com.codex.lockertest.server.CallToken;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** One MQTT 3.1.1 CONNECT/CONNACK/DISCONNECT exchange over caller-owned streams. */
public final class MqttConnectExchange {
    private static final byte[] DISCONNECT = {(byte) 0xe0, 0x00};

    public MqttProbe.Outcome connect(InputStream input, OutputStream output,
            String temporaryClientId, String username, String password, CallToken token)
            throws IOException {
        if (input == null || output == null || token == null) {
            return MqttProbe.Outcome.INVALID_RESPONSE;
        }
        MqttProbe.Outcome cancelled = cancellationOutcome(token);
        if (cancelled != null) return cancelled;

        byte[] clientIdBytes = null;
        byte[] usernameBytes = null;
        byte[] passwordBytes = null;
        byte[] body = null;
        byte[] packet = null;
        try {
            if (!isTemporaryClientId(temporaryClientId)
                    || username == null || password == null
                    || (username.isEmpty() && !password.isEmpty())) {
                return MqttProbe.Outcome.INVALID_RESPONSE;
            }
            clientIdBytes = temporaryClientId.getBytes(StandardCharsets.UTF_8);
            usernameBytes = username.getBytes(StandardCharsets.UTF_8);
            passwordBytes = password.getBytes(StandardCharsets.UTF_8);
            if (clientIdBytes.length > 23 || usernameBytes.length > 65535
                    || passwordBytes.length > 65535) {
                return MqttProbe.Outcome.INVALID_RESPONSE;
            }

            int bodyLength = 10 + 2 + clientIdBytes.length;
            if (!username.isEmpty()) bodyLength += 2 + usernameBytes.length;
            if (!password.isEmpty()) bodyLength += 2 + passwordBytes.length;
            body = new byte[bodyLength];
            int offset = 0;
            body[offset++] = 0x00;
            body[offset++] = 0x04;
            body[offset++] = 'M';
            body[offset++] = 'Q';
            body[offset++] = 'T';
            body[offset++] = 'T';
            body[offset++] = 0x04;
            int flags = 0x02;
            if (!username.isEmpty()) flags |= 0x80;
            if (!password.isEmpty()) flags |= 0x40;
            body[offset++] = (byte) flags;
            body[offset++] = 0x00;
            body[offset++] = 0x00;
            offset = writeUtf8(body, offset, clientIdBytes);
            if (!username.isEmpty()) offset = writeUtf8(body, offset, usernameBytes);
            if (!password.isEmpty()) writeUtf8(body, offset, passwordBytes);

            byte[] encodedLength = encodeRemainingLength(body.length);
            packet = new byte[1 + encodedLength.length + body.length];
            packet[0] = 0x10;
            System.arraycopy(encodedLength, 0, packet, 1, encodedLength.length);
            System.arraycopy(body, 0, packet, 1 + encodedLength.length, body.length);
            Arrays.fill(encodedLength, (byte) 0);

            cancelled = cancellationOutcome(token);
            if (cancelled != null) return cancelled;
            output.write(packet);
            output.flush();
            cancelled = cancellationOutcome(token);
            if (cancelled != null) return cancelled;

            byte[] connack = new byte[4];
            for (int index = 0; index < connack.length; index++) {
                cancelled = cancellationOutcome(token);
                if (cancelled != null) return cancelled;
                int next = input.read();
                if (next < 0) return MqttProbe.Outcome.INVALID_RESPONSE;
                connack[index] = (byte) next;
                cancelled = cancellationOutcome(token);
                if (cancelled != null) return cancelled;
            }
            MqttProbe.Outcome outcome = parseConnack(connack);
            Arrays.fill(connack, (byte) 0);
            if (outcome != MqttProbe.Outcome.CONNECTED) return outcome;

            output.write(DISCONNECT);
            output.flush();
            cancelled = cancellationOutcome(token);
            return cancelled == null ? MqttProbe.Outcome.CONNECTED : cancelled;
        } finally {
            wipe(clientIdBytes);
            wipe(usernameBytes);
            wipe(passwordBytes);
            wipe(body);
            wipe(packet);
        }
    }

    private static MqttProbe.Outcome parseConnack(byte[] packet) {
        if ((packet[0] & 0xff) != 0x20 || (packet[1] & 0xff) != 0x02
                || (packet[2] & 0xff) != 0x00) {
            return MqttProbe.Outcome.INVALID_RESPONSE;
        }
        switch (packet[3] & 0xff) {
            case 0: return MqttProbe.Outcome.CONNECTED;
            case 1: return MqttProbe.Outcome.UNSUPPORTED;
            case 2:
            case 4:
            case 5: return MqttProbe.Outcome.AUTH_REJECTED;
            case 3: return MqttProbe.Outcome.SERVER_UNAVAILABLE;
            default: return MqttProbe.Outcome.INVALID_RESPONSE;
        }
    }

    private static int writeUtf8(byte[] destination, int offset, byte[] value) {
        destination[offset++] = (byte) (value.length >>> 8);
        destination[offset++] = (byte) value.length;
        System.arraycopy(value, 0, destination, offset, value.length);
        return offset + value.length;
    }

    private static byte[] encodeRemainingLength(int length) {
        byte[] encoded = new byte[4];
        int count = 0;
        do {
            int digit = length % 128;
            length /= 128;
            if (length > 0) digit |= 0x80;
            encoded[count++] = (byte) digit;
        } while (length > 0);
        return Arrays.copyOf(encoded, count);
    }

    private static boolean isTemporaryClientId(String value) {
        if (value == null || value.length() <= 6 || value.length() > 23
                || !value.startsWith("qgtest")) {
            return false;
        }
        for (int index = 6; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!((character >= '0' && character <= '9')
                    || (character >= 'a' && character <= 'f'))) return false;
        }
        return true;
    }

    private static MqttProbe.Outcome cancellationOutcome(CallToken token) {
        if (!token.isCancelled()) return null;
        return token.reason() == CallToken.Reason.TIMEOUT
                ? MqttProbe.Outcome.TIMEOUT : MqttProbe.Outcome.CANCELLED;
    }

    private static void wipe(byte[] bytes) {
        if (bytes != null) Arrays.fill(bytes, (byte) 0);
    }
}
