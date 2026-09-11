package com.codex.lockertest.ui;

import com.codex.lockertest.model.UnlockMethod;
import com.codex.lockertest.runtime.CredentialAdmission;
import com.codex.lockertest.runtime.CredentialAdmissionPolicy;

public final class HomeCredentialModel {
    private final NumericKeypadModel phone = new NumericKeypadModel(11, false);
    private final NumericKeypadModel password = new NumericKeypadModel(6, true);
    private final CredentialAdmissionPolicy credentialPolicy;

    private UnlockMethod activeMethod = UnlockMethod.PHONE;
    private String validationError;

    public HomeCredentialModel(CredentialAdmissionPolicy credentialPolicy) {
        if (credentialPolicy == null) {
            throw new IllegalArgumentException("Credential policy is required");
        }
        this.credentialPolicy = credentialPolicy;
    }

    public boolean select(UnlockMethod method) {
        if (method != UnlockMethod.PHONE && method != UnlockMethod.PASSWORD) {
            return false;
        }
        activeMethod = method;
        validationError = null;
        return true;
    }

    public boolean pressDigit(char digit) {
        boolean changed = activeModel().pressDigit(digit);
        if (changed) {
            validationError = null;
        }
        return changed;
    }

    public boolean delete() {
        boolean changed = activeModel().delete();
        if (changed) {
            validationError = null;
        }
        return changed;
    }

    public void clear() {
        activeModel().clear();
        validationError = null;
    }

    public String rawValue(UnlockMethod method) {
        NumericKeypadModel model = modelFor(method);
        return model == null ? "" : model.rawValue();
    }

    public String displayValue(UnlockMethod method) {
        NumericKeypadModel model = modelFor(method);
        return model == null ? "" : model.displayValue();
    }

    public UnlockMethod activeMethod() {
        return activeMethod;
    }

    public String validationError() {
        return validationError;
    }

    public boolean validate() {
        CredentialAdmission admission = credentialPolicy.admit(
                activeMethod, activeModel().rawValue());
        if (admission == null) {
            throw new IllegalStateException("Credential policy returned no admission");
        }
        validationError = admission.message();
        return admission.accepted();
    }

    public void clearAll() {
        phone.clear();
        password.clear();
        validationError = null;
    }

    private NumericKeypadModel activeModel() {
        return activeMethod == UnlockMethod.PHONE ? phone : password;
    }

    private NumericKeypadModel modelFor(UnlockMethod method) {
        if (method == UnlockMethod.PHONE) {
            return phone;
        }
        if (method == UnlockMethod.PASSWORD) {
            return password;
        }
        return null;
    }
}
