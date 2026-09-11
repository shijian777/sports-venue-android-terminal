package com.codex.lockertest.server;

import com.codex.lockertest.business.BusinessEndpoint;
import com.codex.lockertest.business.BusinessResponseParsers;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLException;

/** Fixed-host HTTPS transport using only Android's system TLS policy. */
public final class HttpsUrlConnectionTransport implements ServerTransport {
    interface ConnectionFactory {
        HttpsURLConnection open(URL url) throws IOException;
    }

    private static final String REQUEST_CONTENT_TYPE = "application/json; charset=UTF-8";
    private static final String ACCEPT = "application/json";
    private final ConnectionFactory connectionFactory;

    public HttpsUrlConnectionTransport() {
        this(new SystemConnectionFactory());
    }

    HttpsUrlConnectionTransport(ConnectionFactory connectionFactory) {
        if (connectionFactory == null) {
            throw new IllegalArgumentException("Connection factory is required");
        }
        this.connectionFactory = connectionFactory;
    }

    @Override
    public ApiResult<TransportResponse> execute(TransportRequest request) {
        if (request == null) return failure(ServerFailure.Kind.CONFIGURATION);
        ApiResult<TransportResponse> cancellation = cancellationResult(request.token());
        if (cancellation != null) return cancellation;

        URL url;
        try {
            url = endpointUrl(request.endpointPath());
        } catch (MalformedURLException | SecurityException error) {
            return failureUnlessCancelled(request.token(), ServerFailure.Kind.CONFIGURATION);
        }
        cancellation = cancellationResult(request.token());
        if (cancellation != null) return cancellation;

        HttpsURLConnection connection;
        try {
            connection = connectionFactory.open(url);
        } catch (SocketTimeoutException error) {
            return failureUnlessCancelled(request.token(), ServerFailure.Kind.TIMEOUT);
        } catch (SSLException error) {
            return failureUnlessCancelled(request.token(), ServerFailure.Kind.TLS);
        } catch (IOException error) {
            return failureUnlessCancelled(request.token(), ServerFailure.Kind.NETWORK);
        } catch (SecurityException | IllegalArgumentException error) {
            return failureUnlessCancelled(request.token(), ServerFailure.Kind.CONFIGURATION);
        }
        if (connection == null) {
            return failureUnlessCancelled(request.token(), ServerFailure.Kind.CONFIGURATION);
        }

        DisconnectOnce disconnect = new DisconnectOnce(connection);
        CallToken.Registration registration = null;
        OutputStream output = null;
        InputStream input = null;
        byte[] requestBytes = null;
        ApiResult<TransportResponse> result = null;
        try {
            registration = request.token().onCancel(disconnect);
            cancellation = cancellationResult(request.token());
            if (cancellation != null) {
                result = cancellation;
            } else {
                requestBytes = request.body().getBytes(StandardCharsets.UTF_8);
                boolean configured = true;
                try {
                    configure(connection, request, requestBytes.length);
                } catch (ProtocolException | SecurityException | IllegalArgumentException error) {
                    result = failureUnlessCancelled(
                            request.token(), ServerFailure.Kind.CONFIGURATION);
                    configured = false;
                }
                if (configured) {
                    cancellation = cancellationResult(request.token());
                    if (cancellation != null) {
                        result = cancellation;
                    } else {
                        output = connection.getOutputStream();
                        output.write(requestBytes);
                        output.flush();

                        cancellation = cancellationResult(request.token());
                        if (cancellation != null) {
                            result = cancellation;
                        } else {
                            int statusCode = connection.getResponseCode();
                            result = responseStatusResult(statusCode, request.token());
                            boolean allocationHttpError = statusCode == 400
                                    && request.businessEndpoint() == BusinessEndpoint.USER_BOARD;
                            if (result == null || (allocationHttpError
                                    && result.failure().kind() == ServerFailure.Kind.HTTP)) {
                                String contentType = connection.getContentType();
                                boolean legacyHtml = validContentType(contentType, "text/html");
                                if ((!validContentType(contentType, "application/json")
                                        && !legacyHtml)
                                        || !validContentEncoding(connection.getContentEncoding())) {
                                    result = failureUnlessCancelled(
                                            request.token(), ServerFailure.Kind.CONTRACT);
                                } else {
                                    input = allocationHttpError ? connection.getErrorStream()
                                            : connection.getInputStream();
                                    BodyRead body = null;
                                    try {
                                        body = readBody(input);
                                        if (body.tooLarge) {
                                            result = failureUnlessCancelled(request.token(),
                                                    ServerFailure.Kind.RESPONSE_TOO_LARGE);
                                        } else {
                                            result = decodeResult(statusCode, body.bytes,
                                                    body.length, legacyHtml, request.token());
                                        }
                                    } finally {
                                        if (body != null) body.wipe();
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (SocketTimeoutException error) {
            result = failureUnlessCancelled(request.token(), ServerFailure.Kind.TIMEOUT);
        } catch (SSLException error) {
            result = failureUnlessCancelled(request.token(), ServerFailure.Kind.TLS);
        } catch (IOException error) {
            result = failureUnlessCancelled(request.token(), ServerFailure.Kind.NETWORK);
        } catch (SecurityException error) {
            result = failureUnlessCancelled(
                    request.token(), ServerFailure.Kind.CONFIGURATION);
        } finally {
            closeQuietly(input);
            closeQuietly(output);
            if (registration != null) registration.unregister();
            disconnect.run();
            if (requestBytes != null) Arrays.fill(requestBytes, (byte) 0);
        }

        cancellation = cancellationResult(request.token());
        if (cancellation != null) return cancellation;
        if (result == null) {
            throw new IllegalStateException("Transport completed without a result");
        }
        return result;
    }

    private static void configure(HttpsURLConnection connection,
            TransportRequest request, int contentLength) throws ProtocolException {
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(ProductionServerConfig.CONNECT_TIMEOUT_MILLIS);
        connection.setReadTimeout(ProductionServerConfig.READ_TIMEOUT_MILLIS);
        connection.setDoOutput(true);
        connection.setUseCaches(false);
        connection.setInstanceFollowRedirects(false);
        connection.setFixedLengthStreamingMode(contentLength);
        connection.setRequestProperty("Content-Type", REQUEST_CONTENT_TYPE);
        connection.setRequestProperty("Accept", ACCEPT);
        for (Map.Entry<String, String> header : request.headers().asMap().entrySet()) {
            connection.setRequestProperty(header.getKey(), header.getValue());
        }
    }

    private static ApiResult<TransportResponse> responseStatusResult(
            int statusCode, CallToken token) {
        ApiResult<TransportResponse> cancellation = cancellationResult(token);
        if (cancellation != null) return cancellation;
        if (statusCode == -1) return failure(ServerFailure.Kind.NETWORK);
        if (statusCode >= 300 && statusCode <= 399) {
            return failure(ServerFailure.Kind.REDIRECT);
        }
        if (statusCode < 200 || statusCode > 299) {
            return failure(ServerFailure.Kind.HTTP);
        }
        return null;
    }

    private static ApiResult<TransportResponse> decodeResult(
            int statusCode, byte[] bytes, int length, boolean legacyHtml, CallToken token) {
        ApiResult<TransportResponse> cancellation = cancellationResult(token);
        if (cancellation != null) return cancellation;
        try {
            String body = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, 0, length))
                    .toString();
            cancellation = cancellationResult(token);
            if (cancellation != null) return cancellation;
            // This fixed server mislabels JSON as HTML. Never admit an HTML page or
            // ambiguous JSON through that compatibility path; normal JSON is unchanged.
            if (legacyHtml && !(StrictJson.parse(body) instanceof Map)) {
                return failureUnlessCancelled(token, ServerFailure.Kind.CONTRACT);
            }
            if (statusCode == 400) {
                // Only USER_BOARD reaches this error-body decoder. HTTP failure can
                // carry a validated rejection reason, never a successful response.
                ApiResult<?> rejection = BusinessResponseParsers.userBoard(body);
                if (!rejection.isSuccess()
                        && rejection.failure().kind() == ServerFailure.Kind.REMOTE_REJECTED
                        && !rejection.failure().publicMessage().isEmpty()) {
                    return ApiResult.failure(rejection.failure());
                }
                return failureUnlessCancelled(token, ServerFailure.Kind.HTTP);
            }
            return ApiResult.success(new TransportResponse(statusCode, body));
        } catch (CharacterCodingException error) {
            return failureUnlessCancelled(token, ServerFailure.Kind.INVALID_UTF8);
        } catch (JsonContractException error) {
            return failureUnlessCancelled(token, ServerFailure.Kind.CONTRACT);
        }
    }

    private static BodyRead readBody(InputStream input) throws IOException {
        if (input == null) throw new IOException("Missing response stream");
        byte[] buffer = new byte[ProductionServerConfig.MAX_RESPONSE_BODY_BYTES + 1];
        int total = 0;
        boolean ownershipTransferred = false;
        try {
            while (total < buffer.length) {
                int count = input.read(buffer, total, buffer.length - total);
                if (count < 0) {
                    BodyRead body = BodyRead.complete(buffer, total);
                    ownershipTransferred = true;
                    return body;
                }
                if (count == 0) {
                    int value = input.read();
                    if (value < 0) {
                        BodyRead body = BodyRead.complete(buffer, total);
                        ownershipTransferred = true;
                        return body;
                    }
                    buffer[total++] = (byte) value;
                } else {
                    total += count;
                }
            }
            BodyRead body = BodyRead.tooLarge(buffer);
            ownershipTransferred = true;
            return body;
        } finally {
            if (!ownershipTransferred) Arrays.fill(buffer, (byte) 0);
        }
    }

    private static boolean validContentType(String value, String expectedMediaType) {
        if (!validAsciiHeaderValue(value)) return false;
        String trimmed = trimAsciiWhitespace(value);
        int semicolon = trimmed.indexOf(';');
        if (semicolon < 0) return expectedMediaType.equalsIgnoreCase(trimmed);
        if (trimmed.indexOf(';', semicolon + 1) >= 0) return false;
        String mediaType = trimAsciiWhitespace(trimmed.substring(0, semicolon));
        if (!expectedMediaType.equalsIgnoreCase(mediaType)) return false;
        String parameter = trimAsciiWhitespace(trimmed.substring(semicolon + 1));
        int equals = parameter.indexOf('=');
        if (equals < 0 || parameter.indexOf('=', equals + 1) >= 0) return false;
        String name = trimAsciiWhitespace(parameter.substring(0, equals));
        String charset = trimAsciiWhitespace(parameter.substring(equals + 1));
        return "charset".equalsIgnoreCase(name) && "utf-8".equalsIgnoreCase(charset);
    }

    private static boolean validContentEncoding(String value) {
        return value == null
                || validAsciiHeaderValue(value) && "identity".equalsIgnoreCase(value);
    }

    private static boolean validAsciiHeaderValue(String value) {
        if (value == null || value.length() == 0) return false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x20 || character > 0x7e) return false;
        }
        return true;
    }

    private static String trimAsciiWhitespace(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isAsciiWhitespace(value.charAt(start))) start++;
        while (end > start && isAsciiWhitespace(value.charAt(end - 1))) end--;
        return value.substring(start, end);
    }

    private static boolean isAsciiWhitespace(char character) {
        return character == ' ';
    }

    private static ApiResult<TransportResponse> failureUnlessCancelled(
            CallToken token, ServerFailure.Kind fallback) {
        ApiResult<TransportResponse> cancellation = cancellationResult(token);
        return cancellation != null ? cancellation : failure(fallback);
    }

    private static ApiResult<TransportResponse> cancellationResult(CallToken token) {
        CallToken.Reason reason = token.reason();
        if (reason == CallToken.Reason.NONE) return null;
        return failure(reason == CallToken.Reason.TIMEOUT
                ? ServerFailure.Kind.TIMEOUT : ServerFailure.Kind.CANCELLED);
    }

    private static <T> ApiResult<T> failure(ServerFailure.Kind kind) {
        return ApiResult.failure(ServerFailure.of(kind));
    }

    private static URL endpointUrl(String endpointPath) throws MalformedURLException {
        if (endpointPath == null
                || !endpointPath.startsWith("/v2/central_control_screen/")) {
            throw new MalformedURLException("Invalid fixed HTTPS endpoint");
        }
        URL url = new URL(ProductionServerConfig.BASE_URL + endpointPath);
        boolean valid = "https".equals(url.getProtocol())
                && "devyoga.gmtfit.com".equals(url.getHost())
                && url.getPort() == -1
                && url.getUserInfo() == null
                && url.getQuery() == null
                && url.getRef() == null
                && endpointPath.equals(url.getPath());
        if (!valid) throw new MalformedURLException("Invalid fixed HTTPS endpoint");
        return url;
    }

    private static void closeQuietly(InputStream input) {
        if (input == null) return;
        try {
            input.close();
        } catch (IOException | RuntimeException ignored) {
            // The already established result remains authoritative.
        }
    }

    private static void closeQuietly(OutputStream output) {
        if (output == null) return;
        try {
            output.close();
        } catch (IOException | RuntimeException ignored) {
            // The already established result remains authoritative.
        }
    }

    private static final class DisconnectOnce implements Runnable {
        private final HttpsURLConnection connection;
        private final AtomicBoolean disconnected = new AtomicBoolean();

        private DisconnectOnce(HttpsURLConnection connection) {
            this.connection = connection;
        }

        @Override
        public void run() {
            if (!disconnected.compareAndSet(false, true)) return;
            try {
                connection.disconnect();
            } catch (RuntimeException ignored) {
                // Cleanup failure cannot change the request outcome.
            }
        }
    }

    private static final class BodyRead {
        private final byte[] bytes;
        private final int length;
        private final boolean tooLarge;

        private BodyRead(byte[] bytes, int length, boolean tooLarge) {
            this.bytes = bytes;
            this.length = length;
            this.tooLarge = tooLarge;
        }

        private static BodyRead complete(byte[] bytes, int length) {
            return new BodyRead(bytes, length, false);
        }

        private static BodyRead tooLarge(byte[] bytes) {
            return new BodyRead(bytes, bytes.length, true);
        }

        private void wipe() {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static final class SystemConnectionFactory implements ConnectionFactory {
        @Override
        public HttpsURLConnection open(URL url) throws IOException {
            URLConnection connection = url.openConnection();
            if (!(connection instanceof HttpsURLConnection)) {
                throw new SecurityException("Invalid HTTPS connection implementation");
            }
            return (HttpsURLConnection) connection;
        }
    }
}
