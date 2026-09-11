package com.codex.lockertest.server;

import com.codex.lockertest.bootstrap.DeviceRegistration;
import com.codex.lockertest.business.FaceImageUploadAdapter;

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
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLException;

/**
 * Fixed-host multipart implementation of the documented public face-image upload.
 *
 * <p>The supplied contract documents only the {@code user_face} file part. It does
 * not define how the JSON scode/sign envelope would combine with multipart data,
 * so this isolated adapter deliberately does not invent signature form fields.
 */
public final class ProductionFaceImageUploadAdapter implements FaceImageUploadAdapter {
    interface ConnectionFactory {
        HttpsURLConnection open(URL url) throws IOException;
    }

    interface BoundaryFactory {
        String create();
    }

    interface TimeoutHandle {
        void cancel();
    }

    interface TimeoutScheduler {
        TimeoutHandle schedule(CallToken token, Runnable task);
    }

    private static final String PATH = "/v2/central_control_screen/uploadImagePublic";
    private static final String PART_NAME = "user_face";
    private static final String FILE_NAME = "capture.jpg";
    private static final String IMAGE_CONTENT_TYPE = "image/jpeg";
    private static final int MAX_JPEG_BYTES = 8 * 1024 * 1024;
    private static final int MAX_IMAGE_VALUE_LENGTH = 2048;

    private final BootstrapHeaders headers;
    private final ConnectionFactory connectionFactory;
    private final BoundaryFactory boundaryFactory;
    private final TimeoutScheduler timeoutScheduler;

    public static ProductionFaceImageUploadAdapter createLive(DeviceRegistration registration) {
        if (registration == null) throw new IllegalArgumentException("Registration is required");
        return new ProductionFaceImageUploadAdapter(
                BootstrapHeaders.bound(registration.merchantCode(), registration.deviceNo()),
                new SystemConnectionFactory(), new SecureBoundaryFactory(),
                new TimerTimeoutScheduler());
    }

    ProductionFaceImageUploadAdapter(BootstrapHeaders headers,
            ConnectionFactory connectionFactory, BoundaryFactory boundaryFactory,
            TimeoutScheduler timeoutScheduler) {
        if (headers == null || headers.isEmpty() || connectionFactory == null
                || boundaryFactory == null || timeoutScheduler == null) {
            throw new IllegalArgumentException("Upload dependencies are required");
        }
        this.headers = headers;
        this.connectionFactory = connectionFactory;
        this.boundaryFactory = boundaryFactory;
        this.timeoutScheduler = timeoutScheduler;
    }

