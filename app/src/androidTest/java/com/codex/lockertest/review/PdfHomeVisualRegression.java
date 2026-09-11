package com.codex.lockertest.review;

import android.app.Instrumentation;
import android.os.Bundle;

/** Focused entry point: real Android home views, no network or hardware. */
public final class PdfHomeVisualRegression extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        Throwable[] failure = {null};
        runOnMainSync(() -> {
            try { OnlineHomeRegression.check(getTargetContext()); }
            catch (Throwable value) { failure[0] = value; }
        });
        Bundle result = new Bundle();
        result.putString("stream", failure[0] == null ? "PDF_HOME_VISUAL=PASS\n"
                : "PDF_HOME_VISUAL=FAIL " + failure[0] + "\n");
        finish(failure[0] == null ? -1 : 0, result);
    }
}
