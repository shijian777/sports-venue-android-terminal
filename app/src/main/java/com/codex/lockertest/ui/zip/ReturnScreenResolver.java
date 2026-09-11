package com.codex.lockertest.ui.zip;

import com.codex.lockertest.returnflow.ReturnFlowController;
import com.codex.lockertest.returnflow.ReturnFlowModel;
import com.codex.lockertest.returnflow.ReturnLocker;
import com.codex.lockertest.ui.zip.ReturnScreenPresentation.RetryKind;
import com.codex.lockertest.ui.zip.ReturnScreenPresentation.Surface;

/** Typed mapping from return-domain state to the ZIP pages 27-39. */
public final class ReturnScreenResolver {
    private ReturnScreenResolver() {
    }

    public static ReturnScreenPresentation resolve(Input input) {
        if (input == null || input.snapshot == null) {
            throw new IllegalArgumentException("Return resolver input is required");
        }
        ReturnFlowController.Snapshot snapshot = input.snapshot;
        ReturnFlowModel.State state = snapshot.state();
        ReturnFlowController.ErrorCode error = snapshot.error();
        if (state == null || error == null || state == ReturnFlowModel.State.HOME) {
            throw new IllegalArgumentException("HOME has no return-screen presentation");
        }

        if (input.unlockConsumed
                && (state == ReturnFlowModel.State.AUTHENTICATING
                        || state == ReturnFlowModel.State.LOADING
                        || state == ReturnFlowModel.State.SELECTING
                        || state == ReturnFlowModel.State.CONFIRMING
                        || state == ReturnFlowModel.State.FAILURE)) {
            return noAction(ZipScreenAsset.RETURN_FAILED, Surface.PROGRESS);
        }

        if (state == ReturnFlowModel.State.AUTHENTICATING
                || state == ReturnFlowModel.State.LOADING) {
            return resolveAuthentication(input, state, error);
        }

        if (state == ReturnFlowModel.State.FAILURE
                && error == ReturnFlowController.ErrorCode.NO_LOCKERS) {
            return presentation(ZipScreenAsset.RETURN_LOCKER_EMPTY,
                    Surface.LOCKER_LIST, false, false, false, false,
                    false, false, true, RetryKind.NONE, true);
        }
        if (state == ReturnFlowModel.State.SUCCESS) {
            return presentation(ZipScreenAsset.RETURN_SUCCESS,
                    Surface.PROGRESS, false, false, false, false,
                    false, false, true, RetryKind.NONE, true);
        }

        if (state == ReturnFlowModel.State.FAILURE
                || error != ReturnFlowController.ErrorCode.NONE
                || input.serialFailure || input.commitDeferred) {
            boolean suppressRetryWhileBusy = input.serviceBusy
                    && state != ReturnFlowModel.State.WAITING_FOR_CLOSE;
            RetryKind retryKind = suppressRetryWhileBusy
                    ? RetryKind.NONE : failureRetryKind(input, state, error);
            boolean canHome = !input.unlockConsumed && !input.serviceBusy;
            return presentation(ZipScreenAsset.RETURN_FAILED,
                    Surface.PROGRESS, false, false, false, false,
                    false, false, canHome, retryKind, false);
        }

        if ((input.doorProofReady && (input.commitInFlight || input.serviceBusy))
                || state == ReturnFlowModel.State.COMMITTING) {
            return noAction(ZipScreenAsset.RETURN_COMMITTING, Surface.PROGRESS);
        }
        if (state == ReturnFlowModel.State.WAITING_FOR_CLOSE) {
            return presentation(ZipScreenAsset.RETURN_WAITING_FOR_CLOSE,
                    Surface.PROGRESS, false, false, false, false,
                    false, !snapshot.userAcknowledgedClose(), false,
                    RetryKind.NONE, false);
        }
        if (state == ReturnFlowModel.State.OPENING) {
            return noAction(ZipScreenAsset.RETURN_OPENING, Surface.PROGRESS);
        }
        if ((state == ReturnFlowModel.State.SELECTING
                        || state == ReturnFlowModel.State.CONFIRMING)
                && input.serviceBusy) {
            return noAction(ZipScreenAsset.RETURN_LOCKER_PROCESSING,
                    Surface.LOCKER_LIST);
        }
        if (state == ReturnFlowModel.State.CONFIRMING) {
            if (input.authorizedRequestPresent || input.doorSessionPresent) {
                return noAction(ZipScreenAsset.RETURN_OPENING, Surface.PROGRESS);
            }
            return noAction(ZipScreenAsset.RETURN_LOCKER_PROCESSING,
                    Surface.LOCKER_LIST);
        }
        if (state == ReturnFlowModel.State.SELECTING) {
            boolean validChoice = input.chosenLocker != null
                    && snapshot.remainingLockers().contains(input.chosenLocker);
            return presentation(
                    validChoice
                            ? ZipScreenAsset.RETURN_LOCKER_SELECTED
                            : ZipScreenAsset.RETURN_LOCKER_UNSELECTED,
                    Surface.LOCKER_LIST,
                    true, true, false, true, validChoice, false,
                    false, RetryKind.NONE, false);
        }
        throw new IllegalArgumentException("Unsupported return state: " + state);
    }

