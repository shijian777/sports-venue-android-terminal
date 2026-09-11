package com.codex.lockertest.runtime;

import com.codex.lockertest.model.FeatureAvailability;

/** Required runtime policy dependencies selected by a source-set assembly. */
public final class RuntimeServices {
    private final CredentialAdmissionPolicy credentialPolicy;
    private final AdminCredentialPolicy adminPolicy;
    private final CustomerUnlockAuthorizer unlockAuthorizer;
    private final InitialLayoutPolicy layoutPolicy;
    private final FeatureAvailability featureAvailability;
    private final boolean localDemo;

    public RuntimeServices(
            CredentialAdmissionPolicy credentialPolicy,
            AdminCredentialPolicy adminPolicy,
            CustomerUnlockAuthorizer unlockAuthorizer,
            InitialLayoutPolicy layoutPolicy,
            FeatureAvailability featureAvailability,
            boolean localDemo) {
        this.credentialPolicy = requireDependency(credentialPolicy, "Credential policy");
        this.adminPolicy = requireDependency(adminPolicy, "Admin policy");
        this.unlockAuthorizer = requireDependency(unlockAuthorizer, "Unlock authorizer");
        this.layoutPolicy = requireDependency(layoutPolicy, "Layout policy");
        this.featureAvailability = requireDependency(featureAvailability, "Feature availability");
        this.localDemo = localDemo;
    }

    public CredentialAdmissionPolicy credentialPolicy() { return credentialPolicy; }
    public AdminCredentialPolicy adminPolicy() { return adminPolicy; }
    public CustomerUnlockAuthorizer unlockAuthorizer() { return unlockAuthorizer; }
    public InitialLayoutPolicy layoutPolicy() { return layoutPolicy; }
    public FeatureAvailability featureAvailability() { return featureAvailability; }
    public boolean localDemo() { return localDemo; }

    private static <T> T requireDependency(T dependency, String name) {
        if (dependency == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return dependency;
    }
}
