package com.codex.lockertest.ui.business;

import android.annotation.TargetApi;
import android.os.Build;
import com.codex.lockertest.business.mqtt.MqttConnectExchange;
import com.codex.lockertest.business.mqtt.MqttProbe;
import com.codex.lockertest.business.mqtt.MqttSettings;
import com.codex.lockertest.server.CallToken;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.SecureRandom;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** On-demand TLS-only MQTT 3.1.1 connection diagnostic for the administrator UI. */
public final class AndroidMqttProbe implements MqttProbe {
    private static final int IO_TIMEOUT_MILLIS = 5_000;

    @Override public Outcome test(MqttSettings settings, CallToken token) {
        if (Build.VERSION.SDK_INT < 24) return Outcome.UNSUPPORTED;
        if (settings == null || token == null) return Outcome.INVALID_RESPONSE;
        Outcome cancelled = cancellationOutcome(token);
        if (cancelled != null) return cancelled;

        SocketCloser closer = new SocketCloser();
        CallToken.Registration cancellation = token.onCancel(closer::close);
        try {
            cancelled = cancellationOutcome(token);
            if (cancelled != null) return cancelled;
            InetAddress[] addresses = InetAddress.getAllByName(settings.host());
            cancelled = cancellationOutcome(token);
            if (cancelled != null) return cancelled;
            if (addresses.length == 0) return Outcome.NETWORK_FAILED;

            Socket transport = new Socket();
            closer.attach(transport);
            cancelled = cancellationOutcome(token);
            if (cancelled != null) return cancelled;
            transport.connect(new InetSocketAddress(addresses[0], settings.port()),
                    IO_TIMEOUT_MILLIS);
            cancelled = cancellationOutcome(token);
            if (cancelled != null) return cancelled;
            transport.setSoTimeout(IO_TIMEOUT_MILLIS);

            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            SSLSocket tlsSocket = (SSLSocket) factory.createSocket(
                    transport, settings.host(), settings.port(), true);
            closer.attach(tlsSocket);
            Api24Tls.configure(tlsSocket);
            tlsSocket.setSoTimeout(IO_TIMEOUT_MILLIS);
            cancelled = cancellationOutcome(token);
            if (cancelled != null) return cancelled;
            tlsSocket.startHandshake();
            cancelled = cancellationOutcome(token);
            if (cancelled != null) return cancelled;

            String temporaryClientId = temporaryClientId();
            Outcome outcome = new MqttConnectExchange().connect(
                    tlsSocket.getInputStream(), tlsSocket.getOutputStream(), temporaryClientId,
                    settings.username(), settings.password(), token);
            cancelled = cancellationOutcome(token);
            return cancelled == null ? outcome : cancelled;
        } catch (SocketTimeoutException timeout) {
            cancelled = cancellationOutcome(token);
            return cancelled == null ? Outcome.TIMEOUT : cancelled;
        } catch (SSLException tlsFailure) {
            cancelled = cancellationOutcome(token);
            return cancelled == null ? Outcome.TLS_FAILED : cancelled;
        } catch (UnknownHostException unavailable) {
            cancelled = cancellationOutcome(token);
            return cancelled == null ? Outcome.NETWORK_FAILED : cancelled;
        } catch (IOException networkFailure) {
            cancelled = cancellationOutcome(token);
            return cancelled == null ? Outcome.NETWORK_FAILED : cancelled;
        } catch (RuntimeException platformFailure) {
            cancelled = cancellationOutcome(token);
            return cancelled == null ? Outcome.NETWORK_FAILED : cancelled;
        } finally {
            cancellation.unregister();
            closer.close();
        }
    }

    private static String temporaryClientId() {
        byte[] random = new byte[8];
        new SecureRandom().nextBytes(random);
        StringBuilder id = new StringBuilder(22).append("qgtest");
        for (byte value : random) {
            int unsigned = value & 0xff;
            id.append(Character.forDigit(unsigned >>> 4, 16));
            id.append(Character.forDigit(unsigned & 0x0f, 16));
        }
        java.util.Arrays.fill(random, (byte) 0);
        return id.toString();
    }

    private static Outcome cancellationOutcome(CallToken token) {
        if (!token.isCancelled()) return null;
        return token.reason() == CallToken.Reason.TIMEOUT
                ? Outcome.TIMEOUT : Outcome.CANCELLED;
    }

    /** Kept behind the API guard so API 21-23 never resolve the API 24 SSLParameters method. */
    @TargetApi(24)
    private static final class Api24Tls {
        private static void configure(SSLSocket socket) {
            socket.setEnabledProtocols(new String[] {"TLSv1.2"});
            SSLParameters parameters = socket.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(parameters);
        }
    }

    /** Cancellation may race socket layering; any socket attached after cancellation closes at once. */
    private static final class SocketCloser {
        private Socket socket;
        private boolean closed;

        synchronized void attach(Socket replacement) throws IOException {
            if (closed) {
                replacement.close();
                throw new IOException("MQTT probe cancelled");
            }
            socket = replacement;
        }

        synchronized void close() {
            closed = true;
            if (socket == null) return;
            try {
                socket.close();
            } catch (IOException ignored) {
                // The outcome category never exposes exception or credential text.
            }
        }
    }
}
