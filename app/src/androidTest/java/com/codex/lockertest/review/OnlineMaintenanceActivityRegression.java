package com.codex.lockertest.review;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Build;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;

import com.codex.lockertest.AdminSerialActivity;
import com.codex.lockertest.FaceSdkAdminActivity;
import com.codex.lockertest.admin.OnlineMaintenanceActivity;
import com.codex.lockertest.admin.OnlineMaintenanceGrant;
import com.codex.lockertest.face.FaceBuildVariant;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Emulator-only real guard lifecycle checks. No Activity is launched through the system.
 * The concrete screens are constructed only to read their target bindings: their lifecycle
 * would acquire the serial gateway or check/initialize the Baidu SDK, so it is not invoked.
 */
public final class OnlineMaintenanceActivityRegression {
    private static final String EXTRA_TARGET = "test-only-maintenance-target";

    static void check(Instrumentation test) throws Exception {
        require(isEmulator(), "Maintenance lifecycle regression is emulator-only");
        require(!FaceBuildVariant.isLocalDemo(), "Maintenance authorization regression requires production");
        test.runOnMainSync(() -> {
            require(targetOf(AdminSerialActivity.class) == OnlineMaintenanceGrant.Target.SERIAL,
                    "Serial maintenance activity claims the wrong target");
            require(targetOf(FaceSdkAdminActivity.class) == OnlineMaintenanceGrant.Target.FACE_SDK,
                    "Face maintenance activity claims the wrong target");
        });
        for (OnlineMaintenanceGrant.Target target : OnlineMaintenanceGrant.Target.values()) {
            checkMissingTicket(test, target);
            checkMissingTicketWithPendingGrant(test, target);
            checkWrongTarget(test, target);
            checkNormalReturn(test, target);
            checkBackgroundRevocation(test, target);
            checkLiveRevocation(test, target);
            checkRevokedInput(test, target, true);
            checkRevokedInput(test, target, false);
        }
    }

    private static void checkMissingTicket(Instrumentation test, OnlineMaintenanceGrant.Target target) {
        test.runOnMainSync(() -> {
            GuardActivity activity = create(test, target, null);
            try {
                require(activity.isFinishing() && !activity.allowed(), "Missing ticket entered maintenance");
                test.callActivityOnStart(activity);
                require(activity.isFinishing() && !activity.allowed(), "Missing ticket passed onStart");
            } finally { destroy(test, activity); }
        });
    }

    private static void checkMissingTicketWithPendingGrant(Instrumentation test, OnlineMaintenanceGrant.Target target) {
        test.runOnMainSync(() -> {
            Session session = new Session();
            OnlineMaintenanceGrant.Ticket ticket = issue(target, session);
            GuardActivity activity = create(test, target, null);
            try {
                require(activity.isFinishing() && !activity.allowed(),
                        "Pending grant allowed maintenance without its ticket");
                test.callActivityOnStart(activity);
                require(activity.isFinishing() && !activity.allowed(),
                        "Pending grant allowed a ticketless activity through onStart");
                test.callActivityOnStop(activity);
                destroy(test, activity);
                activity = null;
                OnlineMaintenanceGrant.Lease rightful = OnlineMaintenanceGrant.shared().claim(ticket.id(), target);
                require(rightful != null && rightful.authorized(),
                        "Ticketless activity consumed or invalidated the pending grant");
                rightful.finish(false);
                require(session.authorized.get() && session.revocations == 0,
                        "Ticketless activity revoked the pending administrator session");
            } finally { destroy(test, activity); ticket.finish(false); }
        });
    }

    private static void checkWrongTarget(Instrumentation test, OnlineMaintenanceGrant.Target target) {
        test.runOnMainSync(() -> {
            OnlineMaintenanceGrant.Target other = target == OnlineMaintenanceGrant.Target.SERIAL
                    ? OnlineMaintenanceGrant.Target.FACE_SDK : OnlineMaintenanceGrant.Target.SERIAL;
            Session session = new Session();
            OnlineMaintenanceGrant.Ticket ticket = issue(other, session);
            GuardActivity activity = create(test, target, ticket.id());
            try {
                require(activity.isFinishing() && !activity.allowed(), "Wrong-target ticket entered maintenance");
                OnlineMaintenanceGrant.Lease rightful = OnlineMaintenanceGrant.shared().claim(ticket.id(), other);
                require(rightful != null && rightful.authorized(), "Wrong activity consumed the rightful ticket");
                rightful.finish(false);
                require(session.revocations == 0, "Rejected activity revoked a different target's session");
            } finally { destroy(test, activity); ticket.finish(false); }
        });
    }

    private static void checkNormalReturn(Instrumentation test, OnlineMaintenanceGrant.Target target) {
        test.runOnMainSync(() -> {
            Session session = new Session();
            OnlineMaintenanceGrant.Ticket ticket = issue(target, session);
            GuardActivity activity = create(test, target, ticket.id());
            try {
                test.callActivityOnStart(activity);
                require(!activity.isFinishing() && activity.allowed(), "Matching ticket did not enter maintenance");
                require(OnlineMaintenanceGrant.shared().claim(ticket.id(), target) == null,
                        "The activity's claimed ticket could be replayed");
                activity.finish();
                test.callActivityOnStop(activity);
                require(session.authorized.get() && session.revocations == 0,
                        "Normal return revoked the administrator session");
                require(!activity.allowed(), "Returned activity retained its maintenance lease");
            } finally { destroy(test, activity); ticket.finish(false); }
        });
    }

