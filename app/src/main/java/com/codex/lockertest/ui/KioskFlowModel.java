package com.codex.lockertest.ui;

import com.codex.lockertest.face.verification.FaceVerificationResult;
import com.codex.lockertest.layout.LockerArea;
import com.codex.lockertest.layout.LockerLayoutSnapshot;
import com.codex.lockertest.layout.LockerPage;
import com.codex.lockertest.layout.LockerSlot;
import com.codex.lockertest.model.LockerTarget;
import com.codex.lockertest.model.LockerZone;
import com.codex.lockertest.model.UnlockMethod;
import com.codex.lockertest.runtime.CredentialAdmission;
import com.codex.lockertest.runtime.CredentialAdmissionPolicy;
import com.codex.lockertest.runtime.InitialLayoutPolicy;

import java.util.List;

public final class KioskFlowModel {
    public enum Screen {
        HOME,
        FACE_RECOGNITION,
        UNAVAILABLE,
        LOCKER_SELECTION,
        RESULT,
        ADMIN_PIN
    }

    public enum ConfirmResult {
        ACCEPTED,
        INVALID_SELECTION,
        FACE_CREDENTIAL_EXPIRED
    }

    public enum ResultContext {
        NONE,
        DISCOVERY_FAILURE,
        UNLOCK_FAILURE,
        UNLOCK_SUCCESS
    }

    private final CredentialAdmissionPolicy credentialPolicy;
    private final HomeCredentialModel credentials;
    private final LockerSelectionModel lockerSelection;
    private final TerminalReadinessSource terminalReadinessSource;

    private Screen screen = Screen.HOME;
    private ResultContext resultContext = ResultContext.NONE;
    private UnlockMethod unavailableMethod;
    private UnlockMethod pendingCredentialMethod;
    private String pendingCredential;
    private FaceVerificationResult pendingFaceVerification;
    private LockerArea pendingArea;
    private LockerPage pendingPage;
    private LockerSlot pendingSlot;
    private LockerTarget pendingTarget;
    private String pendingLockerLabel;

    public KioskFlowModel(
            CredentialAdmissionPolicy credentialPolicy,
            InitialLayoutPolicy layoutPolicy,
            TerminalReadinessSource terminalReadinessSource) {
        if (credentialPolicy == null) {
            throw new IllegalArgumentException("Credential policy is required");
        }
        if (terminalReadinessSource == null) {
            throw new IllegalArgumentException("Terminal readiness is required");
        }
        this.credentialPolicy = credentialPolicy;
        this.terminalReadinessSource = terminalReadinessSource;
        credentials = new HomeCredentialModel(credentialPolicy);
        lockerSelection = new LockerSelectionModel(layoutPolicy);
    }

    public boolean customerActionsEnabled() {
        TerminalReadiness readiness = terminalReadinessSource.current();
        return readiness != null && readiness.customerActionsEnabled();
    }

    public boolean requestReturnJourney() {
        return customerActionsEnabled() && screen == Screen.HOME;
    }

    public boolean requestEnrollment() {
        return customerActionsEnabled() && screen == Screen.HOME;
    }

    public Screen screen() {
        return screen;
    }

    public ResultContext resultContext() {
        return resultContext;
    }

    public HomeCredentialModel credentials() {
        return credentials;
    }

    public LockerSelectionModel lockerSelection() {
        return lockerSelection;
    }

    public UnlockMethod unavailableMethod() {
        return unavailableMethod;
    }

    public UnlockMethod pendingCredentialMethod() {
        return pendingCredentialMethod;
    }

    public String pendingCredential() {
        return pendingCredential;
    }

    public FaceVerificationResult pendingFaceVerification() {
        return pendingFaceVerification;
    }

    public LockerTarget pendingTarget() {
        return pendingTarget;
    }

    public String pendingLockerLabel() {
        return pendingLockerLabel;
    }

    public boolean submitCredential() {
        if (!customerActionsEnabled()
                || screen != Screen.HOME
                || !credentials.validate()) {
            return false;
        }
        pendingFaceVerification = null;
        pendingCredentialMethod = credentials.activeMethod();
        pendingCredential = credentials.rawValue(pendingCredentialMethod);
        clearPendingRequest();
        unavailableMethod = null;
        resultContext = ResultContext.NONE;
        lockerSelection.beginDiscovery();
        screen = Screen.LOCKER_SELECTION;
        return true;
    }

