package com.codex.lockertest.ui.zip;

/** Immutable, Android-free action contract for one return-journey screen. */
public final class ReturnScreenPresentation {
    public enum Surface {
        AUTHENTICATION,
        LOCKER_LIST,
        PROGRESS
    }

    public enum RetryKind {
        NONE,
        AUTH,
        QUERY,
        AUTHORIZATION,
        STATUS,
        COMMIT
    }

    private final ZipScreenAsset asset;
    private final Surface surface;
    private final boolean canBack;
    private final boolean canCancel;
    private final boolean canIdentity;
    private final boolean canSelect;
    private final boolean canConfirmLocker;
    private final boolean canDoorCloseConfirm;
    private final boolean canHome;
    private final RetryKind retryKind;
    private final boolean autoHome;

    ReturnScreenPresentation(
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
        if (asset == null || surface == null || retryKind == null) {
            throw new IllegalArgumentException("Return presentation values are required");
        }
        this.asset = asset;
        this.surface = surface;
        this.canBack = canBack;
        this.canCancel = canCancel;
        this.canIdentity = canIdentity;
        this.canSelect = canSelect;
        this.canConfirmLocker = canConfirmLocker;
        this.canDoorCloseConfirm = canDoorCloseConfirm;
        this.canHome = canHome;
        this.retryKind = retryKind;
        this.autoHome = autoHome;
    }

    public ZipScreenAsset asset() {
        return asset;
    }

    public Surface surface() {
        return surface;
    }

    public boolean canBack() {
        return canBack;
    }

    public boolean canCancel() {
        return canCancel;
    }

    public boolean canRetry() {
        return retryKind != RetryKind.NONE;
    }

    public boolean canIdentity() {
        return canIdentity;
    }

    public boolean canSelect() {
        return canSelect;
    }

    public boolean canConfirmLocker() {
        return canConfirmLocker;
    }

    public boolean canDoorCloseConfirm() {
        return canDoorCloseConfirm;
    }

    public boolean canHome() {
        return canHome;
    }

    public RetryKind retryKind() {
        return retryKind;
    }

    public boolean autoHome() {
        return autoHome;
    }
}
