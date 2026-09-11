package com.codex.lockertest.palm;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;

import com.veinauthen.Standalone.JXPalmApi;
import com.veinauthen.Standalone.JXPalmCaptureThread;
import com.veinauthen.Standalone.JXPalmEnrollListener;
import com.veinauthen.Standalone.JXPalmMatchListener;
import com.veinauthen.Standalone.JXPalmStandaloneTool;
import com.veinauthen.palm.JXImage;
import com.veinauthen.palm.JXPalmConfig;
import com.veinauthen.palm.JXPalmFeature;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Local-demo-only adapter for a bounded diagnostic session with the pinned 2.2.17 SDK. */
public final class JxPalmTestDriver implements PalmTestController.Driver {
    public static final int ERROR_NO_DEVICE = -2001;
    public static final int ERROR_PERMISSION_DENIED = -2002;
    public static final int ERROR_MULTIPLE_DEVICES = -2003;
    public static final int ERROR_NATIVE_UNAVAILABLE = -2004;
    public static final int ERROR_OWNER_BUSY = -2005;
    public static final int ERROR_PROCESS_POISONED = -2006;
    public static final int ERROR_INVALID_FEATURE = -2007;
    public static final int ERROR_MATCH_INTEGRITY = -2008;

    private static final String TEST_GROUP = "codex_local_palm_test_single_v1";
    private static final String TEST_UID = "diagnostic-template";
    private static final int TEST_INDEX = 0;
    private static final AtomicReference<JxPalmTestDriver> OWNER =
            new AtomicReference<JxPalmTestDriver>();
    private static final AtomicBoolean PROCESS_POISONED = new AtomicBoolean();
    private static final AtomicLong ACTION_SEQUENCE = new AtomicLong();

    private final Context context;
    private final Executor worker;
    private final UsbManager usbManager;
    private final AtomicBoolean invalidated = new AtomicBoolean();
    private final AtomicLong connectionGeneration = new AtomicLong();
    private final AtomicLong operationGeneration = new AtomicLong();

    private volatile PalmTestController.Driver.Events connectionEvents;
    private volatile UsbDevice selectedDevice;
    private BroadcastReceiver receiver;
    private PendingIntent permissionIntent;
    private boolean receiverRegistered;
    private volatile boolean connected;
    private boolean nativeAttempted;
    private NativeAccess nativeAccess;

    public JxPalmTestDriver(Context context, Executor serializedWorker) {
        if (context == null || serializedWorker == null) {
            throw new IllegalArgumentException("palm driver dependencies cannot be null");
        }
        Context application = context.getApplicationContext();
        this.context = application == null ? context : application;
        this.worker = serializedWorker;
        this.usbManager = (UsbManager) this.context.getSystemService(Context.USB_SERVICE);
    }

    /** Immediately revokes pending handoffs. It deliberately performs no SDK or USB operation. */
    public void invalidate() {
        invalidated.set(true);
        connectionGeneration.incrementAndGet();
        operationGeneration.incrementAndGet();
    }

    @Override
    public void connect(PalmTestController.Driver.Events events) {
        requireEvents(events);
        if (invalidated.get()) return;
        if (PROCESS_POISONED.get()) {
            publishFailure(events, ERROR_PROCESS_POISONED);
            return;
        }
        if (!OWNER.compareAndSet(null, this)) {
            publishFailure(events, ERROR_OWNER_BUSY);
            return;
        }

        final long session = connectionGeneration.incrementAndGet();
        operationGeneration.incrementAndGet();
        connectionEvents = events;
        connected = false;

        if (usbManager == null) {
            failConnection(session, events, ERROR_NO_DEVICE);
            return;
        }
        UsbDevice candidate = null;
        int count = 0;
        for (Map.Entry<String, UsbDevice> entry : usbManager.getDeviceList().entrySet()) {
            UsbDevice device = entry.getValue();
            if (device != null && PalmUsbDeviceFilter.isSupported(
                    device.getVendorId(), device.getProductId())) {
                candidate = device;
                count++;
            }
        }
        if (count == 0) {
            failConnection(session, events, ERROR_NO_DEVICE);
            return;
        }
        if (count != 1) {
            failConnection(session, events, ERROR_MULTIPLE_DEVICES);
            return;
        }

        selectedDevice = candidate;
        String permissionAction = context.getPackageName() + ".PALM_USB_PERMISSION."
                + ACTION_SEQUENCE.incrementAndGet() + "." + session;
        if (!registerReceiver(session, permissionAction, candidate, events)) {
            failConnection(session, events, ERROR_PERMISSION_DENIED);
            return;
        }
        if (usbManager.hasPermission(candidate)) {
            initializeSelected(session, candidate, events);
            return;
        }
        try {
            Intent permission = new Intent(permissionAction).setPackage(context.getPackageName());
            int flags = PendingIntent.FLAG_CANCEL_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            permissionIntent = PendingIntent.getBroadcast(
                    context, actionRequestCode(session), permission, flags);
            usbManager.requestPermission(candidate, permissionIntent);
        } catch (RuntimeException exception) {
            failConnection(session, events, ERROR_PERMISSION_DENIED);
        }
    }

