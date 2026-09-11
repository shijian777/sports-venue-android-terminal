package com.codex.lockertest.business.journey;

import com.codex.lockertest.business.AuthenticatedUser;
import com.codex.lockertest.business.AssignedCabinet;
import com.codex.lockertest.business.BoardAction;
import com.codex.lockertest.business.BusinessService;
import com.codex.lockertest.business.ControlPanelPreview;
import com.codex.lockertest.business.EmptyBusinessResult;
import com.codex.lockertest.business.UsedCabinet;
import com.codex.lockertest.business.UsedCabinetList;
import com.codex.lockertest.business.UserInfoRequest;
import com.codex.lockertest.server.ApiResult;
import com.codex.lockertest.server.CallToken;
import com.codex.lockertest.server.ServerFailure;
import com.codex.lockertest.unlock.AuthorizedUnlockRequest;
import com.codex.lockertest.unlock.UnlockCoordinator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Pure asynchronous coordinator for production customer cabinet journeys.
 * It deliberately has no Android or legacy cabinet-model dependency; only the separately injected
 * open executor can reach physical I/O, while return is a server-confirmed mutation.
 */
public final class OnlineCustomerCoordinator implements AutoCloseable {
    public static final long REQUEST_TIMEOUT_MILLIS = 15_000L;
    public static final int NORMAL_PREVIEW_TYPE = 2;
    public static final int DEFAULT_PAGE = 1;

    public enum Purpose {
        OPEN_CABINET,
        RETURN_CABINET
    }

    public interface Cancellable {
        void cancel();
    }

    public interface Scheduler {
        Cancellable submit(Runnable runnable);
        Cancellable schedule(Runnable runnable, long delayMillis);
    }

    public interface Clock {
        long nowMillis();
    }

    public interface Listener {
        void onSnapshot(OnlineCustomerSnapshot snapshot);
    }

    private enum CallKind {
        AUTHENTICATE,
        PREVIEW,
        USED,
        AUTHORIZE_OPEN,
        RETURN
    }

    private final Object lock = new Object();
    private final Object emissionLock = new Object();
    private final Object physicalCancellationDrainLock = new Object();
    private final BusinessService service;
    private final Scheduler scheduler;
    private final Clock clock;
    private final Listener listener;
    private final OnlineUnlockExecutor unlockExecutor;

    private boolean closed;
    private long nextOperationId;
    private OnlineCustomerSession session;
    private Purpose purpose;
    private AuthenticatedUser user;
    private OnlineCustomerSession.Region selectedRegion;
    private int selectedPage;
    private List<Integer> pages = Collections.emptyList();
    private List<OnlineCustomerSnapshot.Row> rows = Collections.emptyList();
    private List<OnlineCustomerSnapshot.UsedCabinetView> used = Collections.emptyList();
    private Map<Long, ControlPanelPreview.Cabinet> cabinetSources = Collections.emptyMap();
    private boolean unlockExecutorAvailable;
    private UnlockCoordinator.State physicalState;
    private String selectedCabinetLabel = "";
    private String openRejectionMessage = "";
    private final List<Cancellable> pendingPhysicalCancellations = new ArrayList<>();
    private final ArrayDeque<OnlineCustomerSnapshot> pendingEmissions = new ArrayDeque<>();
    private boolean emitting;
    private QuerySpec retryQuery;
    private Operation current;
    private OnlineCustomerSnapshot snapshot = OnlineCustomerSnapshot.idle();

    public OnlineCustomerCoordinator(
            BusinessService service,
            Scheduler scheduler,
            Clock clock,
            Listener listener) {
        this(service, scheduler, clock, listener, null);
    }

    public OnlineCustomerCoordinator(
            BusinessService service,
            Scheduler scheduler,
            Clock clock,
            Listener listener,
            OnlineUnlockExecutor unlockExecutor) {
        if (service == null || scheduler == null || clock == null || listener == null) {
            throw new IllegalArgumentException("Online customer dependencies are required");
        }
        this.service = service;
        this.scheduler = scheduler;
        this.clock = clock;
        this.listener = listener;
        this.unlockExecutor = unlockExecutor;
    }

    public boolean start(
            OnlineCustomerSession nextSession,
            Purpose nextPurpose,
            UserInfoRequest credential) {
        if (nextSession == null || nextPurpose == null || credential == null) return false;
        Operation operation;
        OnlineCustomerSnapshot next;
        synchronized (lock) {
            if (!canStartLocked()) return false;
            clearSessionDataLocked();
            session = nextSession;
            purpose = nextPurpose;
            if (nextPurpose == Purpose.OPEN_CABINET && nextSession.regions().isEmpty()) {
                snapshot = buildSnapshotLocked(
                        OnlineCustomerSnapshot.State.FAILED,
                        OnlineCustomerSnapshot.Failure.INVALID_SESSION,
                        false);
                next = snapshot;
                operation = null;
            } else {
                operation = new Operation(
                        ++nextOperationId,
                        CallKind.AUTHENTICATE,
                        null,
                        clock.nowMillis() + REQUEST_TIMEOUT_MILLIS,
                        credential);
                current = operation;
                snapshot = buildSnapshotLocked(
                        OnlineCustomerSnapshot.State.AUTHENTICATING,
                        OnlineCustomerSnapshot.Failure.NONE,
                        true);
                next = snapshot;
            }
        }
        emit(next);
        return operation != null && schedule(operation, () -> runAuthentication(operation));
    }

