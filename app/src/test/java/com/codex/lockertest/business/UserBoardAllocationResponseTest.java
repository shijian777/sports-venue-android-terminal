package com.codex.lockertest.business;

import com.codex.lockertest.server.ApiResult;
import com.codex.lockertest.server.ServerFailure;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class UserBoardAllocationResponseTest {
    @Test
    public void acceptsDocumentedCabinetIdentityShape() {
        assertAssigned(17L, response("\"fc_id\":17"));
    }

    @Test
    public void acceptsBoundedOptionalOrderMetadataWithoutChangingCabinetIdentity() {
        assertAssigned(4L, response("\"fc_id\":4,\"order_id\":1"));
        assertAssigned(4L, response("\"order_id\":0,\"fc_id\":4"));
        assertAssigned(4L, response(
                "\"fc_id\":4,\"order_id\":9223372036854775807"));
    }

    @Test
    public void rejectsMalformedRequiredCabinetIdentityEvenWithOrderMetadata() {
        String[] invalidFcIds = {
                "0", "-1", "1.0", "1e0", "\"4\"", "true", "null", "[]", "{}"
        };
        for (String invalidFcId : invalidFcIds) {
            assertContract(response("\"fc_id\":" + invalidFcId + ",\"order_id\":1"));
        }
        assertContract(response("\"order_id\":1"));
    }

    @Test
    public void rejectsUnverifiedOrderMetadataTypesValuesAndUnknownFields() {
        String[] invalidOrderIds = {
                "-1", "1.0", "1e0", "\"1\"", "true", "null", "[]", "{}",
                "9223372036854775808"
        };
        for (String invalidOrderId : invalidOrderIds) {
            assertContract(response("\"fc_id\":4,\"order_id\":" + invalidOrderId));
        }
        assertContract(response("\"fc_id\":4,\"order_id\":1,\"extra\":0"));
        assertContract(response("\"fc_id\":4,\"extra\":0"));
    }

    private static String response(String fields) {
        return "{\"code\":200,\"message\":\"success\",\"data\":{" + fields + "}}";
    }

    private static void assertAssigned(long expectedFcId, String response) {
        ApiResult<AssignedCabinet> result = BusinessResponseParsers.userBoard(response);
        assertTrue(response, result.isSuccess());
        assertEquals(expectedFcId, result.value().fcId());
    }

    private static void assertContract(String response) {
        ApiResult<AssignedCabinet> result = BusinessResponseParsers.userBoard(response);
        assertFalse(response, result.isSuccess());
        assertEquals(ServerFailure.Kind.CONTRACT, result.failure().kind());
    }
}