    private static ReturnScreenPresentation resolveAuthentication(
            Input input,
            ReturnFlowModel.State state,
            ReturnFlowController.ErrorCode error) {
        boolean queryStage = state == ReturnFlowModel.State.LOADING;
        boolean networkError = !input.networkOnline
                || error == ReturnFlowController.ErrorCode.SERVICE_UNAVAILABLE;
        boolean typedFailure = error != ReturnFlowController.ErrorCode.NONE
                || input.authenticationFailed;
        if (networkError || typedFailure) {
            RetryKind retryKind = input.serviceBusy
                    ? RetryKind.NONE
                    : (queryStage ? RetryKind.QUERY : RetryKind.AUTH);
            return presentation(
                    networkError
                            ? ZipScreenAsset.RETURN_AUTH_NETWORK_ERROR
                            : ZipScreenAsset.RETURN_AUTH_FAILED,
                    Surface.AUTHENTICATION,
                    false, false, false, false, false, false,
                    !input.serviceBusy, retryKind, false);
        }
        if (input.serviceBusy || queryStage) {
            return noAction(ZipScreenAsset.RETURN_AUTH_QUERYING,
                    Surface.AUTHENTICATION);
        }
        return presentation(ZipScreenAsset.RETURN_AUTH_READY,
                Surface.AUTHENTICATION, true, true, true, false,
                false, false, false, RetryKind.NONE, false);
    }

    private static RetryKind failureRetryKind(
            Input input,
            ReturnFlowModel.State state,
            ReturnFlowController.ErrorCode error) {
        if (input.commitDeferred
                || (state == ReturnFlowModel.State.COMMITTING
                        && error == ReturnFlowController.ErrorCode.COMMIT_FAILED)) {
            return RetryKind.COMMIT;
        }
        if (state == ReturnFlowModel.State.WAITING_FOR_CLOSE
                && (error == ReturnFlowController.ErrorCode.STATUS_UNCERTAIN
                        || input.serialFailure)) {
            return RetryKind.STATUS;
        }
        if (state == ReturnFlowModel.State.OPENING
                && error == ReturnFlowController.ErrorCode.UNLOCK_FAILED) {
            return input.unlockConsumed ? RetryKind.NONE : RetryKind.QUERY;
        }
        if (state == ReturnFlowModel.State.CONFIRMING
                && input.authorizationRetryable && !input.unlockConsumed) {
            return RetryKind.AUTHORIZATION;
        }
        return RetryKind.NONE;
    }

    private static ReturnScreenPresentation noAction(
            ZipScreenAsset asset, Surface surface) {
        return presentation(asset, surface, false, false, false, false,
                false, false, false, RetryKind.NONE, false);
    }

