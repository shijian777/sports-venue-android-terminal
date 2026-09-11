package com.codex.lockertest.server;

import com.codex.lockertest.bootstrap.BaseSettingSnapshot;
import com.codex.lockertest.bootstrap.BasicDataSnapshot;
import com.codex.lockertest.bootstrap.BootstrapService;
import com.codex.lockertest.bootstrap.CheckDeviceResponseParser;
import com.codex.lockertest.bootstrap.DeviceRegistration;

import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class ProductionBootstrapServiceTest {
    private static final long FIXED_MILLIS = 1788408000000L;
    private static final String TEST_KEY = "TEST_ONLY_SYS_CODE";
    private static final String CHECK_BODY = "{\"code\":200,\"message\":\"ok\","
            + "\"data\":{\"merchant_code\":\"TEST_MERCHANT\","
            + "\"device_no\":\"TEST_DEVICE\"}}";
    private static final String BASE_BODY = "{\"code\":200,\"message\":\"ok\",\"data\":{"
            + "\"venue_name\":\"TEST_VENUE\",\"version\":\"1\","
            + "\"recognition_type\":[\"1\",\"3\"],\"use_notice\":\"\","
            + "\"return_notice\":\"\",\"logo\":\"\",\"company\":\"TEST_COMPANY\","
            + "\"cozy_tips\":\"\",\"advertisement_type\":0,"
            + "\"advertisement_list\":[],\"background_img\":\"\","
            + "\"palm_print_img\":\"\",\"area_list\":[{\"area_id\":1,"
            + "\"name\":\"A\"}],\"locker_check_status\":\"1\"}}";
    private static final String BASIC_BODY = "{\"code\":200,\"message\":\"ok\","
            + "\"data\":{\"num\":32,\"surplus_num\":12,"
            + "\"dynamic_verification_code\":\"1\"}}";

    @Test
    public void interfaceExposesOnlyTheThreeBootstrapCalls() {
        assertEquals(3, BootstrapService.class.getDeclaredMethods().length);
    }

    @Test
    public void constructorIsPackagePrivateAndDefensivelyCopiesCallerKey()
            throws Exception {
        Constructor<?>[] constructors = ProductionBootstrapService.class
                .getDeclaredConstructors();
        assertEquals(1, constructors.length);
        assertFalse(Modifier.isPublic(constructors[0].getModifiers()));
        assertFalse(Modifier.isProtected(constructors[0].getModifiers()));
        assertFalse(Modifier.isPrivate(constructors[0].getModifiers()));

        CapturingTransport transport = successfulTransport(CHECK_BODY);
        char[] callerKey = TEST_KEY.toCharArray();
        ProductionBootstrapService service = service(transport, fixedClock(), callerKey);
        char[] ownedKey = ownedKey(service);
        assertNotSame(callerKey, ownedKey);
        Arrays.fill(callerKey, '\0');

        ApiResult<DeviceRegistration> result = service.checkDevice(
                "RK3288-TEST-001", new CallToken());

        assertTrue(result.isSuccess());
        assertEquals("ea37592a7650f3c388e440d21d1cf52a",
                field(parseObject(transport.onlyRequest().body()), "sign"));
        assertArrayEquals(TEST_KEY.toCharArray(), ownedKey);
    }

    @Test
    public void sendsTheExactThreeProfilesAndParsesOnlyResponseData() {
        CapturingTransport transport = successfulTransport(CHECK_BODY, BASE_BODY, BASIC_BODY);
        ProductionBootstrapService service = service(
                transport, fixedClock(), TEST_KEY.toCharArray());
        CallToken token = new CallToken();

        ApiResult<DeviceRegistration> checked = service.checkDevice(
                "RK3288-TEST-001", token);
        assertTrue(checked.isSuccess());
        ApiResult<BaseSettingSnapshot> base = service.baseSetting(checked.value(), token);
        assertTrue(base.isSuccess());
        ApiResult<BasicDataSnapshot> basic = service.basicData(checked.value(), token);
        assertTrue(basic.isSuccess());

        assertEquals("TEST_MERCHANT", checked.value().merchantCode());
        assertEquals("TEST_DEVICE", checked.value().deviceNo());
        assertEquals("TEST_VENUE", base.value().venueName());
        assertEquals(32, basic.value().totalLockers());
        assertEquals(12, basic.value().availableLockers());

        assertEquals(3, transport.requests.size());
        assertRequest(transport.requests.get(0), ApiEndpoint.CHECK_DEVICE,
                "55e8087b21df713aed16d29527c72663",
                "ea37592a7650f3c388e440d21d1cf52a",
                "{\"device_serial\":\"RK3288-TEST-001\"}", false, token);
        assertRequest(transport.requests.get(1), ApiEndpoint.BASE_SETTING,
                "d751713988987e9331980363e24189ce",
                "02895f1e71149b8377237dabadf98ae9", "[]", true, token);
        assertRequest(transport.requests.get(2), ApiEndpoint.BASIC_DATA,
                "d751713988987e9331980363e24189ce",
                "02895f1e71149b8377237dabadf98ae9", "[]", true, token);
    }

    @Test
    public void checkDeviceSendsOneObjectAndPreservesEscapedSerialCharacters() {
        CapturingTransport transport = successfulTransport(CHECK_BODY);
        ProductionBootstrapService service = service(
                transport, fixedClock(), TEST_KEY.toCharArray());

        ApiResult<DeviceRegistration> result = service.checkDevice(
                "RK3288-\"TEST\\001", new CallToken());

        assertTrue(result.isSuccess());
        Object data = parseObject(transport.onlyRequest().body()).get("data");
        assertTrue("checkDevice data must be an object, not an array", data instanceof Map);
        Map<?, ?> requestData = (Map<?, ?>) data;
        assertEquals(1, requestData.size());
        assertEquals("RK3288-\"TEST\\001", requestData.get("device_serial"));
    }

    @Test
    public void transportAndParserFailuresRemainPayloadFree() {
        CapturingTransport transport = new CapturingTransport();
        transport.results.add(ApiResult.failure(
                ServerFailure.of(ServerFailure.Kind.NETWORK)));
        ProductionBootstrapService service = service(
                transport, fixedClock(), TEST_KEY.toCharArray());
        assertFailure(service.checkDevice("RK3288-TEST-001", new CallToken()),
                ServerFailure.Kind.NETWORK);

        transport.results.add(ApiResult.success(new TransportResponse(200, "not-json")));
        assertFailure(service.checkDevice("RK3288-TEST-001", new CallToken()),
                ServerFailure.Kind.INVALID_JSON);
    }

    @Test
    public void missingKeyAndInvalidDependenciesFailBeforeTransport() {
        CapturingTransport transport = successfulTransport(CHECK_BODY);
        assertConstructorRejected(transport, fixedClock(), null);
        assertConstructorRejected(transport, fixedClock(), new char[0]);
        assertConstructorRejected(null, fixedClock(), TEST_KEY.toCharArray());
        assertConstructorRejected(transport, null, TEST_KEY.toCharArray());
        assertEquals(0, transport.requests.size());
    }

    @Test
    public void invalidSerialRegistrationAndPreCancellationUseZeroTransport()
            throws Exception {
        CapturingTransport transport = successfulTransport(CHECK_BODY, BASE_BODY, BASIC_BODY);
        ProductionBootstrapService service = service(
                transport, fixedClock(), TEST_KEY.toCharArray());

        assertFailure(service.checkDevice(null, new CallToken()),
                ServerFailure.Kind.CONFIGURATION);
        assertFailure(service.checkDevice("short", new CallToken()),
                ServerFailure.Kind.CONFIGURATION);
        assertFailure(service.checkDevice("RK3288-TEST-\n", new CallToken()),
                ServerFailure.Kind.CONFIGURATION);
        DeviceRegistration invalid = registration(" BAD", "DEVICE");
        assertFailure(service.baseSetting(invalid, new CallToken()),
                ServerFailure.Kind.CONFIGURATION);
        assertFailure(service.basicData(registration("MERCHANT", "DEVICE "),
                        new CallToken()), ServerFailure.Kind.CONFIGURATION);

        CallToken cancelled = new CallToken();
        cancelled.cancel(CallToken.Reason.CANCELLED);
        assertFailure(service.checkDevice("RK3288-TEST-001", cancelled),
                ServerFailure.Kind.CANCELLED);
        CallToken timeout = new CallToken();
        timeout.cancel(CallToken.Reason.TIMEOUT);
        assertFailure(service.checkDevice("RK3288-TEST-001", timeout),
                ServerFailure.Kind.TIMEOUT);
        assertEquals(0, transport.requests.size());
    }

    @Test
    public void invalidClockMapsToClockInvalidWithoutTransport() {
        long[] invalidMillis = {
                0L,
                ProtocolTimestamp.MIN_EPOCH_SECONDS * 1000L - 1L,
                ProtocolTimestamp.MAX_EPOCH_SECONDS * 1000L + 1000L
        };
        for (long millis : invalidMillis) {
            CapturingTransport transport = successfulTransport(CHECK_BODY);
            ProductionBootstrapService service = service(
                    transport, () -> ProtocolTimestamp.fromEpochMillis(
                            millis, TimeZone.getTimeZone("Asia/Shanghai")),
                    TEST_KEY.toCharArray());

            assertFailure(service.checkDevice("RK3288-TEST-001", new CallToken()),
                    ServerFailure.Kind.CLOCK_INVALID);
            assertEquals(0, transport.requests.size());
        }
    }

    @Test
    public void closeClearsOwnedMasterKeyAndPermanentlyStopsTransport()
            throws Exception {
        CapturingTransport transport = successfulTransport(CHECK_BODY);
        ProductionBootstrapService service = service(
                transport, fixedClock(), TEST_KEY.toCharArray());
        char[] ownedKey = ownedKey(service);

        service.close();
        assertArrayEquals(new char[TEST_KEY.length()], ownedKey);
        service.close();
        assertFailure(service.checkDevice("RK3288-TEST-001", new CallToken()),
                ServerFailure.Kind.CONFIGURATION);
        assertEquals(0, transport.requests.size());
    }

    private static ProductionBootstrapService service(
            ServerTransport transport, ProtocolClock clock, char[] key) {
        return new ProductionBootstrapService(transport, clock, key);
    }

    private static ProtocolClock fixedClock() {
        return () -> ProtocolTimestamp.fromEpochMillis(
                FIXED_MILLIS, TimeZone.getTimeZone("Asia/Shanghai"));
    }

    private static CapturingTransport successfulTransport(String... responseBodies) {
        CapturingTransport result = new CapturingTransport();
        for (String body : responseBodies) {
            result.results.add(ApiResult.success(new TransportResponse(200, body)));
        }
        return result;
    }

    private static void assertConstructorRejected(
            ServerTransport transport, ProtocolClock clock, char[] key) {
        try {
            service(transport, clock, key);
            fail("Expected constructor rejection");
        } catch (IllegalArgumentException expected) {
            assertEquals("Invalid bootstrap service configuration", expected.getMessage());
        }
    }

    private static DeviceRegistration registration(String merchant, String device)
            throws Exception {
        Constructor<DeviceRegistration> constructor = DeviceRegistration.class
                .getDeclaredConstructor(String.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(merchant, device);
    }

    private static char[] ownedKey(ProductionBootstrapService service) throws Exception {
        Field field = ProductionBootstrapService.class.getDeclaredField("sysCode");
        field.setAccessible(true);
        return (char[]) field.get(service);
    }

    private static void assertRequest(
            TransportRequest request,
            ApiEndpoint endpoint,
            String scode,
            String sign,
            String dataJson,
            boolean boundHeaders,
            CallToken token) {
        assertEquals(endpoint, request.endpoint());
        assertTrue(request.token() == token);
        Map<String, Object> body = parseObject(request.body());
        assertEquals(4, body.size());
        assertEquals(scode, field(body, "scode"));
        assertEquals(sign, field(body, "sign"));
        assertEquals(Long.toString(FIXED_MILLIS / 1000L),
                body.get("timestamp").toString());
        assertEquals(StrictJson.parse(dataJson), body.get("data"));
        assertFalse(body.containsKey("token"));
        if (boundHeaders) {
            assertEquals(2, request.headers().asMap().size());
            assertEquals("TEST_MERCHANT", request.headers().asMap().get(
                    BootstrapHeaders.MERCHANT_AUTH));
            assertEquals("TEST_DEVICE", request.headers().asMap().get(
                    BootstrapHeaders.DEVICE_NO));
        } else {
            assertTrue(request.headers().isEmpty());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseObject(String json) {
        return (Map<String, Object>) StrictJson.parse(json);
    }

    private static String field(Map<String, Object> object, String name) {
        return (String) object.get(name);
    }

    private static void assertFailure(ApiResult<?> result, ServerFailure.Kind kind) {
        assertFalse(result.isSuccess());
        assertEquals(kind, result.failure().kind());
        Map<String, Class<?>> instanceFields = new java.util.LinkedHashMap<>();
        for (Field field : ServerFailure.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            assertTrue(Modifier.isPrivate(field.getModifiers()));
            assertTrue(Modifier.isFinal(field.getModifiers()));
            instanceFields.put(field.getName(), field.getType());
        }
        assertEquals(2, instanceFields.size());
        assertEquals(ServerFailure.Kind.class, instanceFields.get("kind"));
        assertEquals(String.class, instanceFields.get("publicMessage"));
        assertEquals("", result.failure().publicMessage());
        for (String payload : Arrays.asList(TEST_KEY, CHECK_BODY, BASE_BODY, BASIC_BODY,
                "RK3288-TEST-001", "not-json", "TEST_MERCHANT", "TEST_DEVICE")) {
            assertFalse(result.failure().toString().contains(payload));
            assertFalse(result.toString().contains(payload));
        }
    }

    private static final class CapturingTransport implements ServerTransport {
        private final List<TransportRequest> requests = new java.util.ArrayList<>();
        private final Deque<ApiResult<TransportResponse>> results = new ArrayDeque<>();

        @Override
        public ApiResult<TransportResponse> execute(TransportRequest request) {
            requests.add(request);
            if (results.isEmpty()) throw new AssertionError("Unexpected transport call");
            return results.removeFirst();
        }

        private TransportRequest onlyRequest() {
            assertEquals(1, requests.size());
            return requests.get(0);
        }
    }
}