    /** Accepts the raw, already-keypad-bounded value emitted by the Android home view. */
    public boolean submitCredential(UnlockMethod method, String rawValue) {
        if (!customerActionsEnabled() || screen != Screen.HOME || rawValue == null) {
            return false;
        }
        credentials.clearAll();
        if (!credentials.select(method)) {
            return false;
        }
        for (int index = 0; index < rawValue.length(); index++) {
            if (!credentials.pressDigit(rawValue.charAt(index))) {
                credentials.clearAll();
                credentials.select(method);
                return false;
            }
        }
        return submitCredential();
    }

    public boolean submitScannedCredential(String rawValue) {
        if (!customerActionsEnabled() || screen != Screen.HOME) {
            return false;
        }
        CredentialAdmission admission =
                credentialPolicy.admit(UnlockMethod.ID_CARD, rawValue);
        if (admission == null || !admission.accepted()) {
            return false;
        }
        credentials.clearAll();
        pendingFaceVerification = null;
        pendingCredentialMethod = admission.method();
        pendingCredential = rawValue;
        clearPendingRequest();
        unavailableMethod = null;
        resultContext = ResultContext.NONE;
        lockerSelection.beginDiscovery();
        screen = Screen.LOCKER_SELECTION;
        return true;
    }

    public boolean beginFaceRecognition() {
        if (!customerActionsEnabled() || screen != Screen.HOME) {
            return false;
        }
        lockerSelection.clearSelection();
        clearCredential();
        unavailableMethod = null;
        clearPendingRequest();
        resultContext = ResultContext.NONE;
        screen = Screen.FACE_RECOGNITION;
        return true;
    }

    public boolean acceptFaceVerification(
            FaceVerificationResult result, long nowEpochMillis) {
        if (!customerActionsEnabled()
                || screen != Screen.FACE_RECOGNITION
                || result == null
                || !result.isPassed()
                || nowEpochMillis < 0L
                || nowEpochMillis >= result.expiresAtEpochMillis()) {
            return false;
        }
        if (!lockerSelection.beginDiscovery()) {
            return false;
        }
        credentials.clearAll();
        pendingFaceVerification = result;
        pendingCredentialMethod = UnlockMethod.FACE;
        pendingCredential = result.credential();
        unavailableMethod = null;
        clearPendingRequest();
        resultContext = ResultContext.NONE;
        screen = Screen.LOCKER_SELECTION;
        return true;
    }

    public boolean applyDiscoverySnapshot(List<LockerZone> onlineZones) {
        if (!customerActionsEnabled()
                || screen != Screen.LOCKER_SELECTION
                || lockerSelection.discoveryState()
                != LockerSelectionModel.DiscoveryState.DETECTING) {
            return false;
        }
        return lockerSelection.applyDiscoverySnapshot(onlineZones);
    }

    public boolean applyLayoutSnapshot(LockerLayoutSnapshot snapshot) {
        if (!customerActionsEnabled()
                || screen != Screen.LOCKER_SELECTION
                || lockerSelection.discoveryState()
                != LockerSelectionModel.DiscoveryState.DETECTING) {
            return false;
        }
        return lockerSelection.applyLayoutSnapshot(snapshot);
    }

    public boolean finishDiscoveryFailure() {
        if (!customerActionsEnabled()
                || screen != Screen.LOCKER_SELECTION
                || lockerSelection.discoveryState()
                != LockerSelectionModel.DiscoveryState.DETECTING) {
            return false;
        }
        clearPendingRequest();
        resultContext = ResultContext.DISCOVERY_FAILURE;
        screen = Screen.RESULT;
        return true;
    }

    public boolean openUnavailable(UnlockMethod method) {
        if (!customerActionsEnabled()
                || screen != Screen.HOME
                || !isUnavailable(method)) {
            return false;
        }
        unavailableMethod = method;
        screen = Screen.UNAVAILABLE;
        return true;
    }

