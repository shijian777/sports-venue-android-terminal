package com.codex.lockertest.business;

import android.content.Context;
import com.codex.lockertest.server.ProductionBusinessService;
import com.codex.lockertest.ui.business.OnlineCustomerHost;
import com.codex.lockertest.ui.business.AndroidScannerSources;
import com.codex.lockertest.bootstrap.Rk3288DeviceSerialProvider;
import com.codex.lockertest.business.journey.OnlineUnlockExecutor;

/** Live signed service. Reader identity needs an explicit source mapping before automatic uploads. */
public final class OnlineCustomerAssembly {
    private OnlineCustomerAssembly() { }
    public static OnlineCustomerHost create(Context context, OnlineCustomerHost.Ui ui) {
        return create(context, ui, null);
    }
    public static OnlineCustomerHost create(Context context, OnlineCustomerHost.Ui ui,
            OnlineUnlockExecutor executor) {
        return new OnlineCustomerHost(context, ProductionBusinessService::createLive,
                new AndroidScannerSources(context), ui, new Rk3288DeviceSerialProvider(), executor,
                com.codex.lockertest.server.ProductionFaceImageUploadAdapter::createLive);
    }
}
