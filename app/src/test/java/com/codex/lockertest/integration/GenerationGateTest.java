package com.codex.lockertest.integration;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class GenerationGateTest {
    @Test
    public void replacingAGatewayRejectsCallbacksQueuedByTheOldGateway() {
        GenerationGate gate = new GenerationGate();
        long oldGateway = gate.activate();
        long currentGateway = gate.activate();

        assertFalse(gate.accepts(oldGateway));
        assertTrue(gate.accepts(currentGateway));
    }

    @Test
    public void invalidationRejectsCallbacksUntilANewGatewayIsActivated() {
        GenerationGate gate = new GenerationGate();
        long stoppedGateway = gate.activate();

        gate.invalidate();

        assertFalse(gate.accepts(stoppedGateway));
        assertFalse(gate.accepts(0L));
        long restartedGateway = gate.activate();
        assertTrue(gate.accepts(restartedGateway));
        assertFalse(gate.accepts(stoppedGateway));
    }
}
