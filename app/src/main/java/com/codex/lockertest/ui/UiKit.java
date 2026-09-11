package com.codex.lockertest.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.widget.Button;
import android.widget.TextView;

public final class UiKit {
    public static final int DEEP_BLUE = Color.rgb(3, 40, 102);
    public static final int NAVY = Color.rgb(8, 53, 113);
    public static final int BLUE = Color.rgb(31, 112, 226);
    public static final int CYAN = Color.rgb(35, 186, 229);
    public static final int GREEN = Color.rgb(30, 193, 139);
    public static final int DARK_GREEN = Color.rgb(9, 119, 84);
    public static final int TEXT = Color.rgb(47, 70, 80);
    public static final int MUTED = Color.rgb(103, 125, 132);
    public static final int RED = Color.rgb(220, 65, 75);
    public static final int AMBER = Color.rgb(235, 143, 28);
    public static final int PANEL = Color.argb(242, 255, 255, 255);

    private UiKit() {
    }

    public static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    public static TextView text(
            Context context,
            CharSequence value,
            float sizeSp,
            int color,
            int style) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans-serif", style));
        view.setIncludeFontPadding(false);
        return view;
    }

    public static Button button(
            Context context,
            CharSequence value,
            float sizeSp,
            int backgroundColor,
            int foregroundColor,
            float radiusDp) {
        Button button = new Button(context);
        button.setText(value);
        button.setTextSize(sizeSp);
        button.setTextColor(foregroundColor);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(context, 8), 0, dp(context, 8), 0);
        button.setBackground(pressableSolid(context, backgroundColor, radiusDp));
        return button;
    }

    public static GradientDrawable roundedSolid(
            Context context,
            int color,
            float radiusDp,
            int strokeColor,
            float strokeDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(context, radiusDp));
        if (strokeDp > 0f) {
            drawable.setStroke(Math.max(1, dp(context, strokeDp)), strokeColor);
        }
        return drawable;
    }

    public static GradientDrawable roundedGradient(
            Context context,
            int startColor,
            int endColor,
            float radiusDp) {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{startColor, endColor});
        drawable.setCornerRadius(dp(context, radiusDp));
        return drawable;
    }

    public static StateListDrawable pressableSolid(
            Context context,
            int color,
            float radiusDp) {
        StateListDrawable states = new StateListDrawable();
        states.addState(
                new int[]{android.R.attr.state_pressed},
                roundedSolid(context, darken(color, 0.84f), radiusDp, Color.TRANSPARENT, 0));
        states.addState(
                new int[]{-android.R.attr.state_enabled},
                roundedSolid(context, Color.rgb(173, 188, 194), radiusDp,
                        Color.TRANSPARENT, 0));
        states.addState(
                new int[0],
                roundedSolid(context, color, radiusDp, Color.TRANSPARENT, 0));
        return states;
    }

    private static int darken(int color, float factor) {
        return Color.argb(
                Color.alpha(color),
                Math.round(Color.red(color) * factor),
                Math.round(Color.green(color) * factor),
                Math.round(Color.blue(color) * factor));
    }
}