    /** Starts from a request-bound userInfo result without retaining or replaying credentials. */
    public boolean startAuthenticated(
            OnlineCustomerSession nextSession,
            Purpose nextPurpose,
            AuthenticatedUser authenticatedUser) {
        if (nextSession == null || nextPurpose == null || authenticatedUser == null) return false;
        Operation operation = null;
        OnlineCustomerSnapshot next;
        synchronized (lock) {
            if (!canStartLocked()) return false;
            clearSessionDataLocked();
            session = nextSession;
            purpose = nextPurpose;
            if (!authenticatedUser.isCustomerReady()) {
                snapshot = buildSnapshotLocked(
                        OnlineCustomerSnapshot.State.FAILED,
                        OnlineCustomerSnapshot.Failure.CUSTOMER_IDENTITY_REQUIRED,
                        false);
            } else if (nextPurpose == Purpose.OPEN_CABINET && nextSession.regions().isEmpty()) {
                snapshot = buildSnapshotLocked(
                        OnlineCustomerSnapshot.State.FAILED,
                        OnlineCustomerSnapshot.Failure.INVALID_SESSION,
                        false);
            } else {
                user = authenticatedUser;
                QuerySpec query = nextPurpose == Purpose.OPEN_CABINET
                        ? QuerySpec.preview(nextSession.regions().get(0), DEFAULT_PAGE)
                        : QuerySpec.used();
                operation = beginQueryLocked(query);
            }
            next = snapshot;
        }
        emit(next);
        Operation launched = operation;
        return launched != null && schedule(launched, () -> runQuery(launched));
    }

    public boolean changeRegion(long areaId) {
        Operation operation;
        OnlineCustomerSnapshot next;
        synchronized (lock) {
            if (!canNavigateOpenLocked()) return false;
            OnlineCustomerSession.Region region = regionLocked(areaId);
            if (region == null || (selectedRegion != null && selectedRegion.id() == areaId)) {
                return false;
            }
            QuerySpec query = QuerySpec.preview(region, DEFAULT_PAGE);
            operation = beginQueryLocked(query);
            next = snapshot;
        }
        emit(next);
        return schedule(operation, () -> runQuery(operation));
    }

    public boolean changePage(int page) {
        Operation operation;
        OnlineCustomerSnapshot next;
        synchronized (lock) {
            if (!canNavigateOpenLocked() || !pages.contains(page) || page == selectedPage) {
                return false;
            }
            QuerySpec query = QuerySpec.preview(selectedRegion, page);
            operation = beginQueryLocked(query);
            next = snapshot;
        }
        emit(next);
        return schedule(operation, () -> runQuery(operation));
    }

    /** Retries only an authenticated read query; authentication credentials are never retained. */
    public boolean retry() {
        Operation operation;
        OnlineCustomerSnapshot next;
        synchronized (lock) {
            if (closed || current != null
                    || (snapshot.state() != OnlineCustomerSnapshot.State.FAILED
                    && snapshot.state() != OnlineCustomerSnapshot.State.RETURN_FAILED)
                    || retryQuery == null || user == null || session == null) {
                return false;
            }
            operation = beginQueryLocked(retryQuery);
            next = snapshot;
        }
        emit(next);
        return schedule(operation, () -> runQuery(operation));
    }

    public OnlineCustomerSnapshot snapshot() {
        synchronized (lock) {
            return snapshot;
        }
    }

    public boolean confirmOpen(long fcId) {
        boolean available = safeIsAvailable(unlockExecutor);
        Operation operation = null;
        OnlineCustomerSnapshot next = null;
        synchronized (lock) {
            if (!canNavigateOpenLocked()) return false;
            if (!available) {
                unlockExecutorAvailable = false;
                snapshot = buildSnapshotLocked(
                        OnlineCustomerSnapshot.State.BROWSING_OPEN,
                        OnlineCustomerSnapshot.Failure.NONE,
                        false);
                next = snapshot;
            } else {
                ControlPanelPreview.Cabinet cabinet = cabinetSources.get(fcId);
                if (cabinet == null || cabinet.status() != 0
                        || selectedRegion == null
                        || cabinet.areaId() != selectedRegion.id()) {
                    return false;
                }
                selectedCabinetLabel = cabinet.cabinetLabel();
                AuthorizedUnlockRequest request;
                long operationId = ++nextOperationId;
                try {
                    request = OnlineCabinetUnlockMapper.create(operationId, cabinet);
                } catch (RuntimeException invalidMapping) {
                    failOpenLocked(OnlineCustomerSnapshot.Failure.CONFIGURATION, null);
                    next = snapshot;
                    request = null;
                }
                if (request != null) {
                    operation = Operation.open(
                            operationId,
                            clock.nowMillis() + REQUEST_TIMEOUT_MILLIS,
                            user,
                            selectedRegion.id(),
                            fcId,
                            session.bootstrapGeneration(),
                            request);
                    current = operation;
                    retryQuery = null;
                    unlockExecutorAvailable = false;
                    physicalState = null;
                    snapshot = buildSnapshotLocked(
                            OnlineCustomerSnapshot.State.AUTHORIZING_OPEN,
                            OnlineCustomerSnapshot.Failure.NONE,
                            false);
                    next = snapshot;
                }
            }
        }
        if (next != null) emit(next);
        Operation launched = operation;
        return launched != null
                && schedule(launched, () -> runOpenAuthorization(launched));
    }

    /** Confirms return for one cabinet from the latest authenticated owned-cabinet list. */
    public boolean confirmReturn(long fcId) {
        Operation operation;
        OnlineCustomerSnapshot next;
        synchronized (lock) {
            if (!canNavigateReturnLocked() || fcId <= 0) return false;
            OnlineCustomerSnapshot.UsedCabinetView cabinet = usedCabinetLocked(fcId);
            if (cabinet == null) return false;
            selectedCabinetLabel = cabinet.name();
            operation = Operation.returnMutation(
                    ++nextOperationId,
                    clock.nowMillis() + REQUEST_TIMEOUT_MILLIS,
                    user,
                    fcId,
                    session.bootstrapGeneration());
            current = operation;
            retryQuery = null;
            snapshot = buildSnapshotLocked(
                    OnlineCustomerSnapshot.State.RETURNING,
                    OnlineCustomerSnapshot.Failure.NONE,
                    false);
            next = snapshot;
        }
        emit(next);
        return schedule(operation, () -> runReturn(operation));
    }

    /** A view can call this to show the snapshot's explicit unavailable explanation. */
    public boolean requestPhysicalAction() {
        return false;
    }