    @Override public ApiResult<String> upload(byte[] jpeg, CallToken token) {
        if (!validJpeg(jpeg) || token == null) return failure(ServerFailure.Kind.CONFIGURATION);
        ApiResult<String> cancelled = cancellation(token);
        if (cancelled != null) return cancelled;

        byte[] owned = Arrays.copyOf(jpeg, jpeg.length);
        HttpsURLConnection connection = null;
        DisconnectOnce disconnect = null;
        CallToken.Registration cancellationRegistration = null;
        TimeoutHandle timeout = null;
        OutputStream output = null;
        InputStream input = null;
        try {
            URL url = endpointUrl();
            connection = connectionFactory.open(url);
            if (connection == null) return failureUnlessCancelled(token,
                    ServerFailure.Kind.CONFIGURATION);
            disconnect = new DisconnectOnce(connection);
            cancellationRegistration = token.onCancel(disconnect);
            final CallToken requestToken = token;
            timeout = timeoutScheduler.schedule(token,
                    () -> requestToken.cancel(CallToken.Reason.TIMEOUT));
            if (timeout == null) return failureUnlessCancelled(token,
                    ServerFailure.Kind.CONFIGURATION);

            String boundary = validBoundary(boundaryFactory.create());
            byte[] prefix = prefix(boundary);
            byte[] suffix = suffix(boundary);
            int contentLength = Math.addExact(Math.addExact(prefix.length, owned.length), suffix.length);
            configure(connection, boundary, contentLength);
            cancelled = cancellation(token);
            if (cancelled != null) return cancelled;

            output = connection.getOutputStream();
            output.write(prefix);
            output.write(owned, 0, owned.length);
            output.write(suffix);
            output.flush();
            cancelled = cancellation(token);
            if (cancelled != null) return cancelled;

            int status = connection.getResponseCode();
            cancelled = cancellation(token);
            if (cancelled != null) return cancelled;
            if (status >= 300 && status <= 399) return failure(ServerFailure.Kind.REDIRECT);
            if (status < 200 || status > 299) return failure(ServerFailure.Kind.HTTP);
            // This fixed server also labels JSON upload responses as text/html.
            // The body still must pass strict UTF-8, JSON and endpoint validation.
            String contentType = connection.getContentType();
            if ((!validContentType(contentType, "application/json")
                    && !validContentType(contentType, "text/html"))
                    || !validContentEncoding(connection.getContentEncoding())) {
                return failure(ServerFailure.Kind.CONTRACT);
            }
            input = connection.getInputStream();
            ApiResult<String> parsed = parseResponse(readResponse(input), token);
            cancelled = cancellation(token);
            return cancelled != null ? cancelled : parsed;
        } catch (SocketTimeoutException error) {
            return failureUnlessCancelled(token, ServerFailure.Kind.TIMEOUT);
        } catch (SSLException error) {
            return failureUnlessCancelled(token, ServerFailure.Kind.TLS);
        } catch (MalformedURLException | ProtocolException | ArithmeticException
                | IllegalArgumentException error) {
            return failureUnlessCancelled(token, ServerFailure.Kind.CONFIGURATION);
        } catch (ResponseTooLarge error) {
            return failureUnlessCancelled(token, ServerFailure.Kind.RESPONSE_TOO_LARGE);
        } catch (CharacterCodingException error) {
            return failureUnlessCancelled(token, ServerFailure.Kind.INVALID_UTF8);
        } catch (JsonContractException error) {
            return failureUnlessCancelled(token, ServerFailure.Kind.CONTRACT);
        } catch (IOException error) {
            return failureUnlessCancelled(token, ServerFailure.Kind.NETWORK);
        } catch (SecurityException error) {
            return failureUnlessCancelled(token, ServerFailure.Kind.CONFIGURATION);
        } finally {
            closeQuietly(input);
            closeQuietly(output);
            if (timeout != null) timeout.cancel();
            if (cancellationRegistration != null) cancellationRegistration.unregister();
            if (disconnect != null) disconnect.run();
            Arrays.fill(owned, (byte) 0);
        }
    }

    @Override public String unavailableReason() {
        return null;
    }

