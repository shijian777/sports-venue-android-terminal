package com.codex.lockertest.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.TextView;

/** Persistent status banner for face-recognition production screens. */
public final class FaceSecurityBanner extends TextView {
    public static final String DEFAULT_TEXT = "身份识别状态";
    private static final int MAX_CHARACTERS = 120;

    public FaceSecurityBanner(Context context) {
        super(context);
        setGravity(Gravity.CENTER);
        setTextColor(Color.rgb(118, 55, 0));
        setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                ZipKioskShell.unit(context, 14));
        int horizontal = ZipKioskShell.unit(context, 14);
        setPadding(horizontal, 0, horizontal, 0);
        setBackground(UiKit.roundedSolid(
                context,
                Color.rgb(255, 232, 183),
                ZipKioskShell.designDp(context, 10),
                Color.rgb(235, 139, 35),
                ZipKioskShell.designDp(context, 2)));
        setClickable(false);
        setFocusable(false);
        setBannerText(DEFAULT_TEXT);
    }

    public void setBannerText(CharSequence text) {
        CharSequence safe = isBlank(text) ? DEFAULT_TEXT : text;
        if (safe.length() > MAX_CHARACTERS) {
            safe = safe.subSequence(0, MAX_CHARACTERS);
        }
        setText(safe);
        setContentDescription(safe);
    }

    private static boolean isBlank(CharSequence value) {
        if (value == null || value.length() == 0) return true;
        for (int index = 0; index < value.length(); index++) {
            if (!Character.isWhitespace(value.charAt(index))) return false;
        }
        return true;
    }
}