    public void cancel() {
        OnlineCustomerSnapshot next;
        synchronized (lock) {
            if (closed) return;
            invalidateCurrentLocked(CallToken.Reason.CANCELLED);
            clearSessionDataLocked();
            snapshot = buildSnapshotLocked(
                    OnlineCustomerSnapshot.State.CANCELLED,
                    OnlineCustomerSnapshot.Failure.CANCELLED,
                    false);
            next = snapshot;
        }
        emit(next);
    }

    @Override public void close() {
        OnlineCustomerSnapshot next;
        synchronized (lock) {
            if (closed) return;
            closed = true;
            invalidateCurrentLocked(CallToken.Reason.CANCELLED);
            clearSessionDataLocked();
            snapshot = buildSnapshotLocked(
                    OnlineCustomerSnapshot.State.CLOSED,
                    OnlineCustomerSnapshot.Failure.NONE,
                    false);
            next = snapshot;
        }
        emit(next);
    }

    private boolean schedule(Operation operation, Runnable worker) {
        Cancellable timer;
        try {
            timer = scheduler.schedule(
                    () -> timeout(operation), REQUEST_TIMEOUT_MILLIS);
            if (timer == null) throw new IllegalStateException("Missing timeout handle");
        } catch (RuntimeException rejected) {
            failScheduling(operation);
            return false;
        }
        attachTimer(operation, timer);

        Cancellable workerHandle;
        try {
            workerHandle = scheduler.submit(worker);
            if (workerHandle == null) throw new IllegalStateException("Missing worker handle");
        } catch (RuntimeException rejected) {
            failScheduling(operation);
            return false;
        }
        attachWorker(operation, workerHandle);
        synchronized (lock) {
            return current == operation || operation.completed;
        }
    }

    private void runAuthentication(Operation operation) {
        UserInfoRequest credential = operation.credential.getAndSet(null);
        if (credential == null || !markCredentialClaimed(operation)) return;
        ApiResult<AuthenticatedUser> result;
        try {
            if (operation.token.isCancelled()) return;
            result = service.authenticate(credential, operation.token);
        } catch (RuntimeException failure) {
            result = null;
        } finally {
            credential = null;
        }
        completeAuthentication(operation, result);
    }

    private boolean markCredentialClaimed(Operation operation) {
        OnlineCustomerSnapshot next;
        synchronized (lock) {
            if (current != operation || closed || operation.token.isCancelled()) return false;
            snapshot = buildSnapshotLocked(
                    OnlineCustomerSnapshot.State.AUTHENTICATING,
                    OnlineCustomerSnapshot.Failure.NONE,
                    false);
            next = snapshot;
        }
        emit(next);
        return true;
    }

    private void completeAuthentication(
            Operation operation, ApiResult<AuthenticatedUser> result) {
        Operation queryOperation = null;
        OnlineCustomerSnapshot next;
        synchronized (lock) {
            if (current != operation || closed) return;
            finishCurrentLocked(operation);
            if (operation.token.isCancelled()
                    || clock.nowMillis() >= operation.deadlineMillis) {
                user = null;
                retryQuery = null;
                snapshot = buildSnapshotLocked(
                        OnlineCustomerSnapshot.State.FAILED,
                        OnlineCustomerSnapshot.Failure.TIMEOUT,
                        false);
            } else if (result == null) {
                user = null;
                retryQuery = null;
                snapshot = buildSnapshotLocked(
                        OnlineCustomerSnapshot.State.FAILED,
                        OnlineCustomerSnapshot.Failure.CONFIGURATION,
                        false);
            } else if (!result.isSuccess()) {
                user = null;
                retryQuery = null;
                snapshot = buildSnapshotLocked(
                        OnlineCustomerSnapshot.State.FAILED,
                        failureFor(result.failure()),
                        false);
            } else if (!result.value().isCustomerReady()) {
                user = null;
                retryQuery = null;
                snapshot = buildSnapshotLocked(
                        OnlineCustomerSnapshot.State.FAILED,
                        OnlineCustomerSnapshot.Failure.CUSTOMER_IDENTITY_REQUIRED,
                        false);
            } else {
                user = result.value();
                QuerySpec query = purpose == Purpose.OPEN_CABINET
                        ? QuerySpec.preview(session.regions().get(0), DEFAULT_PAGE)
                        : QuerySpec.used();
                queryOperation = beginQueryLocked(query);
            }
            next = snapshot;
        }
        emit(next);
        if (queryOperation != null) {
            Operation launched = queryOperation;
            schedule(launched, () -> runQuery(launched));
        }
    }

    private void runQuery(Operation operation) {
        ApiResult<?> result;
        try {
            if (operation.token.isCancelled()) return;
            if (operation.kind == CallKind.PREVIEW) {
                result = service.controlPanelPreview(
                        user,
                        NORMAL_PREVIEW_TYPE,
                        operation.query.region.id(),
                        operation.query.page,
                        operation.token);
            } else {
                result = service.useCabinetList(user, operation.token);
            }
        } catch (RuntimeException failure) {
            result = null;
        }
        completeQuery(operation, result);
    }

    private void runOpenAuthorization(Operation operation) {
        ApiResult<AssignedCabinet> result;
        try {
            if (operation.token.isCancelled() || operation.actionUser == null) return;
            result = service.userBoard(
                    operation.actionUser,
                    operation.areaId,
                    operation.fcId,
                    operation.token);
        } catch (RuntimeException failure) {
            result = null;
        }
        completeOpenAuthorization(operation, result);
    }

    private void runReturn(Operation operation) {
        ApiResult<EmptyBusinessResult> result;
        try {
            if (operation.token.isCancelled() || operation.actionUser == null) return;
            result = service.openBoard(
                    operation.actionUser, BoardAction.RETURN, operation.fcId, operation.token);
        } catch (RuntimeException failure) {
            result = null;
        }
        completeReturn(operation, result);
    }

