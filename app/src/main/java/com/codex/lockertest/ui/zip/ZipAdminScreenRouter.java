package com.codex.lockertest.ui.zip;

import com.codex.lockertest.face.FaceLicenseStateMachine;
import com.codex.lockertest.face.FaceLivenessControl;
import com.codex.lockertest.face.FaceRuntimeStateMachine;
import com.codex.lockertest.serial.SerialSessionState;

import java.util.Arrays;

/** Android-free, typed routing and request-correlation policy for pages 40-54. */
public final class ZipAdminScreenRouter {
    private static final byte[] A1_TEST_COMMAND = new byte[] {
            (byte) 0x8A, 0x01, 0x01, 0x11, (byte) 0x9B
    };
    public enum PinState {
        ENTRY,
        ERROR
    }

    public enum SerialEvent {
        NONE,
        WRITE_ACCEPTED,
        MATCHER_SUCCESS,
        MATCHER_FAILURE,
        TRANSIENT_FAILURE
    }

    public enum LicenseFailureKind {
        NONE,
        INVALID,
        RETRYABLE,
        CORE
    }

    public enum OpenDispatchResult {
        OPEN_ACCEPTED,
        BUSY,
        OPEN_REJECTED
    }

    /** Visible native actions for one already validated administrator route. */
    public static final class ActionAvailability {
        private final boolean nativeBackVisible;
        private final boolean retryVisible;
        private final boolean promptBackVisible;

        private ActionAvailability(boolean nativeBackVisible,
                boolean retryVisible, boolean promptBackVisible) {
            this.nativeBackVisible = nativeBackVisible;
            this.retryVisible = retryVisible;
            this.promptBackVisible = promptBackVisible;
        }

        public boolean nativeBackVisible() { return nativeBackVisible; }
        public boolean retryVisible() { return retryVisible; }
        public boolean promptBackVisible() { return promptBackVisible; }
    }

    private ZipAdminScreenRouter() {
    }

    public static ZipScreenAsset assetForPin(PinState state) {
        if (state == null) {
            throw new IllegalArgumentException("administrator PIN state is required");
        }
        switch (state) {
            case ENTRY:
                return ZipScreenAsset.ADMIN_PIN_ENTRY;
            case ERROR:
                return ZipScreenAsset.ADMIN_PIN_ERROR;
            default:
                throw new IllegalArgumentException("unknown administrator PIN state: " + state);
        }
    }

    public static ZipScreenAsset adminFunctions() {
        return ZipScreenAsset.ADMIN_FUNCTIONS;
    }

    public static ZipScreenAsset assetForSerial(
            SerialSessionState.Phase phase, SerialEvent event) {
        if (phase == null || event == null) {
            throw new IllegalArgumentException("typed serial phase and event are required");
        }
        if ((event == SerialEvent.WRITE_ACCEPTED
                || event == SerialEvent.MATCHER_SUCCESS
                || event == SerialEvent.MATCHER_FAILURE)
                && phase != SerialSessionState.Phase.OPEN) {
            throw new IllegalArgumentException(
                    "serial request events require an open session: " + event);
        }
        if (event == SerialEvent.TRANSIENT_FAILURE
                || event == SerialEvent.MATCHER_FAILURE) {
            return ZipScreenAsset.ADMIN_SERIAL_FAILURE;
        }
        if (event == SerialEvent.MATCHER_SUCCESS) {
            if (phase != SerialSessionState.Phase.OPEN) {
                throw new IllegalArgumentException(
                        "a successful serial response requires an open session");
            }
            return ZipScreenAsset.ADMIN_SERIAL_SUCCESS;
        }
        switch (phase) {
            case OPEN:
                return ZipScreenAsset.ADMIN_SERIAL_CONNECTED;
            case OPENING:
            case CLOSING:
                return ZipScreenAsset.ADMIN_SERIAL_OPENING;
            case CLOSED:
            case DISPOSED:
                return ZipScreenAsset.ADMIN_SERIAL_DISCONNECTED;
            default:
                throw new IllegalArgumentException("unknown serial phase: " + phase);
        }
    }

    public static ActionAvailability actionsForSerial(
            SerialSessionState.Phase phase, SerialEvent event) {
        ZipScreenAsset asset = assetForSerial(phase, event);
        switch (asset) {
            case ADMIN_SERIAL_DISCONNECTED:
            case ADMIN_SERIAL_CONNECTED:
                return new ActionAvailability(true, false, false);
            case ADMIN_SERIAL_FAILURE:
                return new ActionAvailability(false, false, true);
            case ADMIN_SERIAL_OPENING:
            case ADMIN_SERIAL_SUCCESS:
                return new ActionAvailability(false, false, false);
            default:
                throw new IllegalArgumentException(
                        "not an administrator serial asset: " + asset);
        }
    }

