package com.codex.lockertest.ui.business;

import android.text.InputType;
import android.view.inputmethod.EditorInfo;

/** Android editor attributes for administrator credentials. */
final class AdminCredentialInputPolicy {
    private AdminCredentialInputPolicy() { }

    static int accountInputType() {
        return InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
    }

    static int secretInputType() {
        return InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;
    }

    static int imeOptions() {
        return EditorInfo.IME_FLAG_FORCE_ASCII;
    }
}