    private void completeReturn(
            Operation operation, ApiResult<EmptyBusinessResult> result) {
        OnlineCustomerSnapshot next;
        synchronized (lock) {
            if (current != operation || closed) return;
            if (operation.token.isCancelled()
                    || clock.nowMillis() >= operation.deadlineMillis) {
                finishReturnFailedLocked(operation, OnlineCustomerSnapshot.Failure.TIMEOUT);
            } else if (result == null) {
                finishReturnFailedLocked(operation, OnlineCustomerSnapshot.Failure.CONFIGURATION);
            } else if (!result.isSuccess()) {
                finishReturnFailedLocked(operation, failureFor(result.failure()));
            } else if (session == null
                    || purpose != Purpose.RETURN_CABINET
                    || session.bootstrapGeneration() != operation.bootstrapGeneration) {
                finishReturnFailedLocked(operation, OnlineCustomerSnapshot.Failure.INVALID_SESSION);
            } else {
                finishCurrentLocked(operation);
                user = null;
                retryQuery = null;
                snapshot = buildSnapshotLocked(
                        OnlineCustomerSnapshot.State.RETURN_SUCCEEDED,
                        OnlineCustomerSnapshot.Failure.NONE,
                        false);
            }
            next = snapshot;
        }
        emit(next);
    }

    private void completeOpenAuthorization(
            Operation operation, ApiResult<AssignedCabinet> result) {
        boolean launchPhysical = false;
        OnlineCustomerSnapshot next;
        synchronized (lock) {
            if (current != operation || closed) return;
            if (operation.token.isCancelled()
                    || clock.nowMillis() >= operation.deadlineMillis) {
                finishOpenLocked(
                        operation,
                        OnlineCustomerSnapshot.State.OPEN_FAILED,
                        OnlineCustomerSnapshot.Failure.TIMEOUT,
                        UnlockCoordinator.State.FAILURE);
            } else if (result == null) {
                finishOpenLocked(
                        operation,
                        OnlineCustomerSnapshot.State.OPEN_FAILED,
                        OnlineCustomerSnapshot.Failure.CONFIGURATION,
                        UnlockCoordinator.State.FAILURE);
            } else if (!result.isSuccess()) {
                openRejectionMessage = result.failure() == null ? "" : result.failure().publicMessage();
                finishOpenLocked(
                        operation,
                        OnlineCustomerSnapshot.State.OPEN_FAILED,
                        OnlineCustomerSnapshot.Failure.OPEN_AUTHORIZATION_FAILED,
                        UnlockCoordinator.State.FAILURE);
            } else if (result.value() == null
                    || result.value().fcId() != operation.fcId
                    || session == null
                    || session.bootstrapGeneration() != operation.bootstrapGeneration
                    || selectedRegion == null
                    || selectedRegion.id() != operation.areaId) {
                finishOpenLocked(
                        operation,
                        OnlineCustomerSnapshot.State.OPEN_FAILED,
                        OnlineCustomerSnapshot.Failure.INVALID_SERVER_LAYOUT,
                        UnlockCoordinator.State.FAILURE);
            } else {
                operation.actionUser = null;
                operation.worker = null;
                physicalState = UnlockCoordinator.State.VERIFYING;
                snapshot = buildSnapshotLocked(
                        OnlineCustomerSnapshot.State.OPENING,
                        OnlineCustomerSnapshot.Failure.NONE,
                        false);
                launchPhysical = true;
            }
            next = snapshot;
        }
        emit(next);
        if (launchPhysical) launchPhysical(operation);
    }

    private void launchPhysical(Operation operation) {
        AuthorizedUnlockRequest request;
        synchronized (lock) {
            if (current != operation || closed || operation.token.isCancelled()
                    || snapshot.state() != OnlineCustomerSnapshot.State.OPENING
                    || clock.nowMillis() >= operation.deadlineMillis) {
                return;
            }
            request = operation.unlockRequest;
        }
        if (request == null) {
            failPhysicalStart(operation);
            return;
        }

        Cancellable handle;
        try {
            handle = unlockExecutor.execute(
                    request,
                    new OnlineUnlockExecutor.Listener() {
                        @Override
                        public void onState(UnlockCoordinator.State state, String detail) {
                            onPhysicalState(operation, state);
                        }

                        @Override
                        public boolean isCurrent() {
                            return isPhysicalCurrent(operation);
                        }

                        @Override
                        public boolean registerCancellation(Cancellable handle) {
                            return registerPhysicalCancellation(operation, handle);
                        }
                    });
            if (handle == null) throw new IllegalStateException("Missing physical handle");
        } catch (RuntimeException failure) {
            failPhysicalStart(operation);
            discardRegistrationMarker(operation);
            return;
        }
        attachPhysical(operation, handle);
    }

    private boolean isPhysicalCurrent(Operation operation) {
        synchronized (lock) {
            return isPhysicalCurrentLocked(operation);
        }
    }

    private boolean isPhysicalCurrentLocked(Operation operation) {
        return current == operation
                && !closed
                && !operation.token.isCancelled()
                && snapshot.state() == OnlineCustomerSnapshot.State.OPENING
                && clock.nowMillis() < operation.deadlineMillis;
    }

    private boolean registerPhysicalCancellation(Operation operation, Cancellable handle) {
        if (handle == null) return false;
        boolean accepted = false;
        boolean cancel = false;
        synchronized (lock) {
            if (operation.registeredPhysical == null) {
                operation.registeredPhysical = handle;
            }
            if (operation.registeredPhysical != handle) {
                cancel = true;
            } else if (isPhysicalCurrentLocked(operation)
                    && (operation.physical == null || operation.physical == handle)) {
                operation.physical = handle;
                accepted = true;
            } else {
                cancel = true;
            }
        }
        if (cancel) safeCancel(handle);
        return accepted;
    }

    private void attachPhysical(Operation operation, Cancellable handle) {
        boolean cancel = false;
        synchronized (lock) {
            if (operation.registeredPhysical == handle) {
                operation.registeredPhysical = null;
                if (isPhysicalCurrentLocked(operation) && operation.physical == handle) {
                    operation.unlockRequest = null;
                }
            } else if (isPhysicalCurrentLocked(operation) && operation.physical == null) {
                operation.physical = handle;
                operation.unlockRequest = null;
            } else if (operation.physical != handle) {
                cancel = true;
            }
        }
        if (cancel) safeCancel(handle);
    }

