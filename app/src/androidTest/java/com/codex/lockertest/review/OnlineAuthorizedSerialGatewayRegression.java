package com.codex.lockertest.review;

import com.codex.lockertest.integration.OnlineUnlockDispatchPermit;
import com.codex.lockertest.model.LockerTarget;
import com.codex.lockertest.model.LockerZone;
import com.codex.lockertest.protocol.FeedbackPolarity;
import com.codex.lockertest.serial.SerialGateway;
import com.codex.lockertest.unlock.AuthorizedUnlockRequest;

/** Exercises the Android-dependent gateway without opening or discovering a physical port. */
final class OnlineAuthorizedSerialGatewayRegression {
    private static final byte[] A1_UNLOCK = hex(0x8A, 0x01, 0x01, 0x11, 0x9B);

    private OnlineAuthorizedSerialGatewayRegression() {
    }

    static void check() throws Throwable {
        SerialGateway gateway = new SerialGateway();
        OnlineUnlockDispatchPermit permit = permit();

        require(!gateway.sendAuthorized(A1_UNLOCK, permit, () -> true),
                "Closed gateway accepted an authorized send");

        int[] writes = {0};
        require(permit.write(A1_UNLOCK, () -> true, () -> writes[0]++),
                "Closed gateway consumed the refused authorization");
        require(writes[0] == 1, "Permit did not perform the later exact write once");
        require(!permit.write(A1_UNLOCK, () -> true, () -> writes[0]++),
                "Permit allowed a duplicate write");
        require(writes[0] == 1, "Duplicate permit use reached the writer");
    }

    private static OnlineUnlockDispatchPermit permit() {
        LockerTarget target = new LockerTarget(
                LockerZone.A,
                1,
                1,
                FeedbackPolarity.SHORT_WHEN_LOCKED);
        return new OnlineUnlockDispatchPermit(new AuthorizedUnlockRequest(
                73L,
                target,
                A1_UNLOCK,
                hex(0x8A, 0x01, 0x01, 0x00, 0x8A),
                A1_UNLOCK));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static byte[] hex(int... values) {
        byte[] result = new byte[values.length];
        for (int index = 0; index < values.length; index++) {
            result[index] = (byte) values[index];
        }
        return result;
    }
}
