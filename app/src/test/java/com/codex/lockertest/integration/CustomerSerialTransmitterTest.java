package com.codex.lockertest.integration;

import com.codex.lockertest.integration.CustomerSerialWritePolicy.CustomerSerialPhase;
import com.codex.lockertest.integration.CustomerSerialWritePolicy.Decision;
import com.codex.lockertest.model.LockerTarget;
import com.codex.lockertest.model.LockerZone;
import com.codex.lockertest.protocol.FeedbackPolarity;
import com.codex.lockertest.serial.SerialWriteAttribution;
import com.codex.lockertest.unlock.AuthorizedUnlockRequest;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class CustomerSerialTransmitterTest {
    private static final byte[] BOARD_ONE_QUERY =
            bytes(0x80, 0x01, 0x01, 0x33, 0xB3);

    @Test
    public void acceptedResultIsTypedAndAuthorizedAuditPrecedesOneWriterCall() {
        List<String> events = new ArrayList<>();
        List<byte[]> written = new ArrayList<>();
        AtomicReference<CustomerSerialPhase> writtenPhase = new AtomicReference<>();
        AtomicReference<Long> writtenOperationId = new AtomicReference<>();
        CustomerSerialTransmitter transmitter = transmitter(
                (phase, operationId, payload) -> {
                    events.add("writer");
                    writtenPhase.set(phase);
                    writtenOperationId.set(operationId);
                    written.add(Arrays.copyOf(payload, payload.length));
                    return true;
                },
                line -> events.add("audit:" + line));
        assertTrue(transmitter.beginOperation(
                17L, CustomerSerialPhase.LOCKER_DISCOVERY, null));

        CustomerSerialTransmitter.SendResult result = transmitter.send(
                CustomerSerialPhase.LOCKER_DISCOVERY,
                17L,
                SerialWriteAttribution.discovery(1),
                BOARD_ONE_QUERY);

        assertEquals(CustomerSerialTransmitter.Status.ACCEPTED, result.status());
        assertNull(result.policyDecision());
        assertEquals(Arrays.asList(
                "audit:Authorized origin=DISCOVERY phase=LOCKER_DISCOVERY "
                        + "operationId=17 HEX=80 01 01 33 B3",
                "writer"), events);
        assertEquals(1, written.size());
        assertArrayEquals(BOARD_ONE_QUERY, written.get(0));
        assertEquals(CustomerSerialPhase.LOCKER_DISCOVERY, writtenPhase.get());
        assertEquals(Long.valueOf(17L), writtenOperationId.get());
    }

    @Test
    public void policyRejectionReturnsExactDecisionAndCallsNeitherAuditNorWriter() {
        AtomicBoolean audited = new AtomicBoolean();
        AtomicBoolean written = new AtomicBoolean();
        CustomerSerialTransmitter transmitter = transmitter(
                (phase, operationId, payload) -> {
                    written.set(true);
                    return true;
                },
                line -> audited.set(true));
        assertTrue(transmitter.beginOperation(
                18L, CustomerSerialPhase.FACE_PRE_SELECTION, null));

        CustomerSerialTransmitter.SendResult result = transmitter.send(
                CustomerSerialPhase.FACE_PRE_SELECTION,
                18L,
                SerialWriteAttribution.discovery(1),
                BOARD_ONE_QUERY);

        assertEquals(CustomerSerialTransmitter.Status.POLICY_REJECTED, result.status());
        assertEquals(Decision.PAYLOAD_NOT_ALLOWED, result.policyDecision());
        assertFalse(audited.get());
        assertFalse(written.get());
    }

    @Test
    public void nullAndEmptyRequestsFailClosedBeforeAuditOrWriter() {
        RecordingWriter writer = new RecordingWriter();
        RecordingAudit audit = new RecordingAudit();
        CustomerSerialTransmitter transmitter = transmitter(writer, audit);
        assertTrue(transmitter.beginOperation(
                19L, CustomerSerialPhase.LOCKER_DISCOVERY, null));

        CustomerSerialTransmitter.SendResult nullAttribution = transmitter.send(
                CustomerSerialPhase.LOCKER_DISCOVERY, 19L, null, BOARD_ONE_QUERY);
        CustomerSerialTransmitter.SendResult nullPayload = transmitter.send(
                CustomerSerialPhase.LOCKER_DISCOVERY,
                19L,
                SerialWriteAttribution.discovery(1),
                null);
        CustomerSerialTransmitter.SendResult emptyPayload = transmitter.send(
                CustomerSerialPhase.LOCKER_DISCOVERY,
                19L,
                SerialWriteAttribution.discovery(1),
                new byte[0]);

        assertRejected(nullAttribution, Decision.INVALID_REQUEST);
        assertRejected(nullPayload, Decision.INVALID_REQUEST);
        assertRejected(emptyPayload, Decision.INVALID_REQUEST);
        assertEquals(0, audit.lines.size());
        assertEquals(0, writer.calls);
    }

    @Test
    public void auditFailureIsTypedStopsBeforeWriterAndConsumesTheWriteQuota() {
        RecordingWriter writer = new RecordingWriter();
        RecordingAudit audit = new RecordingAudit();
        audit.failure = new IllegalStateException("credential=must-not-escape");
        CustomerSerialTransmitter transmitter = transmitter(writer, audit);
        assertTrue(transmitter.beginOperation(
                20L, CustomerSerialPhase.LOCKER_DISCOVERY, null));

        CustomerSerialTransmitter.SendResult first = transmitter.send(
                CustomerSerialPhase.LOCKER_DISCOVERY,
                20L,
                SerialWriteAttribution.discovery(1),
                BOARD_ONE_QUERY);
        audit.failure = null;
        CustomerSerialTransmitter.SendResult replay = transmitter.send(
                CustomerSerialPhase.LOCKER_DISCOVERY,
                20L,
                SerialWriteAttribution.discovery(1),
                BOARD_ONE_QUERY);

        assertEquals(CustomerSerialTransmitter.Status.AUDIT_FAILED, first.status());
        assertNull(first.policyDecision());
        assertFalse(first.toString().contains("must-not-escape"));
        assertRejected(replay, Decision.DUPLICATE);
        assertEquals(1, audit.lines.size());
        assertEquals(0, writer.calls);
    }

    @Test
    public void writerFalseIsTypedNeverRetriesAndConsumesTheWriteQuota() {
        RecordingWriter writer = new RecordingWriter();
        writer.accept = false;
        RecordingAudit audit = new RecordingAudit();
        CustomerSerialTransmitter transmitter = transmitter(writer, audit);
        assertTrue(transmitter.beginOperation(
                21L, CustomerSerialPhase.LOCKER_DISCOVERY, null));

        CustomerSerialTransmitter.SendResult first = transmitter.send(
                CustomerSerialPhase.LOCKER_DISCOVERY,
                21L,
                SerialWriteAttribution.discovery(1),
                BOARD_ONE_QUERY);
        writer.accept = true;
        CustomerSerialTransmitter.SendResult replay = transmitter.send(
                CustomerSerialPhase.LOCKER_DISCOVERY,
                21L,
                SerialWriteAttribution.discovery(1),
                BOARD_ONE_QUERY);

        assertEquals(CustomerSerialTransmitter.Status.WRITER_REJECTED, first.status());
        assertNull(first.policyDecision());
        assertRejected(replay, Decision.DUPLICATE);
        assertEquals(1, audit.lines.size());
        assertEquals(1, writer.calls);
    }

    @Test
    public void writerExceptionIsTypedNeverRetriesAndDoesNotExposeItsMessage() {
        RecordingWriter writer = new RecordingWriter();
        writer.failure = new IllegalStateException("photo=/private/customer.jpg");
        RecordingAudit audit = new RecordingAudit();
        CustomerSerialTransmitter transmitter = transmitter(writer, audit);
        assertTrue(transmitter.beginOperation(
                22L, CustomerSerialPhase.LOCKER_DISCOVERY, null));

        CustomerSerialTransmitter.SendResult result = transmitter.send(
                CustomerSerialPhase.LOCKER_DISCOVERY,
                22L,
                SerialWriteAttribution.discovery(1),
                BOARD_ONE_QUERY);

        assertEquals(CustomerSerialTransmitter.Status.WRITER_FAILED, result.status());
        assertNull(result.policyDecision());
        assertFalse(result.toString().contains("customer.jpg"));
        assertEquals(1, audit.lines.size());
        assertEquals(1, writer.calls);
    }

    @Test
    public void writerReceivesAnIsolatedCopyAndCannotMutateTheCallerArray() {
        byte[] callerPayload = Arrays.copyOf(BOARD_ONE_QUERY, BOARD_ONE_QUERY.length);
        AtomicReference<byte[]> receivedBeforeMutation = new AtomicReference<>();
        CustomerSerialTransmitter transmitter = transmitter(
                (phase, operationId, payload) -> {
                    receivedBeforeMutation.set(Arrays.copyOf(payload, payload.length));
                    payload[0] = 0x00;
                    return true;
                },
                line -> { });
        assertTrue(transmitter.beginOperation(
                23L, CustomerSerialPhase.LOCKER_DISCOVERY, null));

        CustomerSerialTransmitter.SendResult result = transmitter.send(
                CustomerSerialPhase.LOCKER_DISCOVERY,
                23L,
                SerialWriteAttribution.discovery(1),
                callerPayload);

        assertEquals(CustomerSerialTransmitter.Status.ACCEPTED, result.status());
        assertArrayEquals(BOARD_ONE_QUERY, receivedBeforeMutation.get());
        assertArrayEquals(BOARD_ONE_QUERY, callerPayload);
    }

    @Test
    public void inputIsSnapshottedBeforeAuditEvenWhenCallerMutatesConcurrently()
            throws Exception {
        byte[] callerPayload = Arrays.copyOf(BOARD_ONE_QUERY, BOARD_ONE_QUERY.length);
        CountDownLatch auditEntered = new CountDownLatch(1);
        CountDownLatch releaseAudit = new CountDownLatch(1);
        AtomicReference<byte[]> written = new AtomicReference<>();
        AtomicReference<CustomerSerialTransmitter.SendResult> result =
                new AtomicReference<>();
        CustomerSerialTransmitter transmitter = transmitter(
                (phase, operationId, payload) -> {
                    written.set(Arrays.copyOf(payload, payload.length));
                    return true;
                },
                line -> {
                    auditEntered.countDown();
                    await(releaseAudit);
                });
        assertTrue(transmitter.beginOperation(
                24L, CustomerSerialPhase.LOCKER_DISCOVERY, null));
        Thread sender = new Thread(() -> result.set(transmitter.send(
                CustomerSerialPhase.LOCKER_DISCOVERY,
                24L,
                SerialWriteAttribution.discovery(1),
                callerPayload)), "customer-serial-copy-test");

        sender.start();
        assertTrue(auditEntered.await(2L, TimeUnit.SECONDS));
        Arrays.fill(callerPayload, (byte) 0x00);
        releaseAudit.countDown();
        sender.join(2_000L);

        assertFalse(sender.isAlive());
        assertEquals(CustomerSerialTransmitter.Status.ACCEPTED, result.get().status());
        assertArrayEquals(BOARD_ONE_QUERY, written.get());
    }

    @Test
    public void endOperationCannotInterleaveWithAnAuthorizedSend() throws Exception {
        List<String> events = Collections.synchronizedList(new ArrayList<String>());
        CountDownLatch auditEntered = new CountDownLatch(1);
        CountDownLatch releaseAudit = new CountDownLatch(1);
        CountDownLatch endAttempted = new CountDownLatch(1);
        CountDownLatch endReturned = new CountDownLatch(1);
        AtomicReference<CustomerSerialTransmitter.SendResult> result =
                new AtomicReference<>();
        CustomerSerialTransmitter transmitter = transmitter(
                (phase, operationId, payload) -> {
                    events.add("writer");
                    return true;
                },
                line -> {
                    events.add("audit");
                    auditEntered.countDown();
                    await(releaseAudit);
                });
        assertTrue(transmitter.beginOperation(
                25L, CustomerSerialPhase.LOCKER_DISCOVERY, null));
        Thread sender = new Thread(() -> result.set(transmitter.send(
                CustomerSerialPhase.LOCKER_DISCOVERY,
                25L,
                SerialWriteAttribution.discovery(1),
                BOARD_ONE_QUERY)), "customer-serial-locked-send");
        Thread ender = new Thread(() -> {
            endAttempted.countDown();
            transmitter.endOperation(25L);
            events.add("end");
            endReturned.countDown();
        }, "customer-serial-locked-end");

        sender.start();
        assertTrue(auditEntered.await(2L, TimeUnit.SECONDS));
        ender.start();
        assertTrue(endAttempted.await(2L, TimeUnit.SECONDS));
        assertFalse("end must wait for the in-flight send lock",
                endReturned.await(100L, TimeUnit.MILLISECONDS));
        releaseAudit.countDown();
        sender.join(2_000L);
        ender.join(2_000L);

        assertFalse(sender.isAlive());
        assertFalse(ender.isAlive());
        assertEquals(CustomerSerialTransmitter.Status.ACCEPTED, result.get().status());
        assertEquals(Arrays.asList("audit", "writer", "end"), events);
        CustomerSerialTransmitter.SendResult afterEnd = transmitter.send(
                CustomerSerialPhase.LOCKER_DISCOVERY,
                25L,
                SerialWriteAttribution.discovery(2),
                bytes(0x80, 0x02, 0x01, 0x33, 0xB0));
        assertRejected(afterEnd, Decision.INACTIVE_OPERATION);
    }

    @Test
    public void auditReentrantEndInvalidatesTheAuthorizedSnapshotBeforeWriter() {
        AtomicReference<CustomerSerialTransmitter> transmitterRef =
                new AtomicReference<>();
        RecordingWriter writer = new RecordingWriter();
        CustomerSerialTransmitter transmitter = transmitter(
                writer,
                line -> transmitterRef.get().endOperation(26L));
        transmitterRef.set(transmitter);
        assertTrue(transmitter.beginOperation(
                26L, CustomerSerialPhase.LOCKER_DISCOVERY, null));

        CustomerSerialTransmitter.SendResult result = transmitter.send(
                CustomerSerialPhase.LOCKER_DISCOVERY,
                26L,
                SerialWriteAttribution.discovery(1),
                BOARD_ONE_QUERY);

        assertRejected(result, Decision.INACTIVE_OPERATION);
        assertEquals(0, writer.calls);
    }

    @Test
    public void auditReentrantNewOperationInvalidatesOldSnapshotBeforeWriter() {
        AtomicReference<CustomerSerialTransmitter> transmitterRef =
                new AtomicReference<>();
        AtomicBoolean replacementBegan = new AtomicBoolean();
        RecordingWriter writer = new RecordingWriter();
        CustomerSerialTransmitter transmitter = transmitter(
                writer,
                line -> replacementBegan.set(transmitterRef.get().beginOperation(
                        28L, CustomerSerialPhase.TERMINAL, null)));
        transmitterRef.set(transmitter);
        assertTrue(transmitter.beginOperation(
                27L, CustomerSerialPhase.LOCKER_DISCOVERY, null));

        CustomerSerialTransmitter.SendResult result = transmitter.send(
                CustomerSerialPhase.LOCKER_DISCOVERY,
                27L,
                SerialWriteAttribution.discovery(1),
                BOARD_ONE_QUERY);

        assertTrue(replacementBegan.get());
        assertRejected(result, Decision.INACTIVE_OPERATION);
        assertEquals(0, writer.calls);
    }

    @Test
    public void auditReentrantSendCannotReachANestedWriter() {
        AtomicReference<CustomerSerialTransmitter> transmitterRef =
                new AtomicReference<>();
        AtomicReference<CustomerSerialTransmitter.SendResult> nestedResult =
                new AtomicReference<>();
        AtomicBoolean firstAudit = new AtomicBoolean(true);
        RecordingWriter writer = new RecordingWriter();
        CustomerSerialTransmitter transmitter = transmitter(
                writer,
                line -> {
                    if (firstAudit.getAndSet(false)) {
                        nestedResult.set(transmitterRef.get().send(
                                CustomerSerialPhase.LOCKER_DISCOVERY,
                                29L,
                                SerialWriteAttribution.discovery(2),
                                bytes(0x80, 0x02, 0x01, 0x33, 0xB0)));
                    }
                });
        transmitterRef.set(transmitter);
        assertTrue(transmitter.beginOperation(
                29L, CustomerSerialPhase.LOCKER_DISCOVERY, null));

        CustomerSerialTransmitter.SendResult outer = transmitter.send(
                CustomerSerialPhase.LOCKER_DISCOVERY,
                29L,
                SerialWriteAttribution.discovery(1),
                BOARD_ONE_QUERY);

        assertFalse(nestedResult.get().accepted());
        assertEquals(CustomerSerialTransmitter.Status.ACCEPTED, outer.status());
        assertEquals(1, writer.calls);
        assertArrayEquals(BOARD_ONE_QUERY, writer.payloads.get(0));
    }

    @Test
    public void authorizedReturnUsesOneLocalLifecycleAcrossStatusUnlockAndStatus() {
        RecordingWriter writer = new RecordingWriter();
        RecordingAudit audit = new RecordingAudit();
        CustomerSerialTransmitter transmitter = transmitter(writer, audit);
        LockerTarget target = b2();
        assertTrue(transmitter.beginAuthorizedReturn(70L, b2Request(9_001L)));

        assertTrue(transmitter.send(
                CustomerSerialPhase.RETURN_DOOR_STATUS,
                70L,
                SerialWriteAttribution.doorStatus(target),
                b2DoorQuery()).accepted());
        assertTrue(transmitter.transitionAuthorizedReturn(
                70L, CustomerSerialPhase.RETURN_UNLOCK));
        assertTrue(transmitter.send(
                CustomerSerialPhase.RETURN_UNLOCK,
                70L,
                SerialWriteAttribution.returnUnlock(target),
                b2Unlock()).accepted());
        assertTrue(transmitter.transitionAuthorizedReturn(
                70L, CustomerSerialPhase.RETURN_DOOR_STATUS));
        assertTrue(transmitter.send(
                CustomerSerialPhase.RETURN_DOOR_STATUS,
                70L,
                SerialWriteAttribution.doorStatus(target),
                b2DoorQuery()).accepted());

        assertEquals(Arrays.asList(
                CustomerSerialPhase.RETURN_DOOR_STATUS,
                CustomerSerialPhase.RETURN_UNLOCK,
                CustomerSerialPhase.RETURN_DOOR_STATUS), writer.phases);
        assertEquals(Arrays.asList(70L, 70L, 70L), writer.operationIds);
        assertArrayEquals(b2DoorQuery(), writer.payloads.get(0));
        assertArrayEquals(b2Unlock(), writer.payloads.get(1));
        assertArrayEquals(b2DoorQuery(), writer.payloads.get(2));
        assertTrue(audit.lines.get(0).contains("origin=DOOR_STATUS"));
        assertTrue(audit.lines.get(1).contains("origin=RETURN_UNLOCK"));
    }

    @Test
    public void reentrantReturnTransitionInvalidatesTheOldPhaseBeforeWriter() {
        AtomicReference<CustomerSerialTransmitter> transmitterRef =
                new AtomicReference<>();
        AtomicBoolean transitioned = new AtomicBoolean();
        AtomicBoolean transitionArmed = new AtomicBoolean();
        RecordingWriter writer = new RecordingWriter();
        CustomerSerialTransmitter transmitter = transmitter(
                writer,
                line -> {
                    if (transitionArmed.get()) {
                        transitioned.set(transmitterRef.get().transitionAuthorizedReturn(
                                71L, CustomerSerialPhase.RETURN_UNLOCK));
                    }
                });
        transmitterRef.set(transmitter);
        assertTrue(transmitter.beginAuthorizedReturn(71L, b2Request(9_002L)));
        assertTrue(transmitter.send(
                CustomerSerialPhase.RETURN_DOOR_STATUS,
                71L,
                SerialWriteAttribution.doorStatus(b2()),
                b2DoorQuery()).accepted());
        transitionArmed.set(true);

        CustomerSerialTransmitter.SendResult result = transmitter.send(
                CustomerSerialPhase.RETURN_DOOR_STATUS,
                71L,
                SerialWriteAttribution.doorStatus(b2()),
                b2DoorQuery());

        assertTrue(transitioned.get());
        assertRejected(result, Decision.INACTIVE_OPERATION);
        assertEquals(1, writer.calls);
    }

    @Test
    public void rejectedReturnUnlockCannotTransitionToPostUnlockPolling() {
        RecordingWriter writer = new RecordingWriter();
        CustomerSerialTransmitter transmitter = transmitter(writer, line -> { });
        assertTrue(transmitter.beginAuthorizedReturn(72L, b2Request(9_003L)));
        assertTrue(transmitter.send(
                CustomerSerialPhase.RETURN_DOOR_STATUS,
                72L,
                SerialWriteAttribution.doorStatus(b2()),
                b2DoorQuery()).accepted());
        assertTrue(transmitter.transitionAuthorizedReturn(
                72L, CustomerSerialPhase.RETURN_UNLOCK));
        writer.accept = false;

        CustomerSerialTransmitter.SendResult rejected = transmitter.send(
                CustomerSerialPhase.RETURN_UNLOCK,
                72L,
                SerialWriteAttribution.returnUnlock(b2()),
                b2Unlock());

        assertEquals(CustomerSerialTransmitter.Status.WRITER_REJECTED,
                rejected.status());
        assertFalse(transmitter.transitionAuthorizedReturn(
                72L, CustomerSerialPhase.RETURN_DOOR_STATUS));
        assertRejected(transmitter.send(
                CustomerSerialPhase.RETURN_UNLOCK,
                72L,
                SerialWriteAttribution.returnUnlock(b2()),
                b2Unlock()), Decision.DUPLICATE);
    }

    @Test
    public void sendResultIsAnImmutableValueWithoutPayloadOrFailureFields() {
        assertTrue(Modifier.isFinal(
                CustomerSerialTransmitter.SendResult.class.getModifiers()));
        for (Field field : CustomerSerialTransmitter.SendResult.class.getDeclaredFields()) {
            if (field.isSynthetic()) continue;
            assertTrue(field.getName(), Modifier.isPrivate(field.getModifiers()));
            assertTrue(field.getName(), Modifier.isFinal(field.getModifiers()));
            assertFalse(field.getName(), field.getType().isArray());
            assertFalse(field.getName(), Throwable.class.isAssignableFrom(field.getType()));
        }
    }

    private static CustomerSerialTransmitter transmitter(
            CustomerSerialTransmitter.GatewayWriter writer,
            CustomerSerialTransmitter.AuditSink audit) {
        return new CustomerSerialTransmitter(
                writer, audit);
    }

    private static void assertRejected(
            CustomerSerialTransmitter.SendResult result, Decision decision) {
        assertEquals(CustomerSerialTransmitter.Status.POLICY_REJECTED, result.status());
        assertEquals(decision, result.policyDecision());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2L, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for test latch");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int index = 0; index < values.length; index++) {
            result[index] = (byte) values[index];
        }
        return result;
    }

    private static LockerTarget b2() {
        return new LockerTarget(
                LockerZone.B, 2, 2, FeedbackPolarity.SHORT_WHEN_LOCKED);
    }

    private static AuthorizedUnlockRequest b2Request(long serverOperationId) {
        return new AuthorizedUnlockRequest(
                serverOperationId,
                b2(),
                b2Unlock(),
                bytes(0x8A, 0x02, 0x02, 0x00, 0x8A),
                b2Unlock());
    }

    private static byte[] b2DoorQuery() {
        return bytes(0x80, 0x02, 0x02, 0x33, 0xB3);
    }

    private static byte[] b2Unlock() {
        return bytes(0x8A, 0x02, 0x02, 0x11, 0x9B);
    }

    private static final class RecordingWriter
            implements CustomerSerialTransmitter.GatewayWriter {
        boolean accept = true;
        RuntimeException failure;
        int calls;
        final List<CustomerSerialPhase> phases = new ArrayList<>();
        final List<Long> operationIds = new ArrayList<>();
        final List<byte[]> payloads = new ArrayList<>();

        @Override
        public boolean write(
                CustomerSerialPhase phase, long operationId, byte[] payload) {
            calls++;
            phases.add(phase);
            operationIds.add(operationId);
            payloads.add(Arrays.copyOf(payload, payload.length));
            if (failure != null) throw failure;
            return accept;
        }
    }

    private static final class RecordingAudit
            implements CustomerSerialTransmitter.AuditSink {
        RuntimeException failure;
        final List<String> lines = new ArrayList<>();

        @Override
        public void append(String line) {
            lines.add(line);
            if (failure != null) throw failure;
        }
    }
}