    public boolean cancelLockerSelection() {
        if (!customerActionsEnabled() || screen != Screen.LOCKER_SELECTION) {
            return false;
        }
        returnHome();
        return true;
    }

    public boolean confirmLocker(LockerTarget target) {
        if (!customerActionsEnabled()
                || pendingCredentialMethod == UnlockMethod.FACE) {
            return false;
        }
        return confirmLockerInternal(target);
    }

    public ConfirmResult confirmLockerAt(LockerTarget target, long nowEpochMillis) {
        if (!customerActionsEnabled()) {
            return ConfirmResult.INVALID_SELECTION;
        }
        ConfirmResult preflight = checkLockerConfirmationAt(target, nowEpochMillis);
        if (preflight == ConfirmResult.FACE_CREDENTIAL_EXPIRED) {
            expireFaceCredential();
            return preflight;
        }
        if (preflight != ConfirmResult.ACCEPTED) {
            return preflight;
        }
        return confirmCurrentLockerInternal(target)
                ? ConfirmResult.ACCEPTED
                : ConfirmResult.INVALID_SELECTION;
    }

    /** Checks whether the current selection may be confirmed without changing flow state. */
    public ConfirmResult checkLockerConfirmationAt(
            LockerTarget target, long nowEpochMillis) {
        if (!customerActionsEnabled()
                || screen != Screen.LOCKER_SELECTION
                || lockerSelection.discoveryState()
                != LockerSelectionModel.DiscoveryState.READY) {
            return ConfirmResult.INVALID_SELECTION;
        }
        if (pendingCredentialMethod == UnlockMethod.FACE) {
            if (pendingFaceVerification == null) {
                return ConfirmResult.INVALID_SELECTION;
            }
            if (nowEpochMillis < 0L
                    || nowEpochMillis >= pendingFaceVerification.expiresAtEpochMillis()) {
                return ConfirmResult.FACE_CREDENTIAL_EXPIRED;
            }
        }
        LockerArea selectedArea = lockerSelection.activeArea();
        LockerPage selectedPage = lockerSelection.activePage();
        LockerSlot selectedSlot = lockerSelection.selectedSlot();
        return target != null
                && selectedArea != null
                && selectedPage != null
                && selectedSlot != null
                && lockerSelection.selectedTarget() == target
                && lockerSelection.canConfirm()
                ? ConfirmResult.ACCEPTED
                : ConfirmResult.INVALID_SELECTION;
    }

    private boolean confirmCurrentLockerInternal(LockerTarget target) {
        if (screen != Screen.LOCKER_SELECTION
                || target == null
                || lockerSelection.discoveryState()
                != LockerSelectionModel.DiscoveryState.READY
                || lockerSelection.selectedTarget() != target
                || !lockerSelection.beginSending()) {
            return false;
        }
        return freezeCurrentLockerRequest(target);
    }

    private boolean confirmLockerInternal(LockerTarget target) {
        if (screen != Screen.LOCKER_SELECTION
                || target == null
                || lockerSelection.discoveryState()
                != LockerSelectionModel.DiscoveryState.READY) {
            return false;
        }

        if (lockerSelection.isInteractionEnabled()) {
            if (!lockerSelection.restoreTarget(target)
                    || !lockerSelection.beginSending()) {
                return false;
            }
        } else if (target != lockerSelection.selectedTarget()) {
            return false;
        }

        LockerArea selectedArea = lockerSelection.activeArea();
        LockerPage selectedPage = lockerSelection.activePage();
        LockerSlot selectedSlot = lockerSelection.selectedSlot();
        return freezeCurrentLockerRequest(
                target, selectedArea, selectedPage, selectedSlot);
    }

    private boolean freezeCurrentLockerRequest(LockerTarget target) {
        return freezeCurrentLockerRequest(
                target,
                lockerSelection.activeArea(),
                lockerSelection.activePage(),
                lockerSelection.selectedSlot());
    }