    private void discardRegistrationMarker(Operation operation) {
        synchronized (lock) {
            operation.registeredPhysical = null;
        }
    }

    private void failPhysicalStart(Operation operation) {
        OnlineCustomerSnapshot next;
        synchronized (lock) {
            if (current != operation || closed) return;
            finishOpenLocked(
                    operation,
                    OnlineCustomerSnapshot.State.OPEN_FAILED,
                    OnlineCustomerSnapshot.Failure.PHYSICAL_OPEN_FAILED,
                    UnlockCoordinator.State.FAILURE);
            next = snapshot;
        }
        emit(next);
    }

    private void onPhysicalState(Operation operation, UnlockCoordinator.State state) {
        OnlineCustomerSnapshot next;
        synchronized (lock) {
            if (current != operation || closed || operation.token.isCancelled()
                    || snapshot.state() != OnlineCustomerSnapshot.State.OPENING) {
                return;
            }
            if (clock.nowMillis() >= operation.deadlineMillis) {
                finishOpenLocked(
                        operation,
                        OnlineCustomerSnapshot.State.OPEN_FAILED,
                        OnlineCustomerSnapshot.Failure.TIMEOUT,
                        UnlockCoordinator.State.FAILURE);
            } else if (state == UnlockCoordinator.State.SUCCESS) {
                finishOpenLocked(
                        operation,
                        OnlineCustomerSnapshot.State.OPEN_SUCCESS,
                        OnlineCustomerSnapshot.Failure.NONE,
                        UnlockCoordinator.State.SUCCESS);
            } else if (state == null || state == UnlockCoordinator.State.FAILURE) {
                finishOpenLocked(
                        operation,
                        OnlineCustomerSnapshot.State.OPEN_FAILED,
                        OnlineCustomerSnapshot.Failure.PHYSICAL_OPEN_FAILED,
                        UnlockCoordinator.State.FAILURE);
            } else {
                physicalState = state;
                snapshot = buildSnapshotLocked(
                        OnlineCustomerSnapshot.State.OPENING,
                        OnlineCustomerSnapshot.Failure.NONE,
                        false);
            }
            next = snapshot;
        }
        emit(next);
    }

    private void completeQuery(Operation operation, ApiResult<?> result) {
        boolean executorAvailable = operation.kind == CallKind.PREVIEW
                && safeIsAvailable(unlockExecutor);
        OnlineCustomerSnapshot next;
        synchronized (lock) {
            if (current != operation || closed) return;
            finishCurrentLocked(operation);
            if (operation.token.isCancelled()
                    || clock.nowMillis() >= operation.deadlineMillis) {
                snapshot = buildSnapshotLocked(
                        OnlineCustomerSnapshot.State.FAILED,
                        OnlineCustomerSnapshot.Failure.TIMEOUT,
                        false);
            } else if (result == null) {
                snapshot = buildSnapshotLocked(
                        OnlineCustomerSnapshot.State.FAILED,
                        OnlineCustomerSnapshot.Failure.CONFIGURATION,
                        false);
            } else if (!result.isSuccess()) {
                snapshot = buildSnapshotLocked(
                        OnlineCustomerSnapshot.State.FAILED,
                        failureFor(result.failure()),
                        false);
            } else if (operation.kind == CallKind.PREVIEW) {
                if (!(result.value() instanceof ControlPanelPreview)
                        || !acceptPreviewLocked(
                        operation.query, (ControlPanelPreview) result.value())) {
                    snapshot = buildSnapshotLocked(
                            OnlineCustomerSnapshot.State.FAILED,
                            OnlineCustomerSnapshot.Failure.INVALID_SERVER_LAYOUT,
                            false);
                } else {
                    retryQuery = null;
                    unlockExecutorAvailable = executorAvailable;
                    snapshot = buildSnapshotLocked(
                            OnlineCustomerSnapshot.State.BROWSING_OPEN,
                            OnlineCustomerSnapshot.Failure.NONE,
                            false);
                }
            } else if (!(result.value() instanceof UsedCabinetList)) {
                snapshot = buildSnapshotLocked(
                        OnlineCustomerSnapshot.State.FAILED,
                        OnlineCustomerSnapshot.Failure.INVALID_SERVER_LAYOUT,
                        false);
            } else {
                acceptUsedLocked((UsedCabinetList) result.value());
                retryQuery = null;
                snapshot = buildSnapshotLocked(
                        OnlineCustomerSnapshot.State.BROWSING_USED,
                        OnlineCustomerSnapshot.Failure.NONE,
                        false);
            }
            next = snapshot;
        }
        emit(next);
    }

    private Operation beginQueryLocked(QuerySpec query) {
        selectedRegion = query.region;
        selectedPage = query.page;
        pages = Collections.emptyList();
        rows = Collections.emptyList();
        used = Collections.emptyList();
        cabinetSources = Collections.emptyMap();
        unlockExecutorAvailable = false;
        physicalState = null;
        selectedCabinetLabel = "";
        openRejectionMessage = "";
        retryQuery = query;
        CallKind kind = query.used ? CallKind.USED : CallKind.PREVIEW;
        Operation operation = new Operation(
                ++nextOperationId,
                kind,
                query,
                clock.nowMillis() + REQUEST_TIMEOUT_MILLIS,
                null);
        current = operation;
        snapshot = buildSnapshotLocked(
                query.used
                        ? OnlineCustomerSnapshot.State.LOADING_USED
                        : OnlineCustomerSnapshot.State.LOADING_PREVIEW,
                OnlineCustomerSnapshot.Failure.NONE,
                false);
        return operation;
    }

