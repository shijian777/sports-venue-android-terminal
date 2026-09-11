package com.codex.lockertest.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.codex.lockertest.ui.zip.ZipCustomerScreenRouter;
import com.codex.lockertest.ui.zip.ZipPixelShell;
import com.codex.lockertest.ui.zip.ZipScreenAsset;

/** Presentation-only pixel view for one active customer face attempt. */
public final class FaceRecognitionView extends FrameLayout {
    public static final class FaceBox {
        private final int frameWidth;
        private final int frameHeight;
        private final float centerX;
        private final float centerY;
        private final float width;
        private final float height;

        public FaceBox(int frameWidth, int frameHeight, float centerX,
                float centerY, float width, float height) {
            if (frameWidth <= 0 || frameHeight <= 0 || width <= 0.0f || height <= 0.0f) {
                throw new IllegalArgumentException("Face geometry must be positive");
            }
            if (Float.isNaN(centerX) || Float.isInfinite(centerX)) {
                throw new IllegalArgumentException("Face center must be finite");
            }
            if (Float.isNaN(centerY) || Float.isInfinite(centerY)) {
                throw new IllegalArgumentException("Face center must be finite");
            }
            if (Float.isNaN(width) || Float.isInfinite(width)) {
                throw new IllegalArgumentException("Face size must be finite");
            }
            if (Float.isNaN(height) || Float.isInfinite(height)) {
                throw new IllegalArgumentException("Face size must be finite");
            }
            this.frameWidth = frameWidth;
            this.frameHeight = frameHeight;
            this.centerX = centerX;
            this.centerY = centerY;
            this.width = width;
            this.height = height;
        }

        public int frameWidth() { return frameWidth; }
        public int frameHeight() { return frameHeight; }
        public float centerX() { return centerX; }
        public float centerY() { return centerY; }
        public float width() { return width; }
        public float height() { return height; }
    }

    public interface Listener {
        void onReturnRequested();
        void onRetryRequested();
    }

    private final ZipPixelShell pixelShell;
    private final SurfaceView preview;
    private final PreviewOverlay previewOverlay;
    private final FrameLayout statusPanel;
    private final View statusEmphasis;
    private final TextView status;
    private final FrameLayout errorModal;
    private final TextView errorMessage;
    private final FaceSecurityBanner securityBanner;
    private final Button topReturnButton;
    private final Button retryButton;
    private final Button backButton;
    private final Button homeButton;
    private Listener listener;

    public FaceRecognitionView(Context context) {
        super(context);

        pixelShell = new ZipPixelShell(context, ZipScreenAsset.FACE_PREPARING);
        pixelShell.setOnSafeHomeRequested(this::notifyReturnRequested);
        addView(pixelShell, match());
        FrameLayout content = pixelShell.contentLayer();
        FrameLayout overlay = pixelShell.overlayLayer();

        securityBanner = new FaceSecurityBanner(context);
        securityBanner.setVisibility(View.GONE);
        place(content, securityBanner, 405, 140, 470, 38);

        preview = new SurfaceView(context);
        preview.setBackgroundColor(Color.rgb(10, 28, 42));
        preview.setKeepScreenOn(true);
        preview.setContentDescription("人脸画面预览区域");
        place(content, preview, 270, 185, 740, 410);

        previewOverlay = new PreviewOverlay(context);
        previewOverlay.setContentDescription("人脸位置提示框");
        place(content, previewOverlay, 270, 185, 740, 410);

        statusPanel = new FrameLayout(context);
        statusPanel.setBackground(UiKit.roundedSolid(
                context, Color.WHITE, designDp(context, 10),
                Color.rgb(84, 207, 181), designDp(context, 2)));
        statusPanel.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        status = ZipKioskShell.scaledText(UiKit.text(
                context, "正在准备识别，请稍候", 18,
                UiKit.NAVY, Typeface.BOLD), 18);
        status.setGravity(Gravity.CENTER);
        status.setContentDescription("正在准备识别，请稍候");
        statusPanel.addView(status, match());
        place(content, statusPanel, 405, 500, 470, 74);

        statusEmphasis = new View(context);
        statusEmphasis.setBackgroundColor(UiKit.GREEN);
        statusEmphasis.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        place(content, statusEmphasis, 405, 570, 470, 5);

        topReturnButton = UiKit.button(
                context, "返回", 15, Color.rgb(129, 143, 145),
                Color.WHITE, designDp(context, 20));
        topReturnButton.setContentDescription("返回首页");
        topReturnButton.setOnClickListener(view -> notifyReturnRequested());
        place(overlay, topReturnButton, 1050, 110, 176, 38);

        errorModal = new FrameLayout(context);
        errorModal.setBackground(UiKit.roundedSolid(
                context, Color.rgb(250, 252, 252), designDp(context, 12),
                UiKit.RED, designDp(context, 2)));
        errorModal.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        errorMessage = ZipKioskShell.scaledText(UiKit.text(
                context, "人脸功能暂时不可用，请联系管理员", 21,
                UiKit.RED, Typeface.BOLD), 21);
        errorMessage.setGravity(Gravity.CENTER);
        errorMessage.setPadding(unit(context, 26), unit(context, 20),
                unit(context, 26), unit(context, 60));
        errorModal.addView(errorMessage, match());
        place(overlay, errorModal, 390, 250, 500, 300);

        retryButton = errorButton(context, "重新尝试", UiKit.GREEN);
        retryButton.setContentDescription("重新尝试人脸识别");
        retryButton.setOnClickListener(view -> notifyRetryRequested());
        place(overlay, retryButton, 465, 480, 150, 44);

        backButton = errorButton(context, "返回首页", Color.rgb(129, 143, 145));
        backButton.setContentDescription("返回首页");
        backButton.setOnClickListener(view -> notifyReturnRequested());
        place(overlay, backButton, 665, 480, 150, 44);

        homeButton = errorButton(context, "返回首页", Color.rgb(129, 143, 145));
        homeButton.setContentDescription("安全返回首页");
        homeButton.setOnClickListener(view -> notifyReturnRequested());
        place(overlay, homeButton, 565, 480, 150, 44);

        showPreparing();
    }

