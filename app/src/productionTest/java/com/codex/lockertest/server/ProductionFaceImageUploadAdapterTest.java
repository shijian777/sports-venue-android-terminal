package com.codex.lockertest.server;

import com.codex.lockertest.business.FaceImageUploadAdapter;
import com.codex.lockertest.business.FaceAuthenticationPipeline;
import com.codex.lockertest.business.AuthenticatedUser;
import com.codex.lockertest.business.BusinessEndpoint;
import com.codex.lockertest.bootstrap.CheckDeviceResponseParser;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.security.cert.Certificate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TimeZone;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLPeerUnverifiedException;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ProductionFaceImageUploadAdapterTest {
    private static final byte[] JPEG = new byte[] {
            (byte) 0xff, (byte) 0xd8, 1, 2, 3, (byte) 0xff, (byte) 0xd9
    };

    @Test
    public void documentedPublicUploadStreamsOneImagePartAndReturnsSafeImageValue() {
        FakeConnection connection = success(
                "{\"code\":200,\"message\":\"success\","
                        + "\"data\":{\"image\":\"https://example.invalid/capture.jpg\"}}");
        RecordingFactory factory = new RecordingFactory(connection);
        FaceImageUploadAdapter adapter = adapter(factory);

        ApiResult<String> result = adapter.upload(Arrays.copyOf(JPEG, JPEG.length), new CallToken());

        assertTrue(result.isSuccess());
        assertEquals("https://example.invalid/capture.jpg", result.value());
        assertEquals("https://devyoga.gmtfit.com/v2/central_control_screen/uploadImagePublic",
                factory.url.toString());
        assertEquals("POST", connection.method);
        assertFalse(connection.followRedirects);
        assertEquals("merchant", connection.headers.get(BootstrapHeaders.MERCHANT_AUTH));
        assertEquals("device", connection.headers.get(BootstrapHeaders.DEVICE_NO));
        assertEquals("application/json", connection.headers.get("Accept"));
        assertEquals("multipart/form-data; boundary=BoundaryForTest",
                connection.headers.get("Content-Type"));
        String wire = new String(connection.output.toByteArray(), StandardCharsets.ISO_8859_1);
        assertTrue(wire.startsWith("--BoundaryForTest\r\n"
                + "Content-Disposition: form-data; name=\"user_face\"; filename=\"capture.jpg\"\r\n"
                + "Content-Type: image/jpeg\r\n\r\n"));
        assertTrue(wire.endsWith("\r\n--BoundaryForTest--\r\n"));
        assertEquals(1, occurrences(wire, "name=\"user_face\""));
        assertArrayEquals(new byte[JPEG.length], connection.output.lastArrayWrite);
        assertTrue(connection.disconnected);
    }

    @Test
    public void invalidOrOversizedPhotoNeverOpensAConnection() {
        RecordingFactory factory = new RecordingFactory(success("{}"));
        FaceImageUploadAdapter adapter = adapter(factory);

        ApiResult<String> malformed = adapter.upload(new byte[] {1, 2, 3}, new CallToken());
        ApiResult<String> oversized = adapter.upload(jpegOfSize(8 * 1024 * 1024 + 1), new CallToken());

        assertEquals(ServerFailure.Kind.CONFIGURATION, malformed.failure().kind());
        assertEquals(ServerFailure.Kind.CONFIGURATION, oversized.failure().kind());
        assertEquals(0, factory.opens);
    }

    @Test
    public void redirectIsRejectedAndOwnedPhotoBytesAreCleared() {
        FakeConnection connection = success("{}");
        connection.statusCode = 302;
        RecordingFactory factory = new RecordingFactory(connection);

        ApiResult<String> result = adapter(factory).upload(
                Arrays.copyOf(JPEG, JPEG.length), new CallToken());

        assertEquals(ServerFailure.Kind.REDIRECT, result.failure().kind());
        assertArrayEquals(new byte[JPEG.length], connection.output.lastArrayWrite);
        assertTrue(connection.disconnected);
    }

    @Test
    public void cancellationDisconnectsAndWinsOverAnOtherwiseSuccessfulResponse() {
        FakeConnection connection = success(
                "{\"code\":200,\"message\":\"success\",\"data\":{\"image\":\"ok\"}}");
        CallToken token = new CallToken();
        connection.onResponseCode = () -> token.cancel(CallToken.Reason.CANCELLED);

        ApiResult<String> result = adapter(new RecordingFactory(connection)).upload(
                Arrays.copyOf(JPEG, JPEG.length), token);

        assertEquals(ServerFailure.Kind.CANCELLED, result.failure().kind());
        assertTrue(connection.disconnected);
    }

    @Test
    public void malformedUtf8OrUnsafeImageValueFailsClosed() {
        FakeConnection invalidUtf8 = success("{}");
        invalidUtf8.response = new byte[] {(byte) 0xc3, 0x28};
        ApiResult<String> first = adapter(new RecordingFactory(invalidUtf8)).upload(
                Arrays.copyOf(JPEG, JPEG.length), new CallToken());
        assertEquals(ServerFailure.Kind.INVALID_UTF8, first.failure().kind());

        StringBuilder longValue = new StringBuilder();
        for (int index = 0; index < 2049; index++) longValue.append('x');
        FakeConnection unsafe = success("{\"code\":200,\"message\":\"success\","
                + "\"data\":{\"image\":\"" + longValue + "\"}}");
        ApiResult<String> second = adapter(new RecordingFactory(unsafe)).upload(
                Arrays.copyOf(JPEG, JPEG.length), new CallToken());
        assertEquals(ServerFailure.Kind.CONTRACT, second.failure().kind());
    }

    @Test
    public void standardCompactUtf8ContentTypeIsAccepted() {
        FakeConnection connection = success(
                "{\"code\":200,\"message\":\"success\",\"data\":{\"image\":\"safe\"}}");
        connection.contentType = "application/json;charset=utf-8";

        ApiResult<String> result = adapter(new RecordingFactory(connection)).upload(
                Arrays.copyOf(JPEG, JPEG.length), new CallToken());

        assertTrue(result.isSuccess());
        assertEquals("safe", result.value());
    }

    @Test
    public void legacyHtmlHeaderDoesNotHideValidUploadResult() {
        FakeConnection connection = success(
                "{\"code\":200,\"message\":\"success\",\"data\":{\"image\":\"safe\"}}");
        connection.contentType = "text/html; charset=UTF-8";
        ApiResult<String> result = adapter(new RecordingFactory(connection)).upload(
                Arrays.copyOf(JPEG, JPEG.length), new CallToken());
        assertTrue(result.isSuccess());
        assertEquals("safe", result.value());
        assertTrue(connection.disconnected);
    }

    @Test
    public void legacyHtmlHeaderPreservesBusinessRejectionWithOrWithoutData() {
        for (String tail : new String[] {"", ",\"data\":null", ",\"data\":[]"}) {
            FakeConnection connection = success("{\"code\":400,"
                    + "\"message\":\"系统繁忙,请刷新重试\"" + tail + "}");
            connection.contentType = "text/html; charset=UTF-8";
            ApiResult<String> result = adapter(new RecordingFactory(connection)).upload(
                    Arrays.copyOf(JPEG, JPEG.length), new CallToken());
            assertFalse(result.isSuccess());
            assertEquals(ServerFailure.Kind.REMOTE_REJECTED, result.failure().kind());
            assertEquals("系统繁忙,请刷新重试", result.failure().publicMessage());
            assertArrayEquals(new byte[JPEG.length], connection.output.lastArrayWrite);
            assertTrue(connection.disconnected);
        }
    }

    @Test
    public void validJsonRejectionWithoutDataIsNotAContractError() {
        FakeConnection connection = success("{\"code\":400,\"message\":\"上传失败\"}");
        ApiResult<String> result = adapter(new RecordingFactory(connection)).upload(
                Arrays.copyOf(JPEG, JPEG.length), new CallToken());
        assertEquals(ServerFailure.Kind.REMOTE_REJECTED, result.failure().kind());
        assertEquals("上传失败", result.failure().publicMessage());
    }

    @Test
    public void legacyHeaderNeverAdmitsHtmlAmbiguousJsonOrMissingSuccessImage() {
        for (String body : new String[] {
                "<html>Server error</html>", "[]",
                "{\"code\":400,\"code\":200,\"message\":\"ok\",\"data\":{\"image\":\"x\"}}",
                "{\"code\":200,\"message\":\"success\"}",
                "{\"code\":200,\"message\":\"success\",\"data\":{\"image\":\"\"}}",
                "{\"code\":400,\"message\":null}",
                "{\"code\":400,\"message\":\"bad\",\"debug\":\"trace\"}"
        }) {
            FakeConnection connection = success(body);
            connection.contentType = "text/html; charset=UTF-8";
            ApiResult<String> result = adapter(new RecordingFactory(connection)).upload(
                    Arrays.copyOf(JPEG, JPEG.length), new CallToken());
            assertFalse(result.isSuccess());
            assertEquals(ServerFailure.Kind.CONTRACT, result.failure().kind());
            assertEquals("", result.failure().publicMessage());
        }
    }

    @Test
    public void uploadRejectsUnsupportedOrAmbiguousResponseHeaders() {
        for (String type : new String[] {null, "text/plain", "text/html; charset=GBK",
                "application/json; charset=utf-8; charset=gbk", "text/html\r\n",
                "text/html; charset=utf-8\u0000"}) {
            FakeConnection connection = success(
                    "{\"code\":200,\"message\":\"ok\",\"data\":{\"image\":\"safe\"}}");
            connection.contentType = type;
            ApiResult<String> result = adapter(new RecordingFactory(connection)).upload(
                    Arrays.copyOf(JPEG, JPEG.length), new CallToken());
            assertFalse(result.isSuccess());
            assertEquals(ServerFailure.Kind.CONTRACT, result.failure().kind());
        }
    }

    @Test
    public void rejectionCannotExposeMarkupOrControlCharacters() {
        for (String message : new String[] {"<html>trace</html>", "bad\\ntrace"}) {
            FakeConnection connection = success(
                    "{\"code\":400,\"message\":\"" + message + "\",\"data\":null}");
            ApiResult<String> result = adapter(new RecordingFactory(connection)).upload(
                    Arrays.copyOf(JPEG, JPEG.length), new CallToken());
            assertFalse(result.isSuccess());
            assertEquals(ServerFailure.Kind.REMOTE_REJECTED, result.failure().kind());
            assertEquals("", result.failure().publicMessage());
        }
    }

    @Test
    public void legacyHeaderStillRejectsInvalidUtf8AndOversizedBody() {
        FakeConnection connection = success("{}");
        connection.contentType = "text/html; charset=UTF-8";
        connection.response = new byte[] {(byte) 0xc3, 0x28};
        ApiResult<String> result = adapter(new RecordingFactory(connection)).upload(
                Arrays.copyOf(JPEG, JPEG.length), new CallToken());
        assertEquals(ServerFailure.Kind.INVALID_UTF8, result.failure().kind());
        connection = success("{}");
        connection.contentType = "text/html; charset=UTF-8";
        connection.response = new byte[ProductionServerConfig.MAX_RESPONSE_BODY_BYTES + 1];
        result = adapter(new RecordingFactory(connection)).upload(
                Arrays.copyOf(JPEG, JPEG.length), new CallToken());
        assertEquals(ServerFailure.Kind.RESPONSE_TOO_LARGE, result.failure().kind());
        assertArrayEquals(new byte[connection.input.lastReadBuffer.length],
                connection.input.lastReadBuffer);
    }

    @Test
    public void unsuccessfulHttpStatusCannotReturnAnImageEvenWithSuccessJson() {
        for (int status : new int[] {400, 401, 500}) {
            FakeConnection connection = success(
                    "{\"code\":200,\"message\":\"ok\",\"data\":{\"image\":\"safe\"}}");
            connection.statusCode = status;
            connection.contentType = "text/html; charset=UTF-8";
            ApiResult<String> result = adapter(new RecordingFactory(connection)).upload(
                    Arrays.copyOf(JPEG, JPEG.length), new CallToken());
            assertFalse(result.isSuccess());
            assertEquals(ServerFailure.Kind.HTTP, result.failure().kind());
        }
    }

    @Test
    public void successfulLegacyUploadPassesOnlyImageIdentifierToTypeFiveLogin() {
        FakeConnection connection = success(
                "{\"code\":200,\"message\":\"ok\",\"data\":{\"image\":\"fixture-image-id\"}}");
        connection.contentType = "text/html; charset=UTF-8";
        int[] loginCalls = {0};
        try (ProductionBusinessService service = pipelineService(request -> {
            loginCalls[0]++;
            assertEquals(BusinessEndpoint.USER_INFO, request.businessEndpoint());
            Map<?, ?> envelope = (Map<?, ?>) StrictJson.parse(request.body());
            assertFalse(envelope.containsKey("token"));
            Map<?, ?> data = (Map<?, ?>) envelope.get("data");
            assertEquals(2, data.size());
            assertEquals("5", data.get("type").toString());
            assertEquals("fixture-image-id", data.get("keyword"));
            return ApiResult.success(new TransportResponse(200,
                    "{\"code\":200,\"message\":\"ok\",\"data\":{\"token\":\"fixture-session\","
                    + "\"user_type\":0,\"locker_check_status\":0,\"uid\":9}}"));
        })) {
            ApiResult<AuthenticatedUser> result = new FaceAuthenticationPipeline(service,
                    adapter(new RecordingFactory(connection))).authenticate(JPEG, new CallToken());
            assertTrue(result.isSuccess());
            assertTrue(result.value().isCustomerReady());
            assertEquals(1, loginCalls[0]);
        }
    }

    @Test
    public void rejectedLegacyUploadStopsBeforeAuthenticationOrAnyCabinetRequest() {
        FakeConnection connection = success("{\"code\":400,\"message\":\"系统繁忙,请刷新重试\"}");
        connection.contentType = "text/html; charset=UTF-8";
        try (ProductionBusinessService service = pipelineService(request -> {
            throw new AssertionError("Upload rejected: no subsequent business request is allowed");
        })) {
            ApiResult<AuthenticatedUser> result = new FaceAuthenticationPipeline(service,
                    adapter(new RecordingFactory(connection))).authenticate(JPEG, new CallToken());
            assertFalse(result.isSuccess());
            assertEquals(ServerFailure.Kind.REMOTE_REJECTED, result.failure().kind());
            assertEquals("系统繁忙,请刷新重试", result.failure().publicMessage());
        }
    }

    private static ProductionBusinessService pipelineService(ServerTransport transport) {
        return new ProductionBusinessService(transport,
                () -> ProtocolTimestamp.fromEpochMillis(1788681600000L,
                        TimeZone.getTimeZone("Asia/Shanghai")),
                new CheckDeviceResponseParser().parse(
                        "{\"code\":200,\"message\":\"ok\",\"data\":{\"device_no\":17,"
                        + "\"merchant_code\":\"MERCHANT\"}}").value(),
                "test-key".toCharArray());
    }

    @Test
    public void globalTimeoutCancelsTokenDisconnectsAndStopsBeforeUpload() {
        FakeConnection connection = success("{}");
        boolean[] timeoutHandleCancelled = {false};
        ProductionFaceImageUploadAdapter adapter = new ProductionFaceImageUploadAdapter(
                BootstrapHeaders.bound("merchant", "device"),
                new RecordingFactory(connection), () -> "BoundaryForTest",
                (token, task) -> {
                    task.run();
                    return () -> timeoutHandleCancelled[0] = true;
                });
        CallToken token = new CallToken();

        ApiResult<String> result = adapter.upload(
                Arrays.copyOf(JPEG, JPEG.length), token);

        assertEquals(ServerFailure.Kind.TIMEOUT, result.failure().kind());
        assertTrue(token.isCancelled());
        assertEquals(CallToken.Reason.TIMEOUT, token.reason());
        assertTrue(connection.disconnected);
        assertEquals(0, connection.output.size());
        assertTrue(timeoutHandleCancelled[0]);
    }

    @Test
    public void tlsFailureFailsClosedAndDisconnects() {
        FakeConnection connection = success("{}");
        connection.outputFailure = new SSLException("test TLS handshake failure");

        ApiResult<String> result = adapter(new RecordingFactory(connection)).upload(
                Arrays.copyOf(JPEG, JPEG.length), new CallToken());

        assertEquals(ServerFailure.Kind.TLS, result.failure().kind());
        assertTrue(connection.disconnected);
    }

    @Test
    public void responseReadBufferIsClearedAfterSuccessfulParsing() {
        FakeConnection connection = success(
                "{\"code\":200,\"message\":\"success\",\"data\":{\"image\":\"safe\"}}");

        ApiResult<String> result = adapter(new RecordingFactory(connection)).upload(
                Arrays.copyOf(JPEG, JPEG.length), new CallToken());

        assertTrue(result.isSuccess());
        assertTrue(connection.input.lastReadBuffer != null);
        assertArrayEquals(new byte[connection.input.lastReadBuffer.length],
                connection.input.lastReadBuffer);
    }

    private static ProductionFaceImageUploadAdapter adapter(RecordingFactory factory) {
        return new ProductionFaceImageUploadAdapter(
                BootstrapHeaders.bound("merchant", "device"), factory,
                () -> "BoundaryForTest", (token, task) -> () -> { });
    }

    private static FakeConnection success(String body) {
        try {
            return new FakeConnection(new URL("https://devyoga.gmtfit.com/"),
                    body.getBytes(StandardCharsets.UTF_8));
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static byte[] jpegOfSize(int size) {
        byte[] jpeg = new byte[size];
        jpeg[0] = (byte) 0xff;
        jpeg[1] = (byte) 0xd8;
        jpeg[size - 2] = (byte) 0xff;
        jpeg[size - 1] = (byte) 0xd9;
        return jpeg;
    }

    private static int occurrences(String value, String target) {
        int count = 0;
        for (int index = 0; (index = value.indexOf(target, index)) >= 0;
                index += target.length()) count++;
        return count;
    }

    private static final class RecordingFactory
            implements ProductionFaceImageUploadAdapter.ConnectionFactory {
        final FakeConnection connection;
        URL url;
        int opens;

        RecordingFactory(FakeConnection connection) { this.connection = connection; }

        @Override public HttpsURLConnection open(URL requestedUrl) {
            opens++;
            url = requestedUrl;
            return connection;
        }
    }

    private static final class RetainingOutputStream extends ByteArrayOutputStream {
        byte[] lastArrayWrite;

        @Override public synchronized void write(byte[] bytes, int offset, int length) {
            if (length == JPEG.length && offset == 0) lastArrayWrite = bytes;
            super.write(bytes, offset, length);
        }
    }

    @SuppressWarnings("deprecation")
    private static final class RetainingInputStream extends ByteArrayInputStream {
        byte[] lastReadBuffer;

        RetainingInputStream(byte[] bytes) { super(bytes); }

        @Override public synchronized int read(byte[] bytes, int offset, int length) {
            lastReadBuffer = bytes;
            return super.read(bytes, offset, length);
        }
    }

    @SuppressWarnings("deprecation")
    private static final class FakeConnection extends HttpsURLConnection {
        final Map<String, String> headers = new LinkedHashMap<>();
        final RetainingOutputStream output = new RetainingOutputStream();
        byte[] response;
        int statusCode = 200;
        String contentType = "application/json; charset=UTF-8";
        String method;
        boolean followRedirects = true;
        boolean disconnected;
        Runnable onResponseCode;
        IOException outputFailure;
        RetainingInputStream input;

        FakeConnection(URL url, byte[] response) {
            super(url);
            this.response = response;
        }

        @Override public void setRequestMethod(String method) { this.method = method; }
        @Override public void setInstanceFollowRedirects(boolean follow) { followRedirects = follow; }
        @Override public void setRequestProperty(String key, String value) { headers.put(key, value); }
        @Override public OutputStream getOutputStream() throws IOException {
            if (outputFailure != null) throw outputFailure;
            return output;
        }
        @Override public int getResponseCode() {
            if (onResponseCode != null) onResponseCode.run();
            return statusCode;
        }
        @Override public String getContentType() { return contentType; }
        @Override public InputStream getInputStream() {
            input = new RetainingInputStream(response);
            return input;
        }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() throws IOException { }
        @Override public String getCipherSuite() { return "test"; }
        @Override public Certificate[] getLocalCertificates() { return null; }
        @Override public Certificate[] getServerCertificates() throws SSLPeerUnverifiedException {
            return null;
        }
        @Override public Principal getPeerPrincipal() throws SSLPeerUnverifiedException { return null; }
        @Override public Principal getLocalPrincipal() { return null; }
    }
}