    private boolean acceptPreviewLocked(QuerySpec query, ControlPanelPreview preview) {
        if (!preview.pages().contains(query.page)) return invalidPreviewLocked();
        int cabinetCount = 0;
        for (List<ControlPanelPreview.Cabinet> cabinets : preview.layers().values()) {
            if (cabinets.size() > OnlineCustomerSnapshot.DISPLAY_PAGE_CAPACITY - cabinetCount) {
                return invalidPreviewLocked();
            }
            cabinetCount += cabinets.size();
        }
        ArrayList<OnlineCustomerSnapshot.Row> mappedRows = new ArrayList<>();
        LinkedHashMap<Long, ControlPanelPreview.Cabinet> mappedSources =
                new LinkedHashMap<>();
        Set<Long> cabinetIds = new HashSet<>();
        Set<Long> channelIds = new HashSet<>();
        for (Map.Entry<Integer, List<ControlPanelPreview.Cabinet>> entry
                : preview.layers().entrySet()) {
            List<ControlPanelPreview.Cabinet> cabinets = entry.getValue();
            ArrayList<OnlineCustomerSnapshot.Slot> slots = new ArrayList<>();
            int position = 1;
            for (ControlPanelPreview.Cabinet cabinet : cabinets) {
                if (cabinet.areaId() != query.region.id()
                        || !cabinetIds.add(cabinet.fcId())
                        || !channelIds.add(cabinet.channelId())) {
                    return invalidPreviewLocked();
                }
                mappedSources.put(cabinet.fcId(), cabinet);
                slots.add(new OnlineCustomerSnapshot.Slot(
                        position++,
                        cabinet.fcId(),
                        cabinet.channelId(),
                        cabinet.cabinetLabel(),
                        cabinet.status(),
                        cabinet.areaId(),
                        cabinet.checkStatus()));
            }
            mappedRows.add(new OnlineCustomerSnapshot.Row(entry.getKey(), slots));
        }
        pages = Collections.unmodifiableList(new ArrayList<>(preview.pages()));
        rows = Collections.unmodifiableList(mappedRows);
        cabinetSources = Collections.unmodifiableMap(mappedSources);
        return true;
    }

    private boolean invalidPreviewLocked() {
        rows = Collections.emptyList();
        pages = Collections.emptyList();
        cabinetSources = Collections.emptyMap();
        return false;
    }

    private void acceptUsedLocked(UsedCabinetList list) {
        ArrayList<OnlineCustomerSnapshot.UsedCabinetView> mapped = new ArrayList<>();
        for (UsedCabinet cabinet : list.cabinets()) {
            mapped.add(new OnlineCustomerSnapshot.UsedCabinetView(
                    cabinet.recordId(),
                    cabinet.fcId(),
                    cabinet.name(),
                    cabinet.startUse(),
                    cabinet.useTimeMinutes(),
                    cabinet.useNotice()));
        }
        used = Collections.unmodifiableList(mapped);
    }

    private void timeout(Operation operation) {
        OnlineCustomerSnapshot next;
        synchronized (lock) {
            if (current != operation || closed) return;
            operation.token.cancel(CallToken.Reason.TIMEOUT);
            if (operation.kind == CallKind.AUTHORIZE_OPEN) {
                finishOpenLocked(
                        operation,
                        OnlineCustomerSnapshot.State.OPEN_FAILED,
                        OnlineCustomerSnapshot.Failure.TIMEOUT,
                        UnlockCoordinator.State.FAILURE);
            } else if (operation.kind == CallKind.RETURN) {
                finishReturnFailedLocked(operation, OnlineCustomerSnapshot.Failure.TIMEOUT);
            } else {
                Cancellable worker = operation.worker;
                operation.clearReferences();
                operation.clearHandles();
                operation.completed = true;
                current = null;
                safeCancel(worker);
                if (operation.kind == CallKind.AUTHENTICATE) {
                    user = null;
                    retryQuery = null;
                }
                snapshot = buildSnapshotLocked(
                        OnlineCustomerSnapshot.State.FAILED,
                        OnlineCustomerSnapshot.Failure.TIMEOUT,
                        false);
            }
            next = snapshot;
        }
        emit(next);
    }

    private void failScheduling(Operation operation) {
        OnlineCustomerSnapshot next;
        synchronized (lock) {
            if (current != operation || closed) return;
            operation.token.cancel(CallToken.Reason.CANCELLED);
            if (operation.kind == CallKind.AUTHORIZE_OPEN) {
                finishOpenLocked(
                        operation,
                        OnlineCustomerSnapshot.State.OPEN_FAILED,
                        OnlineCustomerSnapshot.Failure.CONFIGURATION,
                        UnlockCoordinator.State.FAILURE);
            } else if (operation.kind == CallKind.RETURN) {
                finishReturnFailedLocked(
                        operation, OnlineCustomerSnapshot.Failure.CONFIGURATION);
            } else {
                Cancellable timer = operation.timer;
                Cancellable worker = operation.worker;
                operation.clearReferences();
                operation.clearHandles();
                operation.completed = true;
                current = null;
                safeCancel(timer);
                safeCancel(worker);
                if (operation.kind == CallKind.AUTHENTICATE) {
                    user = null;
                    retryQuery = null;
                }
                snapshot = buildSnapshotLocked(
                        OnlineCustomerSnapshot.State.FAILED,
                        OnlineCustomerSnapshot.Failure.CONFIGURATION,
                        false);
            }
            next = snapshot;
        }
        emit(next);
    }

    private void attachTimer(Operation operation, Cancellable timer) {
        synchronized (lock) {
            if (current == operation && !operation.completed) operation.timer = timer;
            else safeCancel(timer);
        }
    }

    private void attachWorker(Operation operation, Cancellable worker) {
        synchronized (lock) {
            if (current == operation && !operation.completed) operation.worker = worker;
            else safeCancel(worker);
        }
    }

    private void finishCurrentLocked(Operation operation) {
        Cancellable timer = operation.timer;
        Cancellable physical = operation.physical;
        operation.clearReferences();
        operation.clearHandles();
        operation.completed = true;
        current = null;
        safeCancel(timer);
        queuePhysicalCancellationLocked(physical);
    }

    private void invalidateCurrentLocked(CallToken.Reason reason) {
        if (current == null) return;
        Operation invalidated = current;
        Cancellable timer = invalidated.timer;
        Cancellable worker = invalidated.worker;
        Cancellable physical = invalidated.physical;
        invalidated.token.cancel(reason);
        invalidated.clearReferences();
        invalidated.clearHandles();
        invalidated.completed = true;
        current = null;
        safeCancel(timer);
        safeCancel(worker);
        queuePhysicalCancellationLocked(physical);
    }

