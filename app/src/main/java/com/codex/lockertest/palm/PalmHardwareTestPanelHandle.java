package com.codex.lockertest.palm;

import android.view.View;

/** UI-scoped diagnostic resource; carries no authentication or locker authority. */
public interface PalmHardwareTestPanelHandle {
    View view();
    void close();
}
