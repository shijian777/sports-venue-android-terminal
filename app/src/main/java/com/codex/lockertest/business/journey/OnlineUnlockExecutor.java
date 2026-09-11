package com.codex.lockertest.business.journey;

import com.codex.lockertest.unlock.AuthorizedUnlockRequest;
import com.codex.lockertest.unlock.UnlockCoordinator;

/** Optional physical dispatch boundary for an authorized online cabinet selection. */
public interface OnlineUnlockExecutor {
    interface Listener {
        void onState(UnlockCoordinator.State state, String detail);

        default boolean isCurrent() {
            return false;
        }

        default boolean registerCancellation(
                OnlineCustomerCoordinator.Cancellable handle) {
            return false;
        }
    }

    boolean isAvailable();

    OnlineCustomerCoordinator.Cancellable execute(
            AuthorizedUnlockRequest request, Listener listener);
}
