package com.codex.lockertest.runtime;

/** localDemo source-set assembly for deterministic local fixture behavior. */
public final class RuntimeAssembly {
    private RuntimeAssembly() {
    }

    public static RuntimeServices create(android.content.Context context) {
        return new RuntimeServices(
                new LocalDemoCredentialAdmissionPolicy(),
                new LocalDemoAdminCredentialPolicy(),
                new LocalDemoCustomerUnlockAuthorizer(),
                new LocalDemoInitialLayoutPolicy(),
                DemoFeatureFlags.defaults(),
                true);
    }

    public static FaceBannerPolicy createFaceBannerPolicy() {
        return new FaceBannerPolicy() {
            @Override
            public CharSequence text() {
                return "本机联调：未进行身份比对";
            }

            @Override
            public boolean visible() {
                return true;
            }
        };
    }
}
