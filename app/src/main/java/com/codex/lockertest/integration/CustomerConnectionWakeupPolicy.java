package com.codex.lockertest.integration;

import com.codex.lockertest.serial.SerialConfig;
import com.codex.lockertest.serial.SerialSessionState;

/** Pure decision seam applied only after a posted customer callback passes its gates. */
public final class CustomerConnectionWakeupPolicy {
    public enum Action {
        IGNORE,
        RECONCILE,
        CONNECTION_EVENT,
        FAIL
    }

    private CustomerConnectionWakeupPolicy() {
    }

    public static Action decide(
            boolean currentOperation,
            boolean protocolConnected,
            SerialSessionState.Phase freshPhase,
            SerialConfig freshConfig,
            boolean callbackConnected,
            String callbackDetail) {
        if (!currentOperation) {
            return Action.IGNORE;
        }
        if (freshPhase == null) {
            return Action.FAIL;
        }

        if (protocolConnected) {
            return freshPhase != SerialSessionState.Phase.OPEN
                    || !SerialConfig.defaults().equals(freshConfig)
                    ? Action.FAIL : Action.IGNORE;
        }

        String detail = callbackDetail == null ? "" : callbackDetail;
        boolean actualFailureEvent = !callbackConnected
                && !detail.startsWith("正在")
                && !detail.startsWith("串口已关闭");
        return actualFailureEvent ? Action.CONNECTION_EVENT : Action.RECONCILE;
    }
}
