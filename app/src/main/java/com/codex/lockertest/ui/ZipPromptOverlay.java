package com.codex.lockertest.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.codex.lockertest.ui.zip.ZipLockerScreenRouter.ResultActions;

/** Modal ZIP prompt whose button meaning is supplied entirely by {@link ZipPromptModel}. */
public final class ZipPromptOverlay extends FrameLayout {
    public interface ActionCallback {
        void onAction(ZipPromptModel.ActionRole role);
    }

    private boolean actionDelivered;

    /** Pixel-mode prompt: dynamic copy and opaque native actions over an already composed asset. */
    public static ZipPromptOverlay pixelPrompt(
            Context context,
            CharSequence titleCopy,
            CharSequence detailCopy,
            CharSequence supportingCopy,
            ResultActions actions,
            ActionCallback retryAction,
            ActionCallback homeAction) {
        if (actions == null) {
            throw new IllegalArgumentException("actions must not be null");
        }
        ZipPromptOverlay prompt = new ZipPromptOverlay(context, true);
        prompt.setContentDescription(safe(titleCopy) + "，" + safe(detailCopy)
                + "，" + safe(supportingCopy));

        TextView title = pixelText(
                context, safe(titleCopy), 26, UiKit.GREEN, Typeface.BOLD);
        place(prompt, title, 440, 335, 400, 48);

        TextView detail = pixelText(
                context, safe(detailCopy), 18, UiKit.TEXT, Typeface.BOLD);
        place(prompt, detail, 430, 386, 420, 40);

        TextView supporting = pixelText(
                context, safe(supportingCopy), 15, UiKit.TEXT, Typeface.NORMAL);
        place(prompt, supporting, 430, 422, 420, 35);

        FrameLayout actionCover = new FrameLayout(context);
        actionCover.setBackgroundColor(Color.rgb(224, 246, 249));
        actionCover.setClickable(false);
        actionCover.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        place(prompt, actionCover, 430, 466, 420, 118);

        Button retry = pixelButton(
                context, "再次尝试", "再次尝试", UiKit.GREEN, Color.WHITE);
        retry.setOnClickListener(view -> prompt.deliver(
                retryAction, ZipPromptModel.ActionRole.RETRY));
        Button home = pixelButton(
                context, "返回首页", "返回首页",
                Color.rgb(226, 231, 232), UiKit.TEXT);
        home.setOnClickListener(view -> prompt.deliver(
                homeAction, ZipPromptModel.ActionRole.HOME));

        retry.setVisibility(GONE);
        home.setVisibility(GONE);
        if (actions == ResultActions.NONE) {
            retry.setEnabled(false);
            home.setEnabled(false);
        } else if (actions == ResultActions.HOME) {
            home.setVisibility(VISIBLE);
            home.setEnabled(homeAction != null);
            place(prompt, home, 565, 480, 150, 46);
        } else if (actions == ResultActions.RETRY_AND_HOME) {
            retry.setVisibility(VISIBLE);
            home.setVisibility(VISIBLE);
            retry.setEnabled(retryAction != null);
            home.setEnabled(homeAction != null);
            place(prompt, retry, 470, 480, 150, 46);
            place(prompt, home, 660, 480, 150, 46);
        } else {
            throw new IllegalArgumentException("unsupported result actions: " + actions);
        }
        return prompt;
    }

    private ZipPromptOverlay(Context context, boolean pixelMode) {
        super(context);
        if (!pixelMode) {
            throw new IllegalArgumentException("pixel mode marker is required");
        }
        setClickable(true);
        setFocusable(true);
        setBackgroundColor(Color.TRANSPARENT);
    }

