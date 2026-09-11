package com.codex.lockertest.face.verification;

import android.content.Context;

public final class FaceVerificationAssembly {
    private final FaceVerificationEnvironment environment;
    private final FaceVerificationClient client;

    private FaceVerificationAssembly(FaceVerificationEnvironment environment,
            FaceVerificationClient client) {
        this.environment = environment;
        this.client = client;
    }

    public static FaceVerificationAssembly create(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context cannot be null");
        }
        FaceVerificationEnvironment.EnabledStateStore disabledStore =
                new FaceVerificationEnvironment.EnabledStateStore() {
                    @Override
                    public boolean load() {
                        return false;
                    }

                    @Override
                    public void save(boolean enabled) {
                        // Production never persists or accepts a demo enable operation.
                    }
                };
        FaceVerificationEnvironment.IssuerCapability issuerCapability =
                FaceVerificationEnvironment.newIssuerCapability();
        FaceVerificationEnvironment environment = new FaceVerificationEnvironment(
                FaceVerificationSource.REMOTE_SERVER, false, disabledStore,
                FaceVerificationEnvironment::secureTicketId, issuerCapability);
        FaceVerificationClient client = new UnavailableFaceVerificationClient();
        return new FaceVerificationAssembly(environment, client);
    }

    public FaceVerificationEnvironment environment() {
        return environment;
    }

    public FaceVerificationClient client() {
        return client;
    }
}