    public static ActionAvailability actionsForFaceAsset(
            ZipScreenAsset asset, boolean retryAvailable) {
        if (asset == null || asset.id() < 48 || asset.id() > 54) {
            throw new IllegalArgumentException("face administrator asset is required");
        }
        if (retryAvailable && asset != ZipScreenAsset.FACE_SDK_ERROR) {
            throw new IllegalArgumentException("retry is only valid for page 54");
        }
        switch (asset) {
            case FACE_SDK_CHECKING_LICENSE:
            case FACE_SDK_AWAITING_ACTIVATION:
            case FACE_SDK_READY:
                return new ActionAvailability(true, false, false);
            case FACE_SDK_ERROR:
                return new ActionAvailability(false, retryAvailable, true);
            case FACE_SDK_ACTIVATING:
            case FACE_SDK_LICENSED:
            case FACE_SDK_INITIALIZING:
                return new ActionAvailability(false, false, false);
            default:
                throw new IllegalArgumentException(
                        "not a face administrator asset: " + asset);
        }
    }

    public static OpenDispatchResult classifyOpenDispatch(
            boolean accepted, SerialSessionState.Phase phaseAfterDispatch) {
        if (phaseAfterDispatch == null) {
            throw new IllegalArgumentException("typed serial phase is required");
        }
        if (accepted) return OpenDispatchResult.OPEN_ACCEPTED;
        if (phaseAfterDispatch == SerialSessionState.Phase.OPENING
                || phaseAfterDispatch == SerialSessionState.Phase.CLOSING) {
            return OpenDispatchResult.BUSY;
        }
        return OpenDispatchResult.OPEN_REJECTED;
    }

    /** Exact A1 bytes always use the correlated matcher and retry fence. */
    public static SerialRequestGate.Kind classifySerialCommand(byte[] command) {
        if (command == null || command.length == 0) {
            throw new IllegalArgumentException("serial command is required");
        }
        return Arrays.equals(command, A1_TEST_COMMAND)
                ? SerialRequestGate.Kind.A1_TEST
                : SerialRequestGate.Kind.RAW_HEX;
    }

    public static ZipScreenAsset assetForFace(
            FaceLicenseStateMachine.State license,
            FaceRuntimeStateMachine.State runtime,
            FaceLivenessControl.Capability liveness,
            LicenseFailureKind failure,
            boolean activationInFlight) {
        if (license == null || runtime == null || liveness == null || failure == null) {
            throw new IllegalArgumentException("typed face SDK state is required");
        }
        validateFaceCompatibility(
                license, runtime, liveness, failure, activationInFlight);
        if (activationInFlight
                || license == FaceLicenseStateMachine.State.ACTIVATING_ONLINE) {
            return ZipScreenAsset.FACE_SDK_ACTIVATING;
        }
        switch (license) {
            case UNKNOWN:
            case CHECKING_LOCAL:
                requireFailure(failure, LicenseFailureKind.NONE, license);
                return ZipScreenAsset.FACE_SDK_CHECKING_LICENSE;
            case INVALID:
                if (failure != LicenseFailureKind.NONE
                        && failure != LicenseFailureKind.INVALID) {
                    throw incompatibleFailure(license, failure);
                }
                return ZipScreenAsset.FACE_SDK_AWAITING_ACTIVATION;
            case FAILED:
                if (failure == LicenseFailureKind.RETRYABLE
                        || failure == LicenseFailureKind.INVALID) {
                    return ZipScreenAsset.FACE_SDK_AWAITING_ACTIVATION;
                }
                if (failure == LicenseFailureKind.CORE) {
                    return ZipScreenAsset.FACE_SDK_ERROR;
                }
                throw incompatibleFailure(license, failure);
            case READY:
                requireFailure(failure, LicenseFailureKind.NONE, license);
                return assetForReadyFaceRuntime(runtime);
            case ACTIVATING_ONLINE:
            default:
                throw new IllegalArgumentException("illegal face SDK state combination");
        }
    }

    public static boolean isLivenessToggleEnabled(
            FaceLivenessControl.Capability capability) {
        if (capability == null) {
            throw new IllegalArgumentException("liveness capability is required");
        }
        return capability == FaceLivenessControl.Capability.SUPPORTED;
    }

    private static ZipScreenAsset assetForReadyFaceRuntime(
            FaceRuntimeStateMachine.State runtime) {
        switch (runtime) {
            case UNINITIALIZED:
                return ZipScreenAsset.FACE_SDK_LICENSED;
            case INITIALIZING:
                return ZipScreenAsset.FACE_SDK_INITIALIZING;
            case READY:
                return ZipScreenAsset.FACE_SDK_READY;
            case FAILED:
                return ZipScreenAsset.FACE_SDK_ERROR;
            case RELEASED:
                throw new IllegalArgumentException("released face runtime is terminal");
            default:
                throw new IllegalArgumentException("unknown face runtime state: " + runtime);
        }
    }