    private static void checkBackgroundRevocation(Instrumentation test, OnlineMaintenanceGrant.Target target) {
        test.runOnMainSync(() -> {
            Session session = new Session();
            OnlineMaintenanceGrant.Ticket ticket = issue(target, session);
            GuardActivity activity = create(test, target, ticket.id());
            try {
                test.callActivityOnStart(activity);
                require(activity.allowed(), "Background fixture did not enter maintenance");
                test.callActivityOnStop(activity);
                require(!session.authorized.get() && session.revocations == 1 && !activity.allowed(),
                        "Background stop did not revoke the administrator session");
                test.callActivityOnStart(activity);
                require(activity.isFinishing() && !activity.allowed(), "Backgrounded maintenance restarted without a ticket");
            } finally { destroy(test, activity); ticket.finish(false); }
        });
    }

    private static void checkLiveRevocation(Instrumentation test, OnlineMaintenanceGrant.Target target) throws Exception {
        GuardActivity[] activity = new GuardActivity[1];
        OnlineMaintenanceGrant.Ticket[] ticket = new OnlineMaintenanceGrant.Ticket[1];
        Session session = new Session();
        test.runOnMainSync(() -> {
            ticket[0] = issue(target, session);
            activity[0] = create(test, target, ticket[0].id());
            test.callActivityOnStart(activity[0]);
            require(activity[0].allowed() && !activity[0].isFinishing(), "Revocation fixture did not enter maintenance");
            session.authorized.set(false);
        });
        try {
            // Poll only the exit signal. Calling allowed() here would itself consume the
            // revoked grant and could conceal a missing periodic authorization check.
            OnlineReaderRegression.await(test, () -> activity[0].isFinishing());
            test.runOnMainSync(() -> require(!activity[0].allowed(), "Revoked activity retained authorization"));
        } finally {
            test.runOnMainSync(() -> { destroy(test, activity[0]); ticket[0].finish(false); });
        }
    }

    private static void checkRevokedInput(Instrumentation test, OnlineMaintenanceGrant.Target target, boolean touch) {
        test.runOnMainSync(() -> {
            Session session = new Session();
            OnlineMaintenanceGrant.Ticket ticket = issue(target, session);
            GuardActivity activity = create(test, target, ticket.id());
            try {
                test.callActivityOnStart(activity);
                require(activity.allowed(), "Input fixture did not enter maintenance");
                session.authorized.set(false);
                boolean consumed;
                if (touch) {
                    long now = SystemClock.uptimeMillis();
                    MotionEvent event = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 10, 10, 0);
                    try { consumed = activity.dispatchTouchEvent(event); }
                    finally { event.recycle(); }
                } else consumed = activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
                require(consumed && activity.isFinishing(),
                        touch ? "Revoked touch reached maintenance controls" : "Revoked key reached maintenance controls");
            } finally { destroy(test, activity); ticket.finish(false); }
        });
    }

    @SuppressWarnings("deprecation")
    private static GuardActivity create(Instrumentation test, OnlineMaintenanceGrant.Target target, String ticket) {
        Context context = test.getTargetContext();
        Intent intent = new Intent(context, GuardActivity.class).putExtra(EXTRA_TARGET, target.name());
        if (ticket != null) intent.putExtra(OnlineMaintenanceActivity.EXTRA_TICKET, ticket);
        ActivityInfo info = new ActivityInfo();
        info.applicationInfo = context.getApplicationInfo();
        info.packageName = context.getPackageName();
        info.name = GuardActivity.class.getName();
        try {
            // Attach a real Android Activity without a task/window launch. A null system token
            // is deliberate; finish() is observed below instead of contacting ActivityManager.
            GuardActivity activity = (GuardActivity) test.newActivity(GuardActivity.class, context, null,
                    (Application) context.getApplicationContext(), intent, info, "Guard regression", null, null, null);
            test.callActivityOnCreate(activity, null);
            return activity;
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Cannot attach isolated maintenance guard", failure);
        }
    }

    private static void destroy(Instrumentation test, GuardActivity activity) {
        if (activity == null) return;
        // Test teardown is a normal return, so failed assertions never intentionally revoke
        // an unrelated session. onDestroy removes the production polling callbacks.
        activity.finish();
        test.callActivityOnDestroy(activity);
    }

    private static OnlineMaintenanceGrant.Ticket issue(OnlineMaintenanceGrant.Target target, Session session) {
        OnlineMaintenanceGrant.Ticket ticket = OnlineMaintenanceGrant.shared().issue(target,
                session.authorized::get, () -> { session.revocations++; session.authorized.set(false); });
        require(ticket != null, "Authorized fixture could not issue a maintenance ticket");
        return ticket;
    }

    private static OnlineMaintenanceGrant.Target targetOf(Class<? extends Activity> type) {
        try {
            Activity activity = type.getDeclaredConstructor().newInstance();
            Method target = type.getDeclaredMethod("maintenanceTarget");
            target.setAccessible(true);
            return (OnlineMaintenanceGrant.Target) target.invoke(activity);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Cannot inspect concrete maintenance target", failure);
        }
    }

    private static boolean isEmulator() {
        return "goldfish".equals(Build.HARDWARE) || "ranchu".equals(Build.HARDWARE)
                || Build.PRODUCT != null && Build.PRODUCT.startsWith("sdk");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static final class Session {
        final AtomicBoolean authorized = new AtomicBoolean(true);
        int revocations;
    }

    /** Only the OS finish boundary is replaced; production grant and guard methods execute. */
    public static final class GuardActivity extends OnlineMaintenanceActivity {
        private boolean finishing;
        @Override protected OnlineMaintenanceGrant.Target maintenanceTarget() {
            return OnlineMaintenanceGrant.Target.valueOf(getIntent().getStringExtra(EXTRA_TARGET));
        }
        @Override public void finish() { finishing = true; }
        @Override public boolean isFinishing() { return finishing; }
        boolean allowed() { return maintenanceAuthorized(); }
    }
}
