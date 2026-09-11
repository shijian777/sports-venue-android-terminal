package com.codex.lockertest.ui.zip;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.codex.lockertest.ui.UiKit;
import com.codex.lockertest.ui.ZipDesignMetrics;
import com.codex.lockertest.ui.ZipKioskShell;

/** A fixed-design ZIP background with native content and interaction layers above it. */
public final class ZipPixelShell extends FrameLayout {
    private static final String TAG = "ZipPixelShell";
    private static final float PROMPT_DIMMED_ALPHA = 0.42f;

    private final ImageView backgroundView;
    private final FrameLayout contentLayer;
    private final FrameLayout overlayLayer;
    private final int nativeCanvasWidth;
    private final int nativeCanvasHeight;

    private ZipScreenAsset currentAsset;
    private int resolvedDrawableId;
    private boolean viewportValid = true;
    private String viewportDiagnostic;
    private String resourceDiagnostic;
    private Runnable safeHomeRequested;
    private LinearLayout diagnosticCard;
    private TextView diagnosticTitle;
    private TextView diagnosticMessage;
    private Button diagnosticHomeButton;

    public ZipPixelShell(Context context, ZipScreenAsset initialAsset) {
        super(context);
        // Child controls use unit(context, ...). Keep their coordinate space fixed even
        // when immersive mode changes the window after those controls were created.
        nativeCanvasWidth = ZipKioskShell.unit(context, ZipBrand.DESIGN_WIDTH);
        nativeCanvasHeight = ZipKioskShell.unit(context, ZipBrand.DESIGN_HEIGHT);
        setClipChildren(false);
        setClipToPadding(false);
        setBackgroundColor(UiKit.DEEP_BLUE);

        backgroundView = new ImageView(context);
        backgroundView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        backgroundView.setClickable(false);
        backgroundView.setFocusable(false);
        backgroundView.setContentDescription(null);
        backgroundView.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);

