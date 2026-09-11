package com.codex.lockertest.runtime;

import com.codex.lockertest.model.LockerTarget;
import com.codex.lockertest.model.LockerZone;
import com.codex.lockertest.model.UnlockMethod;
import com.codex.lockertest.protocol.FeedbackPolarity;
import com.codex.lockertest.unlock.AuthorizedUnlockRequest;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public final class RuntimeContractTest {
    @Test
    public void rejectedAdmissionNeverExposesCredential() {
        CredentialAdmission result = CredentialAdmission.rejected("服务器认证尚未配置");

        assertFalse(result.accepted());
        assertNull(result.method());
        assertEquals("服务器认证尚未配置", result.message());
    }

    @Test
    public void acceptedAdmissionExposesOnlyTheRequestedMethod() {
        CredentialAdmission result = CredentialAdmission.accepted(UnlockMethod.QR);

        assertTrue(result.accepted());
        assertEquals(UnlockMethod.QR, result.method());
        assertNull(result.message());
    }

    @Test
    public void unavailableUnlockHasNoSerialAuthority() {
        CustomerUnlockAuthorization result =
                CustomerUnlockAuthorization.unavailable("服务器开柜协议未配置");

        assertFalse(result.authorized());
        assertNull(result.request());
        assertEquals("服务器开柜协议未配置", result.message());
    }

    @Test
    public void authorizedUnlockReturnsIndependentSerialAuthoritySnapshots() {
        AuthorizedUnlockRequest request = request();
        CustomerUnlockAuthorization result = CustomerUnlockAuthorization.authorized(request);

        AuthorizedUnlockRequest first = result.request();
        AuthorizedUnlockRequest second = result.request();
        first.unlockCommand()[0] = 0;

        assertTrue(result.authorized());
        assertNull(result.message());
        assertNotSame(request, first);
        assertNotSame(first, second);
        assertArrayEquals(command(), second.unlockCommand());
    }

    @Test
    public void runtimeServicesRetainsSuppliedDependencies() {
        CredentialAdmissionPolicy credentialPolicy = (method, credential) ->
                CredentialAdmission.rejected("not configured");
        AdminCredentialPolicy adminPolicy = new AdminCredentialPolicy() {
            @Override public int requiredLength() { return 0; }
            @Override public boolean matches(char[] candidate) { return false; }
            @Override public String unavailableMessage() { return "not configured"; }
        };
        CustomerUnlockAuthorizer unlockAuthorizer = (method, credential, faceResult, now,
                requestId, deviceBinding, processBinding, environment, target) ->
                CustomerUnlockAuthorization.unavailable("not configured");
        InitialLayoutPolicy layoutPolicy = new InitialLayoutPolicy() {
            @Override public com.codex.lockertest.layout.LockerLayoutSnapshot initialLayout() {
                return null;
            }
            @Override public com.codex.lockertest.layout.LockerLayoutSnapshot layoutForDiscovery(
                    java.util.List<LockerZone> onlineZones) {
                return null;
            }
        };
        com.codex.lockertest.model.FeatureAvailability availability = method -> false;

        RuntimeServices services = new RuntimeServices(credentialPolicy, adminPolicy,
                unlockAuthorizer, layoutPolicy, availability, true);

        assertSame(credentialPolicy, services.credentialPolicy());
        assertSame(adminPolicy, services.adminPolicy());
        assertSame(unlockAuthorizer, services.unlockAuthorizer());
        assertSame(layoutPolicy, services.layoutPolicy());
        assertSame(availability, services.featureAvailability());
        assertTrue(services.localDemo());
    }

    @Test
    public void factoriesAndServicesRejectMissingRequiredInputs() {
        assertIllegalArgument(() -> CredentialAdmission.accepted(null));
        assertIllegalArgument(() -> CredentialAdmission.rejected(" "));
        assertIllegalArgument(() -> CustomerUnlockAuthorization.authorized(null));
        assertIllegalArgument(() -> CustomerUnlockAuthorization.unavailable(" "));
        assertIllegalArgument(() -> new RuntimeServices(null, adminPolicy(), authorizer(),
                layoutPolicy(), method -> false, false));
    }

    private static AuthorizedUnlockRequest request() {
        return new AuthorizedUnlockRequest(91L, target(), command(), success(), failure());
    }

    private static LockerTarget target() {
        return new LockerTarget(LockerZone.B, 2, 2, FeedbackPolarity.SHORT_WHEN_LOCKED);
    }

    private static byte[] command() { return bytes(0x8A, 0x02, 0x02, 0x11, 0x9B); }
    private static byte[] success() { return bytes(0x8A, 0x02, 0x02, 0x00, 0x8A); }
    private static byte[] failure() { return bytes(0x8A, 0x02, 0x02, 0x11, 0x9B); }

    private static AdminCredentialPolicy adminPolicy() {
        return new AdminCredentialPolicy() {
            @Override public int requiredLength() { return 0; }
            @Override public boolean matches(char[] candidate) { return false; }
            @Override public String unavailableMessage() { return "not configured"; }
        };
    }

    private static CustomerUnlockAuthorizer authorizer() {
        return (method, credential, faceResult, now, requestId, deviceBinding, processBinding,
                environment, target) -> CustomerUnlockAuthorization.unavailable("not configured");
    }

    private static InitialLayoutPolicy layoutPolicy() {
        return new InitialLayoutPolicy() {
            @Override public com.codex.lockertest.layout.LockerLayoutSnapshot initialLayout() {
                return null;
            }
            @Override public com.codex.lockertest.layout.LockerLayoutSnapshot layoutForDiscovery(
                    java.util.List<LockerZone> onlineZones) {
                return null;
            }
        };
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int index = 0; index < values.length; index++) {
            result[index] = (byte) values[index];
        }
        return result;
    }

    private static void assertIllegalArgument(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Expected IllegalArgumentException");
    }
}