    private static ReturnScreenPresentation presentation(
            ZipScreenAsset asset,
            Surface surface,
            boolean canBack,
            boolean canCancel,
            boolean canIdentity,
            boolean canSelect,
            boolean canConfirmLocker,
            boolean canDoorCloseConfirm,
            boolean canHome,
            RetryKind retryKind,
            boolean autoHome) {
        return new ReturnScreenPresentation(asset, surface, canBack, canCancel,
                canIdentity, canSelect, canConfirmLocker, canDoorCloseConfirm,
                canHome, retryKind, autoHome);
    }

    public static final class Input {
        private final ReturnFlowController.Snapshot snapshot;
        private final boolean networkOnline;
        private final boolean serviceBusy;
        private final ReturnLocker chosenLocker;
        private final boolean authorizedRequestPresent;
        private final boolean doorSessionPresent;
        private final boolean unlockConsumed;
        private final boolean serialFailure;
        private final boolean commitDeferred;
        private final boolean doorProofReady;
        private final boolean commitInFlight;
        private final boolean authenticationFailed;
        private final boolean authorizationRetryable;

        private Input(Builder builder) {
            snapshot = builder.snapshot;
            networkOnline = builder.networkOnline;
            serviceBusy = builder.serviceBusy;
            chosenLocker = builder.chosenLocker;
            authorizedRequestPresent = builder.authorizedRequestPresent;
            doorSessionPresent = builder.doorSessionPresent;
            unlockConsumed = builder.unlockConsumed;
            serialFailure = builder.serialFailure;
            commitDeferred = builder.commitDeferred;
            doorProofReady = builder.doorProofReady;
            commitInFlight = builder.commitInFlight;
            authenticationFailed = builder.authenticationFailed;
            authorizationRetryable = builder.authorizationRetryable;
        }

        public static Builder builder(
                ReturnFlowController.Snapshot snapshot, boolean networkOnline) {
            return new Builder(snapshot, networkOnline);
        }

        public static final class Builder {
            private final ReturnFlowController.Snapshot snapshot;
            private final boolean networkOnline;
            private boolean serviceBusy;
            private ReturnLocker chosenLocker;
            private boolean authorizedRequestPresent;
            private boolean doorSessionPresent;
            private boolean unlockConsumed;
            private boolean serialFailure;
            private boolean commitDeferred;
            private boolean doorProofReady;
            private boolean commitInFlight;
            private boolean authenticationFailed;
            private boolean authorizationRetryable;

            private Builder(ReturnFlowController.Snapshot snapshot,
                    boolean networkOnline) {
                this.snapshot = snapshot;
                this.networkOnline = networkOnline;
            }

            public Builder serviceBusy(boolean value) {
                serviceBusy = value;
                return this;
            }

            public Builder chosenLocker(ReturnLocker value) {
                chosenLocker = value;
                return this;
            }

            public Builder authorizedRequestPresent(boolean value) {
                authorizedRequestPresent = value;
                return this;
            }

            public Builder doorSessionPresent(boolean value) {
                doorSessionPresent = value;
                return this;
            }

            public Builder unlockConsumed(boolean value) {
                unlockConsumed = value;
                return this;
            }

            public Builder serialFailure(boolean value) {
                serialFailure = value;
                return this;
            }

            public Builder commitDeferred(boolean value) {
                commitDeferred = value;
                return this;
            }

            public Builder doorProofReady(boolean value) {
                doorProofReady = value;
                return this;
            }

            public Builder commitInFlight(boolean value) {
                commitInFlight = value;
                return this;
            }

            public Builder authenticationFailed(boolean value) {
                authenticationFailed = value;
                return this;
            }

            public Builder authorizationRetryable(boolean value) {
                authorizationRetryable = value;
                return this;
            }

            public ReturnScreenPresentation build() {
                return ReturnScreenResolver.resolve(new Input(this));
            }

            public Input buildInput() {
                return new Input(this);
            }
        }
    }
}
