package com.codex.lockertest.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class NumericKeypadView extends LinearLayout {
    public interface Listener {
        void onValueChanged(String rawValue, String displayValue);
    }

    private static final String[][] KEYS = {
            {"1", "2", "3"},
            {"4", "5", "6"},
            {"7", "8", "9"},
            {"清空", "0", "删除"}
    };

    private final NumericKeypadModel model;
    private final TextView display;
    private Listener listener;
    private CharSequence hint = "请输入";

    public NumericKeypadView(Context context, int maxLength, boolean masked) {
        super(context);
        model = new NumericKeypadModel(maxLength, masked);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);

        display = UiKit.text(context, hint, 25, UiKit.MUTED, Typeface.BOLD);
        display.setGravity(Gravity.CENTER);
        display.setSingleLine(true);
        display.setPadding(UiKit.dp(context, 14), 0, UiKit.dp(context, 14), 0);
        display.setBackground(UiKit.roundedSolid(
                context, Color.WHITE, 10, Color.rgb(153, 207, 221), 1));
        addView(display, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(context, 58)));

        LinearLayout grid = new LinearLayout(context);
        grid.setOrientation(VERTICAL);
        grid.setPadding(0, UiKit.dp(context, 8), 0, 0);
        addView(grid, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        for (String[] rowKeys : KEYS) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            grid.addView(row, new LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            for (int index = 0; index < rowKeys.length; index++) {
                String label = rowKeys[index];
                Button key = createKey(context, label);
                LayoutParams keyParams = new LayoutParams(0,
                        ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                keyParams.setMargins(
                        index == 0 ? 0 : UiKit.dp(context, 4),
                        UiKit.dp(context, 4),
                        0,
                        0);
                row.addView(key, keyParams);
            }
        }
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setHint(CharSequence hint) {
        this.hint = hint == null ? "" : hint;
        refreshDisplay(false);
    }

    public String rawValue() {
        return model.rawValue();
    }

    public String displayValue() {
        return model.displayValue();
    }

    public void clearInput() {
        if (model.rawValue().isEmpty()) {
            return;
        }
        model.clear();
        refreshDisplay(true);
    }

    private Button createKey(Context context, String label) {
        boolean action = "清空".equals(label) || "删除".equals(label);
        Button key = UiKit.button(
                context,
                label,
                action ? 17 : 25,
                action ? Color.rgb(226, 239, 243) : Color.WHITE,
                action ? UiKit.MUTED : UiKit.NAVY,
                9);
        key.setContentDescription(label);
        key.setOnClickListener(view -> handleKey(label));
        return key;
    }

    private void handleKey(String label) {
        boolean changed;
        if ("清空".equals(label)) {
            changed = !model.rawValue().isEmpty();
            model.clear();
        } else if ("删除".equals(label)) {
            changed = model.delete();
        } else {
            changed = label.length() == 1 && model.pressDigit(label.charAt(0));
        }
        if (changed) {
            refreshDisplay(true);
        }
    }

    private void refreshDisplay(boolean notify) {
        String shown = model.displayValue();
        display.setText(shown.isEmpty() ? hint : shown);
        display.setTextColor(shown.isEmpty() ? UiKit.MUTED : UiKit.NAVY);
        if (notify && listener != null) {
            listener.onValueChanged(model.rawValue(), shown);
        }
    }
}
