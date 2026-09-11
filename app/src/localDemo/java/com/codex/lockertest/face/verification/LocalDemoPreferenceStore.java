package com.codex.lockertest.face.verification;

final class LocalDemoPreferenceStore
        implements FaceVerificationEnvironment.EnabledStateStore {
    interface BooleanStorage {
        boolean loadDisabledByDefault();
        void save(boolean enabled);
    }

    private final BooleanStorage storage;

    LocalDemoPreferenceStore(BooleanStorage storage) {
        if (storage == null) {
            throw new IllegalArgumentException("storage cannot be null");
        }
        this.storage = storage;
    }

    @Override
    public boolean load() {
        return storage.loadDisabledByDefault();
    }

    @Override
    public void save(boolean enabled) {
        storage.save(enabled);
    }
}