    private static void validateFaceCompatibility(
            FaceLicenseStateMachine.State license,
            FaceRuntimeStateMachine.State runtime,
            FaceLivenessControl.Capability liveness,
            LicenseFailureKind failure,
            boolean activationInFlight) {
        if (license != FaceLicenseStateMachine.State.READY) {
            if (runtime != FaceRuntimeStateMachine.State.UNINITIALIZED
                    || liveness != FaceLivenessControl.Capability.WAITING_FOR_LICENSE) {
                throw new IllegalArgumentException(
                        "an unready license requires an uninitialized runtime and waiting liveness");
            }
            switch (license) {
                case UNKNOWN:
                case CHECKING_LOCAL:
                case ACTIVATING_ONLINE:
                    requireFailure(failure, LicenseFailureKind.NONE, license);
                    break;
                case INVALID:
                    if (failure != LicenseFailureKind.NONE
                            && failure != LicenseFailureKind.INVALID) {
                        throw incompatibleFailure(license, failure);
                    }
                    break;
                case FAILED:
                    if (failure != LicenseFailureKind.RETRYABLE
                            && failure != LicenseFailureKind.INVALID
                            && failure != LicenseFailureKind.CORE) {
                        throw incompatibleFailure(license, failure);
                    }
                    break;
                default:
                    throw new IllegalArgumentException("unknown face license state");
            }
            return;
        }
        if (activationInFlight) {
            throw new IllegalArgumentException("a ready license cannot still be activating");
        }
        requireFailure(failure, LicenseFailureKind.NONE, license);
        switch (runtime) {
            case UNINITIALIZED:
                if (liveness != FaceLivenessControl.Capability.WAITING_FOR_LICENSE) {
                    throw incompatibleLiveness(runtime, liveness);
                }
                return;
            case INITIALIZING:
                return;
            case READY:
                if (liveness == FaceLivenessControl.Capability.WAITING_FOR_LICENSE) {
                    throw incompatibleLiveness(runtime, liveness);
                }
                return;
            case FAILED:
                if (liveness != FaceLivenessControl.Capability.WAITING_FOR_LICENSE
                        && liveness != FaceLivenessControl.Capability.UNSUPPORTED
                        && liveness != FaceLivenessControl.Capability.FAILED) {
                    throw incompatibleLiveness(runtime, liveness);
                }
                return;
            case RELEASED:
            default:
                throw new IllegalArgumentException(
                        "released or unknown face runtime is not an administrator route");
        }
    }

    private static IllegalArgumentException incompatibleLiveness(
            FaceRuntimeStateMachine.State runtime,
            FaceLivenessControl.Capability liveness) {
        return new IllegalArgumentException(
                "incompatible liveness " + liveness + " for runtime " + runtime);
    }

    private static void requireFailure(LicenseFailureKind actual,
            LicenseFailureKind expected, FaceLicenseStateMachine.State license) {
        if (actual != expected) throw incompatibleFailure(license, actual);
    }

    private static IllegalArgumentException incompatibleFailure(
            FaceLicenseStateMachine.State license, LicenseFailureKind failure) {
        return new IllegalArgumentException(
                "incompatible typed license failure " + failure + " for " + license);
    }

    /**
     * Process-session fence for identical A1 bytes after an ambiguous terminal.
     * A typed CLOSED observation followed by a later OPEN observation is the only reset.
     */
    public static final class SerialA1RetryFence {
        private enum State {
            READY,
            WAITING_FOR_CLOSED,
            WAITING_FOR_NEW_OPEN
        }

        private State state = State.READY;

        public synchronized boolean canBeginA1() {
            return state == State.READY;
        }

        public synchronized void onAmbiguousTerminal() {
            state = State.WAITING_FOR_CLOSED;
        }

        /** Returns whether a new A1 request is safe after consuming this observation. */
        public synchronized boolean observe(SerialSessionState.Phase phase) {
            if (phase == null) {
                throw new IllegalArgumentException("typed serial phase is required");
            }
            if (state == State.WAITING_FOR_CLOSED
                    && phase == SerialSessionState.Phase.CLOSED) {
                state = State.WAITING_FOR_NEW_OPEN;
            } else if (state == State.WAITING_FOR_NEW_OPEN
                    && phase == SerialSessionState.Phase.OPEN) {
                state = State.READY;
            }
            return state == State.READY;
        }
    }

    /**
     * One-outstanding request gate. The immutable request identity includes the
     * request ID, UI generation, exact lease identity, kind and exact command.
     */
    public static final class SerialRequestGate<L> {
        public static final long TIMEOUT_MILLIS = 3000L;