    private boolean freezeCurrentLockerRequest(
            LockerTarget target,
            LockerArea selectedArea,
            LockerPage selectedPage,
            LockerSlot selectedSlot) {
        if (selectedArea == null || selectedPage == null || selectedSlot == null
                || lockerSelection.selectedTarget() != target) {
            return false;
        }
        pendingArea = selectedArea;
        pendingPage = selectedPage;
        pendingSlot = selectedSlot;
        pendingTarget = target;
        pendingLockerLabel = selectedSlot.displayLabel();
        resultContext = ResultContext.NONE;
        screen = Screen.RESULT;
        return true;
    }

    public boolean confirmLocker() {
        if (!customerActionsEnabled()
                || pendingCredentialMethod == UnlockMethod.FACE) {
            return false;
        }
        return confirmLocker(lockerSelection.selectedTarget());
    }

    public boolean finishUnlockFailure() {
        return finishUnlock(ResultContext.UNLOCK_FAILURE);
    }

    public boolean finishSuccessfulRequest() {
        return finishUnlock(ResultContext.UNLOCK_SUCCESS);
    }

    private boolean finishUnlock(ResultContext terminalContext) {
        if (!customerActionsEnabled()
                || screen != Screen.RESULT
                || resultContext != ResultContext.NONE
                || lockerSelection.isInteractionEnabled()
                || !hasPendingRequest()) {
            return false;
        }
        lockerSelection.finishSending();
        resultContext = terminalContext;
        return true;
    }

    public boolean retry() {
        if (!customerActionsEnabled() || screen != Screen.RESULT) {
            return false;
        }
        if (resultContext == ResultContext.DISCOVERY_FAILURE) {
            clearPendingRequest();
            resultContext = ResultContext.NONE;
            lockerSelection.beginDiscovery();
            screen = Screen.LOCKER_SELECTION;
            return true;
        }
        if (resultContext != ResultContext.UNLOCK_FAILURE) {
            return false;
        }
        if (!lockerSelection.isInteractionEnabled()
                || pendingArea == null
                || pendingPage == null
                || pendingSlot == null
                || pendingTarget == null
                || pendingLockerLabel == null
                || lockerSelection.activeArea() != pendingArea
                || lockerSelection.activePage() != pendingPage
                || lockerSelection.selectedSlot() != pendingSlot
                || lockerSelection.selectedTarget() != pendingTarget) {
            returnHome();
            return false;
        }

        clearPendingRequest();
        resultContext = ResultContext.NONE;
        screen = Screen.LOCKER_SELECTION;
        return true;
    }

    public boolean openAdminPin() {
        if (screen != Screen.HOME) {
            return false;
        }
        screen = Screen.ADMIN_PIN;
        return true;
    }

    public boolean back() {
        if (screen == Screen.HOME) {
            return false;
        }
        if (screen == Screen.RESULT) {
            if (resultContext == ResultContext.DISCOVERY_FAILURE
                    || resultContext == ResultContext.UNLOCK_FAILURE) {
                return retry();
            }
            if (resultContext != ResultContext.UNLOCK_SUCCESS) {
                return false;
            }
        }
        returnHome();
        return true;
    }

    public void returnHome() {
        lockerSelection.clearSelection();
        clearCredential();
        unavailableMethod = null;
        clearPendingRequest();
        resultContext = ResultContext.NONE;
        screen = Screen.HOME;
    }

    private boolean hasPendingRequest() {
        return pendingArea != null
                && pendingPage != null
                && pendingSlot != null
                && pendingTarget != null
                && pendingLockerLabel != null;
    }

    private void clearPendingRequest() {
        pendingArea = null;
        pendingPage = null;
        pendingSlot = null;
        pendingTarget = null;
        pendingLockerLabel = null;
    }

    private void clearCredential() {
        credentials.clearAll();
        credentials.select(UnlockMethod.PHONE);
        pendingFaceVerification = null;
        pendingCredentialMethod = null;
        pendingCredential = null;
    }

    private void expireFaceCredential() {
        lockerSelection.finishSending();
        lockerSelection.beginDiscovery();
        clearCredential();
        unavailableMethod = null;
        clearPendingRequest();
        resultContext = ResultContext.NONE;
        screen = Screen.FACE_RECOGNITION;
    }

    private static boolean isUnavailable(UnlockMethod method) {
        return method == UnlockMethod.PALM;
    }
}