    @Override
    public void enroll(final PalmTestController.Driver.Events events) {
        requireEvents(events);
        final NativeAccess current = readyNativeAccess(events);
        if (current == null) return;
        final long session = connectionGeneration.get();
        final long operation = operationGeneration.incrementAndGet();
        final AtomicBoolean delivered = new AtomicBoolean();
        try {
            if (!current.startEnroll(this, session, operation, delivered, events)) {
                publishOperationFailure(session, operation, delivered, events,
                        ERROR_NATIVE_UNAVAILABLE);
            }
        } catch (LinkageError error) {
            publishOperationFailure(session, operation, delivered, events,
                    ERROR_NATIVE_UNAVAILABLE);
        } catch (RuntimeException exception) {
            publishOperationFailure(session, operation, delivered, events,
                    ERROR_NATIVE_UNAVAILABLE);
        }
    }

    @Override
    public void capture(final PalmTestController.Driver.Events events) {
        requireEvents(events);
        final NativeAccess current = readyNativeAccess(events);
        if (current == null) return;
        final long session = connectionGeneration.get();
        final long operation = operationGeneration.incrementAndGet();
        final AtomicBoolean delivered = new AtomicBoolean();
        try {
            if (!current.startMatch(this, session, operation, delivered, events)) {
                publishOperationFailure(session, operation, delivered, events,
                        ERROR_NATIVE_UNAVAILABLE);
            }
        } catch (LinkageError error) {
            publishOperationFailure(session, operation, delivered, events,
                    ERROR_NATIVE_UNAVAILABLE);
        } catch (RuntimeException exception) {
            publishOperationFailure(session, operation, delivered, events,
                    ERROR_NATIVE_UNAVAILABLE);
        }
    }

    @Override
    public int verify(byte[] enrolled, byte[] presented) {
        if (!PalmDeviceFeaturePolicy.isValid(enrolled)
                || !PalmDeviceFeaturePolicy.isValid(presented)) {
            return ERROR_INVALID_FEATURE;
        }
        if (invalidated.get() || PROCESS_POISONED.get()) return ERROR_PROCESS_POISONED;
        NativeAccess current = nativeAccess;
        if (!connected || current == null || OWNER.get() != this) return ERROR_NATIVE_UNAVAILABLE;
        try {
            return current.matchOne(enrolled, presented);
        } catch (LinkageError error) {
            return ERROR_NATIVE_UNAVAILABLE;
        } catch (RuntimeException exception) {
            return ERROR_NATIVE_UNAVAILABLE;
        }
    }

    @Override
    public void stop() {
        operationGeneration.incrementAndGet();
        if (!connected) {
            connectionGeneration.incrementAndGet();
            boolean clean = revokeUsbResources();
            if (nativeAttempted || nativeAccess != null) clean &= cleanupNative(true);
            connectionEvents = null;
            selectedDevice = null;
            if (clean) {
                OWNER.compareAndSet(this, null);
            } else {
                poisonProcess();
                throw cleanupFailure();
            }
            return;
        }
        if (!cleanupNative(false)) {
            poisonProcess();
            throw cleanupFailure();
        }
    }

