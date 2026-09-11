package com.codex.lockertest.server;

import com.codex.lockertest.bootstrap.CheckDeviceResponseParser;
import com.codex.lockertest.business.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** The server token is obtained by login and then copied unchanged into query envelopes. */
public final class ProductionTokenFlowTest {
    @Test public void successfulLoginCarriesReturnedTokenAndParametersToBothListEndpoints() {
        assertLoginCarriesTokenToBothLists("locker_check_status");
    }
    @Test public void renamedMemberFlagCarriesReturnedTokenAndParametersToBothListEndpoints() {
        assertLoginCarriesTokenToBothLists("dynamic_verification_code");
    }
    private static void assertLoginCarriesTokenToBothLists(String flagField) {
        List<TransportRequest> requests = new ArrayList<>();
        ProductionBusinessService service = service(request -> {
            requests.add(request);
            String body = requests.size() == 1 ? login("\"server-session-fixture\"")
                    .replace("locker_check_status", flagField)
                    : "{\"code\":403,\"message\":\"test rejection\",\"data\":[]}";
            return ApiResult.success(new TransportResponse(200, body));
        });
        try {
            ApiResult<AuthenticatedUser> login = service.authenticate(UserInfoRequest.card("card-fixture"), new CallToken());
            assertTrue(login.isSuccess());
            assertFalse(json(requests.get(0)).containsKey("token"));
            service.controlPanelPreview(login.value(), 2, 9, 1, new CallToken());
            service.useCabinetList(login.value(), new CallToken());
            assertEquals(3, requests.size());
            Map<?,?> preview = json(requests.get(1));
            assertEquals("server-session-fixture", preview.get("token"));
            Map<?,?> data = (Map<?,?>) preview.get("data");
            assertEquals("2", data.get("type").toString());
            assertEquals("9", data.get("area_id").toString());
            assertEquals("1", data.get("page").toString());
            assertEquals("server-session-fixture", json(requests.get(2)).get("token"));
        } finally { service.close(); }
    }
    @Test public void emptyNullWhitespaceOrMissingLoginTokenNeverCreatesAuthenticatedIdentity() {
        for (String token : Arrays.asList("\"\"", "null", "\"   \"", "\"\\t\"")) {
            assertFalse(BusinessResponseParsers.userInfo(login(token)).isSuccess());
        }
        assertFalse(BusinessResponseParsers.userInfo(login("\"fixture\"")
                .replace("\"token\":\"fixture\",", "")).isSuccess());
    }
    @Test public void missingAuthenticatedSessionIsRejectedBeforeAnyQueryIsSent() {
        int[] requests = {0};
        ProductionBusinessService service = service(request -> { requests[0]++; throw new AssertionError("No request expected"); });
        try {
            assertFalse(service.controlPanelPreview((AuthenticatedUser)null, 2, 9, 1, new CallToken()).isSuccess());
            assertFalse(service.controlPanelPreview((SessionToken)null, 2, 9, 1, new CallToken()).isSuccess());
            assertFalse(service.useCabinetList(null, new CallToken()).isSuccess());
            assertEquals(0, requests[0]);
        } finally { service.close(); }
    }
    private static ProductionBusinessService service(ServerTransport transport) {
        return new ProductionBusinessService(transport,
                () -> ProtocolTimestamp.fromEpochMillis(1788681600000L, TimeZone.getTimeZone("Asia/Shanghai")),
                new CheckDeviceResponseParser().parse("{\"code\":200,\"message\":\"ok\",\"data\":{\"device_no\":17,\"merchant_code\":\"fixture\"}}").value(),
                "test-key".toCharArray());
    }
    private static String login(String token) {
        return "{\"code\":200,\"message\":\"ok\",\"data\":{\"token\":" + token
                + ",\"user_type\":0,\"locker_check_status\":0,\"uid\":9}}";
    }
    private static Map<?,?> json(TransportRequest request) { return (Map<?,?>)StrictJson.parse(request.body()); }
}
