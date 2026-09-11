package com.codex.lockertest.admin;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import com.codex.lockertest.face.FaceBuildVariant;

/** Internal maintenance handoff. No credentials or server tokens are placed in an Intent. */
public abstract class OnlineMaintenanceActivity extends Activity {
    public static final String EXTRA_TICKET = "com.codex.lockertest.MAINTENANCE_TICKET";
    private final Handler authorizationHandler = new Handler(Looper.getMainLooper());
    private OnlineMaintenanceGrant.Lease maintenanceLease;
    protected abstract OnlineMaintenanceGrant.Target maintenanceTarget();

    private final Runnable authorizationCheck = new Runnable() {
        @Override public void run() {
            if (!maintenanceAuthorized()) { finish(); return; }
            authorizationHandler.postDelayed(this, 250);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!FaceBuildVariant.isLocalDemo()) {
            maintenanceLease = OnlineMaintenanceGrant.shared().claim(
                    getIntent().getStringExtra(EXTRA_TICKET), maintenanceTarget());
            if (!maintenanceAuthorized()) finish();
        }
    }

    @Override protected void onStart() {
        super.onStart();
        if (!maintenanceAuthorized()) { finish(); return; }
        authorizationHandler.removeCallbacks(authorizationCheck);
        if (!FaceBuildVariant.isLocalDemo()) authorizationCheck.run();
    }

    protected final boolean maintenanceAuthorized() {
        return FaceBuildVariant.isLocalDemo()
                || maintenanceLease != null && maintenanceLease.authorized();
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (!maintenanceAuthorized()) { finish(); return true; }
        return super.dispatchTouchEvent(event);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (!maintenanceAuthorized()) { finish(); return true; }
        return super.dispatchKeyEvent(event);
    }

    @Override protected void onStop() {
        authorizationHandler.removeCallbacksAndMessages(null);
        // Returning to the menu keeps its original short session; backgrounding revokes it.
        if (maintenanceLease != null) maintenanceLease.finish(!isFinishing());
        maintenanceLease = null;
        super.onStop();
    }

    @Override protected void onDestroy() {
        authorizationHandler.removeCallbacksAndMessages(null);
        if (maintenanceLease != null) maintenanceLease.finish(false);
        maintenanceLease = null;
        super.onDestroy();
    }
}