    private static ApiResult<String> parseResponse(byte[] response, CallToken token)
            throws CharacterCodingException {
        try {
            String body = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(response)).toString();
            Object parsed = StrictJson.parse(body);
            if (!(parsed instanceof Map)) throw JsonContractException.invalidJson();
            Map<?, ?> root = (Map<?, ?>) parsed;
            if (!root.containsKey("code") || !(root.get("message") instanceof String)
                    || !(root.size() == 2 || (root.size() == 3 && root.containsKey("data")))) {
                throw JsonContractException.invalidJson();
            }
            Object code = root.get("code");
            if (!(code instanceof JsonNumber)) throw JsonContractException.invalidJson();
            int businessCode = ((JsonNumber) code).toInt(Integer.MIN_VALUE, Integer.MAX_VALUE);
            if (businessCode != 200) {
                ApiResult<String> cancelled = cancellation(token);
                if (cancelled != null) return cancelled;
                // Failure responses may omit data; no image can be issued from them.
                return businessCode == 400
                        ? ApiResult.failure(ServerFailure.businessRejection((String) root.get("message")))
                        : failure(ServerFailure.Kind.REMOTE_REJECTED);
            }
            if (!(root.get("message") instanceof String) || !(root.get("data") instanceof Map)) {
                throw JsonContractException.invalidJson();
            }
            Map<?, ?> data = (Map<?, ?>) root.get("data");
            if (data.size() != 1 || !(data.get("image") instanceof String)) {
                throw JsonContractException.invalidJson();
            }
            String image = (String) data.get("image");
            if (!safeImageValue(image)) throw JsonContractException.invalidJson();
            return ApiResult.success(image);
        } finally {
            Arrays.fill(response, (byte) 0);
        }
    }

    private static byte[] readResponse(InputStream input) throws IOException, ResponseTooLarge {
        if (input == null) throw new IOException("Missing response stream");
        byte[] bytes = new byte[ProductionServerConfig.MAX_RESPONSE_BODY_BYTES + 1];
        int total = 0;
        try {
            while (total < bytes.length) {
                int count = input.read(bytes, total, bytes.length - total);
                if (count < 0) return Arrays.copyOf(bytes, total);
                if (count == 0) {
                    int value = input.read();
                    if (value < 0) return Arrays.copyOf(bytes, total);
                    bytes[total++] = (byte) value;
                } else {
                    total += count;
                }
            }
            throw new ResponseTooLarge();
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private void configure(HttpsURLConnection connection, String boundary,
            int contentLength) throws ProtocolException {
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(ProductionServerConfig.CONNECT_TIMEOUT_MILLIS);
        connection.setReadTimeout(ProductionServerConfig.READ_TIMEOUT_MILLIS);
        connection.setDoOutput(true);
        connection.setUseCaches(false);
        connection.setInstanceFollowRedirects(false);
        connection.setFixedLengthStreamingMode(contentLength);
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        connection.setRequestProperty("Accept", "application/json");
        for (Map.Entry<String, String> header : headers.asMap().entrySet()) {
            connection.setRequestProperty(header.getKey(), header.getValue());
        }
    }

    private static byte[] prefix(String boundary) {
        return ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + PART_NAME
                + "\"; filename=\"" + FILE_NAME + "\"\r\n"
                + "Content-Type: " + IMAGE_CONTENT_TYPE + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] suffix(String boundary) {
        return ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII);
    }

    private static URL endpointUrl() throws MalformedURLException {
        URL url = new URL(ProductionServerConfig.BASE_URL + PATH);
        if (!"https".equals(url.getProtocol()) || !"devyoga.gmtfit.com".equals(url.getHost())
                || url.getPort() != -1 || url.getUserInfo() != null || url.getQuery() != null
                || url.getRef() != null || !PATH.equals(url.getPath())) {
            throw new MalformedURLException("Invalid fixed upload endpoint");
        }
        return url;
    }

    private static String validBoundary(String value) {
        if (value == null || value.length() < 12 || value.length() > 70) {
            throw new IllegalArgumentException("Invalid multipart boundary");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!(character >= 'A' && character <= 'Z')
                    && !(character >= 'a' && character <= 'z')
                    && !(character >= '0' && character <= '9')) {
                throw new IllegalArgumentException("Invalid multipart boundary");
            }
        }
        return value;
    }

    private static boolean validJpeg(byte[] value) {
        return value != null && value.length >= 4 && value.length <= MAX_JPEG_BYTES
                && (value[0] & 0xff) == 0xff && (value[1] & 0xff) == 0xd8
                && (value[value.length - 2] & 0xff) == 0xff
                && (value[value.length - 1] & 0xff) == 0xd9;
    }

    private static boolean safeImageValue(String value) {
        if (value.length() == 0 || value.length() > MAX_IMAGE_VALUE_LENGTH
                || !value.equals(value.trim())) return false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isISOControl(character)) return false;
        }
        return true;
    }

    private static boolean validContentType(String value, String expectedMediaType) {
        if (value == null || value.isEmpty()) return false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x20 || character > 0x7e) return false;
        }
        String normalized = value.trim();
        int separator = normalized.indexOf(';');
        if (separator < 0) return expectedMediaType.equalsIgnoreCase(normalized);
        if (normalized.indexOf(';', separator + 1) >= 0
                || !expectedMediaType.equalsIgnoreCase(
                        normalized.substring(0, separator).trim())) return false;
        String parameter = normalized.substring(separator + 1).trim();
        int equals = parameter.indexOf('=');
        return equals > 0 && parameter.indexOf('=', equals + 1) < 0
                && "charset".equalsIgnoreCase(parameter.substring(0, equals).trim())
                && "utf-8".equalsIgnoreCase(parameter.substring(equals + 1).trim());
    }

    private static boolean validContentEncoding(String value) {
        return value == null || "identity".equalsIgnoreCase(value.trim());
    }

    private static <T> ApiResult<T> cancellation(CallToken token) {
        if (!token.isCancelled()) return null;
        return failure(token.reason() == CallToken.Reason.TIMEOUT
                ? ServerFailure.Kind.TIMEOUT : ServerFailure.Kind.CANCELLED);
    }

    private static <T> ApiResult<T> failureUnlessCancelled(
            CallToken token, ServerFailure.Kind fallback) {
        ApiResult<T> cancelled = cancellation(token);
        return cancelled != null ? cancelled : failure(fallback);
    }

    private static <T> ApiResult<T> failure(ServerFailure.Kind kind) {
        return ApiResult.failure(ServerFailure.of(kind));
    }

    private static void closeQuietly(InputStream input) {
        if (input == null) return;
        try { input.close(); } catch (IOException | RuntimeException ignored) { }
    }

    private static void closeQuietly(OutputStream output) {
        if (output == null) return;
        try { output.close(); } catch (IOException | RuntimeException ignored) { }
    }

    private static final class DisconnectOnce implements Runnable {
        private final HttpsURLConnection connection;
        private final AtomicBoolean disconnected = new AtomicBoolean();

        DisconnectOnce(HttpsURLConnection connection) { this.connection = connection; }

        @Override public void run() {
            if (!disconnected.compareAndSet(false, true)) return;
            try { connection.disconnect(); } catch (RuntimeException ignored) { }
        }
    }

    private static final class SystemConnectionFactory implements ConnectionFactory {
        @Override public HttpsURLConnection open(URL url) throws IOException {
            URLConnection opened = url.openConnection();
            if (!(opened instanceof HttpsURLConnection)) {
                throw new SecurityException("Invalid HTTPS connection implementation");
            }
            return (HttpsURLConnection) opened;
        }
    }

    private static final class SecureBoundaryFactory implements BoundaryFactory {
        private final SecureRandom random = new SecureRandom();

        @Override public String create() {
            byte[] randomBytes = new byte[18];
            random.nextBytes(randomBytes);
            StringBuilder value = new StringBuilder("CodexFace");
            for (byte item : randomBytes) {
                value.append(Character.forDigit((item >>> 4) & 0xf, 16));
                value.append(Character.forDigit(item & 0xf, 16));
            }
            Arrays.fill(randomBytes, (byte) 0);
            return value.toString();
        }
    }

    private static final class TimerTimeoutScheduler implements TimeoutScheduler {
        @Override public TimeoutHandle schedule(CallToken token, Runnable task) {
            Timer timer = new Timer("face-upload-timeout", true);
            TimerTask timerTask = new TimerTask() {
                @Override public void run() { task.run(); }
            };
            timer.schedule(timerTask, ProductionServerConfig.REQUEST_TIMEOUT_MILLIS);
            return () -> {
                timerTask.cancel();
                timer.cancel();
            };
        }
    }

    private static final class ResponseTooLarge extends Exception { }

}
