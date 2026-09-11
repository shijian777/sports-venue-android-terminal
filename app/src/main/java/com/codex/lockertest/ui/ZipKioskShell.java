package com.codex.lockertest.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.codex.lockertest.ui.zip.ZipBrand;
import com.codex.lockertest.ui.zip.ZipPixelShell;
import com.codex.lockertest.ui.zip.ZipScreenAsset;

import java.util.TimeZone;

/** Shared scalable ZIP chrome used by customer-facing kiosk pages. */
public final class ZipKioskShell extends FrameLayout {
    private final FrameLayout content;
    private final Button returnButton;
    private final TextView clock;
    private final ZipPixelShell pixelShell;
    private OnClickListener pixelReturnListener;
    private boolean pixelReturnEnabled = true;
    private final Handler clockHandler = new Handler(Looper.getMainLooper());
    private final TimeZone clockTimeZone = TimeZone.getDefault();
    private final Runnable clockTicker = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            clock.setText(ZipClockText.format(now, clockTimeZone));
            long uptime = SystemClock.uptimeMillis();
            clockHandler.postDelayed(this, 1_000L - uptime % 1_000L);
        }
    };

    public ZipKioskShell(
            Context context,
            CharSequence title,
            CharSequence subtitle,
            boolean showReturn) {
        super(context);
        pixelShell = null;
        setClipChildren(false);

        addView(new TechBackgroundView(context), match());
        addView(new ChromeView(context), match());

        TextView titleView = scaledText(
                UiKit.text(context, title, 30, Color.WHITE, Typeface.BOLD), 30);
        titleView.setGravity(Gravity.CENTER);
        titleView.setContentDescription(title);
        LayoutParams titleParams = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, unit(context, 60));
        titleParams.gravity = Gravity.TOP;
        titleParams.topMargin = unit(context, 27);
        addView(titleView, titleParams);

        TextView subtitleView = scaledText(UiKit.text(
                context, subtitle, 12, UiKit.GREEN, Typeface.BOLD), 12);
        subtitleView.setGravity(Gravity.CENTER);
        LayoutParams subtitleParams = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, unit(context, 28));
        subtitleParams.gravity = Gravity.TOP;
        subtitleParams.topMargin = unit(context, 91);
        addView(subtitleView, subtitleParams);

        clock = scaledText(UiKit.text(
                context, "", 20, Color.WHITE, Typeface.BOLD), 20);
        clock.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        clock.setContentDescription("当前日期时间");
        LayoutParams clockParams = new LayoutParams(unit(context, 270), unit(context, 38));
        clockParams.gravity = Gravity.TOP | Gravity.RIGHT;
        clockParams.topMargin = unit(context, 13);
        clockParams.rightMargin = unit(context, 35);
        addView(clock, clockParams);

        content = new FrameLayout(context);
        content.setClipChildren(false);
        LayoutParams contentParams = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        contentParams.setMargins(
                unit(context, 35), unit(context, 128),
                unit(context, 35), unit(context, 72));
        addView(content, contentParams);

        returnButton = UiKit.button(
                context, "返回首页", 15, UiKit.GREEN, Color.WHITE, designDp(context, 22));
        scaleButton(returnButton, 15);
        returnButton.setContentDescription("返回首页");
        returnButton.setVisibility(showReturn ? VISIBLE : GONE);
        LayoutParams returnParams = new LayoutParams(
                unit(context, 132), unit(context, 42));
        returnParams.gravity = Gravity.TOP | Gravity.RIGHT;
        returnParams.topMargin = unit(context, 88);
        returnParams.rightMargin = unit(context, 54);
        addView(returnButton, returnParams);

        TextView brand = scaledText(UiKit.text(
                context, ZipBrand.FOOTER, 15,
                Color.rgb(28, 82, 105), Typeface.BOLD), 15);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        LayoutParams brandParams = new LayoutParams(
                unit(context, 350), unit(context, 38));
        brandParams.gravity = Gravity.BOTTOM | Gravity.LEFT;
        brandParams.leftMargin = unit(context, 48);
        brandParams.bottomMargin = unit(context, 12);
        addView(brand, brandParams);

        TextView version = scaledText(UiKit.text(
                context, ZipBrand.VERSION, 12, Color.WHITE, Typeface.BOLD), 12);
        version.setGravity(Gravity.CENTER);
        version.setBackground(UiKit.roundedSolid(
                context, UiKit.GREEN, designDp(context, 20), Color.TRANSPARENT, 0));
        LayoutParams versionParams = new LayoutParams(
                unit(context, 138), unit(context, 30));
        versionParams.gravity = Gravity.BOTTOM | Gravity.RIGHT;
        versionParams.rightMargin = unit(context, 48);
        versionParams.bottomMargin = unit(context, 15);
        addView(version, versionParams);
    }

    /** Creates the new pixel-backed shell without changing any legacy constructor call. */
    public ZipKioskShell(Context context, ZipScreenAsset initialAsset) {
        super(context);
        setClipChildren(false);
        pixelShell = new ZipPixelShell(context, initialAsset);
        content = pixelShell.contentLayer();
        returnButton = new Button(context);
        returnButton.setText("");
        returnButton.setAllCaps(false);
        returnButton.setMinWidth(0);
        returnButton.setMinimumWidth(0);
        returnButton.setMinHeight(0);
        returnButton.setMinimumHeight(0);
        returnButton.setPadding(0, 0, 0, 0);
        returnButton.setBackgroundColor(Color.TRANSPARENT);
        returnButton.setContentDescription("返回首页");
        returnButton.setImportantForAccessibility(
                IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        returnButton.setVisibility(GONE);
        returnButton.setClickable(false);
        returnButton.setEnabled(false);
        clock = null;
        addView(pixelShell, match());
        LayoutParams returnParams = new LayoutParams(
                unit(context, 176), unit(context, 38));
        returnParams.gravity = Gravity.TOP | Gravity.RIGHT;
        returnParams.topMargin = unit(context, 110);
        returnParams.rightMargin = unit(context, 54);
        pixelShell.overlayLayer().addView(returnButton, returnParams);
    }

    public FrameLayout content() {
        return content;
    }

    public void setOnReturnClickListener(OnClickListener listener) {
        pixelReturnListener = listener;
        if (returnButton != null) {
            returnButton.setOnClickListener(listener);
        }
        syncPixelReturnListener();
    }

    public void setReturnEnabled(boolean enabled) {
        pixelReturnEnabled = enabled;
        if (returnButton != null) {
            returnButton.setEnabled(enabled);
            returnButton.setAlpha(enabled ? 1f : 0.45f);
            returnButton.setContentDescription(enabled ? "返回首页" : "返回首页，暂不可用");
        }
        syncPixelReturnListener();
    }

    public void setScreenAsset(ZipScreenAsset asset) {
        if (pixelShell == null) {
            throw new IllegalStateException(
                    "setScreenAsset requires the ZipScreenAsset constructor");
        }
        pixelShell.setScreenAsset(asset);
    }

    public FrameLayout pixelContent() {
        return content;
    }

    public FrameLayout pixelOverlay() {
        return pixelShell == null ? this : pixelShell.overlayLayer();
    }

    public void setPromptDimmed(boolean dimmed) {
        if (pixelShell != null) {
            pixelShell.setPromptDimmed(dimmed);
        }
    }

    private void syncPixelReturnListener() {
        if (pixelShell == null) {
            return;
        }
        final OnClickListener listener = pixelReturnListener;
        boolean active = pixelReturnEnabled && listener != null;
        returnButton.setVisibility(active ? VISIBLE : GONE);
        returnButton.setClickable(active);
        returnButton.setEnabled(active);
        returnButton.setImportantForAccessibility(active
                ? IMPORTANT_FOR_ACCESSIBILITY_YES
                : IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        returnButton.setContentDescription(active ? "返回首页" : null);
        if (!active) {
            pixelShell.setOnSafeHomeRequested(null);
            return;
        }
        pixelShell.setOnSafeHomeRequested(() -> listener.onClick(pixelShell));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (clock != null) {
            clockHandler.removeCallbacks(clockTicker);
            clockTicker.run();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        clockHandler.removeCallbacks(clockTicker);
        super.onDetachedFromWindow();
    }

    public static int unit(Context context, float designUnits) {
        int width = context.getResources().getDisplayMetrics().widthPixels;
        int height = context.getResources().getDisplayMetrics().heightPixels;
        int landscapeWidth = Math.max(width, height);
        int landscapeHeight = Math.min(width, height);
        return ZipDesignMetrics.px(landscapeWidth, landscapeHeight, designUnits);
    }

    static float designDp(Context context, float designUnits) {
        float density = context.getResources().getDisplayMetrics().density;
        return unit(context, designUnits) / Math.max(0.01f, density);
    }

    static <T extends TextView> T scaledText(T view, float designTextPixels) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, unit(view.getContext(), designTextPixels));
        return view;
    }

    static Button scaleButton(Button button, float designTextPixels) {
        scaledText(button, designTextPixels);
        int horizontal = unit(button.getContext(), 8);
        button.setPadding(horizontal, 0, horizontal, 0);
        return button;
    }

    private static LayoutParams match() {
        return new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private static final class ChromeView extends View {
        private final Paint panelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint headerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint accentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path headerPath = new Path();
        private final RectF panelRect = new RectF();

        ChromeView(Context context) {
            super(context);
            panelPaint.setStyle(Paint.Style.FILL);
            panelPaint.setColor(Color.argb(244, 255, 255, 255));
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(unit(context, 2));
            borderPaint.setColor(Color.argb(225, 152, 235, 247));
            headerPaint.setStyle(Paint.Style.FILL);
            headerPaint.setColor(UiKit.GREEN);
            accentPaint.setStyle(Paint.Style.STROKE);
            accentPaint.setStrokeWidth(unit(context, 2));
            accentPaint.setColor(Color.rgb(79, 211, 169));
        }

        @Override
        protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            float inset = Math.max(unit(getContext(), 20), width * 0.018f);
            panelRect.set(inset, height * 0.083f, width - inset, height * 0.915f);

            float left = width * 0.25f;
            float right = width * 0.75f;
            float shoulder = width * 0.018f;
            float top = height * 0.035f;
            float bottom = height * 0.13f;
            headerPath.reset();
            headerPath.moveTo(left + shoulder, top);
            headerPath.lineTo(right - shoulder, top);
            headerPath.lineTo(right, (top + bottom) * 0.5f);
            headerPath.lineTo(right - shoulder, bottom);
            headerPath.lineTo(left + shoulder, bottom);
            headerPath.lineTo(left, (top + bottom) * 0.5f);
            headerPath.close();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawRoundRect(panelRect,
                    unit(getContext(), 10), unit(getContext(), 10), panelPaint);
            canvas.drawRoundRect(panelRect,
                    unit(getContext(), 10), unit(getContext(), 10), borderPaint);
            canvas.drawPath(headerPath, headerPaint);
            canvas.drawPath(headerPath, borderPaint);
            float lineY = getHeight() * 0.151f;
            canvas.drawLine(getWidth() * 0.268f, lineY,
                    getWidth() * 0.36f, lineY, accentPaint);
            canvas.drawLine(getWidth() * 0.64f, lineY,
                    getWidth() * 0.732f, lineY, accentPaint);
        }
    }
}