    public SurfaceHolder previewHolder() {
        return preview.getHolder();
    }

    public void setSecurityBanner(CharSequence text, boolean visible) {
        securityBanner.setBannerText(text);
        securityBanner.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    public void showPreparing() {
        showActiveState(
                ZipCustomerScreenRouter.State.FACE_PREPARING,
                "正在准备识别，请稍候", null);
    }

    public void showDetecting(String hint, FaceBox box) {
        CharSequence safeHint = isBlank(hint) ? "请正对摄像头" : hint;
        showActiveState(
                ZipCustomerScreenRouter.State.FACE_DETECTING,
                safeHint, box);
    }

    public void showVerifying() {
        showActiveState(
                ZipCustomerScreenRouter.State.FACE_UPLOADING,
                "正在验证，请稍候", null);
    }

    public void showPermissionDenied(boolean permanentlyDenied) {
        CharSequence message = permanentlyDenied
                ? "请在系统设置中允许摄像头"
                : "无法使用摄像头，请重新授权";
        showErrorState(
                permanentlyDenied
                        ? ZipCustomerScreenRouter.State.FACE_CAMERA_PERMISSION_PERMANENT
                        : ZipCustomerScreenRouter.State.FACE_CAMERA_PERMISSION_TEMPORARY,
                message,
                !permanentlyDenied,
                !permanentlyDenied,
                permanentlyDenied);
        homeButton.setText("返回首页");
        homeButton.setContentDescription("安全返回首页");
        if (!permanentlyDenied) {
            retryButton.setText("重新授权");
            retryButton.setContentDescription("重新授权摄像头");
        }
    }

    public void showFailure(String message, boolean retryable) {
        CharSequence safeMessage = isBlank(message)
                ? "验证失败，请重新尝试" : message;
        showErrorState(
                retryable
                        ? ZipCustomerScreenRouter.State.FACE_RETRYABLE_FAILURE
                        : ZipCustomerScreenRouter.State.FACE_COMPONENT_UNAVAILABLE,
                safeMessage,
                retryable,
                retryable,
                !retryable);
        if (!retryable) {
            homeButton.setText("返回");
            homeButton.setContentDescription("返回首页");
        }
        if (retryable) {
            retryButton.setText("重新尝试");
            retryButton.setContentDescription("重新尝试人脸识别");
        }
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    private void showActiveState(ZipCustomerScreenRouter.State state,
            CharSequence message, FaceBox box) {
        pixelShell.setScreenAsset(ZipCustomerScreenRouter.assetFor(state));
        boolean active = true;
        preview.setVisibility(active ? View.VISIBLE : View.INVISIBLE);
        previewOverlay.setVisibility(View.VISIBLE);
        previewOverlay.setFaceBox(box);
        status.setText(message);
        status.setContentDescription(message);
        statusPanel.setVisibility(View.VISIBLE);
        statusEmphasis.setVisibility(View.VISIBLE);
        topReturnButton.setVisibility(View.VISIBLE);
        topReturnButton.setEnabled(true);
        errorModal.setVisibility(View.GONE);
        configureErrorButtons(false, false, false);
    }

    private void showErrorState(ZipCustomerScreenRouter.State state,
            CharSequence message, boolean showRetry,
            boolean showBack, boolean showHome) {
        pixelShell.setScreenAsset(ZipCustomerScreenRouter.assetFor(state));
        boolean active = false;
        preview.setVisibility(active ? View.VISIBLE : View.INVISIBLE);
        previewOverlay.setVisibility(View.INVISIBLE);
        previewOverlay.setFaceBox(null);
        statusPanel.setVisibility(View.GONE);
        statusEmphasis.setVisibility(View.GONE);
        topReturnButton.setVisibility(View.GONE);
        topReturnButton.setEnabled(false);
        errorMessage.setText(message);
        errorMessage.setContentDescription(message);
        errorModal.setVisibility(View.VISIBLE);
        errorModal.bringToFront();
        configureErrorButtons(showRetry, showBack, showHome);
    }

    private void configureErrorButtons(
            boolean showRetry, boolean showBack, boolean showHome) {
        retryButton.setVisibility(showRetry ? View.VISIBLE : View.GONE);
        retryButton.setEnabled(showRetry);
        backButton.setVisibility(showBack ? View.VISIBLE : View.GONE);
        backButton.setEnabled(showBack);
        homeButton.setVisibility(showHome ? View.VISIBLE : View.GONE);
        homeButton.setEnabled(showHome);
        if (showRetry) retryButton.bringToFront();
        if (showBack) backButton.bringToFront();
        if (showHome) homeButton.bringToFront();
    }

    private void notifyReturnRequested() {
        Listener current = listener;
        if (current != null) {
            try { current.onReturnRequested(); }
            catch (RuntimeException | LinkageError ignored) { }
        }
    }

    private void notifyRetryRequested() {
        Listener current = listener;
        if (current != null) {
            try { current.onRetryRequested(); }
            catch (RuntimeException | LinkageError ignored) { }
        }
    }

    private static Button errorButton(Context context, String label, int color) {
        Button button = UiKit.button(
                context, label, 15, color, Color.WHITE, designDp(context, 20));
        button.setAllCaps(false);
        return button;
    }

    private static void place(FrameLayout parent, View child,
            int x, int y, int width, int height) {
        LayoutParams params = new LayoutParams(
                unit(parent.getContext(), width), unit(parent.getContext(), height));
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.leftMargin = unit(parent.getContext(), x);
        params.topMargin = unit(parent.getContext(), y);
        parent.addView(child, params);
    }

    private static boolean isBlank(CharSequence value) {
        if (value == null || value.length() == 0) return true;
        for (int index = 0; index < value.length(); index++) {
            if (!Character.isWhitespace(value.charAt(index))) return false;
        }
        return true;
    }

    private static int unit(Context context, float value) {
        return ZipKioskShell.unit(context, value);
    }

    private static float designDp(Context context, float value) {
        return ZipKioskShell.designDp(context, value);
    }

    private static LayoutParams match() {
        return new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private static final class PreviewOverlay extends View {
        private final Paint guidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint facePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rectangle = new RectF();
        private FaceBox faceBox;

        PreviewOverlay(Context context) {
            super(context);
            setClickable(false);
            setFocusable(false);
            guidePaint.setStyle(Paint.Style.STROKE);
            guidePaint.setStrokeWidth(unit(context, 3));
            guidePaint.setColor(Color.argb(190, 124, 239, 213));
            facePaint.setStyle(Paint.Style.STROKE);
            facePaint.setStrokeWidth(unit(context, 4));
            facePaint.setColor(Color.rgb(255, 180, 50));
        }

        void setFaceBox(FaceBox box) {
            faceBox = box;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float inset = unit(getContext(), 18);
            rectangle.set(inset, inset, Math.max(inset, getWidth() - inset),
                    Math.max(inset, getHeight() - inset));
            canvas.drawRoundRect(rectangle, inset, inset, guidePaint);

            FaceBox box = faceBox;
            if (box == null || getWidth() <= 0 || getHeight() <= 0) return;
            float left = (box.centerX() - box.width() * 0.5f)
                    * getWidth() / box.frameWidth();
            float top = (box.centerY() - box.height() * 0.5f)
                    * getHeight() / box.frameHeight();
            float right = (box.centerX() + box.width() * 0.5f)
                    * getWidth() / box.frameWidth();
            float bottom = (box.centerY() + box.height() * 0.5f)
                    * getHeight() / box.frameHeight();
            left = Math.max(0.0f, Math.min(getWidth(), left));
            top = Math.max(0.0f, Math.min(getHeight(), top));
            right = Math.max(left, Math.min(getWidth(), right));
            bottom = Math.max(top, Math.min(getHeight(), bottom));
            if (right <= left || bottom <= top) return;
            rectangle.set(left, top, right, bottom);
            canvas.drawRoundRect(rectangle, inset * 0.5f, inset * 0.5f, facePaint);
        }
    }
}