    private void clearSessionDataLocked() {
        session = null;
        purpose = null;
        user = null;
        selectedRegion = null;
        selectedPage = 0;
        pages = Collections.emptyList();
        rows = Collections.emptyList();
        used = Collections.emptyList();
        cabinetSources = Collections.emptyMap();
        unlockExecutorAvailable = false;
        physicalState = null;
        selectedCabinetLabel = "";
        openRejectionMessage = "";
        retryQuery = null;
    }

    private boolean canNavigateOpenLocked() {
        return !closed && current == null && user != null && session != null
                && purpose == Purpose.OPEN_CABINET
                && snapshot.state() == OnlineCustomerSnapshot.State.BROWSING_OPEN;
    }

    private boolean canStartLocked() {
        return !closed && current == null
                && (snapshot.state() == OnlineCustomerSnapshot.State.IDLE
                || snapshot.state() == OnlineCustomerSnapshot.State.FAILED
                || snapshot.state() == OnlineCustomerSnapshot.State.OPEN_FAILED
                || snapshot.state() == OnlineCustomerSnapshot.State.RETURN_FAILED
                || snapshot.state() == OnlineCustomerSnapshot.State.CANCELLED);
    }

    private boolean canNavigateReturnLocked() {
        return !closed && current == null && user != null && session != null
                && purpose == Purpose.RETURN_CABINET
                && snapshot.state() == OnlineCustomerSnapshot.State.BROWSING_USED;
    }

    private OnlineCustomerSnapshot.UsedCabinetView usedCabinetLocked(long fcId) {
        for (OnlineCustomerSnapshot.UsedCabinetView cabinet : used) {
            if (cabinet.fcId() == fcId) return cabinet;
        }
        return null;
    }

    private OnlineCustomerSession.Region regionLocked(long areaId) {
        if (session == null) return null;
        for (OnlineCustomerSession.Region region : session.regions()) {
            if (region.id() == areaId) return region;
        }
        return null;
    }

    private void finishOpenLocked(
            Operation operation,
            OnlineCustomerSnapshot.State state,
            OnlineCustomerSnapshot.Failure failure,
            UnlockCoordinator.State terminalPhysicalState) {
        Cancellable timer = operation.timer;
        Cancellable worker = operation.worker;
        Cancellable physical = operation.physical;
        operation.token.cancel(CallToken.Reason.CANCELLED);
        operation.clearReferences();
        operation.clearHandles();
        operation.completed = true;
        current = null;
        user = null;
        retryQuery = null;
        cabinetSources = Collections.emptyMap();
        unlockExecutorAvailable = false;
        physicalState = terminalPhysicalState;
        snapshot = buildSnapshotLocked(state, failure, false);
        safeCancel(timer);
        safeCancel(worker);
        queuePhysicalCancellationLocked(physical);
    }

    private void failOpenLocked(
            OnlineCustomerSnapshot.Failure failure,
            UnlockCoordinator.State terminalPhysicalState) {
        user = null;
        retryQuery = null;
        cabinetSources = Collections.emptyMap();
        unlockExecutorAvailable = false;
        physicalState = terminalPhysicalState;
        snapshot = buildSnapshotLocked(
                OnlineCustomerSnapshot.State.OPEN_FAILED,
                failure,
                false);
    }

    private void finishReturnFailedLocked(
            Operation operation, OnlineCustomerSnapshot.Failure failure) {
        Cancellable timer = operation.timer;
        Cancellable worker = operation.worker;
        operation.token.cancel(CallToken.Reason.CANCELLED);
        operation.clearReferences();
        operation.clearHandles();
        operation.completed = true;
        current = null;
        retryQuery = user == null || session == null ? null : QuerySpec.used();
        snapshot = buildSnapshotLocked(
                OnlineCustomerSnapshot.State.RETURN_FAILED,
                failure,
                false);
        safeCancel(timer);
        safeCancel(worker);
    }

    private OnlineCustomerSnapshot buildSnapshotLocked(
            OnlineCustomerSnapshot.State state,
            OnlineCustomerSnapshot.Failure failure,
            boolean pendingCredential) {
        return new OnlineCustomerSnapshot(
                state,
                failure,
                purpose,
                session == null ? 0L : session.bootstrapGeneration(),
                session == null
                        ? Collections.<OnlineCustomerSession.Region>emptyList()
                        : session.regions(),
                selectedRegion,
                pages,
                selectedPage,
                rows,
                used,
                pendingCredential,
                state == OnlineCustomerSnapshot.State.BROWSING_OPEN
                        && unlockExecutorAvailable,
                physicalState,
                physicalDetail(state, physicalState, failure),
                selectedCabinetLabel);
    }

    private String physicalDetail(
            OnlineCustomerSnapshot.State state, UnlockCoordinator.State physicalState,
            OnlineCustomerSnapshot.Failure failure) {
        if (state == OnlineCustomerSnapshot.State.AUTHORIZING_OPEN) {
            return "正在向服务器确认柜门分配。";
        }
        if (state == OnlineCustomerSnapshot.State.OPEN_SUCCESS) {
            return "柜门已打开，请存放物品后关闭柜门。";
        }
        if (state == OnlineCustomerSnapshot.State.OPEN_FAILED) {
            if (failure == OnlineCustomerSnapshot.Failure.OPEN_AUTHORIZATION_FAILED) {
                return openRejectionMessage.isEmpty()
                        ? "服务器未能分配所选柜门，请返回首页后重新验证或联系工作人员。"
                        : openRejectionMessage;
            }
            return "开柜未完成，请重新验证后再试。";
        }
        if (state != OnlineCustomerSnapshot.State.OPENING || physicalState == null) {
            return "";
        }
        switch (physicalState) {
            case CONNECTING:
                return "正在连接设备。";
            case QUIETING:
                return "正在准备设备。";
            case WAITING_ACK:
                return "正在开启柜门，请稍候。";
            default:
                return "正在准备开柜。";
        }
    }

