package com.codex.lockertest.runtime;

import com.codex.lockertest.model.FeatureAvailability;
import com.codex.lockertest.model.UnlockMethod;

/** Production source-set assembly that supplies no local customer authorization path. */
public final class RuntimeAssembly {
    private RuntimeAssembly() {
    }

    public static RuntimeServices create(android.content.Context context) {
        return new RuntimeServices(
                new RejectingCredentialAdmissionPolicy(),
                new UnprovisionedAdminCredentialPolicy(),
                new FailClosedCustomerUnlockAuthorizer(),
                new EmptyInitialLayoutPolicy(),
                new FeatureAvailability() {
                    @Override
                    public boolean isEnabled(UnlockMethod method) {
                        return false;
                    }
                },
                false);
    }

    public static FaceBannerPolicy createFaceBannerPolicy() {
        return new FaceBannerPolicy() {
            @Override
            public CharSequence text() {
                return "";
            }

            @Override
            public boolean visible() {
                return false;
            }
        };
    }
}
