package com.codex.lockertest.ui;

import org.junit.Test;

import java.lang.reflect.Constructor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class CustomerActionBoundaryTest {
    @Test
    public void exposesOnlyTheRequiredFiniteCustomerEffectSet() {
        assertEquals(14, CustomerActionBoundary.Effect.values().length);
        assertEquals(CustomerActionBoundary.Effect.PHONE_CREDENTIAL,
                CustomerActionBoundary.Effect.valueOf("PHONE_CREDENTIAL"));
        assertEquals(CustomerActionBoundary.Effect.SCANNER_CREDENTIAL,
                CustomerActionBoundary.Effect.valueOf("SCANNER_CREDENTIAL"));
        assertEquals(CustomerActionBoundary.Effect.FACE,
                CustomerActionBoundary.Effect.valueOf("FACE"));
        assertEquals(CustomerActionBoundary.Effect.PALM,
                CustomerActionBoundary.Effect.valueOf("PALM"));
        assertEquals(CustomerActionBoundary.Effect.HOME_ENROLLMENT,
                CustomerActionBoundary.Effect.valueOf("HOME_ENROLLMENT"));
        assertEquals(CustomerActionBoundary.Effect.RETURN_JOURNEY,
                CustomerActionBoundary.Effect.valueOf("RETURN_JOURNEY"));
        assertEquals(CustomerActionBoundary.Effect.RETRY,
                CustomerActionBoundary.Effect.valueOf("RETRY"));
        assertEquals(CustomerActionBoundary.Effect.RESUME,
                CustomerActionBoundary.Effect.valueOf("RESUME"));
        assertEquals(CustomerActionBoundary.Effect.ASYNC_CALLBACK,
                CustomerActionBoundary.Effect.valueOf("ASYNC_CALLBACK"));
        assertEquals(CustomerActionBoundary.Effect.DISCOVERY,
                CustomerActionBoundary.Effect.valueOf("DISCOVERY"));
        assertEquals(CustomerActionBoundary.Effect.AUTHORIZATION,
                CustomerActionBoundary.Effect.valueOf("AUTHORIZATION"));
        assertEquals(CustomerActionBoundary.Effect.RETURN_SERVICE,
                CustomerActionBoundary.Effect.valueOf("RETURN_SERVICE"));
        assertEquals(CustomerActionBoundary.Effect.SERIAL_CONNECT,
                CustomerActionBoundary.Effect.valueOf("SERIAL_CONNECT"));
        assertEquals(CustomerActionBoundary.Effect.SERIAL_SEND,
                CustomerActionBoundary.Effect.valueOf("SERIAL_SEND"));
    }

    @Test
    public void everyUnavailableStateRejectsEveryEffectWithoutInvokingItsDelegate() {
        TerminalReadiness[] unavailable = {
                TerminalReadiness.readyReadOnly(),
                TerminalReadiness.serverConnecting(),
                TerminalReadiness.serverNotConfigured(),
                TerminalReadiness.serverContractUnapproved(),
                TerminalReadiness.deviceSerialUnavailable(),
                TerminalReadiness.deviceNotRegistered(),
                TerminalReadiness.serverRequestRejected(),
                TerminalReadiness.deviceClockInvalid(),
                TerminalReadiness.networkUnavailable(),
                TerminalReadiness.serverSecurityError(),
                TerminalReadiness.serverResponseInvalid(),
                TerminalReadiness.bootstrapStopped()
        };
        for (TerminalReadiness readiness : unavailable) {
            CustomerActionBoundary boundary = new CustomerActionBoundary(readiness);
            for (CustomerActionBoundary.Effect effect
                    : CustomerActionBoundary.Effect.values()) {
                int[] voidCalls = {0};
                assertFalse(boundary.run(effect, () -> voidCalls[0]++));
                assertEquals(0, voidCalls[0]);

                int[] valueCalls = {0};
                Object denied = new Object();
                assertSame(denied, boundary.call(effect, () -> {
                    valueCalls[0]++;
                    return new Object();
                }, denied));
                assertEquals(0, valueCalls[0]);
            }
        }
    }

    @Test
    public void readyLocalDemoInvokesEveryDelegateExactlyOnceAndReturnsItsValue() {
        CustomerActionBoundary boundary = new CustomerActionBoundary(
                TerminalReadiness.localDemoReady());
        for (CustomerActionBoundary.Effect effect
                : CustomerActionBoundary.Effect.values()) {
            int[] voidCalls = {0};
            assertTrue(boundary.run(effect, () -> voidCalls[0]++));
            assertEquals(1, voidCalls[0]);

            int[] valueCalls = {0};
            Object expected = new Object();
            assertSame(expected, boundary.call(effect, () -> {
                valueCalls[0]++;
                return expected;
            }, new Object()));
            assertEquals(1, valueCalls[0]);
        }
    }

    @Test
    public void requiresExplicitReadinessEffectAndDelegate() {
        expectIllegalArgument(() -> new CustomerActionBoundary(null));
        CustomerActionBoundary boundary = new CustomerActionBoundary(
                TerminalReadiness.localDemoReady());
        expectIllegalArgument(() -> boundary.run(null, () -> { }));
        expectIllegalArgument(() -> boundary.run(
                CustomerActionBoundary.Effect.FACE, null));
        expectIllegalArgument(() -> boundary.call(null, () -> "value", "denied"));
        expectIllegalArgument(() -> boundary.call(
                CustomerActionBoundary.Effect.FACE, null, "denied"));

        Constructor<?>[] constructors = CustomerActionBoundary.class
                .getDeclaredConstructors();
        assertEquals(1, constructors.length);
        assertEquals(1, constructors[0].getParameterTypes().length);
        assertEquals(TerminalReadinessSource.class,
                constructors[0].getParameterTypes()[0]);
    }

    @Test
    public void readyBoundaryPreservesRuntimeExceptionAndLinkageErrorIdentity() {
        CustomerActionBoundary boundary = new CustomerActionBoundary(
                TerminalReadiness.localDemoReady());
        RuntimeException runtime = new RuntimeException("runtime");
        try {
            boundary.run(CustomerActionBoundary.Effect.RETRY, () -> {
                throw runtime;
            });
            fail("runtime exception must escape");
        } catch (RuntimeException actual) {
            assertSame(runtime, actual);
        }

        LinkageError linkage = new LinkageError("linkage");
        try {
            boundary.call(CustomerActionBoundary.Effect.SERIAL_SEND, () -> {
                throw linkage;
            }, Boolean.FALSE);
            fail("linkage error must escape");
        } catch (LinkageError actual) {
            assertSame(linkage, actual);
        }
    }

    @Test
    public void readsMutableReadinessAtEveryEffectBoundary() {
        MutableTerminalReadinessSource source = new MutableTerminalReadinessSource(
                TerminalReadiness.localDemoReady());
        CustomerActionBoundary boundary = new CustomerActionBoundary(source);
        int[] calls = {0};

        assertTrue(boundary.run(CustomerActionBoundary.Effect.FACE, () -> calls[0]++));
        source.update(TerminalReadiness.readyReadOnly());
        assertFalse(boundary.run(CustomerActionBoundary.Effect.FACE, () -> calls[0]++));
        source.update(TerminalReadiness.serverNotConfigured());
        assertFalse(boundary.run(CustomerActionBoundary.Effect.SERIAL_SEND,
                () -> calls[0]++));
        assertEquals(1, calls[0]);
    }

    private static void expectIllegalArgument(Runnable action) {
        try {
            action.run();
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }
}