    private static boolean safeIsAvailable(OnlineUnlockExecutor executor) {
        if (executor == null) return false;
        try {
            return executor.isAvailable();
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private static OnlineCustomerSnapshot.Failure failureFor(ServerFailure failure) {
        if (failure == null) return OnlineCustomerSnapshot.Failure.CONFIGURATION;
        switch (failure.kind()) {
            case NO_ENTRY_RECORD:
                return OnlineCustomerSnapshot.Failure.NO_ENTRY_RECORD;
            case REMOTE_REJECTED:
                return OnlineCustomerSnapshot.Failure.REMOTE_REJECTED;
            case TIMEOUT:
                return OnlineCustomerSnapshot.Failure.TIMEOUT;
            case HTTP:
                return OnlineCustomerSnapshot.Failure.SERVER_ERROR;
            case CONTRACT:
            case INVALID_JSON:
            case INVALID_UTF8:
            case RESPONSE_TOO_LARGE:
                return OnlineCustomerSnapshot.Failure.INVALID_SERVER_LAYOUT;
            case CONFIGURATION:
                return OnlineCustomerSnapshot.Failure.CONFIGURATION;
            default:
                return OnlineCustomerSnapshot.Failure.SERVICE_UNAVAILABLE;
        }
    }

    private void emit(OnlineCustomerSnapshot next) {
        boolean drainEmissions;
        synchronized (emissionLock) {
            pendingEmissions.addLast(next);
            if (emitting) {
                drainEmissions = false;
            } else {
                emitting = true;
                drainEmissions = true;
            }
        }
        if (!drainEmissions) {
            drainPendingPhysicalCancellations();
            return;
        }
        while (true) {
            OnlineCustomerSnapshot candidate;
            synchronized (emissionLock) {
                candidate = pendingEmissions.pollFirst();
                if (candidate == null) {
                    emitting = false;
                    return;
                }
            }
            drainPendingPhysicalCancellations();
            synchronized (lock) {
                if (snapshot != candidate) continue;
            }
            try {
                listener.onSnapshot(candidate);
            } catch (RuntimeException ignored) {
                // UI callback failure cannot mutate authoritative customer state.
            }
        }
    }

    private void queuePhysicalCancellationLocked(Cancellable handle) {
        if (handle == null) return;
        for (Cancellable pending : pendingPhysicalCancellations) {
            if (pending == handle) return;
        }
        pendingPhysicalCancellations.add(handle);
    }

    private void drainPendingPhysicalCancellations() {
        synchronized (physicalCancellationDrainLock) {
            while (true) {
                List<Cancellable> pending;
                synchronized (lock) {
                    if (pendingPhysicalCancellations.isEmpty()) return;
                    pending = new ArrayList<>(pendingPhysicalCancellations);
                    pendingPhysicalCancellations.clear();
                }
                for (Cancellable handle : pending) safeCancel(handle);
            }
        }
    }

    private static void safeCancel(Cancellable cancellable) {
        if (cancellable == null) return;
        try {
            cancellable.cancel();
        } catch (RuntimeException ignored) {
            // Generation/identity checks remain authoritative if cancellation rejects.
        }
    }

    private static final class QuerySpec {
        final boolean used;
        final OnlineCustomerSession.Region region;
        final int page;

        private QuerySpec(boolean used, OnlineCustomerSession.Region region, int page) {
            this.used = used;
            this.region = region;
            this.page = page;
        }

        static QuerySpec preview(OnlineCustomerSession.Region region, int page) {
            return new QuerySpec(false, region, page);
        }

        static QuerySpec used() {
            return new QuerySpec(true, null, 0);
        }
    }

    private static final class Operation {
        final long id;
        final CallKind kind;
        final QuerySpec query;
        final long deadlineMillis;
        final CallToken token = new CallToken();
        final AtomicReference<UserInfoRequest> credential;
        final long areaId;
        final long fcId;
        final long bootstrapGeneration;
        AuthenticatedUser actionUser;
        AuthorizedUnlockRequest unlockRequest;
        Cancellable timer;
        Cancellable worker;
        Cancellable physical;
        Cancellable registeredPhysical;
        boolean completed;

        Operation(
                long id,
                CallKind kind,
                QuerySpec query,
                long deadlineMillis,
                UserInfoRequest credential) {
            this.id = id;
            this.kind = kind;
            this.query = query;
            this.deadlineMillis = deadlineMillis;
            this.credential = new AtomicReference<>(credential);
            this.areaId = 0L;
            this.fcId = 0L;
            this.bootstrapGeneration = 0L;
        }

        private Operation(
                long id,
                CallKind kind,
                long deadlineMillis,
                AuthenticatedUser actionUser,
                long areaId,
                long fcId,
                long bootstrapGeneration,
                AuthorizedUnlockRequest unlockRequest) {
            this.id = id;
            this.kind = kind;
            this.query = null;
            this.deadlineMillis = deadlineMillis;
            this.credential = new AtomicReference<>(null);
            this.actionUser = actionUser;
            this.areaId = areaId;
            this.fcId = fcId;
            this.bootstrapGeneration = bootstrapGeneration;
            this.unlockRequest = unlockRequest;
        }

        static Operation open(
                long id,
                long deadlineMillis,
                AuthenticatedUser actionUser,
                long areaId,
                long fcId,
                long bootstrapGeneration,
                AuthorizedUnlockRequest unlockRequest) {
            return new Operation(
                    id,
                    CallKind.AUTHORIZE_OPEN,
                    deadlineMillis,
                    actionUser,
                    areaId,
                    fcId,
                    bootstrapGeneration,
                    unlockRequest);
        }

        static Operation returnMutation(
                long id,
                long deadlineMillis,
                AuthenticatedUser actionUser,
                long fcId,
                long bootstrapGeneration) {
            return new Operation(
                    id,
                    CallKind.RETURN,
                    deadlineMillis,
                    actionUser,
                    0L,
                    fcId,
                    bootstrapGeneration,
                    null);
        }

        void clearReferences() {
            credential.set(null);
            actionUser = null;
            unlockRequest = null;
        }

        void clearHandles() {
            timer = null;
            worker = null;
            physical = null;
        }
    }
}
