package com.codex.lockertest.ui.business;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.InputDevice;
import com.codex.lockertest.business.FailClosedScannerSourceStore;
import com.codex.lockertest.business.ScannerSourceRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Stores only hashed hardware identities and roles; never card or QR payloads. */
public final class AndroidScannerSources implements OnlineCustomerHost.ScanSourceResolver {
    private static volatile ScannerSourceRegistry.Store processStore;
    private final ScannerSourceRegistry registry;

    public AndroidScannerSources(Context context) {
        registry = new ScannerSourceRegistry(processStore(context));
    }

    private static ScannerSourceRegistry.Store processStore(Context context) {
        ScannerSourceRegistry.Store current = processStore;
        if (current != null) return current;
        synchronized (AndroidScannerSources.class) {
            current = processStore;
            if (current != null) return current;
            Context application = context.getApplicationContext();
            if (application == null) throw new IllegalArgumentException("Application context required");
            SharedPreferences preferences = application.getSharedPreferences("reader-sources-v1", Context.MODE_PRIVATE);
            ScannerSourceRegistry.Store backing = new ScannerSourceRegistry.Store() {
                public int read(String key) { return preferences.getInt(key, 0); }
                public boolean write(String key, int type) {
                    return (type == 0 ? preferences.edit().remove(key) : preferences.edit().putInt(key, type)).commit();
                }
            };
            current = new FailClosedScannerSourceStore(backing);
            processStore = current;
            return current;
        }
    }
    @Override public int credentialType(int id) { return registry.resolve(identities(), id); }
    @Override public boolean assign(OnlineCustomerHost.ScanInputDevice device, int type) {
        return device != null && registry.assignExpected(identities(), device.id, device.identity, type);
    }
    @Override public List<OnlineCustomerHost.ScanInputDevice> devices() {
        List<ScannerSourceRegistry.Device> identities = identities();
        List<OnlineCustomerHost.ScanInputDevice> result = new ArrayList<>();
        for (ScannerSourceRegistry.Device identity : identities) {
            InputDevice device = InputDevice.getDevice(identity.id());
            if (device == null) continue;
            String name = device.getName();
            if (name == null) name = "输入设备";
            name = name.replaceAll("[\\p{Cntrl}]", " ");
            if (name.length() > 70) name = name.substring(0, 70);
            result.add(new OnlineCustomerHost.ScanInputDevice(identity.id(), name + " · VID "
                    + device.getVendorId() + " / PID " + device.getProductId(),
                    registry.resolve(identities, identity.id()), registry.configurable(identities, identity.id()), identity.identityToken()));
        }
        return result;
    }
    private static List<ScannerSourceRegistry.Device> identities() {
        try {
            List<ScannerSourceRegistry.Device> result = new ArrayList<>();
            for (int id : InputDevice.getDeviceIds()) {
                InputDevice device = InputDevice.getDevice(id);
                if (device == null || device.isVirtual() || !device.supportsSource(InputDevice.SOURCE_KEYBOARD)) continue;
                result.add(new ScannerSourceRegistry.Device(id, device.getDescriptor(), device.getVendorId(), device.getProductId(), true));
            }
            return result;
        } catch (RuntimeException failure) { return Collections.emptyList(); }
    }
}
