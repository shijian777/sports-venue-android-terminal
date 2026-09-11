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

import com.codex.lockertest.ui.zip.ZipPixelShell;
import com.codex.lockertest.ui.zip.ZipScreenAsset;

/** One-surface local face detection/capture demo for page 56. */
public final class FaceEnrollmentView extends FrameLayout {
    public static final class FaceBox {
        public final int frameWidth;
        public final int frameHeight;
        public final float centerX;
        public final float centerY;
        public final float width;
        public final float height;

        public FaceBox(int frameWidth, int frameHeight, float centerX,
                float centerY, float width, float height) {
            this.frameWidth = frameWidth;
            this.frameHeight = frameHeight;
            this.centerX = centerX;
            this.centerY = centerY;
            this.width = width;
            this.height = height;
        }
    }

    public interface Listener {
        void onBackRequested();
        void onRetryRequested();
    }

    private final SurfaceView preview;
    private final PreviewGuide previewGuide;
    private final FrameLayout statusPanel;
    private final TextView status;
    private final Button backButton;
    private final Button retryButton;
    private Listener listener;

    public FaceEnrollmentView(Context context) {
        super(context);
        ZipPixelShell pixelShell = new ZipPixelShell(
                context, ZipScreenAsset.FACE_ENROLLMENT_CAPTURING);
        pixelShell.setOnSafeHomeRequested(null);
        addView(pixelShell, match());
        FrameLayout content = pixelShell.contentLayer();
        FrameLayout overlay = pixelShell.overlayLayer();

        preview = new SurfaceView(context);
        preview.setBackgroundColor(Color.rgb(10, 28, 42));
        preview.setKeepScreenOn(true);
        preview.setContentDescription("人脸画面预览区域");
        place(content, preview, 270, 185, 740, 310);

        previewGuide = new PreviewGuide(context);
        previewGuide.setContentDescription("人脸位置提示框");
        place(overlay, previewGuide, 270, 185, 740, 310);

        statusPanel = new FrameLayout(context);
        statusPanel.setBackground(UiKit.roundedSolid(
                context, Color.WHITE, designDp(context, 10), UiKit.GREEN, 2));
        statusPanel.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        status = ZipKioskShell.scaledText(UiKit.text(
                context, "正在准备摄像头，请稍候", 18,
                UiKit.NAVY, Typeface.BOLD), 18);
        status.setGravity(Gravity.CENTER);
        status.setPadding(unit(context, 18), 0, unit(context, 18), 0);
        statusPanel.addView(status, match());
        place(overlay, statusPanel, 405, 500, 470, 78);

        backButton = UiKit.button(context, "返回", 15,
                Color.rgb(129, 143, 145), Color.WHITE, designDp(context, 20));
        backButton.setContentDescription("返回录入方式选择");
        backButton.setOnClickListener(view -> notifyBack());
        place(overlay, backButton, 1050, 110, 176, 38);

        retryButton = UiKit.button(context, "重新尝试", 16,
                UiKit.GREEN, Color.WHITE, designDp(context, 22));
        retryButton.setContentDescription("重新尝试人脸采集");
        retryButton.setOnClickListener(view -> notifyRetry());
        place(overlay, retryButton, 550, 602, 180, 48);
        retryButton.setVisibility(View.GONE);
        retryButton.setEnabled(false);
    }

    public SurfaceHolder previewHolder() {
        return preview.getHolder();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void showPreparing() {
        showStatus("正在准备摄像头，请稍候", false, null);
    }

    public void showDetecting(CharSequence hint, FaceBox box) {
        showStatus(isBlank(hint) ? "请正对摄像头" : hint, false, box);
    }

    public void showCapturing() {
        showStatus("正在处理采集画面，请稍候", false, null);
    }

    public void showCaptureComplete() {
        showStatus("人脸采集完成，等待接入服务器提交", false, null);
    }

    public void showFailure(CharSequence message, boolean retryable) {
        showStatus(isBlank(message) ? "人脸采集失败，请重新尝试" : message,
                retryable, null);
    }

    private void showStatus(CharSequence message, boolean retryable, FaceBox box) {
        status.setText(message);
        status.setContentDescription(message);
        statusPanel.setVisibility(View.VISIBLE);
        statusPanel.bringToFront();
        previewGuide.setFaceBox(box);
        retryButton.setVisibility(retryable ? View.VISIBLE : View.GONE);
        retryButton.setEnabled(retryable);
        if (retryable) retryButton.bringToFront();
        backButton.setVisibility(View.VISIBLE);
        backButton.setEnabled(true);
        backButton.bringToFront();
    }

    private void notifyBack() {
        Listener current = listener;
        if (current != null) current.onBackRequested();
    }

    private void notifyRetry() {
        Listener current = listener;
        if (current != null) current.onRetryRequested();
    }

    private static boolean isBlank(CharSequence value) {
        if (value == null || value.length() == 0) return true;
        for (int index = 0; index < value.length(); index++) {
            if (!Character.isWhitespace(value.charAt(index))) return false;
        }
        return true;
    }

    private static void place(FrameLayout parent, View child,
            int x, int y, int width, int height) {
        LayoutParams params = new LayoutParams(unit(parent.getContext(), width),
                unit(parent.getContext(), height));
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.leftMargin = unit(parent.getContext(), x);
        params.topMargin = unit(parent.getContext(), y);
        parent.addView(child, params);
    }

    private static int unit(Context context, float value) {
        return ZipKioskShell.unit(context, value);
    }

    private static float designDp(Context context, float value) {
        return ZipKioskShell.designDp(context, value);
    }

    private static LayoutParams match() {
        return new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private static final class PreviewGuide extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rectangle = new RectF();
        private FaceBox faceBox;

        PreviewGuide(Context context) {
            super(context);
            setClickable(false);
            setFocusable(false);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(unit(context, 4));
            paint.setColor(Color.rgb(255, 180, 50));
        }

        void setFaceBox(FaceBox box) {
            faceBox = box;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            FaceBox box = faceBox;
            if (box == null || box.frameWidth <= 0 || box.frameHeight <= 0
                    || box.width <= 0.0f || box.height <= 0.0f) return;
            float left = (box.centerX - box.width * 0.5f)
                    * getWidth() / box.frameWidth;
            float top = (box.centerY - box.height * 0.5f)
                    * getHeight() / box.frameHeight;
            float right = (box.centerX + box.width * 0.5f)
                    * getWidth() / box.frameWidth;
            float bottom = (box.centerY + box.height * 0.5f)
                    * getHeight() / box.frameHeight;
            rectangle.set(Math.max(0.0f, left), Math.max(0.0f, top),
                    Math.min(getWidth(), right), Math.min(getHeight(), bottom));
            if (rectangle.right <= rectangle.left || rectangle.bottom <= rectangle.top) return;
            canvas.drawRoundRect(rectangle, unit(getContext(), 10),
                    unit(getContext(), 10), paint);
        }
    }
}