        contentLayer = new FrameLayout(context);
        contentLayer.setClipChildren(false);
        contentLayer.setClipToPadding(false);
        contentLayer.setContentDescription("页面动态内容");
        contentLayer.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);

        overlayLayer = new FrameLayout(context);
        overlayLayer.setClipChildren(false);
        overlayLayer.setClipToPadding(false);
        overlayLayer.setClickable(false);
        overlayLayer.setFocusable(false);
        overlayLayer.setContentDescription("页面交互层");
        overlayLayer.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);

        addView(backgroundView, match());
        addView(contentLayer, nativeCanvasParams());
        addView(overlayLayer, nativeCanvasParams());
        addVersionLabel(context);
        setScreenAsset(initialAsset);
    }

    private void addVersionLabel(Context context) {
        // The supplied PNGs have a v16 Demo footer baked in. Mask only that badge.
        TextView version = new TextView(context);
        version.setText(ZipBrand.VERSION);
        version.setTextColor(Color.WHITE);
        version.setTextSize(TypedValue.COMPLEX_UNIT_PX, ZipKioskShell.unit(context, 14));
        version.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        version.setGravity(Gravity.CENTER);
        version.setBackgroundColor(Color.rgb(22, 190, 156));
        LayoutParams params = new LayoutParams(
                ZipKioskShell.unit(context, 170), ZipKioskShell.unit(context, 34));
        params.leftMargin = ZipKioskShell.unit(context, 1080);
        params.topMargin = ZipKioskShell.unit(context, 749);
        contentLayer.addView(version, params);
    }

    public void setScreenAsset(ZipScreenAsset asset) {
        currentAsset = asset;
        resolvedDrawableId = 0;
        String drawableName = asset == null ? "" : asset.drawableName();
        if (!drawableName.isEmpty()) {
            resolvedDrawableId = getResources().getIdentifier(drawableName, "drawable", getContext().getPackageName());
        }
        if (resolvedDrawableId == 0) {
            backgroundView.setImageDrawable(null);
            resourceDiagnostic = drawableName.isEmpty()
                    ? "页面状态未提供资源映射。"
                    : "找不到页面资源：" + drawableName;
            Log.e(TAG, resourceDiagnostic);
            showDiagnostic("资源加载失败", resourceDiagnostic);
            refreshPresentation();
            return;
        }

        try {
            backgroundView.setImageResource(resolvedDrawableId);
            resourceDiagnostic = null;
        } catch (RuntimeException exception) {
            backgroundView.setImageDrawable(null);
            resolvedDrawableId = 0;
            resourceDiagnostic = "页面资源无法解码：" + drawableName;
            Log.e(TAG, resourceDiagnostic, exception);
            showDiagnostic("资源加载失败", resourceDiagnostic);
        }
        refreshPresentation();
    }

    public ZipScreenAsset screenAsset() {
        return currentAsset;
    }

    public FrameLayout contentLayer() {
        return contentLayer;
    }

    public FrameLayout overlayLayer() {
        return overlayLayer;
    }

    public void setPromptDimmed(boolean dimmed) {
        float alpha = dimmed ? PROMPT_DIMMED_ALPHA : 1f;
        backgroundView.setAlpha(alpha);
        contentLayer.setAlpha(alpha);
    }

    public void setOnSafeHomeRequested(Runnable listener) {
        safeHomeRequested = listener;
        refreshDiagnosticHomeButton();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (width <= 0 || height <= 0 || width < height) {
            viewportValid = false;
            viewportDiagnostic = "界面需要非空横屏显示，当前尺寸为 " + width + "×" + height + "。";
            backgroundView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            scaleDynamicLayers(Math.max(1, width), Math.max(1, height));
            Log.e(TAG, viewportDiagnostic);
            refreshPresentation();
            return;
        }

        viewportValid = true;
        viewportDiagnostic = null;
        if (isExactDesignAspectRatio(width, height)) {
            backgroundView.setScaleType(ImageView.ScaleType.FIT_XY);
        } else {
            backgroundView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        }

        int designWidth = ZipDesignMetrics.px(width, height, ZipBrand.DESIGN_WIDTH);
        int designHeight = ZipDesignMetrics.px(width, height, ZipBrand.DESIGN_HEIGHT);
        scaleDynamicLayers(designWidth, designHeight);
        updateDiagnosticLayout();
        refreshPresentation();
    }

    private static boolean isExactDesignAspectRatio(int width, int height) {
        return (long) width * ZipBrand.DESIGN_HEIGHT
                == (long) height * ZipBrand.DESIGN_WIDTH;
    }

    private LayoutParams nativeCanvasParams() {
        LayoutParams designParams = new LayoutParams(nativeCanvasWidth, nativeCanvasHeight);
        designParams.gravity = Gravity.CENTER;
        return designParams;
    }

    private void scaleDynamicLayers(int width, int height) {
        for (FrameLayout layer : new FrameLayout[] {contentLayer, overlayLayer}) {
            layer.setPivotX(nativeCanvasWidth / 2f);
            layer.setPivotY(nativeCanvasHeight / 2f);
            layer.setScaleX(width / (float) nativeCanvasWidth);
            layer.setScaleY(height / (float) nativeCanvasHeight);
        }
    }

    private void refreshPresentation() {
        boolean drawableReady = resolvedDrawableId != 0;
        boolean ready = viewportValid && drawableReady;
        backgroundView.setVisibility(ready ? VISIBLE : INVISIBLE);
        contentLayer.setVisibility(ready ? VISIBLE : INVISIBLE);
        if (!viewportValid) {
            showDiagnostic("显示尺寸异常", viewportDiagnostic);
        } else if (!drawableReady) {
            showDiagnostic("资源加载失败", resourceDiagnostic);
        } else {
            hideDiagnostic();
        }
    }

    private void showDiagnostic(CharSequence title, CharSequence message) {
        ensureDiagnosticCard();
        diagnosticTitle.setText(title);
        diagnosticMessage.setText(message == null ? "页面无法显示。" : message);
        diagnosticCard.setContentDescription(title + "。" + diagnosticMessage.getText());
        diagnosticCard.setVisibility(VISIBLE);
        diagnosticCard.bringToFront();
        refreshDiagnosticHomeButton();
        updateDiagnosticLayout();
    }

    private void hideDiagnostic() {
        if (diagnosticCard != null) {
            diagnosticCard.setVisibility(GONE);
        }
    }

    private void ensureDiagnosticCard() {
        if (diagnosticCard != null) {
            return;
        }
        Context context = getContext();
        diagnosticCard = new LinearLayout(context);
        diagnosticCard.setOrientation(LinearLayout.VERTICAL);
        diagnosticCard.setGravity(Gravity.CENTER_HORIZONTAL);
        diagnosticCard.setPadding(
                UiKit.dp(context, 28), UiKit.dp(context, 24),
                UiKit.dp(context, 28), UiKit.dp(context, 24));
        diagnosticCard.setBackground(UiKit.roundedSolid(
                context, Color.WHITE, 12, UiKit.RED, 2));
        diagnosticCard.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);

        diagnosticTitle = UiKit.text(
                context, "资源加载失败", 24, UiKit.RED, Typeface.BOLD);
        diagnosticTitle.setGravity(Gravity.CENTER);
        diagnosticCard.addView(diagnosticTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        diagnosticMessage = UiKit.text(
                context, "页面无法显示。", 16, UiKit.TEXT, Typeface.NORMAL);
        diagnosticMessage.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams messageParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        messageParams.topMargin = UiKit.dp(context, 14);
        diagnosticCard.addView(diagnosticMessage, messageParams);

        diagnosticHomeButton = UiKit.button(
                context, "安全返回首页", 17, UiKit.GREEN, Color.WHITE, 20);
        diagnosticHomeButton.setContentDescription("安全返回首页");
        diagnosticHomeButton.setOnClickListener(view -> {
            Runnable listener = safeHomeRequested;
            if (listener != null) {
                safeHomeRequested.run();
            }
        });
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                UiKit.dp(context, 190), UiKit.dp(context, 48));
        buttonParams.topMargin = UiKit.dp(context, 20);
        diagnosticCard.addView(diagnosticHomeButton, buttonParams);

        overlayLayer.addView(diagnosticCard);
    }

    private void refreshDiagnosticHomeButton() {
        if (diagnosticHomeButton == null) {
            return;
        }
        boolean enabled = safeHomeRequested != null;
        diagnosticHomeButton.setEnabled(enabled);
        diagnosticHomeButton.setContentDescription(
                enabled ? "安全返回首页" : "安全返回首页，暂不可用");
    }

    private void updateDiagnosticLayout() {
        if (diagnosticCard == null) {
            return;
        }
        LayoutParams params;
        int width = getWidth();
        int height = getHeight();
        if (width > 0 && height > 0 && width >= height) {
            params = new LayoutParams(
                    Math.round(nativeCanvasWidth * 560f / ZipBrand.DESIGN_WIDTH),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        } else {
            params = new LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            int margin = UiKit.dp(getContext(), 24);
            params.setMargins(margin, 0, margin, 0);
        }
        params.gravity = Gravity.CENTER;
        diagnosticCard.setLayoutParams(params);
    }

    private static LayoutParams match() {
        return new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }
}
