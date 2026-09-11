package com.codex.lockertest.returnflow;

import com.codex.lockertest.model.LockerTarget;
import com.codex.lockertest.model.LockerZone;
import com.codex.lockertest.model.UnlockMethod;
import com.codex.lockertest.protocol.FeedbackPolarity;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ReturnAuthorizationTest {
    @Test
    public void identityKeepsCredentialOpaqueInItsTextRepresentation() {
        ReturnIdentity identity = new ReturnIdentity(UnlockMethod.ID_CARD, "secret-card", 25L);

        assertTrue(identity.toString().indexOf("secret-card") < 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void authorizationRejectsAWellFormedCommandForAnotherLocker() {
        new ReturnAuthorization(
                91L,
                new ReturnLocker("server-7", "A-07", "Lobby", target()),
                new byte[] {(byte) 0x8A, 1, 6, 0x11, (byte) 0x9C},
                new byte[] {(byte) 0x8A, 1, 7, 0, (byte) 0x8C},
                new byte[] {(byte) 0x8A, 1, 7, 0x11, (byte) 0x9D},
                "completion-91",
                1_000L);
    }

    @Test
    public void authorizationRejectsMalformedChecksumAndWrongPolarityFrames() {
        ReturnLocker locker = new ReturnLocker("server-7", "A-07", "Lobby", target());

        assertInvalid(0L, locker, unlock7(), success7(), failure7());
        assertInvalid(1L, locker, new byte[] {(byte) 0x8A}, success7(), failure7());
        assertInvalid(1L, locker, unlock7(),
                new byte[] {(byte) 0x8A, 1, 7, 0x11, (byte) 0x9D}, failure7());
        assertInvalid(1L, locker, unlock7(), success7(),
                new byte[] {(byte) 0x8A, 1, 7, 0, (byte) 0x8C});
        assertInvalid(1L, locker,
                new byte[] {(byte) 0x8A, 1, 7, 0x11, 0}, success7(), failure7());
    }

    @Test
    public void identityLockerAndAuthorizationConstructorsRejectMissingOrUnsafeValues() {
        assertInvalid(new Runnable() {
            @Override public void run() {
                new ReturnIdentity(null, "identity", 0L);
            }
        });
        assertInvalid(new Runnable() {
            @Override public void run() {
                new ReturnIdentity(UnlockMethod.ID_CARD, "  ", 0L);
            }
        });
        assertInvalid(new Runnable() {
            @Override public void run() {
                new ReturnIdentity(UnlockMethod.ID_CARD, "identity", -1L);
            }
        });
        assertInvalid(new Runnable() {
            @Override public void run() {
                new ReturnLocker(" ", "A-07", "Lobby", target());
            }
        });
        assertInvalid(new Runnable() {
            @Override public void run() {
                new ReturnLocker("server-7", "A-07", "Lobby", null);
            }
        });
        assertInvalid(new Runnable() {
            @Override public void run() {
                new ReturnAuthorization(1L, new ReturnLocker("server-7", "A-07", "Lobby", target()),
                        unlock7(), success7(), failure7(), " ", 1L);
            }
        });
    }

    @Test
    public void authorizationRejectsANullLocker() {
        assertInvalid(new Runnable() {
            @Override public void run() {
                new ReturnAuthorization(1L, null, unlock7(), success7(), failure7(), "token", 1L);
            }
        });
    }

    @Test
    public void authorizationRejectsEachNullProtocolFrame() {
        final ReturnLocker locker = new ReturnLocker("server-7", "A-07", "Lobby", target());
        assertInvalid(new Runnable() {
            @Override public void run() {
                new ReturnAuthorization(1L, locker, null, success7(), failure7(), "token", 1L);
            }
        });
        assertInvalid(new Runnable() {
            @Override public void run() {
                new ReturnAuthorization(1L, locker, unlock7(), null, failure7(), "token", 1L);
            }
        });
        assertInvalid(new Runnable() {
            @Override public void run() {
                new ReturnAuthorization(1L, locker, unlock7(), success7(), null, "token", 1L);
            }
        });
    }

    @Test
    public void authorizationRejectsNonpositiveExpiry() {
        final ReturnLocker locker = new ReturnLocker("server-7", "A-07", "Lobby", target());
        assertInvalid(new Runnable() {
            @Override public void run() {
                new ReturnAuthorization(1L, locker, unlock7(), success7(), failure7(), "token", 0L);
            }
        });
    }

    @Test
    public void lockerRejectsBlankDisplayLabelAndArea() {
        assertInvalid(new Runnable() {
            @Override public void run() {
                new ReturnLocker("server-7", " ", "Lobby", target());
            }
        });
        assertInvalid(new Runnable() {
            @Override public void run() {
                new ReturnLocker("server-7", "A-07", " ", target());
            }
        });
    }

    @Test
    public void authorizationCopiesFramesAndExpiresAtItsBoundary() {
        byte[] unlock = unlock7();
        byte[] success = success7();
        byte[] failure = failure7();
        ReturnAuthorization authorization = new ReturnAuthorization(
                91L,
                new ReturnLocker("server-7", "A-07", "Lobby", target()),
                unlock,
                success,
                failure,
                "completion-91",
                1_000L);

        unlock[0] = 9;
        success[0] = 9;
        failure[0] = 9;

        assertArrayEquals(new byte[] {(byte) 0x8A, 1, 7, 0x11, (byte) 0x9D}, authorization.unlockCommand());
        assertArrayEquals(new byte[] {(byte) 0x8A, 1, 7, 0, (byte) 0x8C}, authorization.expectedSuccessFrame());
        assertArrayEquals(new byte[] {(byte) 0x8A, 1, 7, 0x11, (byte) 0x9D}, authorization.expectedFailureFrame());
        byte[] returnedCommand = authorization.unlockCommand();
        returnedCommand[0] = 8;
        assertArrayEquals(new byte[] {(byte) 0x8A, 1, 7, 0x11, (byte) 0x9D}, authorization.unlockCommand());
        assertFalse(authorization.isExpiredAt(999L));
        assertTrue(authorization.isExpiredAt(1_000L));
    }

    private static LockerTarget target() {
        return new LockerTarget(
                LockerZone.A,
                LockerZone.A.boardAddress(),
                7,
                FeedbackPolarity.SHORT_WHEN_LOCKED);
    }

    private static void assertInvalid(long operationId, ReturnLocker locker,
            byte[] unlock, byte[] success, byte[] failure) {
        try {
            new ReturnAuthorization(operationId, locker, unlock, success, failure,
                    "completion-91", 1_000L);
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Expected invalid authorization to be rejected");
    }

    private static void assertInvalid(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Expected invalid value to be rejected");
    }

    private static byte[] unlock7() {
        return new byte[] {(byte) 0x8A, 1, 7, 0x11, (byte) 0x9D};
    }

    private static byte[] success7() {
        return new byte[] {(byte) 0x8A, 1, 7, 0, (byte) 0x8C};
    }

    private static byte[] failure7() {
        return new byte[] {(byte) 0x8A, 1, 7, 0x11, (byte) 0x9D};
    }
}
