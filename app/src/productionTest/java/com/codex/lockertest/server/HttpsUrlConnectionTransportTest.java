package com.codex.lockertest.server;

import com.codex.lockertest.business.BusinessEndpoint;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.CookieHandler;
import java.net.ProtocolException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocketFactory;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class HttpsUrlConnectionTransportTest {
    private static final String BASE_URL = "https://devyoga.gmtfit.com";
    private static final String JSON_TYPE = "application/json";

    @Test
    public void sendsExactUtf8PostProfileWithoutChangingTlsOrGlobalDefaults()
            throws Exception {
        FakeConnection connection = successful("{\"accepted\":true}");
        RecordingFactory factory = new RecordingFactory(connection);
        HttpsUrlConnectionTransport transport = new HttpsUrlConnectionTransport(factory);
        HostnameVerifier hostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier();
        SSLSocketFactory socketFactory = HttpsURLConnection.getDefaultSSLSocketFactory();
        CookieHandler cookieHandler = CookieHandler.getDefault();
        String body = "{\"label\":\"\u67dc\u5b50-\u20ac\"}";

        ApiResult<TransportResponse> result = transport.execute(request(
                ApiEndpoint.CHECK_DEVICE, body, BootstrapHeaders.none(), new CallToken()));

        assertTrue(result.isSuccess());
        assertEquals(200, result.value().statusCode());
        assertEquals("{\"accepted\":true}", result.value().body());
        assertEquals(1, factory.openCount);
        assertEquals(BASE_URL + "/v2/central_control_screen/checkDevice",
                factory.lastRequestedUrl.toExternalForm());
        assertEquals("POST", connection.requestMethod);
        assertEquals(5_000, connection.connectTimeout);
        assertEquals(10_000, connection.readTimeout);
        assertTrue(connection.doOutput);
        assertFalse(connection.useCaches);
        assertFalse(connection.instanceFollowRedirects);
        byte[] expectedBody = body.getBytes(StandardCharsets.UTF_8);
        assertEquals(expectedBody.length, connection.fixedLength);
        assertArrayEquals(expectedBody, connection.output.bytes());
        assertEquals(exactHeaders(false), connection.requestHeaders);
        assertEquals(2, connection.requestHeaderCalls.size());
        assertEquals(0, connection.hostnameVerifierSetCount);
        assertEquals(0, connection.sslSocketFactorySetCount);
        assertSame(hostnameVerifier, HttpsURLConnection.getDefaultHostnameVerifier());
        assertSame(socketFactory, HttpsURLConnection.getDefaultSSLSocketFactory());
        assertSame(cookieHandler, CookieHandler.getDefault());
        assertCleanup(connection, 1, 1, 1);
    }

    @Test
    public void usesOnlyTheThreeFixedEndpointPathsAndExactBootstrapPair()
            throws Exception {
        ApiEndpoint[] endpoints = ApiEndpoint.values();
        assertEquals(3, endpoints.length);
        assertEquals(ApiEndpoint.CHECK_DEVICE, endpoints[0]);
        assertEquals(ApiEndpoint.BASE_SETTING, endpoints[1]);
        assertEquals(ApiEndpoint.BASIC_DATA, endpoints[2]);

        String[] paths = {
                "/v2/central_control_screen/checkDevice",
                "/v2/central_control_screen/baseSetting",
                "/v2/central_control_screen/basicData"
        };
        for (int index = 0; index < endpoints.length; index++) {
            FakeConnection connection = successful("{}");
            RecordingFactory factory = new RecordingFactory(connection);
            BootstrapHeaders headers = index == 0
                    ? BootstrapHeaders.none()
                    : BootstrapHeaders.bound("TEST_MERCHANT", "TEST_DEVICE");

            ApiResult<TransportResponse> result = new HttpsUrlConnectionTransport(factory)
                    .execute(request(endpoints[index], "{}", headers, new CallToken()));

            assertTrue(result.isSuccess());
            assertEquals(paths[index], endpoints[index].path());
            assertEquals(BASE_URL + paths[index], factory.lastRequestedUrl.toExternalForm());
            assertEquals(exactHeaders(index != 0), connection.requestHeaders);
            assertEquals(index == 0 ? 2 : 4, connection.requestHeaderCalls.size());
            assertCleanup(connection, 1, 1, 1);
        }
    }

    @Test
    public void businessEndpointUsesItsClosedPathAndRequiresBoundHeaders()
            throws Exception {
        FakeConnection connection = successful("{}");
        RecordingFactory factory = new RecordingFactory(connection);
        BootstrapHeaders headers = BootstrapHeaders.bound("TEST_MERCHANT", "TEST_DEVICE");

        ApiResult<TransportResponse> result = new HttpsUrlConnectionTransport(factory)
                .execute(new TransportRequest(BusinessEndpoint.USER_INFO,
                        "{}", headers, new CallToken()));

        assertTrue(result.isSuccess());
        assertEquals(BASE_URL + "/v2/central_control_screen/userInfo",
                factory.lastRequestedUrl.toExternalForm());
        assertEquals(exactHeaders(true), connection.requestHeaders);
        try {
            new TransportRequest(BusinessEndpoint.USER_INFO, "{}",
                    BootstrapHeaders.none(), new CallToken());
            fail("Business request accepted missing device headers");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    @Test
    public void requestRejectsWrongEndpointHeaderShapeAndNullFields() {
        assertInvalidRequest(ApiEndpoint.CHECK_DEVICE, "{}",
                BootstrapHeaders.bound("M", "D"), new CallToken());
        assertInvalidRequest(ApiEndpoint.BASE_SETTING, "{}",
                BootstrapHeaders.none(), new CallToken());
        assertInvalidRequest(ApiEndpoint.BASIC_DATA, "{}",
                BootstrapHeaders.none(), new CallToken());
        assertInvalidRequest(null, "{}", BootstrapHeaders.none(), new CallToken());
        assertInvalidRequest(ApiEndpoint.CHECK_DEVICE, null,
                BootstrapHeaders.none(), new CallToken());
        assertInvalidRequest(ApiEndpoint.CHECK_DEVICE, "{}", null, new CallToken());
        assertInvalidRequest(ApiEndpoint.CHECK_DEVICE, "{}",
                BootstrapHeaders.none(), null);
    }

    @Test
    public void preCancelledRequestNeverInvokesTheConnectionFactory() throws Exception {
        for (CallToken.Reason reason : Arrays.asList(
                CallToken.Reason.CANCELLED, CallToken.Reason.TIMEOUT)) {
            FakeConnection connection = successful("{}");
            RecordingFactory factory = new RecordingFactory(connection);
            CallToken token = new CallToken();
            assertTrue(token.cancel(reason));

            ApiResult<TransportResponse> result = new HttpsUrlConnectionTransport(factory)
                    .execute(request(ApiEndpoint.CHECK_DEVICE, "{}",
                            BootstrapHeaders.none(), token));

            assertFailure(result, reason == CallToken.Reason.TIMEOUT
                    ? ServerFailure.Kind.TIMEOUT : ServerFailure.Kind.CANCELLED);
            assertEquals(0, factory.openCount);
            assertCleanup(connection, 0, 0, 0);
        }
    }

    @Test
    public void everyThreeHundredStatusIsRedirectWithoutReadingItsBody()
            throws Exception {
        int[] statuses = {300, 301, 302, 303, 307, 308, 399};
        for (int status : statuses) {
            FakeConnection connection = successful("forbidden redirect body");
            connection.responseCode = status;

            ApiResult<TransportResponse> result = execute(connection);

            assertFailure(result, ServerFailure.Kind.REDIRECT);
            assertEquals(status, connection.responseCode);
            assertEquals(0, connection.inputOpenCount);
            assertCleanup(connection, 1, 0, 1);
        }
    }

    @Test
    public void everyOtherNonTwoHundredStatusIsHttpWithoutReadingItsBody()
            throws Exception {
        int[] statuses = {100, 199, 400, 404, 500, 599};
        for (int status : statuses) {
            FakeConnection connection = successful("forbidden error body");
            connection.responseCode = status;

            ApiResult<TransportResponse> result = execute(connection);

            assertFailure(result, ServerFailure.Kind.HTTP);
            assertEquals(0, connection.inputOpenCount);
            assertCleanup(connection, 1, 0, 1);
        }
    }

    @Test
    public void allocationHttp400PreservesOnlyValidatedBusinessMessageFromErrorStream()
            throws Exception {
        for (String contentType : new String[] {JSON_TYPE, "text/html; charset=UTF-8"}) {
            for (String data : new String[] {"", ",\"data\":null",
                    ",\"data\":{\"private\":\"RAW_RESPONSE_SECRET\"}"}) {
                FakeConnection connection = allocationError(
                        "{\"code\":400,\"message\":\"您无法使用该区域柜子\"" + data + "}");
                connection.responseContentType = contentType;
                TrackingInputStream input = (TrackingInputStream) connection.errorInput;
                RecordingFactory factory = new RecordingFactory(connection);

                ApiResult<TransportResponse> result = new HttpsUrlConnectionTransport(factory)
                        .execute(businessRequest(BusinessEndpoint.USER_BOARD, new CallToken()));

                assertFailure(result, ServerFailure.Kind.REMOTE_REJECTED);
                assertEquals("您无法使用该区域柜子", result.failure().publicMessage());
                assertFalse(result.failure().toString().contains("您无法使用该区域柜子"));
                assertFalse(result.failure().toString().contains("RAW_RESPONSE_SECRET"));
                assertEquals(1, factory.openCount);
                assertEquals(0, connection.inputOpenCount);
                assertEquals(1, connection.errorOpenCount);
                assertEquals(1, connection.errorCloseCount);
                input.assertObservedStorageWiped();
                assertCleanup(connection, 1, 0, 1);
            }
        }
    }

    @Test
    public void allocationHttp400NeverTurnsSuccessCodeOrUnsafeErrorIntoSuccessOrMessage()
            throws Exception {
        String longMessage = new String(new char[513]).replace('\0', 'x');
        for (String body : new String[] {
                "{\"code\":200,\"message\":\"ok\",\"data\":{\"fc_id\":1}}",
                "{\"code\":500,\"message\":\"PRIVATE_ERROR\"}",
                "{\"code\":400,\"message\":\"PRIVATE_ERROR\",\"trace\":\"secret\"}",
                "{\"code\":400,\"code\":400,\"message\":\"PRIVATE_ERROR\"}",
                "{\"code\":\"400\",\"message\":\"PRIVATE_ERROR\"}",
                "{\"code\":400,\"message\":null}",
                "{\"code\":400,\"message\":\"\"}",
                "{\"code\":400,\"message\":\" \"}",
                "{\"code\":400,\"message\":\"PRIVATE_ERROR\\nline\"}",
                "{\"code\":400,\"message\":\"PRIVATE_ERROR\u202e\"}",
                "{\"code\":400,\"message\":\"<html>PRIVATE_ERROR</html>\"}",
                "{\"code\":400,\"message\":\"" + longMessage + "\"}",
                "<html>PRIVATE_ERROR</html>", "[]", "not-json"}) {
            FakeConnection connection = allocationError(body);
            ApiResult<TransportResponse> result = executeBusiness(connection,
                    BusinessEndpoint.USER_BOARD, new CallToken());
            assertFailure(result, ServerFailure.Kind.HTTP);
            assertEquals("", result.failure().publicMessage());
            assertFalse(result.failure().toString().contains("PRIVATE_ERROR"));
            assertEquals(0, connection.inputOpenCount);
            assertEquals(1, connection.errorOpenCount);
            assertEquals(1, connection.errorCloseCount);
            assertCleanup(connection, 1, 0, 1);
        }
        try {
            new TransportResponse(400, "{\"code\":200}");
            fail("HTTP 400 must remain outside successful TransportResponse");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void allocationHttp400RejectsActualHtmlUnderLegacyContentType() throws Exception {
        FakeConnection connection = allocationError("<html>PRIVATE_ERROR</html>");
        connection.responseContentType = "text/html; charset=UTF-8";
        ApiResult<TransportResponse> result = executeBusiness(connection,
                BusinessEndpoint.USER_BOARD, new CallToken());
        assertFailure(result, ServerFailure.Kind.CONTRACT);
        assertEquals("", result.failure().publicMessage());
        assertEquals(1, connection.errorCloseCount);
        assertCleanup(connection, 1, 0, 1);
    }

    @Test
    public void allocationErrorBodyIsNeverReadForOtherEndpointsOrOtherHttpStatuses()
            throws Exception {
        for (BusinessEndpoint endpoint : BusinessEndpoint.values()) {
            if (endpoint == BusinessEndpoint.USER_BOARD) continue;
            FakeConnection connection = allocationError(
                    "{\"code\":400,\"message\":\"PRIVATE_ERROR\"}");
            ApiResult<TransportResponse> result = executeBusiness(connection, endpoint, new CallToken());
            assertFailure(result, ServerFailure.Kind.HTTP);
            assertEquals("", result.failure().publicMessage());
            assertEquals(0, connection.errorOpenCount);
            assertEquals(0, connection.inputOpenCount);
        }
        FakeConnection bootstrap = allocationError(
                "{\"code\":400,\"message\":\"PRIVATE_ERROR\"}");
        assertFailure(execute(bootstrap), ServerFailure.Kind.HTTP);
        assertEquals(0, bootstrap.errorOpenCount);
        for (int status : new int[] {301, 307, 399, 401, 403, 404, 500, 599}) {
            FakeConnection connection = allocationError(
                    "{\"code\":400,\"message\":\"PRIVATE_ERROR\"}");
            connection.responseCode = status;
            ApiResult<TransportResponse> result = executeBusiness(connection,
                    BusinessEndpoint.USER_BOARD, new CallToken());
            assertFailure(result, status < 400 ? ServerFailure.Kind.REDIRECT : ServerFailure.Kind.HTTP);
            assertEquals("", result.failure().publicMessage());
            assertEquals(0, connection.errorOpenCount);
            assertEquals(0, connection.inputOpenCount);
            assertCleanup(connection, 1, 0, 1);
        }
    }

    @Test
    public void allocationHttp400KeepsStrictHeadersBeforeReadingErrorStream() throws Exception {
        for (String contentType : new String[] {null, "text/plain", "application/json; charset=gbk",
                "text/html; charset=iso-8859-1", "application/json, text/html"}) {
            FakeConnection connection = allocationError("{\"code\":400,\"message\":\"denied\"}");
            connection.responseContentType = contentType;
            assertFailure(executeBusiness(connection, BusinessEndpoint.USER_BOARD, new CallToken()),
                    ServerFailure.Kind.CONTRACT);
            assertEquals(0, connection.errorOpenCount);
            assertCleanup(connection, 1, 0, 1);
        }
        FakeConnection encoded = allocationError("{\"code\":400,\"message\":\"denied\"}");
        encoded.responseContentEncoding = "gzip";
        assertFailure(executeBusiness(encoded, BusinessEndpoint.USER_BOARD, new CallToken()),
                ServerFailure.Kind.CONTRACT);
        assertEquals(0, encoded.errorOpenCount);
    }

    @Test
    public void allocationHttp400KeepsUtf8AndBodyLimitAndWipesEveryReadBuffer() throws Exception {
        FakeConnection invalid = allocationError("");
        TrackingInputStream invalidInput = TrackingInputStream.bytes(new byte[] {(byte) 0xc3, (byte) 0x28});
        invalid.errorInput = invalidInput;
        assertFailure(executeBusiness(invalid, BusinessEndpoint.USER_BOARD, new CallToken()),
                ServerFailure.Kind.INVALID_UTF8);
        invalidInput.assertObservedStorageWiped();
        assertEquals(1, invalid.errorCloseCount);

        byte[] exactBytes = new byte[ProductionServerConfig.MAX_RESPONSE_BODY_BYTES];
        Arrays.fill(exactBytes, (byte) ' ');
        byte[] denial = "{\"code\":400,\"message\":\"denied\"}".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(denial, 0, exactBytes, 0, denial.length);
        FakeConnection exact = allocationError("");
        TrackingInputStream exactInput = TrackingInputStream.bytes(exactBytes);
        exact.errorInput = exactInput;
        assertFailure(executeBusiness(exact, BusinessEndpoint.USER_BOARD, new CallToken()),
                ServerFailure.Kind.REMOTE_REJECTED);
        exactInput.assertObservedStorageWiped();
        assertEquals(1, exact.errorCloseCount);

        FakeConnection large = allocationError("");
        TrackingInputStream largeInput = TrackingInputStream.repeating(
                ProductionServerConfig.MAX_RESPONSE_BODY_BYTES + 1, (byte) ' ');
        large.errorInput = largeInput;
        assertFailure(executeBusiness(large, BusinessEndpoint.USER_BOARD, new CallToken()),
                ServerFailure.Kind.RESPONSE_TOO_LARGE);
        largeInput.assertObservedStorageWiped();
        assertEquals(1, large.errorCloseCount);
    }

    @Test
    public void allocationHttp400CancellationAtStatusSkipsErrorStreamAndWinsDuringRead()
            throws Exception {
        for (CallToken.Reason reason : new CallToken.Reason[] {
                CallToken.Reason.CANCELLED, CallToken.Reason.TIMEOUT}) {
            CallToken beforeRead = new CallToken();
            FakeConnection stopped = allocationError("{\"code\":400,\"message\":\"denied\"}");
            stopped.responseHook = () -> beforeRead.cancel(reason);
            ServerFailure.Kind expected = reason == CallToken.Reason.TIMEOUT
                    ? ServerFailure.Kind.TIMEOUT : ServerFailure.Kind.CANCELLED;
            assertFailure(executeBusiness(stopped, BusinessEndpoint.USER_BOARD, beforeRead), expected);
            assertEquals(0, stopped.errorOpenCount);
            assertCleanup(stopped, 1, 0, 1);

            CallToken duringRead = new CallToken();
            FakeConnection reading = allocationError("{\"code\":400,\"message\":\"denied\"}");
            TrackingInputStream input = (TrackingInputStream) reading.errorInput;
            input.cancelToken = duringRead;
            input.cancelReason = reason;
            ApiResult<TransportResponse> result = executeBusiness(reading,
                    BusinessEndpoint.USER_BOARD, duringRead);
            assertFailure(result, expected);
            assertEquals("", result.failure().publicMessage());
            input.assertObservedStorageWiped();
            assertEquals(1, reading.errorCloseCount);
            assertCleanup(reading, 1, 0, 1);
        }
    }

    @Test
    public void allocationHttp400ReadFailuresAndMissingStreamNeverExposePayloadOrUseSuccessStream()
            throws Exception {
        FakeConnection missing = allocationError("");
        missing.errorInput = null;
        assertFailure(executeBusiness(missing, BusinessEndpoint.USER_BOARD, new CallToken()),
                ServerFailure.Kind.NETWORK);
        assertEquals(0, missing.inputOpenCount);
        assertEquals(0, missing.errorCloseCount);
        assertCleanup(missing, 1, 0, 1);

        FakeConnection failing = allocationError("PRIVATE_PARTIAL_BODY");
        TrackingInputStream input = (TrackingInputStream) failing.errorInput;
        input.throwAfterRead = true;
        assertFailure(executeBusiness(failing, BusinessEndpoint.USER_BOARD, new CallToken()),
                ServerFailure.Kind.NETWORK);
        input.assertObservedStorageWiped();
        assertEquals(1, failing.errorCloseCount);

        FakeConnection timeout = allocationError("");
        timeout.errorInput = new InputStream() {
            @Override public int read() throws IOException {
                throw new SocketTimeoutException("PRIVATE_TIMEOUT");
            }
        };
        ApiResult<TransportResponse> result = executeBusiness(timeout,
                BusinessEndpoint.USER_BOARD, new CallToken());
        assertFailure(result, ServerFailure.Kind.TIMEOUT);
        assertEquals("", result.failure().publicMessage());
        assertEquals(1, timeout.errorCloseCount);
        assertEquals(0, timeout.inputOpenCount);
    }

    @Test
    public void allocationHttp400CleanupErrorsDoNotReplaceValidatedFailure() throws Exception {
        FakeConnection connection = allocationError("{\"code\":400,\"message\":\"denied\"}");
        connection.errorThrowOnClose = true;
        connection.output.throwOnClose = true;
        connection.disconnectFailure = new IllegalStateException("PRIVATE_CLEANUP");
        ApiResult<TransportResponse> result = executeBusiness(connection,
                BusinessEndpoint.USER_BOARD, new CallToken());
        assertFailure(result, ServerFailure.Kind.REMOTE_REJECTED);
        assertEquals("denied", result.failure().publicMessage());
        assertEquals(1, connection.errorCloseCount);
        assertCleanup(connection, 1, 0, 1);
    }

    @Test
    public void responseCodeMinusOneIsNetworkFailure() throws Exception {
        FakeConnection connection = successful("{}");
        connection.responseCode = -1;

        ApiResult<TransportResponse> result = execute(connection);

        assertFailure(result, ServerFailure.Kind.NETWORK);
        assertEquals(0, connection.inputOpenCount);
        assertCleanup(connection, 1, 0, 1);
    }

    @Test
    public void mapsTimeoutTlsAndNetworkExceptionsWithoutRetainingMessages()
            throws Exception {
        assertMappedException(new SocketTimeoutException("SENSITIVE_TIMEOUT"),
                ServerFailure.Kind.TIMEOUT);
        assertMappedException(new SSLException("SENSITIVE_TLS"),
                ServerFailure.Kind.TLS);
        assertMappedException(new IOException("SENSITIVE_NETWORK"),
                ServerFailure.Kind.NETWORK);
    }

    @Test
    public void factorySetupAndConnectionSecurityFailuresAreConfigurationFailures()
            throws Exception {
        HttpsUrlConnectionTransport factoryFailure = new HttpsUrlConnectionTransport(
                url -> { throw new SecurityException("SENSITIVE_FACTORY"); });
        ApiResult<TransportResponse> unopened = factoryFailure.execute(request(
                ApiEndpoint.CHECK_DEVICE, "{}", BootstrapHeaders.none(), new CallToken()));
        assertFailure(unopened, ServerFailure.Kind.CONFIGURATION);

        FakeConnection connection = successful("{}");
        connection.setupFailure = new SecurityException("SENSITIVE_SETUP");
        ApiResult<TransportResponse> configured = execute(connection);
        assertFailure(configured, ServerFailure.Kind.CONFIGURATION);
        assertCleanup(connection, 0, 0, 1);

        FakeConnection output = successful("{}");
        output.outputSecurityFailure = new SecurityException("SENSITIVE_OUTPUT");
        assertConnectionSecurityFailure(output, 0);

        FakeConnection response = successful("{}");
        response.runtimeResponseFailure = new SecurityException("SENSITIVE_RESPONSE");
        assertConnectionSecurityFailure(response, 1);

        FakeConnection header = successful("{}");
        header.headerSecurityFailure = new SecurityException("SENSITIVE_HEADER");
        assertConnectionSecurityFailure(header, 1);

        FakeConnection input = successful("{}");
        input.inputSecurityFailure = new SecurityException("SENSITIVE_INPUT");
        assertConnectionSecurityFailure(input, 1);
    }

    @Test
    public void unexpectedRuntimeFailureIsRethrownAfterCleanup() throws Exception {
        FakeConnection connection = successful("{}");
        connection.runtimeResponseFailure = new IllegalStateException("PROGRAMMER_BUG");

        try {
            execute(connection);
            fail("Expected programmer failure to be rethrown");
        } catch (IllegalStateException expected) {
            assertEquals("PROGRAMMER_BUG", expected.getMessage());
        }
        assertCleanup(connection, 1, 0, 1);
    }

    @Test
    public void acceptsOnlyTheStrictJsonUtf8ContentTypeProfile() throws Exception {
        String[] valid = {
                "application/json",
                "APPLICATION/JSON",
                " application/json ",
                "application/json;charset=utf-8",
                "Application/Json ; charset = UTF-8"
        };
        for (String contentType : valid) {
            FakeConnection connection = successful("{}");
            connection.responseContentType = contentType;
            assertTrue(contentType, execute(connection).isSuccess());
            assertCleanup(connection, 1, 1, 1);
        }

        String[] invalid = {
                null,
                "",
                "text/json",
                "application/problem+json",
                "application/json; charset=iso-8859-1",
                "application/json; charset=\"UTF-8\"",
                "application/json; charset=utf-8; charset=utf-8",
                "application/json; boundary=x",
                "\tapplication/json; charset=utf-8",
                "application/json\n",
                "application/json; charset=utf-8\u0080"
        };
        for (String contentType : invalid) {
            FakeConnection connection = successful("body must not be read");
            connection.responseContentType = contentType;
            ApiResult<TransportResponse> result = execute(connection);
            assertFailure(result, ServerFailure.Kind.CONTRACT);
            assertEquals(0, connection.inputOpenCount);
            assertCleanup(connection, 1, 0, 1);
        }
    }

    @Test
    public void acceptsLegacyHtmlUtf8WhenTheBodyIsAStrictJsonObjectWithoutResending()
            throws Exception {
        String body = " \r\n{\"code\":200,\"data\":{\"label\":\"\u67dc\u5b50\","
                + "\"values\":[true,null,1]},\"message\":\"ok\"}\t";
        for (String contentType : new String[] {
                "text/html", "TEXT/HTML", " text/html ",
                "text/html; charset=UTF-8", "Text/Html ; charset = utf-8"}) {
            FakeConnection connection = successful(body);
            connection.responseContentType = contentType;
            RecordingFactory factory = new RecordingFactory(connection);

            ApiResult<TransportResponse> result = new HttpsUrlConnectionTransport(factory)
                    .execute(request(ApiEndpoint.CHECK_DEVICE, "{}",
                            BootstrapHeaders.none(), new CallToken()));

            assertTrue(contentType, result.isSuccess());
            assertEquals(200, result.value().statusCode());
            assertEquals(body, result.value().body());
            assertEquals(1, factory.openCount);
            assertArrayEquals(new byte[] {'{', '}'}, connection.output.bytes());
            assertEquals(0, connection.hostnameVerifierSetCount);
            assertEquals(0, connection.sslSocketFactorySetCount);
            assertFalse(connection.instanceFollowRedirects);
            assertCleanup(connection, 1, 1, 1);
        }
    }

    @Test
    public void rejectsLegacyHtmlUnlessTheWholeBodyIsOneStrictObject() throws Exception {
        String[] invalidBodies = {
                "<!doctype html><html>gateway error</html>", "<html>{}</html>",
                "", "null", "true", "200", "\"ok\"", "[]", "[{}]",
                "{", "{\"code\":200,}", "{'code':200}", "{\"code\":NaN}",
                "{}{}", "{}<html>error</html>", "{} trailing", "/* comment */{}",
                "{\"code\":200,\"code\":500}",
                "{\"data\":{\"a\":1,\"\\u0061\":2}}", "\ufeff{}",
                "{\"message\":\"\\uD800\"}"
        };
        for (String body : invalidBodies) {
            FakeConnection connection = successful(body);
            connection.responseContentType = "text/html; charset=UTF-8";
            TrackingInputStream input = TrackingInputStream.bytes(
                    body.getBytes(StandardCharsets.UTF_8));
            connection.responseInput = input;

            ApiResult<TransportResponse> result = execute(connection);

            assertFailure(result, ServerFailure.Kind.CONTRACT);
            assertEquals(1, connection.inputOpenCount);
            input.assertObservedStorageWiped();
            assertCleanup(connection, 1, 1, 1);
        }
    }

    @Test
    public void rejectsLegacyHtmlWithUnsupportedCharsetOrParametersBeforeReading()
            throws Exception {
        for (String contentType : new String[] {
                "text/html; charset=iso-8859-1", "text/html; charset=\"UTF-8\"",
                "text/html; charset=utf-8; charset=utf-8", "text/html; boundary=x",
                "text/html\n", "\ttext/html", "text/html; charset=utf-8\u0080",
                "text/html,application/json", "application/xhtml+xml"}) {
            FakeConnection connection = successful("{}");
            connection.responseContentType = contentType;

            assertFailure(execute(connection), ServerFailure.Kind.CONTRACT);
            assertEquals(0, connection.inputOpenCount);
            assertCleanup(connection, 1, 0, 1);
        }
    }

    @Test
    public void legacyHtmlKeepsHttpAndRedirectRejectionBeforeReading() throws Exception {
        int[] statuses = {301, 307, 400, 500};
        ServerFailure.Kind[] expected = {ServerFailure.Kind.REDIRECT,
                ServerFailure.Kind.REDIRECT, ServerFailure.Kind.HTTP, ServerFailure.Kind.HTTP};
        for (int index = 0; index < statuses.length; index++) {
            FakeConnection connection = successful("{\"code\":200}");
            connection.responseContentType = "text/html; charset=UTF-8";
            connection.responseCode = statuses[index];

            assertFailure(execute(connection), expected[index]);
            assertEquals(0, connection.inputOpenCount);
            assertCleanup(connection, 1, 0, 1);
        }
    }

    @Test
    public void legacyHtmlKeepsUnsupportedContentEncodingRejectionBeforeReading()
            throws Exception {
        FakeConnection connection = successful("{}");
        connection.responseContentType = "text/html; charset=UTF-8";
        connection.responseContentEncoding = "gzip";

        assertFailure(execute(connection), ServerFailure.Kind.CONTRACT);
        assertEquals(0, connection.inputOpenCount);
        assertCleanup(connection, 1, 0, 1);
    }

    @Test
    public void legacyHtmlKeepsMalformedUtf8FailureAndWipesStorage() throws Exception {
        FakeConnection connection = successful("");
        connection.responseContentType = "text/html; charset=UTF-8";
        TrackingInputStream input = TrackingInputStream.bytes(
                new byte[] {'{', '"', 'x', '"', ':', '"', (byte) 0xc3, (byte) 0x28, '"', '}'});
        connection.responseInput = input;

        assertFailure(execute(connection), ServerFailure.Kind.INVALID_UTF8);
        input.assertObservedStorageWiped();
        assertCleanup(connection, 1, 1, 1);
    }

    @Test
    public void legacyHtmlKeepsResponseSizeLimitBeforeJsonValidation() throws Exception {
        FakeConnection connection = successful("");
        connection.responseContentType = "text/html; charset=UTF-8";
        TrackingInputStream input = TrackingInputStream.repeating(
                ProductionServerConfig.MAX_RESPONSE_BODY_BYTES + 1, (byte) ' ');
        connection.responseInput = input;

        assertFailure(execute(connection), ServerFailure.Kind.RESPONSE_TOO_LARGE);
        input.assertObservedStorageWiped();
        assertCleanup(connection, 1, 1, 1);
    }

    @Test
    public void legacyHtmlKeepsCancellationPrecedenceOverInvalidBody() throws Exception {
        CallToken token = new CallToken();
        FakeConnection connection = successful("");
        connection.responseContentType = "text/html; charset=UTF-8";
        TrackingInputStream input = TrackingInputStream.bytes(
                "<html>error</html>".getBytes(StandardCharsets.UTF_8));
        input.cancelToken = token;
        connection.responseInput = input;

        ApiResult<TransportResponse> result = new HttpsUrlConnectionTransport(
                new RecordingFactory(connection)).execute(request(
                        ApiEndpoint.CHECK_DEVICE, "{}", BootstrapHeaders.none(), token));

        assertFailure(result, ServerFailure.Kind.CANCELLED);
        input.assertObservedStorageWiped();
        assertCleanup(connection, 1, 1, 1);
    }

    @Test
    public void acceptsAbsentOrIdentityEncodingAndRejectsEveryOtherEncoding()
            throws Exception {
        for (String encoding : new String[] {null, "identity", "IDENTITY"}) {
            FakeConnection connection = successful("{}");
            connection.responseContentEncoding = encoding;
            assertTrue(execute(connection).isSuccess());
            assertCleanup(connection, 1, 1, 1);
        }

        for (String encoding : new String[] {"", "gzip", "br", "identity, gzip"}) {
            FakeConnection connection = successful("body must not be read");
            connection.responseContentEncoding = encoding;
            ApiResult<TransportResponse> result = execute(connection);
            assertFailure(result, ServerFailure.Kind.CONTRACT);
            assertEquals(0, connection.inputOpenCount);
            assertCleanup(connection, 1, 0, 1);
        }
    }

    @Test
    public void acceptsExactlyTheByteCapAndRejectsTheNextObservedByte()
            throws Exception {
        FakeConnection exact = successful("");
        exact.responseInput = new RepeatingInputStream(
                ProductionServerConfig.MAX_RESPONSE_BODY_BYTES, (byte) 'a');
        ApiResult<TransportResponse> accepted = execute(exact);
        assertTrue(accepted.isSuccess());
        assertEquals(ProductionServerConfig.MAX_RESPONSE_BODY_BYTES,
                accepted.value().body().length());
        assertCleanup(exact, 1, 1, 1);

        FakeConnection over = successful("");
        over.responseInput = new RepeatingInputStream(
                ProductionServerConfig.MAX_RESPONSE_BODY_BYTES + 1, (byte) 'a');
        ApiResult<TransportResponse> rejected = execute(over);
        assertFailure(rejected, ServerFailure.Kind.RESPONSE_TOO_LARGE);
        assertCleanup(over, 1, 1, 1);
    }

    @Test
    public void rejectsMalformedUtf8WithoutReplacement() throws Exception {
        FakeConnection connection = successful("");
        connection.responseInput = new ByteArrayInputStream(
                new byte[] {(byte) 0xc3, (byte) 0x28});

        ApiResult<TransportResponse> result = execute(connection);

        assertFailure(result, ServerFailure.Kind.INVALID_UTF8);
        assertCleanup(connection, 1, 1, 1);
    }

    @Test
    public void ownedResponseStorageIsWipedAfterSuccessInvalidUtf8AndTooLarge()
            throws Exception {
        TrackingInputStream successInput = TrackingInputStream.bytes(
                "{\"token\":\"secret-fixture\"}".getBytes(StandardCharsets.UTF_8));
        FakeConnection success = successful("");
        success.responseInput = successInput;
        assertTrue(execute(success).isSuccess());
        successInput.assertObservedStorageWiped();

        TrackingInputStream invalidInput = TrackingInputStream.bytes(
                new byte[] {(byte) 0xc3, (byte) 0x28});
        FakeConnection invalid = successful("");
        invalid.responseInput = invalidInput;
        assertFailure(execute(invalid), ServerFailure.Kind.INVALID_UTF8);
        invalidInput.assertObservedStorageWiped();

        TrackingInputStream largeInput = TrackingInputStream.repeating(
                ProductionServerConfig.MAX_RESPONSE_BODY_BYTES + 1, (byte) 's');
        FakeConnection large = successful("");
        large.responseInput = largeInput;
        assertFailure(execute(large), ServerFailure.Kind.RESPONSE_TOO_LARGE);
        largeInput.assertObservedStorageWiped();
    }

    @Test
    public void ownedResponseStorageIsWipedOnReadFailureAndCancellation()
            throws Exception {
        TrackingInputStream failingInput = TrackingInputStream.bytes(
                "partial-secret".getBytes(StandardCharsets.UTF_8));
        failingInput.throwAfterRead = true;
        FakeConnection failing = successful("");
        failing.responseInput = failingInput;
        assertFailure(execute(failing), ServerFailure.Kind.NETWORK);
        failingInput.assertObservedStorageWiped();

        CallToken token = new CallToken();
        TrackingInputStream cancellingInput = TrackingInputStream.bytes(
                "cancelled-secret".getBytes(StandardCharsets.UTF_8));
        cancellingInput.cancelToken = token;
        FakeConnection cancelling = successful("");
        cancelling.responseInput = cancellingInput;
        ApiResult<TransportResponse> result = new HttpsUrlConnectionTransport(
                new RecordingFactory(cancelling)).execute(request(
                        ApiEndpoint.CHECK_DEVICE, "{}", BootstrapHeaders.none(), token));
        assertFailure(result, ServerFailure.Kind.CANCELLED);
        cancellingInput.assertObservedStorageWiped();
    }

    @Test
    public void leavesUtf8BomForTheJsonParserAndAllowsEmpty204() throws Exception {
        FakeConnection bom = successful("");
        bom.responseInput = new ByteArrayInputStream(new byte[] {
                (byte) 0xef, (byte) 0xbb, (byte) 0xbf, '{', '}'
        });
        ApiResult<TransportResponse> bomResult = execute(bom);
        assertTrue(bomResult.isSuccess());
        assertEquals("\ufeff{}", bomResult.value().body());

        FakeConnection empty = successful("");
        empty.responseCode = 204;
        ApiResult<TransportResponse> emptyResult = execute(empty);
        assertTrue(emptyResult.isSuccess());
        assertEquals(204, emptyResult.value().statusCode());
        assertEquals("", emptyResult.value().body());
    }

    @Test
    public void timeoutFirstWinsWhenDisconnectedFakeReturnsLateSuccess()
            throws Exception {
        assertLateCancellation(CallToken.Reason.TIMEOUT,
                CallToken.Reason.CANCELLED, ServerFailure.Kind.TIMEOUT);
    }

    @Test
    public void lifecycleCancellationFirstWinsWhenDisconnectedFakeReturnsLateSuccess()
            throws Exception {
        assertLateCancellation(CallToken.Reason.CANCELLED,
                CallToken.Reason.TIMEOUT, ServerFailure.Kind.CANCELLED);
    }

    @Test
    public void cleanupFailuresDoNotOverwriteACompletedSuccess() throws Exception {
        FakeConnection connection = successful("{}");
        connection.output.throwOnClose = true;
        connection.inputThrowOnClose = true;
        connection.disconnectFailure = new IllegalStateException("cleanup disconnect");

        ApiResult<TransportResponse> result = execute(connection);

        assertTrue(result.isSuccess());
        assertEquals("{}", result.value().body());
        assertCleanup(connection, 1, 1, 1);
    }

    @Test
    public void cancellationDuringConfigurationFailureCleanupStillWins()
            throws Exception {
        FakeConnection connection = successful("{}");
        connection.setupFailure = new SecurityException("SENSITIVE_SETUP");
        connection.blockDisconnect = true;
        CallToken token = new CallToken();
        HttpsUrlConnectionTransport transport = new HttpsUrlConnectionTransport(
                new RecordingFactory(connection));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ApiResult<TransportResponse>> future = executor.submit(() ->
                    transport.execute(request(ApiEndpoint.CHECK_DEVICE, "{}",
                            BootstrapHeaders.none(), token)));
            assertTrue(connection.disconnectEntered.await(2, TimeUnit.SECONDS));
            assertTrue(token.cancel(CallToken.Reason.TIMEOUT));
            connection.releaseDisconnect.countDown();

            ApiResult<TransportResponse> result = future.get(2, TimeUnit.SECONDS);
            assertFailure(result, ServerFailure.Kind.TIMEOUT);
            assertCleanup(connection, 0, 0, 1);
        } finally {
            connection.releaseDisconnect.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void resultAndFailureObjectsAreMutuallyExclusiveAndPayloadFree()
            throws Exception {
        ApiResult<String> success = ApiResult.success("value");
        assertTrue(success.isSuccess());
        assertEquals("value", success.value());
        try {
            success.failure();
            fail("Successful result must not expose a failure");
        } catch (IllegalStateException expected) {
            assertNotNull(expected.getMessage());
        }

        ServerFailure network = ServerFailure.of(ServerFailure.Kind.NETWORK);
        ApiResult<String> failure = ApiResult.failure(network);
        assertFalse(failure.isSuccess());
        assertSame(network, failure.failure());
        assertEquals(ServerFailure.Kind.NETWORK, failure.failure().kind());
        try {
            failure.value();
            fail("Failed result must not expose a value");
        } catch (IllegalStateException expected) {
            assertNotNull(expected.getMessage());
        }

        Map<String, Class<?>> instanceFields = new LinkedHashMap<>();
        for (Field field : ServerFailure.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            assertTrue(Modifier.isPrivate(field.getModifiers()));
            assertTrue(Modifier.isFinal(field.getModifiers()));
            instanceFields.put(field.getName(), field.getType());
        }
        assertEquals(2, instanceFields.size());
        assertEquals(ServerFailure.Kind.class, instanceFields.get("kind"));
        assertEquals(String.class, instanceFields.get("publicMessage"));
        for (ServerFailure.Kind kind : ServerFailure.Kind.values()) {
            ServerFailure defaultFailure = ServerFailure.of(kind);
            assertEquals(kind, defaultFailure.kind());
            assertEquals("", defaultFailure.publicMessage());
            assertFalse(defaultFailure.toString().contains("SENSITIVE_REQUEST"));
            assertFalse(defaultFailure.toString().contains("SENSITIVE_RESPONSE"));
        }

        String safeMessage = "SENSITIVE_RESPONSE: 您无法使用该区域柜子";
        ServerFailure businessFailure = ServerFailure.businessRejection(safeMessage);
        assertEquals(ServerFailure.Kind.REMOTE_REJECTED, businessFailure.kind());
        assertEquals(safeMessage, businessFailure.publicMessage());
        assertFalse(businessFailure.toString().contains(safeMessage));
        assertFalse(businessFailure.toString().contains("SENSITIVE_RESPONSE"));
        String maximumMessage = new String(new char[512]).replace('\0', 'x');
        assertEquals(maximumMessage,
                ServerFailure.businessRejection(maximumMessage).publicMessage());
        for (String unsafeMessage : new String[] {
                null, "", "   ", maximumMessage + "x",
                "SENSITIVE_RESPONSE\nline", "SENSITIVE_RESPONSE\rline",
                "SENSITIVE_RESPONSE\tline", "SENSITIVE_RESPONSE\u0000",
                "SENSITIVE_RESPONSE\u001f", "SENSITIVE_RESPONSE\u007f",
                "SENSITIVE_RESPONSE\u0085", "SENSITIVE_RESPONSE\u202e",
                "SENSITIVE_RESPONSE\u200b", "<html>SENSITIVE_RESPONSE</html>",
                "SENSITIVE_RESPONSE<", "SENSITIVE_RESPONSE>"}) {
            ServerFailure rejected = ServerFailure.businessRejection(unsafeMessage);
            assertEquals(ServerFailure.Kind.REMOTE_REJECTED, rejected.kind());
            assertEquals("", rejected.publicMessage());
            assertFalse(rejected.toString().contains("SENSITIVE_RESPONSE"));
        }
        assertEquals(Arrays.asList(ServerFailure.Kind.CONFIGURATION,
                        ServerFailure.Kind.CLOCK_INVALID,
                        ServerFailure.Kind.CANCELLED,
                        ServerFailure.Kind.TIMEOUT,
                        ServerFailure.Kind.NETWORK,
                        ServerFailure.Kind.TLS,
                        ServerFailure.Kind.REDIRECT,
                        ServerFailure.Kind.HTTP,
                        ServerFailure.Kind.RESPONSE_TOO_LARGE,
                        ServerFailure.Kind.INVALID_UTF8,
                        ServerFailure.Kind.INVALID_JSON,
                        ServerFailure.Kind.REMOTE_REJECTED,
                        ServerFailure.Kind.NO_ENTRY_RECORD,
                        ServerFailure.Kind.CONTRACT),
                Arrays.asList(ServerFailure.Kind.values()));

        assertFactoryRejectsNulls();
    }

    @Test
    public void crossPackageTransportValueApiHasTheRequiredPublicShape()
            throws Exception {
        Method failureOf = ServerFailure.class.getMethod("of", ServerFailure.Kind.class);
        Method resultSuccess = ApiResult.class.getMethod("success", Object.class);
        Method resultFailure = ApiResult.class.getMethod("failure", ServerFailure.class);
        Constructor<TransportRequest> requestConstructor = TransportRequest.class
                .getConstructor(ApiEndpoint.class, String.class,
                        BootstrapHeaders.class, CallToken.class);
        Constructor<TransportResponse> responseConstructor = TransportResponse.class
                .getConstructor(int.class, String.class);

        assertTrue(Modifier.isFinal(ServerFailure.class.getModifiers()));
        assertTrue(Modifier.isPublic(failureOf.getModifiers()));
        assertTrue(Modifier.isStatic(failureOf.getModifiers()));
        assertTrue(Modifier.isPublic(resultSuccess.getModifiers()));
        assertTrue(Modifier.isStatic(resultSuccess.getModifiers()));
        assertTrue(Modifier.isPublic(resultFailure.getModifiers()));
        assertTrue(Modifier.isStatic(resultFailure.getModifiers()));
        assertTrue(Modifier.isPublic(requestConstructor.getModifiers()));
        assertTrue(Modifier.isPublic(responseConstructor.getModifiers()));
        for (String accessor : Arrays.asList("endpoint", "body", "headers", "token")) {
            assertTrue(Modifier.isPublic(
                    TransportRequest.class.getMethod(accessor).getModifiers()));
        }
        assertTrue(Modifier.isPublic(
                TransportResponse.class.getMethod("statusCode").getModifiers()));
        assertTrue(Modifier.isPublic(
                TransportResponse.class.getMethod("body").getModifiers()));
        assertEquals(ApiResult.class,
                ServerTransport.class.getMethod("execute", TransportRequest.class)
                        .getReturnType());
    }

    private static void assertFactoryRejectsNulls() {
        try {
            ApiResult.success(null);
            fail("Null success must be rejected");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
        try {
            ApiResult.failure((ServerFailure) null);
            fail("Null failure must be rejected");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    private static void assertMappedException(IOException error, ServerFailure.Kind kind)
            throws Exception {
        FakeConnection connection = successful("{}");
        connection.responseFailure = error;

        ApiResult<TransportResponse> result = execute(connection);

        assertFailure(result, kind);
        assertFalse(result.failure().toString().contains(error.getMessage()));
        assertCleanup(connection, 1, 0, 1);
    }

    private static void assertConnectionSecurityFailure(
            FakeConnection connection, int outputCloseCount) {
        ApiResult<TransportResponse> result = execute(connection);
        assertFailure(result, ServerFailure.Kind.CONFIGURATION);
        assertCleanup(connection, outputCloseCount, 0, 1);
    }

    private static void assertLateCancellation(CallToken.Reason first,
            CallToken.Reason second, ServerFailure.Kind expected) throws Exception {
        FakeConnection connection = successful("{}");
        connection.blockResponseCode = true;
        RecordingFactory factory = new RecordingFactory(connection);
        HttpsUrlConnectionTransport transport = new HttpsUrlConnectionTransport(factory);
        CallToken token = new CallToken();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ApiResult<TransportResponse>> future = executor.submit(() ->
                    transport.execute(request(ApiEndpoint.CHECK_DEVICE, "{}",
                            BootstrapHeaders.none(), token)));
            assertTrue(connection.responseCodeEntered.await(2, TimeUnit.SECONDS));

            assertTrue(token.cancel(first));
            assertEquals(1, connection.disconnectCount);
            assertFalse(token.cancel(second));
            connection.releaseResponseCode.countDown();

            ApiResult<TransportResponse> result = future.get(2, TimeUnit.SECONDS);
            assertFailure(result, expected);
            assertEquals(first, token.reason());
            assertEquals(0, connection.inputOpenCount);
            assertCleanup(connection, 1, 0, 1);
        } finally {
            connection.releaseResponseCode.countDown();
            executor.shutdownNow();
        }
    }

    private static ApiResult<TransportResponse> execute(FakeConnection connection) {
        return new HttpsUrlConnectionTransport(new RecordingFactory(connection)).execute(
                request(ApiEndpoint.CHECK_DEVICE, "{}", BootstrapHeaders.none(),
                        new CallToken()));
    }

    private static ApiResult<TransportResponse> executeBusiness(FakeConnection connection,
            BusinessEndpoint endpoint, CallToken token) {
        return new HttpsUrlConnectionTransport(new RecordingFactory(connection))
                .execute(businessRequest(endpoint, token));
    }

    private static TransportRequest businessRequest(BusinessEndpoint endpoint, CallToken token) {
        return new TransportRequest(endpoint, "{\"token\":\"PRIVATE_REQUEST_TOKEN\"}",
                BootstrapHeaders.bound("TEST_MERCHANT", "TEST_DEVICE"), token);
    }

    private static FakeConnection allocationError(String body) throws Exception {
        FakeConnection connection = successful("{\"code\":200,\"data\":{\"fc_id\":1}}");
        connection.responseCode = 400;
        connection.errorInput = TrackingInputStream.bytes(body.getBytes(StandardCharsets.UTF_8));
        return connection;
    }

    private static TransportRequest request(ApiEndpoint endpoint, String body,
            BootstrapHeaders headers, CallToken token) {
        return new TransportRequest(endpoint, body, headers, token);
    }

    private static FakeConnection successful(String body) throws Exception {
        FakeConnection connection = new FakeConnection();
        connection.responseInput = new ByteArrayInputStream(
                body.getBytes(StandardCharsets.UTF_8));
        return connection;
    }

    private static void assertFailure(ApiResult<?> result, ServerFailure.Kind kind) {
        assertFalse(result.isSuccess());
        assertEquals(kind, result.failure().kind());
    }

    private static void assertCleanup(FakeConnection connection,
            int outputCloseCount, int inputCloseCount, int disconnectCount) {
        assertEquals(outputCloseCount, connection.output.closeCount);
        assertEquals(inputCloseCount, connection.inputCloseCount);
        assertEquals(disconnectCount, connection.disconnectCount);
    }

    private static void assertInvalidRequest(ApiEndpoint endpoint, String body,
            BootstrapHeaders headers, CallToken token) {
        try {
            new TransportRequest(endpoint, body, headers, token);
            fail("Expected invalid transport request to be rejected");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    private static Map<String, String> exactHeaders(boolean includeBootstrap) {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("Content-Type", "application/json; charset=UTF-8");
        expected.put("Accept", "application/json");
        if (includeBootstrap) {
            expected.put("gmt-merchant-auth", "TEST_MERCHANT");
            expected.put("device-no", "TEST_DEVICE");
        }
        return expected;
    }

    private static final class RecordingFactory
            implements HttpsUrlConnectionTransport.ConnectionFactory {
        private final FakeConnection connection;
        private int openCount;
        private URL lastRequestedUrl;

        private RecordingFactory(FakeConnection connection) {
            this.connection = connection;
        }

        @Override
        public HttpsURLConnection open(URL url) {
            openCount++;
            lastRequestedUrl = url;
            return connection;
        }
    }

    private static final class FakeConnection extends HttpsURLConnection {
        private int responseCode = 200;
        private String responseContentType = JSON_TYPE;
        private String responseContentEncoding;
        private InputStream responseInput = new ByteArrayInputStream(new byte[0]);
        private InputStream errorInput;
        private int errorOpenCount;
        private int errorCloseCount;
        private boolean errorThrowOnClose;
        private Runnable responseHook;
        private IOException responseFailure;
        private RuntimeException runtimeResponseFailure;
        private SecurityException setupFailure;
        private SecurityException outputSecurityFailure;
        private SecurityException headerSecurityFailure;
        private SecurityException inputSecurityFailure;
        private RuntimeException disconnectFailure;
        private boolean blockResponseCode;
        private boolean blockDisconnect;
        private boolean inputThrowOnClose;
        private final CountDownLatch responseCodeEntered = new CountDownLatch(1);
        private final CountDownLatch releaseResponseCode = new CountDownLatch(1);
        private final CountDownLatch disconnectEntered = new CountDownLatch(1);
        private final CountDownLatch releaseDisconnect = new CountDownLatch(1);
        private final TrackingOutputStream output = new TrackingOutputStream();
        private final Map<String, String> requestHeaders = new LinkedHashMap<>();
        private final List<String> requestHeaderCalls = new ArrayList<>();
        private String requestMethod;
        private int connectTimeout;
        private int readTimeout;
        private int fixedLength = -1;
        private boolean doOutput;
        private boolean useCaches = true;
        private boolean instanceFollowRedirects = true;
        private int hostnameVerifierSetCount;
        private int sslSocketFactorySetCount;
        private int inputOpenCount;
        private int inputCloseCount;
        private int disconnectCount;

        private FakeConnection() throws Exception {
            super(new URL("https://transport-fixture.invalid/"));
        }

        @Override
        public void setRequestMethod(String method) throws ProtocolException {
            if (setupFailure != null) throw setupFailure;
            requestMethod = method;
        }

        @Override
        public void setConnectTimeout(int timeout) {
            connectTimeout = timeout;
        }

        @Override
        public void setReadTimeout(int timeout) {
            readTimeout = timeout;
        }

        @Override
        public void setDoOutput(boolean value) {
            doOutput = value;
        }

        @Override
        public void setUseCaches(boolean value) {
            useCaches = value;
        }

        @Override
        public void setInstanceFollowRedirects(boolean followRedirects) {
            instanceFollowRedirects = followRedirects;
        }

        @Override
        public void setFixedLengthStreamingMode(int contentLength) {
            fixedLength = contentLength;
        }

        @Override
        public void setRequestProperty(String name, String value) {
            requestHeaderCalls.add(name);
            requestHeaders.put(name, value);
        }

        @Override
        public OutputStream getOutputStream() {
            if (outputSecurityFailure != null) throw outputSecurityFailure;
            return output;
        }

        @Override
        public int getResponseCode() throws IOException {
            responseCodeEntered.countDown();
            if (blockResponseCode) {
                try {
                    if (!releaseResponseCode.await(2, TimeUnit.SECONDS)) {
                        throw new IOException("fixture response latch timed out");
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException("fixture interrupted", error);
                }
            }
            if (runtimeResponseFailure != null) throw runtimeResponseFailure;
            if (responseFailure != null) throw responseFailure;
            if (responseHook != null) responseHook.run();
            return responseCode;
        }

        @Override
        public String getContentType() {
            if (headerSecurityFailure != null) throw headerSecurityFailure;
            return responseContentType;
        }

        @Override
        public String getContentEncoding() {
            return responseContentEncoding;
        }

        @Override
        public InputStream getInputStream() {
            if (inputSecurityFailure != null) throw inputSecurityFailure;
            inputOpenCount++;
            return new InputStream() {
                @Override
                public int read() throws IOException {
                    return responseInput.read();
                }

                @Override
                public int read(byte[] bytes, int offset, int length) throws IOException {
                    return responseInput.read(bytes, offset, length);
                }

                @Override
                public void close() throws IOException {
                    inputCloseCount++;
                    responseInput.close();
                    if (inputThrowOnClose) throw new IOException("input close fixture");
                }
            };
        }

        @Override public InputStream getErrorStream() {
            errorOpenCount++;
            if (errorInput == null) return null;
            return new InputStream() {
                @Override public int read() throws IOException { return errorInput.read(); }
                @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                    return errorInput.read(bytes, offset, length);
                }
                @Override public void close() throws IOException {
                    errorCloseCount++;
                    errorInput.close();
                    if (errorThrowOnClose) throw new IOException("private error close fixture");
                }
            };
        }

        @Override
        public void setHostnameVerifier(HostnameVerifier verifier) {
            hostnameVerifierSetCount++;
        }

        @Override
        public void setSSLSocketFactory(SSLSocketFactory factory) {
            sslSocketFactorySetCount++;
        }

        @Override
        public void disconnect() {
            disconnectCount++;
            disconnectEntered.countDown();
            if (blockDisconnect) {
                try {
                    if (!releaseDisconnect.await(2, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("fixture disconnect latch timed out");
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("fixture disconnect interrupted", error);
                }
            }
            if (disconnectFailure != null) throw disconnectFailure;
        }

        @Override
        public boolean usingProxy() {
            return false;
        }

        @Override
        public void connect() { }

        @Override
        public String getCipherSuite() {
            return "TLS_FAKE";
        }

        @Override
        public Certificate[] getLocalCertificates() {
            return null;
        }

        @Override
        public Certificate[] getServerCertificates() throws SSLPeerUnverifiedException {
            return new Certificate[0];
        }

        @Override
        public Principal getPeerPrincipal() throws SSLPeerUnverifiedException {
            return null;
        }

        @Override
        public Principal getLocalPrincipal() {
            return null;
        }
    }

    private static final class TrackingOutputStream extends OutputStream {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private int closeCount;
        private boolean throwOnClose;

        @Override
        public void write(int value) {
            bytes.write(value);
        }

        @Override
        public void write(byte[] values, int offset, int length) {
            bytes.write(values, offset, length);
        }

        @Override
        public void close() throws IOException {
            closeCount++;
            if (throwOnClose) throw new IOException("output close fixture");
        }

        private byte[] bytes() {
            return bytes.toByteArray();
        }
    }

    private static final class RepeatingInputStream extends InputStream {
        private int remaining;
        private final byte value;

        private RepeatingInputStream(int length, byte value) {
            remaining = length;
            this.value = value;
        }

        @Override
        public int read() {
            if (remaining == 0) return -1;
            remaining--;
            return value & 0xff;
        }

        @Override
        public int read(byte[] target, int offset, int length) {
            if (remaining == 0) return -1;
            int count = Math.min(remaining, length);
            Arrays.fill(target, offset, offset + count, value);
            remaining -= count;
            return count;
        }
    }

    private static final class TrackingInputStream extends InputStream {
        private final byte[] bytes;
        private final int repeatingLength;
        private final byte repeatingValue;
        private int position;
        private int readCalls;
        private byte[] observedStorage;
        private boolean throwAfterRead;
        private CallToken cancelToken;
        private CallToken.Reason cancelReason = CallToken.Reason.CANCELLED;

        private TrackingInputStream(byte[] bytes, int repeatingLength, byte repeatingValue) {
            this.bytes = bytes;
            this.repeatingLength = repeatingLength;
            this.repeatingValue = repeatingValue;
        }

        private static TrackingInputStream bytes(byte[] bytes) {
            return new TrackingInputStream(Arrays.copyOf(bytes, bytes.length), 0, (byte) 0);
        }

        private static TrackingInputStream repeating(int length, byte value) {
            return new TrackingInputStream(null, length, value);
        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            observedStorage = target;
            if (throwAfterRead && readCalls++ > 0) throw new IOException("fixture read failure");
            int remaining = bytes != null ? bytes.length - position : repeatingLength - position;
            if (remaining == 0) return -1;
            int count = Math.min(remaining, length);
            if (bytes != null) {
                System.arraycopy(bytes, position, target, offset, count);
            } else {
                Arrays.fill(target, offset, offset + count, repeatingValue);
            }
            position += count;
            if (cancelToken != null) cancelToken.cancel(cancelReason);
            return count;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int count = read(one, 0, 1);
            return count < 0 ? -1 : one[0] & 0xff;
        }

        private void assertObservedStorageWiped() {
            assertNotNull(observedStorage);
            for (byte value : observedStorage) assertEquals(0, value);
        }
    }
}