    @Override
    public void close() {
        operationGeneration.incrementAndGet();
        connectionGeneration.incrementAndGet();
        boolean clean = revokeUsbResources();
        if (nativeAttempted || nativeAccess != null) clean &= cleanupNative(true);
        connected = false;
        connectionEvents = null;
        selectedDevice = null;
        if (!clean) {
            poisonProcess();
            throw cleanupFailure();
        }
        nativeAccess = null;
        nativeAttempted = false;
        OWNER.compareAndSet(this, null);
    }

    private boolean registerReceiver(final long session, final String permissionAction,
            final UsbDevice expected, final PalmTestController.Driver.Events events) {
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ignored, Intent intent) {
                if (intent == null || invalidated.get()) return;
                String action = intent.getAction();
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (permissionAction.equals(action)) {
                    if (!isSessionActive(session)) return;
                    final boolean granted = intent.getBooleanExtra(
                            UsbManager.EXTRA_PERMISSION_GRANTED, false);
                    enqueue(new Runnable() {
                        @Override public void run() {
                            handlePermissionResult(session, expected, device, granted, events);
                        }
                    });
                } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)
                        && sameDevice(expected, device)
                        && connectionGeneration.compareAndSet(session, session + 1L)) {
                    operationGeneration.incrementAndGet();
                    handleSelectedDeviceRemoved(events);
                }
            }
        };
        IntentFilter filter = new IntentFilter(permissionAction);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter);
            }
            receiverRegistered = true;
            return true;
        } catch (RuntimeException exception) {
            receiver = null;
            receiverRegistered = false;
            return false;
        }
    }

    private void handlePermissionResult(long session, UsbDevice expected, UsbDevice received,
            boolean granted, PalmTestController.Driver.Events events) {
        if (!isSessionActive(session)) return;
        if (!cancelPermissionIntent()) {
            failConnection(session, events, ERROR_PROCESS_POISONED, true);
            return;
        }
        if (!granted || !sameDevice(expected, received)
                || !isAttached(expected) || !usbManager.hasPermission(expected)) {
            failConnection(session, events, ERROR_PERMISSION_DENIED);
            return;
        }
        initializeSelected(session, expected, events);
    }

    private void initializeSelected(long session, UsbDevice device,
            PalmTestController.Driver.Events events) {
        if (!isSessionActive(session) || !sameDevice(selectedDevice, device)
                || !isAttached(device) || !usbManager.hasPermission(device)) {
            failConnection(session, events, ERROR_PERMISSION_DENIED);
            return;
        }
        int code;
        try {
            nativeAttempted = true;
            NativeAccess created = new NativeAccess();
            nativeAccess = created;
            code = created.initialize(context, device);
        } catch (LinkageError error) {
            failConnection(session, events, ERROR_NATIVE_UNAVAILABLE);
            return;
        } catch (RuntimeException exception) {
            failConnection(session, events, ERROR_NATIVE_UNAVAILABLE);
            return;
        }
        if (code != 0) {
            failConnection(session, events, nativeFailureCode(code));
            return;
        }
        if (!isSessionActive(session) || !isAttached(device)) {
            failConnection(session, events, ERROR_PERMISSION_DENIED);
            return;
        }
        connected = true;
        publishConnected(events);
    }

    private void failConnection(long session, PalmTestController.Driver.Events events, int code) {
        failConnection(session, events, code, false);
    }

    private void failConnection(long session, PalmTestController.Driver.Events events, int code,
            boolean cleanupAlreadyUncertain) {
        boolean deliver = !invalidated.get() && connectionGeneration.get() == session;
        if (connectionGeneration.get() == session) connectionGeneration.incrementAndGet();
        operationGeneration.incrementAndGet();
        boolean clean = !cleanupAlreadyUncertain;
        clean &= revokeUsbResources();
        if (nativeAttempted || nativeAccess != null) clean &= cleanupNative(true);
        connected = false;
        selectedDevice = null;
        connectionEvents = null;
        if (clean) {
            nativeAccess = null;
            nativeAttempted = false;
            OWNER.compareAndSet(this, null);
            if (deliver) publishFailure(events, code);
        } else {
            poisonProcess();
            if (deliver) publishFailure(events, ERROR_PROCESS_POISONED);
        }
    }

    private void handleSelectedDeviceRemoved(PalmTestController.Driver.Events events) {
        if (invalidated.get() || connectionEvents != events) return;
        connected = false;
        publishDisconnected(events);
    }

    private void acceptFeatureCallback(long session, long operation, AtomicBoolean delivered,
            PalmTestController.Driver.Events events, int code,
            PalmDeviceFeaturePolicy.Decision decision, byte[] owned, boolean recycled) {
        if (!recycled) {
            zero(owned);
            publishOperationFailure(session, operation, delivered, events,
                    ERROR_PROCESS_POISONED);
            poisonProcess();
            return;
        }

        if (decision == PalmDeviceFeaturePolicy.Decision.WAIT) return;
        if (!isOperationActive(session, operation) || !delivered.compareAndSet(false, true)) {
            zero(owned);
            return;
        }
        if (owned != null) {
            publishFeature(events, owned);
        } else {
            publishFailure(events, code == 0
                    ? ERROR_INVALID_FEATURE : nativeFailureCode(code));
        }
    }

    private void publishOperationFailure(long session, long operation, AtomicBoolean delivered,
            PalmTestController.Driver.Events events, int code) {
        if (isOperationActive(session, operation) && delivered.compareAndSet(false, true)) {
            publishFailure(events, code);
        }
    }

    private NativeAccess readyNativeAccess(PalmTestController.Driver.Events events) {
        if (invalidated.get()) return null;
        if (PROCESS_POISONED.get()) {
            publishFailure(events, ERROR_PROCESS_POISONED);
            return null;
        }
        NativeAccess current = nativeAccess;
        if (!connected || current == null || OWNER.get() != this) {
            publishFailure(events, OWNER.get() == null
                    ? ERROR_NATIVE_UNAVAILABLE : ERROR_OWNER_BUSY);
            return null;
        }
        return current;
    }

    private boolean cleanupNative(boolean uninitialize) {
        NativeAccess current = nativeAccess;
        if (current == null) return !nativeAttempted;
        return current.cleanup(uninitialize);
    }

    private boolean revokeUsbResources() {
        boolean clean = cancelPermissionIntent();
        if (receiverRegistered && receiver != null) {
            try {
                context.unregisterReceiver(receiver);
            } catch (RuntimeException exception) {
                clean = false;
            }
        }
        receiverRegistered = false;
        receiver = null;
        return clean;
    }

    private boolean cancelPermissionIntent() {
        final PendingIntent current = permissionIntent;
        if (current == null) return true;
        boolean clean = PalmCleanupGuard.attempt(new PalmCleanupGuard.Action() {
            @Override public void run() { current.cancel(); }
        });
        if (clean && permissionIntent == current) permissionIntent = null;
        return clean;
    }

    private boolean isAttached(UsbDevice expected) {
        if (usbManager == null || expected == null) return false;
        for (UsbDevice attached : usbManager.getDeviceList().values()) {
            if (sameDevice(expected, attached)) return true;
        }
        return false;
    }

    private boolean isSessionActive(long session) {
        return !invalidated.get() && connectionGeneration.get() == session
                && OWNER.get() == this && !PROCESS_POISONED.get();
    }

    private boolean isOperationActive(long session, long operation) {
        return isSessionActive(session) && operationGeneration.get() == operation && connected;
    }

    private void enqueue(Runnable task) {
        try {
            worker.execute(task);
        } catch (RuntimeException ignored) {
            operationGeneration.incrementAndGet();
        }
    }

    private static boolean sameDevice(UsbDevice expected, UsbDevice actual) {
        return expected != null && actual != null
                && expected.getVendorId() == actual.getVendorId()
                && expected.getProductId() == actual.getProductId()
                && expected.getDeviceName().equals(actual.getDeviceName());
    }

    private static int actionRequestCode(long session) {
        return (int) (session ^ (session >>> 32));
    }

    private static int nativeFailureCode(int code) {
        return code == 0 ? ERROR_NATIVE_UNAVAILABLE : code;
    }

    private static int verificationFailure(int code) {
        if (code == 0) return ERROR_NATIVE_UNAVAILABLE;
        return code > 0 ? -code : code;
    }

    private static void requireEvents(PalmTestController.Driver.Events events) {
        if (events == null) throw new IllegalArgumentException("palm events cannot be null");
    }

    private static void publishConnected(PalmTestController.Driver.Events events) {
        try { events.onConnected(); } catch (RuntimeException ignored) {}
    }

    private static void publishFeature(PalmTestController.Driver.Events events, byte[] feature) {
        try {
            events.onFeature(feature);
        } catch (RuntimeException exception) {
            zero(feature);
        }
    }

    private static void publishFailure(PalmTestController.Driver.Events events, int code) {
        try { events.onFailure(code); } catch (RuntimeException ignored) {}
    }

    private static void publishDisconnected(PalmTestController.Driver.Events events) {
        try { events.onDisconnected(); } catch (RuntimeException ignored) {}
    }

    private static void zero(byte[] bytes) {
        if (bytes != null) Arrays.fill(bytes, (byte) 0);
    }

    /** Loaded only after one supported, permitted USB device has been selected. */
    private static final class NativeAccess {
        private JXPalmApi api;
        private boolean acquisitionAttempted;

        int initialize(Context context, UsbDevice device) {
            acquisitionAttempted = true;
            JXPalmConfig.setShowPrint(false);
            api = JXPalmStandaloneTool.getPalmInstance();
            return api.initializeDevice(context, device, JXPalmApi.ANDROID_960_OTG_DEVICE);
        }

        boolean startEnroll(final JxPalmTestDriver owner, final long session,
                final long operation, final AtomicBoolean delivered,
                final PalmTestController.Driver.Events events) {
            api.setMatchListener(null);
            api.setEnrollListener(new JXPalmEnrollListener() {
                @Override
                public void onEnroll(int code, int tip, JXPalmFeature feature, JXImage image) {
                    forwardFeature(owner, session, operation, delivered, events,
                            code, feature, image);
                }
            });
            api.startEnroll(30_000);
            return api.startCapture(recyclingCaptureListener(
                    owner, session, operation, delivered, events));
        }

        boolean startMatch(final JxPalmTestDriver owner, final long session,
                final long operation, final AtomicBoolean delivered,
                final PalmTestController.Driver.Events events) {
            api.setEnrollListener(null);
            api.setMatchListener(new JXPalmMatchListener() {
                @Override
                public void onMatch(int code, JXPalmFeature feature, JXImage image) {
                    forwardFeature(owner, session, operation, delivered, events,
                            code, feature, image);
                }

                @Override
                public void onDetected(int code, int[] rect, JXImage image) {
                    if (!recycleDistinct(image, null)) {
                        owner.publishOperationFailure(session, operation, delivered, events,
                                ERROR_PROCESS_POISONED);
                        poisonProcess();
                    }
                }

                @Override
                public void onLiveness(int result, float score) {
                    // The completed onMatch callback remains the single terminal signal.
                }
            });
            return api.startCapture(recyclingCaptureListener(
                    owner, session, operation, delivered, events));
        }

        int matchOne(byte[] enrolled, byte[] presented) {
            int result = ERROR_NATIVE_UNAVAILABLE;
            boolean groupMayExist = false;
            boolean cleanupCertain = true;
            try {
                int code = api.initGroup(TEST_GROUP);
                if (code != 0) {
                    result = verificationFailure(code);
                } else {
                    groupMayExist = true;
                    code = api.clearPalm(TEST_GROUP);
                    if (code != 0) {
                        result = verificationFailure(code);
                    } else if (api.palmCount(TEST_GROUP) != 0) {
                        result = ERROR_MATCH_INTEGRITY;
                    } else {
                        code = api.addPalm(TEST_GROUP, TEST_UID, TEST_INDEX, enrolled);
                        if (code != 0) {
                            result = verificationFailure(code);
                        } else if (api.palmCount(TEST_GROUP) != 1) {
                            result = ERROR_MATCH_INTEGRITY;
                        } else {
                            String[] ids = new String[2];
                            int score = api.palmMatch(TEST_GROUP, presented, ids);
                            if (score <= 0) {
                                result = verificationFailure(score);
                            } else if (!TEST_UID.equals(ids[0])
                                    || !Integer.toString(TEST_INDEX).equals(ids[1])) {
                                result = ERROR_MATCH_INTEGRITY;
                            } else {
                                result = score;
                            }
                        }
                    }
                }
            } finally {
                if (groupMayExist) {
                    try {
                        int clearCode = api.clearPalm(TEST_GROUP);
                        int remaining = api.palmCount(TEST_GROUP);
                        cleanupCertain = clearCode == 0 && remaining == 0;
                    } catch (LinkageError error) {
                        cleanupCertain = false;
                    } catch (RuntimeException exception) {
                        cleanupCertain = false;
                    }
                }
                if (!cleanupCertain) {
                    poisonProcess();
                    result = ERROR_PROCESS_POISONED;
                }
            }
            return result;
        }

        boolean cleanup(boolean uninitialize) {
            final JXPalmApi current = api;
            if (current == null) return !acquisitionAttempted;
            boolean clean = true;
            clean &= attempt(new NativeAction() {
                @Override public void run() { current.setMatchListener(null); }
            });
            clean &= attempt(new NativeAction() {
                @Override public void run() { current.setEnrollListener(null); }
            });
            clean &= attempt(new NativeAction() {
                @Override public void run() { current.cancelEnroll(); }
            });
            clean &= attempt(new NativeAction() {
                @Override public void run() { current.stopEnroll(); }
            });
            clean &= attempt(new NativeAction() {
                @Override public void run() { current.stopCapture(); }
            });
            if (uninitialize) {
                clean &= attempt(new NativeAction() {
                    @Override public void run() { current.unInitializeDevice(); }
                });
            }
            return clean;
        }

        private static void forwardFeature(JxPalmTestDriver owner, long session,
                long operation, AtomicBoolean delivered,
                PalmTestController.Driver.Events events, int code,
                JXPalmFeature feature, JXImage callbackImage) {
            byte[] raw = feature == null ? null : feature.feature;
            PalmDeviceFeaturePolicy.Decision decision =
                    PalmDeviceFeaturePolicy.classify(code, raw);
            byte[] owned = decision == PalmDeviceFeaturePolicy.Decision.SUCCESS
                    ? PalmDeviceFeaturePolicy.copyValidated(raw) : null;
            zero(raw);
            JXImage embeddedImage = feature == null ? null : feature.image;
            boolean recycled = recycleDistinct(callbackImage, embeddedImage);
            owner.acceptFeatureCallback(session, operation, delivered, events,
                    code, decision, owned, recycled);
        }

        private static JXPalmCaptureThread.PalmCaptureListener recyclingCaptureListener(
                final JxPalmTestDriver owner, final long session, final long operation,
                final AtomicBoolean delivered,
                final PalmTestController.Driver.Events events) {
            return new JXPalmCaptureThread.PalmCaptureListener() {
                @Override public void onDistance(int distance) {}

                @Override
                public void onCapture(int code, JXImage vein, JXImage print) {
                    if (!recycleDistinct(vein, print)) {
                        owner.publishOperationFailure(session, operation, delivered, events,
                                ERROR_PROCESS_POISONED);
                        poisonProcess();
                    }
                }
            };
        }

        private static boolean recycleDistinct(JXImage first, JXImage second) {
            boolean clean = recycle(first);
            if (second != first) clean &= recycle(second);
            return clean;
        }

        private static boolean recycle(JXImage image) {
            if (image == null) return true;
            try {
                image.recycle();
                return true;
            } catch (LinkageError error) {
                return false;
            } catch (RuntimeException exception) {
                return false;
            }
        }
    }

    private static boolean attempt(NativeAction action) {
        try {
            action.run();
            return true;
        } catch (LinkageError error) {
            return false;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static void poisonProcess() {
        PROCESS_POISONED.set(true);
    }

    private static IllegalStateException cleanupFailure() {
        return new IllegalStateException(
                "Palm SDK cleanup was incomplete; this process cannot start another session");
    }

    private interface NativeAction { void run(); }
}
