package com.codex.lockertest.server;

import com.codex.lockertest.bootstrap.CheckDeviceResponseParser;
import com.codex.lockertest.bootstrap.DeviceRegistration;
import com.codex.lockertest.business.AdminLogin;
import com.codex.lockertest.business.AssignedCabinet;
import com.codex.lockertest.business.AuthenticatedUser;
import com.codex.lockertest.business.BoardAction;
import com.codex.lockertest.business.BusinessEndpoint;
import com.codex.lockertest.business.BusinessService;
import com.codex.lockertest.business.EmptyBusinessResult;
import com.codex.lockertest.business.InstallerSession;
import com.codex.lockertest.business.PalmKeywordProvider;
import com.codex.lockertest.business.SessionToken;
import com.codex.lockertest.business.SignalRecord;
import com.codex.lockertest.business.UserInfoRequest;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class ProductionBusinessServiceTest {
    @Test
    public void installationContractPinsCanonicalPathAndUnavailableDefault() throws Exception {
        BusinessEndpoint endpoint;
        try {
            endpoint = BusinessEndpoint.valueOf("INSTALLATION_VERIFY");
        } catch (IllegalArgumentException missingEndpoint) {
            fail("Missing closed installation verification endpoint");
            return;
        }
        assertEquals("/v2/central_control_screen/installationVerify", endpoint.path());

        java.lang.reflect.Method method;
        try {
            method = BusinessService.class.getMethod("installationVerify",
                    SessionToken.class, Long.TYPE, Long.TYPE, Integer.TYPE,
                    List.class, CallToken.class);
        } catch (NoSuchMethodException missingMethod) {
            fail("Missing typed installation verification service boundary");
            return;
        }
        assertTrue(method.isDefault());
        ApiResult<?> result = (ApiResult<?>) method.invoke(new UnavailableService(),
                SessionToken.of("installer"), 9L, 5L, 0,
                Arrays.asList(new SignalRecord("01", "AA", "BB")), new CallToken());
        assertFailure(result, ServerFailure.Kind.CONFIGURATION);
    }

    @Test
    public void phoneAuthenticationSignsObjectDataWithBoundHeadersAndEscaping() {
        RecordingTransport transport = new RecordingTransport(authFixture());
        ProductionBusinessService service = service(transport);

        ApiResult<AuthenticatedUser> result = service.authenticate(
                UserInfoRequest.phone("00123\"x", "0007\\z"), new CallToken());

        assertTrue(result.isSuccess());
        TransportRequest request = transport.onlyRequest();
        assertSignedObjectRequest(request, BusinessEndpoint.USER_INFO,
                "fcec4f759da1835b1cba504388175dfa", "fa4af1dba8f21abffc4f6c04ffe48f3d",
                "{\"type\":1,\"keyword\":\"00123\\\"x\",\"user_code\":\"0007\\\\z\"}");
        Map<String, Object> envelope = object(StrictJson.parse(request.body()));
        Map<String, Object> fields = object(envelope.get("data"));
        assertEquals(3, fields.size());
        assertEquals("00123\"x", fields.get("keyword"));
        assertEquals("0007\\z", fields.get("user_code"));
        assertEquals("1", fields.get("type").toString());
    }

    @Test
    public void cardAuthenticationSignsObjectDataWithoutAddingPhoneCode() {
        RecordingTransport transport = new RecordingTransport(authFixture());
        ProductionBusinessService service = service(transport);

        assertTrue(service.authenticate(
                UserInfoRequest.card("0000123"), new CallToken()).isSuccess());

        assertSignedObjectRequest(transport.onlyRequest(), BusinessEndpoint.USER_INFO,
                "ebdc2192a9d5b364f6d877a6019114df", "db8c371798a0f4bdf488439bac7a76ac",
                "{\"type\":4,\"keyword\":\"0000123\"}");
    }

    @Test
    public void administratorLoginSignsObjectDataWithoutOptionalDynamicCode() {
        RecordingTransport transport = new RecordingTransport(adminFixture());
        ProductionBusinessService service = service(transport);

        assertTrue(service.adminLogin(
                "oper\"ator", "fake\\input", null, new CallToken()).isSuccess());

        assertSignedObjectRequest(transport.onlyRequest(), BusinessEndpoint.MBR_LOGIN,
                "325825161062b018e82b40073b4473c1", "7224c4b34e396bb2d8d21ead040236d2",
                "{\"username\":\"oper\\\"ator\",\"password\":\"fake\\\\input\"}");
    }

    @Test
    public void administratorLoginIncludesOptionalDynamicCodeInSignedObjectData() {
        RecordingTransport transport = new RecordingTransport(adminFixture());
        ProductionBusinessService service = service(transport);

        assertTrue(service.adminLogin(
                "oper\"ator", "fake\\input", "0099", new CallToken()).isSuccess());

        assertSignedObjectRequest(transport.onlyRequest(), BusinessEndpoint.MBR_LOGIN,
                "e662aa58fb0b9790d5432b613f03109d", "66597eab3c78e6ab7268ada973c8b847",
                "{\"username\":\"oper\\\"ator\",\"password\":\"fake\\\\input\","
                        + "\"dynamic_code\":\"0099\"}");
    }

    @Test
    public void authenticatedQueriesCarryServerTokenAndExactDocumentFields() {
        RecordingTransport transport = new RecordingTransport(
                previewFixture(), usedFixture(), assignedFixture(), emptyObjectFixture());
        ProductionBusinessService service = service(transport);
        AuthenticatedUser user = user();

        assertTrue(service.controlPanelPreview(user, 2, 9, 1, new CallToken()).isSuccess());
        assertTrue(service.useCabinetList(user, new CallToken()).isSuccess());
        ApiResult<AssignedCabinet> assigned =
                service.userBoard(user, 9, 0, new CallToken());
        assertEquals(88L, assigned.value().fcId());
        ApiResult<EmptyBusinessResult> opened =
                service.openBoard(user, BoardAction.OPEN, 88, new CallToken());
        assertTrue(opened.isSuccess());

        assertPreviewObjectRequest(transport.requests.get(0));
        assertRequest(transport.requests.get(1), BusinessEndpoint.USE_CABINET_LIST,
                "session");
        assertSignedAuthenticatedObjectRequest(transport.requests.get(2),
                BusinessEndpoint.USER_BOARD,
                "c9f927e93b5b214b28e74a34b57d18fa", "2fd7a91d167689bbaa00115d15ea238d",
                "{\"area_id\":9,\"fc_id\":0}");
        assertSignedAuthenticatedObjectRequest(transport.requests.get(3),
                BusinessEndpoint.OPEN_BOARD,
                "eaa13ac731d2c97a22dd496b9a7bd3f4", "4b0c8f59f137ce2776b30da52c5fb654",
                "{\"type\":1,\"fc_id\":88}");
    }

    @Test
    public void selectedCabinetAssignmentSignsObjectDataWithDirectAreaAndCabinetFields() {
        RecordingTransport transport = new RecordingTransport(
                "{\"code\":200,\"message\":\"ok\",\"data\":{\"fc_id\":1}}");
        ProductionBusinessService service = service(transport);

        ApiResult<AssignedCabinet> result = service.userBoard(user(), 2, 1, new CallToken());

        assertTrue(result.isSuccess());
        assertEquals(1L, result.value().fcId());
        assertSignedAuthenticatedObjectRequest(transport.onlyRequest(),
                BusinessEndpoint.USER_BOARD,
                "b70a3564d620722fcde51e7a43c9f8c5", "9ea23849879a163c97d7c28222f2da22",
                "{\"area_id\":2,\"fc_id\":1}");
    }

    @Test
    public void openingSelectedCabinetSignsObjectDataWithDirectTypeAndCabinetFields() {
        RecordingTransport transport = new RecordingTransport(emptyObjectFixture());
        ProductionBusinessService service = service(transport);

        assertTrue(service.openBoard(user(), BoardAction.OPEN, 88, new CallToken()).isSuccess());

        assertSignedAuthenticatedObjectRequest(transport.onlyRequest(),
                BusinessEndpoint.OPEN_BOARD,
                "eaa13ac731d2c97a22dd496b9a7bd3f4", "4b0c8f59f137ce2776b30da52c5fb654",
                "{\"type\":1,\"fc_id\":88}");
    }

    @Test
    public void returnUsesSelectedCabinetIdAndDocumentedTypeInSignedObjectData() {
        RecordingTransport transport = new RecordingTransport(emptyObjectFixture());
        ProductionBusinessService service = service(transport);

        assertTrue(service.openBoard(
                user(), BoardAction.RETURN, 501L, new CallToken()).isSuccess());

        assertSignedAuthenticatedObjectRequest(transport.onlyRequest(),
                BusinessEndpoint.OPEN_BOARD,
                "da652acf0d91b040279940ce3b4e6b65", "ccfe831cb97a356c7121505626facced",
                "{\"type\":2,\"fc_id\":501}");
    }

    @Test
    public void singleCabinetActionsRejectClearAllCabinetIdWithoutSendingRequest() {
        RecordingTransport transport = new RecordingTransport(emptyObjectFixture());
        ProductionBusinessService service = service(transport);

        assertFailure(service.openBoard(
                user(), BoardAction.RETURN, 0L, new CallToken()),
                ServerFailure.Kind.CONFIGURATION);
        assertFailure(service.openBoard(
                user(), BoardAction.OPEN, 0L, new CallToken()),
                ServerFailure.Kind.CONFIGURATION);

        assertEquals(0, transport.requests.size());
    }

    @Test
    public void sessionPreviewSignsTheSameObjectSentOnTheWire() {
        RecordingTransport transport = new RecordingTransport(previewFixture());
        ProductionBusinessService service = service(transport);

        assertTrue(service.controlPanelPreview(
                SessionToken.of("session"), 2, 9, 1, new CallToken()).isSuccess());

        assertPreviewObjectRequest(transport.onlyRequest());
    }

    @Test
    public void previewDoesNotInventMissingServerPageFromRequestedPage() {
        RecordingTransport transport = new RecordingTransport(
                "{\"code\":200,\"message\":\"ok\",\"data\":{"
                        + "\"all_open_command\":[],\"0\":[]}}");
        ProductionBusinessService service = service(transport);

        assertFailure(service.controlPanelPreview(
                user(), 2, 9, 1, new CallToken()), ServerFailure.Kind.CONTRACT);
        assertPreviewObjectRequest(transport.onlyRequest());
    }

    @Test
    public void dynamicCodePromotesOnlyPendingAdminAndUsesReturnedToken() {
        RecordingTransport transport = new RecordingTransport(
                "{\"code\":200,\"message\":\"ok\",\"data\":{\"token\":\"final\"}}" );
        ProductionBusinessService service = service(transport);
        AuthenticatedUser pending = new AuthenticatedUser(
                com.codex.lockertest.business.SessionToken.of("pending"),
                com.codex.lockertest.business.UserType.ADMIN, 1, 71);

        ApiResult<AuthenticatedUser> result = service.verifyMemberDynamicCode(
                pending, "000099", new CallToken());

        assertTrue(result.isSuccess());
        assertEquals(com.codex.lockertest.business.UserType.ADMIN, result.value().userType());
        assertFalse(result.value().dynamicCodeRequired());
        assertFalse(result.value().isCustomerReady());
        assertRequest(transport.onlyRequest(), BusinessEndpoint.MEMBER_DYNAMIC_CODE,
                "pending", "dynamic_code", "000099", "uid", "71");
    }

    @Test
    public void administratorIdentityCannotEnterCustomerCabinetMethods() {
        RecordingTransport transport = new RecordingTransport();
        ProductionBusinessService service = service(transport);
        AuthenticatedUser admin = new AuthenticatedUser(
                com.codex.lockertest.business.SessionToken.of("admin"),
                com.codex.lockertest.business.UserType.ADMIN, 0, 71);

        assertFailure(service.controlPanelPreview(
                admin, 2, 9, 1, new CallToken()), ServerFailure.Kind.CONFIGURATION);
        assertFailure(service.useCabinetList(admin, new CallToken()),
                ServerFailure.Kind.CONFIGURATION);
        assertFailure(service.userBoard(admin, 9, 0, new CallToken()),
                ServerFailure.Kind.CONFIGURATION);
        assertFailure(service.openBoard(admin, BoardAction.OPEN, 8, new CallToken()),
                ServerFailure.Kind.CONFIGURATION);
        assertEquals(0, transport.requests.size());
    }

    @Test
    public void documentedInstallerAndAdminMethodsUseClosedEndpoints() {
        RecordingTransport transport = new RecordingTransport(
                emptyFixture(),
                "{\"code\":200,\"message\":\"ok\",\"data\":{\"token\":\"installer\"}}",
                emptyFixture(),
                adminFixture(),
                emptyFixture());
        ProductionBusinessService service = service(transport);

        assertTrue(service.mobileSmsCode("00123", new CallToken()).isSuccess());
        ApiResult<InstallerSession> installer = service.installerLogin(
                "00123", "0007", new CallToken());
        assertTrue(installer.isSuccess());
        assertTrue(service.storeyCabinet(installer.value(), 5, 2, 8,
                new CallToken()).isSuccess());
        ApiResult<AdminLogin> admin = service.adminLogin(
                "operator", "password", null, new CallToken());
        assertTrue(admin.isSuccess());
        assertTrue(service.quickClearCabinet(admin.value().token(), 9,
                Arrays.asList(new SignalRecord("01", "AA", "BB")),
                new CallToken()).isSuccess());

        assertEquals(BusinessEndpoint.MOBILE_SMS_CODE,
                transport.requests.get(0).businessEndpoint());
        assertEquals(BusinessEndpoint.RIG_LOGIN,
                transport.requests.get(1).businessEndpoint());
        assertEquals(BusinessEndpoint.STOREY_CABINET,
                transport.requests.get(2).businessEndpoint());
        assertEquals(BusinessEndpoint.MBR_LOGIN,
                transport.requests.get(3).businessEndpoint());
        assertEquals(BusinessEndpoint.QUICK_CLEAR_CABINET,
                transport.requests.get(4).businessEndpoint());
    }

    @Test
    public void installationVerifyUsesExactSignedEnvelopeBoundHeadersAndEscaping() {
        RecordingTransport transport = new RecordingTransport(emptyFixture());
        ProductionBusinessService service = service(transport);
        CallToken callToken = new CallToken();

        ApiResult<EmptyBusinessResult> result = service.installationVerify(
                SessionToken.of("installer"), 9, 5, 1,
                Arrays.asList(
                        new SignalRecord("01\"A", "AA\\BB", "CC"),
                        new SignalRecord("02", "DD", "EE")),
                callToken);

        assertTrue(result.isSuccess());
        assertSame(EmptyBusinessResult.INSTANCE, result.value());
        TransportRequest request = transport.onlyRequest();
        assertEquals(BusinessEndpoint.INSTALLATION_VERIFY, request.businessEndpoint());
        assertEquals("/v2/central_control_screen/installationVerify",
                request.endpointPath());
        assertEquals("MERCHANT",
                request.headers().asMap().get(BootstrapHeaders.MERCHANT_AUTH));
        assertEquals("17", request.headers().asMap().get(BootstrapHeaders.DEVICE_NO));
        assertSame(callToken, request.token());
        assertEquals("{\"scode\":\"9172bdb0b8e8b8d51f9136ca2cd93982\","
                        + "\"sign\":\"ca6ce2462b0e6f98344965ff20901051\","
                        + "\"timestamp\":1788681600,\"token\":\"installer\","
                        + "\"data\":[{\"verify_type\":0,\"type\":1,\"area_id\":9,"
                        + "\"fc_id\":5,\"command\":[{\"board_hex\":\"01\\\"A\","
                        + "\"send\":\"AA\\\\BB\",\"receive\":\"CC\"},{"
                        + "\"board_hex\":\"02\",\"send\":\"DD\","
                        + "\"receive\":\"EE\"}]}]}",
                request.body());
    }

    @Test
    public void installationVerifyAcceptsBothTypesAndTheBoundedCommandMaximum() {
        SignalRecord command = new SignalRecord("01", "AA", "BB");
        RecordingTransport transport = new RecordingTransport(
                emptyFixture(), emptyFixture());
        ProductionBusinessService service = service(transport);

        assertTrue(service.installationVerify(SessionToken.of("installer"),
                9, 5, 0, Collections.nCopies(256, command), new CallToken()).isSuccess());
        assertTrue(service.installationVerify(SessionToken.of("installer"),
                9, 5, 1, Arrays.asList(command), new CallToken()).isSuccess());

        assertEquals(2, transport.requests.size());
        Map<String, Object> typeZero = object(
                array(object(StrictJson.parse(transport.requests.get(0).body()))
                        .get("data")).get(0));
        assertEquals("0", typeZero.get("verify_type").toString());
        assertEquals("0", typeZero.get("type").toString());
        assertEquals(256, array(typeZero.get("command")).size());
        Map<String, Object> typeOne = object(
                array(object(StrictJson.parse(transport.requests.get(1).body()))
                        .get("data")).get(0));
        assertEquals("0", typeOne.get("verify_type").toString());
        assertEquals("1", typeOne.get("type").toString());
    }

    @Test
    public void installationVerifyRejectsInvalidInputBeforeTransport() {
        RecordingTransport transport = new RecordingTransport(emptyFixture());
        ProductionBusinessService service = service(transport);
        SessionToken session = SessionToken.of("installer");
        SignalRecord command = new SignalRecord("01", "AA", "BB");
        List<SignalRecord> valid = Arrays.asList(command);

        assertFailure(service.installationVerify(
                null, 9, 5, 0, valid, new CallToken()),
                ServerFailure.Kind.CONFIGURATION);
        assertFailure(service.installationVerify(
                session, 0, 5, 0, valid, new CallToken()),
                ServerFailure.Kind.CONFIGURATION);
        assertFailure(service.installationVerify(
                session, 9, 0, 0, valid, new CallToken()),
                ServerFailure.Kind.CONFIGURATION);
        assertFailure(service.installationVerify(
                session, 9, 5, -1, valid, new CallToken()),
                ServerFailure.Kind.CONFIGURATION);
        assertFailure(service.installationVerify(
                session, 9, 5, 2, valid, new CallToken()),
                ServerFailure.Kind.CONFIGURATION);
        assertFailure(service.installationVerify(
                session, 9, 5, 0, null, new CallToken()),
                ServerFailure.Kind.CONFIGURATION);
        assertFailure(service.installationVerify(
                session, 9, 5, 0, Collections.<SignalRecord>emptyList(), new CallToken()),
                ServerFailure.Kind.CONFIGURATION);
        assertFailure(service.installationVerify(
                session, 9, 5, 0, Collections.nCopies(257, command), new CallToken()),
                ServerFailure.Kind.CONFIGURATION);
        assertFailure(service.installationVerify(
                session, 9, 5, 0, Arrays.asList(command, null), new CallToken()),
                ServerFailure.Kind.CONFIGURATION);
        assertFailure(service.installationVerify(
                session, 9, 5, 0, valid, null),
                ServerFailure.Kind.CONFIGURATION);

        assertEquals(0, transport.requests.size());
    }

    @Test
    public void installationVerifyHonorsCancellationAndNeverRetriesMutation() {
        RecordingTransport transport = new RecordingTransport(emptyFixture());
        ProductionBusinessService service = service(transport);
        CallToken cancelled = new CallToken();
        cancelled.cancel(CallToken.Reason.CANCELLED);

        assertFailure(service.installationVerify(SessionToken.of("installer"),
                9, 5, 0, Arrays.asList(new SignalRecord("01", "AA", "BB")), cancelled),
                ServerFailure.Kind.CANCELLED);
        assertEquals(0, transport.requests.size());

        transport.failures.add(ApiResult.<TransportResponse>failure(
                ServerFailure.of(ServerFailure.Kind.HTTP)));
        assertFailure(service.installationVerify(SessionToken.of("installer"),
                9, 5, 0, Arrays.asList(new SignalRecord("01", "AA", "BB")),
                new CallToken()), ServerFailure.Kind.HTTP);
        assertEquals(1, transport.requests.size());
    }

    @Test
    public void installationVerifyAcceptsOnlyConfirmedEmptyArraySuccess() {
        RecordingTransport transport = new RecordingTransport(
                emptyFixture(), emptyObjectFixture(),
                "{\"code\":200,\"message\":\"ok\",\"data\":[{"
                        + "\"storey\":2,\"number\":\"8\"}]}");
        ProductionBusinessService service = service(transport);
        SessionToken session = SessionToken.of("installer");
        List<SignalRecord> commands = Arrays.asList(new SignalRecord("01", "AA", "BB"));

        assertTrue(service.installationVerify(
                session, 9, 5, 0, commands, new CallToken()).isSuccess());
        assertFailure(service.installationVerify(
                session, 9, 5, 0, commands, new CallToken()),
                ServerFailure.Kind.CONTRACT);
        assertFailure(service.installationVerify(
                session, 9, 5, 0, commands, new CallToken()),
                ServerFailure.Kind.CONTRACT);
        assertEquals(3, transport.requests.size());
    }

    @Test
    public void palmBindingRequiresAnExplicitConfiguredKeywordProvider() {
        RecordingTransport blockedTransport = new RecordingTransport();
        ProductionBusinessService blocked = service(blockedTransport);
        assertFailure(blocked.bindUserHand(
                user(), PalmKeywordProvider.unconfigured(), new CallToken()),
                ServerFailure.Kind.CONFIGURATION);
        assertEquals(0, blockedTransport.requests.size());

        RecordingTransport transport = new RecordingTransport(emptyObjectFixture());
        ProductionBusinessService service = service(transport);
        ApiResult<EmptyBusinessResult> result = service.bindUserHand(
                user(), new PalmKeywordProvider() {
                    @Override public String keyword() { return "opaque-palm-id"; }
                }, new CallToken());
        assertTrue(result.isSuccess());
        assertRequest(transport.onlyRequest(), BusinessEndpoint.BIND_USER_HAND,
                "session", "hand_person_id", "opaque-palm-id");
    }

    @Test
    public void cancellationFailureAndClosedKeyNeverIssueOrRetryMutation() {
        RecordingTransport transport = new RecordingTransport(emptyFixture());
        ProductionBusinessService service = service(transport);
        CallToken cancelled = new CallToken();
        cancelled.cancel(CallToken.Reason.CANCELLED);
        assertFailure(service.openBoard(user(), BoardAction.RETURN, 7, cancelled),
                ServerFailure.Kind.CANCELLED);
        assertEquals(0, transport.requests.size());

        transport.failures.add(ApiResult.<TransportResponse>failure(
                ServerFailure.of(ServerFailure.Kind.HTTP)));
        assertFailure(service.openBoard(user(), BoardAction.OPEN, 7, new CallToken()),
                ServerFailure.Kind.HTTP);
        assertEquals(1, transport.requests.size());

        service.close();
        assertFailure(service.authenticate(UserInfoRequest.card("001"), new CallToken()),
                ServerFailure.Kind.CONFIGURATION);
        assertEquals(1, transport.requests.size());
    }

    private static ProductionBusinessService service(RecordingTransport transport) {
        return new ProductionBusinessService(transport, new ProtocolClock() {
            @Override public ProtocolTimestamp now() {
                return ProtocolTimestamp.fromEpochMillis(
                        1788681600000L, TimeZone.getTimeZone("Asia/Shanghai"));
            }
        }, registration(), "test-key".toCharArray());
    }

    private static DeviceRegistration registration() {
        return new CheckDeviceResponseParser().parse(
                "{\"code\":200,\"message\":\"ok\",\"data\":{"
                        + "\"device_no\":17,\"merchant_code\":\"MERCHANT\"}}" ).value();
    }

    private static AuthenticatedUser user() {
        return new AuthenticatedUser(
                com.codex.lockertest.business.SessionToken.of("session"),
                com.codex.lockertest.business.UserType.USER, 0, 9);
    }

    private static void assertPreviewObjectRequest(TransportRequest request) {
        assertEquals(BusinessEndpoint.CONTROL_PANEL_PREVIEW, request.businessEndpoint());
        assertEquals("/v2/central_control_screen/controlPanelPreview", request.endpointPath());
        assertEquals("MERCHANT", request.headers().asMap().get(BootstrapHeaders.MERCHANT_AUTH));
        assertEquals("17", request.headers().asMap().get(BootstrapHeaders.DEVICE_NO));
        Map<String, Object> envelope = object(StrictJson.parse(request.body()));
        assertTrue("Preview data must be a JSON object, not an array",
                envelope.get("data") instanceof Map);
        // Literal fixture independently calculated with standard MD5/SHA-1 hash tools.
        assertEquals("{\"scode\":\"55d8638234f8188aa35548ea2da97532\","
                        + "\"sign\":\"25b655fff64512f448c7e7811ed45f34\","
                        + "\"timestamp\":1788681600,\"token\":\"session\","
                        + "\"data\":{\"type\":2,\"area_id\":9,\"page\":1}}",
                request.body());
    }

    private static void assertSignedObjectRequest(TransportRequest request,
            BusinessEndpoint endpoint, String expectedScode, String expectedSign,
            String expectedData) {
        assertEquals(endpoint, request.businessEndpoint());
        assertEquals(endpoint.path(), request.endpointPath());
        assertEquals("MERCHANT", request.headers().asMap().get(BootstrapHeaders.MERCHANT_AUTH));
        assertEquals("17", request.headers().asMap().get(BootstrapHeaders.DEVICE_NO));
        Map<String, Object> envelope = object(StrictJson.parse(request.body()));
        assertEquals(4, envelope.size());
        assertFalse(envelope.containsKey("token"));
        assertTrue("Login data must be a JSON object, not an array",
                envelope.get("data") instanceof Map);
        // These literal fixtures were calculated independently with standard hash tools.
        assertEquals("{\"scode\":\"" + expectedScode + "\",\"sign\":\"" + expectedSign
                        + "\",\"timestamp\":1788681600,\"data\":" + expectedData + "}",
                request.body());
    }

    private static void assertSignedAuthenticatedObjectRequest(TransportRequest request,
            BusinessEndpoint endpoint, String expectedScode, String expectedSign,
            String expectedData) {
        assertEquals(endpoint, request.businessEndpoint());
        assertEquals(endpoint.path(), request.endpointPath());
        assertEquals("MERCHANT", request.headers().asMap().get(BootstrapHeaders.MERCHANT_AUTH));
        assertEquals("17", request.headers().asMap().get(BootstrapHeaders.DEVICE_NO));
        Map<String, Object> envelope = object(StrictJson.parse(request.body()));
        assertEquals(5, envelope.size());
        assertEquals("session", envelope.get("token"));
        assertTrue(endpoint + " data must expose its fields directly as a JSON object",
                envelope.get("data") instanceof Map);
        // Literal fixtures independently calculated with standard MD5/SHA-1 hash tools.
        assertEquals("{\"scode\":\"" + expectedScode + "\",\"sign\":\"" + expectedSign
                        + "\",\"timestamp\":1788681600,\"token\":\"session\",\"data\":"
                        + expectedData + "}",
                request.body());
    }

    private static void assertRequest(TransportRequest request, BusinessEndpoint endpoint,
            String token, String... pairs) {
        assertEquals(endpoint, request.businessEndpoint());
        Map<String, Object> root = object(StrictJson.parse(request.body()));
        assertEquals(token, root.get("token"));
        List<Object> data = array(root.get("data"));
        if (pairs.length == 0) {
            assertTrue(data.isEmpty());
            return;
        }
        Map<String, Object> fields = object(data.get(0));
        assertEquals(pairs.length / 2, fields.size());
        for (int index = 0; index < pairs.length; index += 2) {
            assertEquals(pairs[index + 1], fields.get(pairs[index]).toString());
        }
    }

    private static String authFixture() {
        return "{\"code\":200,\"message\":\"ok\",\"data\":{"
                + "\"token\":\"session\",\"user_type\":0,"
                + "\"locker_check_status\":0,\"uid\":9}}";
    }

    private static String previewFixture() {
        return "{\"code\":200,\"message\":\"ok\",\"data\":{"
                + "\"page\":[1],\"all_open_command\":[],\"0\":[]}}";
    }

    private static String usedFixture() {
        return "{\"code\":200,\"message\":\"ok\",\"data\":{"
                + "\"user_name\":\"U\",\"mobile\":\"00123\",\"list\":[]}}";
    }

    private static String assignedFixture() {
        return "{\"code\":200,\"message\":\"ok\",\"data\":{\"fc_id\":88}}";
    }

    private static String emptyFixture() {
        return "{\"code\":200,\"message\":\"ok\",\"data\":[]}";
    }

    private static String emptyObjectFixture() {
        return "{\"code\":200,\"message\":\"ok\",\"data\":{}}";
    }

    private static String adminFixture() {
        return "{\"code\":200,\"message\":\"ok\",\"data\":{"
                + "\"token\":\"admin\",\"venue_name\":\"Venue\","
                + "\"device_name\":\"Device\",\"device_serial\":\"Serial\","
                + "\"area_name\":\"Area\",\"username\":\"operator\","
                + "\"name\":\"Name\"}}";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> array(Object value) {
        return (List<Object>) value;
    }

    private static void assertFailure(ApiResult<?> result, ServerFailure.Kind kind) {
        assertFalse(result.isSuccess());
        assertEquals(kind, result.failure().kind());
    }

    private static final class RecordingTransport implements ServerTransport {
        final Deque<ApiResult<TransportResponse>> responses = new ArrayDeque<>();
        final Deque<ApiResult<TransportResponse>> failures = new ArrayDeque<>();
        final java.util.ArrayList<TransportRequest> requests = new java.util.ArrayList<>();

        RecordingTransport(String... bodies) {
            for (String body : bodies) responses.add(ApiResult.success(new TransportResponse(200, body)));
        }

        @Override public ApiResult<TransportResponse> execute(TransportRequest request) {
            requests.add(request);
            if (!failures.isEmpty()) return failures.removeFirst();
            return responses.removeFirst();
        }

        TransportRequest onlyRequest() {
            assertEquals(1, requests.size());
            return requests.get(0);
        }
    }

    private static final class UnavailableService implements BusinessService {
        @Override public ApiResult<AuthenticatedUser> authenticate(
                UserInfoRequest request, CallToken token) { return null; }

        @Override public ApiResult<AuthenticatedUser> verifyMemberDynamicCode(
                AuthenticatedUser user, String code, CallToken token) { return null; }

        @Override public ApiResult<com.codex.lockertest.business.ControlPanelPreview>
                controlPanelPreview(AuthenticatedUser user, int type, long areaId,
                int page, CallToken token) { return null; }

        @Override public ApiResult<com.codex.lockertest.business.UsedCabinetList>
                useCabinetList(AuthenticatedUser user, CallToken token) { return null; }

        @Override public ApiResult<AssignedCabinet> userBoard(
                AuthenticatedUser user, long areaId, long fcId, CallToken token) {
            return null;
        }

        @Override public ApiResult<EmptyBusinessResult> openBoard(
                AuthenticatedUser user, BoardAction action, long fcId, CallToken token) {
            return null;
        }
    }
}