        public enum Kind {
            RAW_HEX,
            A1_TEST
        }

        public enum MatcherEvent {
            SUCCESS,
            FAILURE
        }

        public enum Completion {
            IGNORED,
            PENDING,
            WRITE_COMPLETED,
            MATCHER_SUCCESS,
            MATCHER_FAILURE,
            TRANSIENT_FAILURE,
            TIMEOUT
        }

        public static final class Request<L> {
            private final long requestId;
            private final long generation;
            private final L lease;
            private final Kind kind;
            private final byte[] command;
            private boolean accepted;

            private Request(long requestId, long generation, L lease,
                    Kind kind, byte[] command) {
                this.requestId = requestId;
                this.generation = generation;
                this.lease = lease;
                this.kind = kind;
                this.command = Arrays.copyOf(command, command.length);
            }

            public long requestId() { return requestId; }
            public long generation() { return generation; }
            public L lease() { return lease; }
            public Kind kind() { return kind; }
            public byte[] command() { return Arrays.copyOf(command, command.length); }
        }

        private long lastRequestId;
        private Request<L> pending;

        public synchronized Request<L> begin(
                long generation, L lease, Kind kind, byte[] exactCommand) {
            if (generation <= 0L || lease == null || kind == null
                    || exactCommand == null || exactCommand.length == 0) {
                throw new IllegalArgumentException("complete serial request identity is required");
            }
            if (kind == Kind.RAW_HEX
                    && classifySerialCommand(exactCommand) == Kind.A1_TEST) {
                throw new IllegalArgumentException(
                        "the exact A1 command requires the typed A1 request path");
            }
            if (pending != null) return null;
            if (lastRequestId == Long.MAX_VALUE) {
                throw new IllegalStateException("serial request identity exhausted");
            }
            pending = new Request<L>(++lastRequestId, generation, lease,
                    kind, exactCommand);
            return pending;
        }

        public synchronized boolean accept(Request<L> request,
                long generation, L lease, Kind kind, byte[] exactCommand) {
            if (!matches(request, generation, lease, kind, exactCommand)
                    || request.accepted) return false;
            request.accepted = true;
            return true;
        }

        public synchronized boolean reject(Request<L> request,
                long generation, L lease, Kind kind, byte[] exactCommand) {
            if (!matches(request, generation, lease, kind, exactCommand)) return false;
            pending = null;
            return true;
        }

        public synchronized Completion onWritten(Request<L> request,
                long generation, L lease, Kind kind, byte[] exactCommand) {
            if (!acceptedMatches(request, generation, lease, kind, exactCommand)) {
                return Completion.IGNORED;
            }
            if (request.kind == Kind.RAW_HEX) {
                pending = null;
                return Completion.WRITE_COMPLETED;
            }
            return Completion.PENDING;
        }

        public synchronized Completion onMatcher(Request<L> request,
                long generation, L lease, Kind kind, byte[] exactCommand,
                MatcherEvent event) {
            if (event == null
                    || !acceptedMatches(request, generation, lease, kind, exactCommand)
                    || request.kind != Kind.A1_TEST) return Completion.IGNORED;
            pending = null;
            return event == MatcherEvent.SUCCESS
                    ? Completion.MATCHER_SUCCESS : Completion.MATCHER_FAILURE;
        }

        public synchronized Completion onSendFailed(Request<L> request,
                long generation, L lease, Kind kind, byte[] exactCommand) {
            if (!acceptedMatches(request, generation, lease, kind, exactCommand)) {
                return Completion.IGNORED;
            }
            pending = null;
            return Completion.TRANSIENT_FAILURE;
        }

        public synchronized Completion onTimeout(Request<L> request,
                long generation, L lease, Kind kind, byte[] exactCommand) {
            if (!acceptedMatches(request, generation, lease, kind, exactCommand)) {
                return Completion.IGNORED;
            }
            pending = null;
            return Completion.TIMEOUT;
        }

        public synchronized Request<L> pending() {
            return pending;
        }

        public synchronized boolean hasPending() {
            return pending != null;
        }

        public synchronized void clear() {
            pending = null;
        }

        private boolean acceptedMatches(Request<L> request,
                long generation, L lease, Kind kind, byte[] exactCommand) {
            return matches(request, generation, lease, kind, exactCommand)
                    && request.accepted;
        }

        private boolean matches(Request<L> request,
                long generation, L lease, Kind kind, byte[] exactCommand) {
            return request != null && pending == request
                    && request.requestId > 0L
                    && request.generation == generation
                    && request.lease == lease
                    && request.kind == kind
                    && exactCommand != null
                    && Arrays.equals(request.command, exactCommand);
        }
    }
}
