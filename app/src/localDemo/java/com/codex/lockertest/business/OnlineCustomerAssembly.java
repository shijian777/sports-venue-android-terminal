package com.codex.lockertest.business;

import android.content.Context;
import com.codex.lockertest.ui.business.OnlineCustomerHost;
import com.codex.lockertest.business.journey.OnlineUnlockExecutor;

/** Keep existing localDemo interactions; no live customer service is assembled here. */
public final class OnlineCustomerAssembly {
    private OnlineCustomerAssembly() { }
    public static OnlineCustomerHost create(Context context, OnlineCustomerHost.Ui ui) { return null; }
    public static OnlineCustomerHost create(Context context, OnlineCustomerHost.Ui ui,
            OnlineUnlockExecutor executor) { return null; }
}