    public ZipPromptOverlay(
            Context context,
            ZipPromptModel model,
            ActionCallback secondaryAction,
            ActionCallback primaryAction) {
        super(context);
        if (model == null) {
            throw new IllegalArgumentException("model must not be null");
        }
        setClickable(true);
        setFocusable(true);
        setContentDescription(model.title() + "，" + model.message());
        setBackgroundColor(Color.argb(184, 3, 23, 53));

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(
                unit(context, 38), unit(context, 34),
                unit(context, 38), unit(context, 30));
        card.setBackground(UiKit.roundedGradient(
                context, Color.rgb(255, 244, 252), Color.rgb(216, 247, 255),
                designDp(context, 28)));

        TextView title = ZipKioskShell.scaledText(UiKit.text(
                context, model.title(), 27, UiKit.GREEN, Typeface.BOLD), 27);
        title.setGravity(Gravity.CENTER);
        card.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, unit(context, 54)));

        TextView message = ZipKioskShell.scaledText(UiKit.text(
                context, model.message(), 20, UiKit.TEXT, Typeface.NORMAL), 20);
        message.setGravity(Gravity.CENTER);
        message.setLineSpacing(0, 1.25f);
        LinearLayout.LayoutParams messageParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        messageParams.topMargin = unit(context, 10);
        card.addView(message, messageParams);

        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        Button secondary = UiKit.button(
                context, model.secondaryLabel(), 17,
                Color.rgb(224, 229, 231), UiKit.TEXT, designDp(context, 24));
        ZipKioskShell.scaleButton(secondary, 17);
        secondary.setContentDescription(
                model.secondaryLabel() + "，" + model.secondaryRole().name());
        secondary.setOnClickListener(view -> deliver(
                secondaryAction, model.secondaryRole()));
        Button primary = UiKit.button(
                context, model.primaryLabel(), 17,
                UiKit.GREEN, Color.WHITE, designDp(context, 24));
        ZipKioskShell.scaleButton(primary, 17);
        primary.setContentDescription(
                model.primaryLabel() + "，" + model.primaryRole().name());
        primary.setOnClickListener(view -> deliver(
                primaryAction, model.primaryRole()));

        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                0, unit(context, 48), 1f);
        LinearLayout.LayoutParams leftButtonParams = new LinearLayout.LayoutParams(
                0, unit(context, 48), 1f);
        leftButtonParams.rightMargin = unit(context, 22);
        actions.addView(secondary, leftButtonParams);
        actions.addView(primary, buttonParams);
        card.addView(actions, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, unit(context, 48)));

        int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
        int screenHeight = context.getResources().getDisplayMetrics().heightPixels;
        int cardWidth = Math.max(1, Math.min(
                unit(context, 620), screenWidth - unit(context, 56)));
        int cardHeight = Math.max(1, Math.min(
                unit(context, 400), screenHeight - unit(context, 80)));
        LayoutParams cardParams = new LayoutParams(cardWidth, cardHeight);
        cardParams.gravity = Gravity.CENTER;
        addView(card, cardParams);
    }

    private void deliver(ActionCallback action, ZipPromptModel.ActionRole role) {
        if (actionDelivered) {
            return;
        }
        actionDelivered = true;
        if (action != null) {
            action.onAction(role);
        }
    }

    private static TextView pixelText(Context context, String text,
            float size, int color, int style) {
        TextView view = ZipKioskShell.scaledText(
                UiKit.text(context, text, size, color, style), size);
        view.setGravity(Gravity.CENTER);
        view.setBackgroundColor(Color.rgb(240, 250, 250));
        view.setSingleLine(true);
        return view;
    }

    private static Button pixelButton(Context context, String text,
            String contentDescription, int fill, int textColor) {
        Button button = UiKit.button(
                context, text, 16, fill, textColor, designDp(context, 10));
        ZipKioskShell.scaleButton(button, 16);
        button.setAllCaps(false);
        button.setSingleLine(true);
        button.setContentDescription(contentDescription);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        return button;
    }

    private static void place(FrameLayout parent, View child,
            int x, int y, int width, int height) {
        LayoutParams params = new LayoutParams(
                unit(parent.getContext(), width), unit(parent.getContext(), height));
        params.leftMargin = unit(parent.getContext(), x);
        params.topMargin = unit(parent.getContext(), y);
        parent.addView(child, params);
    }

    private static String safe(CharSequence value) {
        return value == null ? "" : value.toString();
    }

    private static int unit(Context context, float designUnits) {
        return ZipKioskShell.unit(context, designUnits);
    }

    private static float designDp(Context context, float designUnits) {
        return ZipKioskShell.designDp(context, designUnits);
    }
}
