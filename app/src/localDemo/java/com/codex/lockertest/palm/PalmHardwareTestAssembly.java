package com.codex.lockertest.palm;

import android.content.Context;

public final class PalmHardwareTestAssembly {
    private PalmHardwareTestAssembly() { }

    public static PalmHardwareTestPanelHandle create(Context context, Runnable onBack) {
        return new PalmHardwareTestPanel(context, onBack);
    }
}
