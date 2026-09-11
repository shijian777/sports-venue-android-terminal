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
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.codex.lockertest.model.UnlockMethod;
import com.codex.lockertest.model.FeatureAvailability;

public final class UnlockPageView extends FrameLayout {
    public interface Listener {
        void onSubmit(UnlockMethod method, String payload);

        void onReturnHome();
    }

    private static final long INACTIVITY_MILLIS = 30_000L;
    private static final long SIMULATED_RECOGNITION_MILLIS = 800L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final UnlockPageModel model;
    private final UnlockMethod method;
    private final Runnable inactivityReturn;
    private final Runnable recognitionSubmit;

    private Listener listener;
    private NumericKeypadView keypad;
    private Button submitButton;
    private boolean attached;
    private boolean navigationFinished;
    private boolean submissionPending;

    public UnlockPageView(
            Context context, UnlockMethod method, FeatureAvailability availability) {
        super(context);
        this.method = method;
        model = new UnlockPageModel(method, availability);
        inactivityReturn = () -> {
            if (attached && !navigationFinished) {
                returnHome();
            }
        };
        recognitionSubmit = () -> {
            if (!attached || navigationFinished || !submissionPending
                    || !model.canSubmit("simulated")) {
                submissionPending = false;
                return;
            }
            Listener current = listener;
            if (current == null) {
                submissionPending = false;
                if (submitButton != null) {
                    submitButton.setEnabled(true);
                    submitButton.setText(model.recognitionActionLabel());
                }
                return;
            }
            current.onSubmit(method, "simulated");
        };

        addView(new TechBackgroundView(context), match());

        LinearLayout page = new LinearLayout(context);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(
                UiKit.dp(context, 22), UiKit.dp(context, 12),
                UiKit.dp(context, 22), UiKit.dp(context, 18));
        addView(page, match());

        page.addView(buildHeader(context), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(context, 68)));

        View content = model.usesNumericKeypad()
                ? buildNumericContent(context) : buildRecognitionContent(context);
        LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        contentParams.topMargin = UiKit.dp(context, 12);
        page.addView(content, contentParams);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
        if (listener != null) {
            navigationFinished = false;
            scheduleInactivityReturn();
        }
    }

    public UnlockMethod getMethod() {
        return method;
    }

    public boolean isMethodAvailable() {
        return model.isAvailable();
    }

    public void resetSubmission() {
        submissionPending = false;
        handler.removeCallbacks(recognitionSubmit);
        if (submitButton != null) {
            if (model.usesNumericKeypad()) {
                String raw = keypad == null ? "" : keypad.rawValue();
                submitButton.setEnabled(model.canSubmit(raw));
            } else {
                submitButton.setText(model.isAvailable()
                        ? model.recognitionActionLabel() : "设备暂未接入");
                submitButton.setEnabled(model.isAvailable());
            }
        }
        scheduleInactivityReturn();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        scheduleInactivityReturn();
        return super.dispatchTouchEvent(event);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        navigationFinished = false;
        scheduleInactivityReturn();
    }

    @Override
    protected void onDetachedFromWindow() {
        attached = false;
        handler.removeCallbacks(inactivityReturn);
        handler.removeCallbacks(recognitionSubmit);
        submissionPending = false;
        super.onDetachedFromWindow();
    }

    private View buildHeader(Context context) {
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(
                UiKit.dp(context, 16), 0, UiKit.dp(context, 16), 0);
        header.setBackground(UiKit.roundedGradient(
                context, Color.rgb(17, 105, 220), Color.rgb(24, 161, 224), 10));

        Button back = UiKit.button(
                context, "‹  返回首页", 17,
                Color.argb(75, 255, 255, 255), Color.WHITE, 9);
        back.setOnClickListener(view -> returnHome());
        header.addView(back, new LinearLayout.LayoutParams(
                UiKit.dp(context, 142), UiKit.dp(context, 44)));

        LinearLayout titles = new LinearLayout(context);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setGravity(Gravity.CENTER);
        TextView title = UiKit.text(
                context, chineseTitle(method), 27, Color.WHITE, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        TextView subtitle = UiKit.text(
                context, englishTitle(method), 10,
                Color.argb(215, 255, 255, 255), Typeface.BOLD);
        subtitle.setGravity(Gravity.CENTER);
        titles.addView(title);
        titles.addView(subtitle);
        header.addView(titles, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        TextView timeout = UiKit.text(
                context, "30秒无操作自动返回", 13,
                Color.argb(225, 255, 255, 255), Typeface.NORMAL);
        timeout.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        header.addView(timeout, new LinearLayout.LayoutParams(
                UiKit.dp(context, 180), ViewGroup.LayoutParams.MATCH_PARENT));
        return header;
    }

    private View buildNumericContent(Context context) {
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout guide = panel(context);
        guide.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView icon = UiKit.text(
                context,
                method == UnlockMethod.PHONE ? "▣" : "● ● ●",
                method == UnlockMethod.PHONE ? 62 : 34,
                method == UnlockMethod.PHONE ? UiKit.BLUE : UiKit.GREEN,
                Typeface.BOLD);
        icon.setGravity(Gravity.CENTER);
        guide.addView(icon, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(context, 92)));

        TextView guideTitle = UiKit.text(
                context,
                method == UnlockMethod.PHONE ? "请输入手机号码" : "请输入解锁密码",
                25, UiKit.NAVY, Typeface.BOLD);
        guideTitle.setGravity(Gravity.CENTER);
        guide.addView(guideTitle);

        TextView description = UiKit.text(
                context,
                method == UnlockMethod.PHONE
                        ? "使用右侧数字键盘输入11位手机号\n输入完成后点击确认解锁"
                        : "使用右侧数字键盘输入6位密码\n密码内容将以圆点隐藏",
                16, UiKit.MUTED, Typeface.NORMAL);
        description.setGravity(Gravity.CENTER);
        description.setLineSpacing(0, 1.25f);
        LinearLayout.LayoutParams descriptionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        descriptionParams.topMargin = UiKit.dp(context, 18);
        guide.addView(description, descriptionParams);

        TextView privacy = UiKit.text(
                context,
                method == UnlockMethod.PHONE
                        ? "仅用于本次设备验证" : "请注意遮挡，避免密码泄露",
                14, Color.rgb(42, 132, 150), Typeface.BOLD);
        privacy.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams privacyParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        privacyParams.topMargin = UiKit.dp(context, 24);
        guide.addView(privacy, privacyParams);

        LinearLayout.LayoutParams guideParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 0.42f);
        guideParams.rightMargin = UiKit.dp(context, 14);
        content.addView(guide, guideParams);

        LinearLayout inputPanel = panel(context);
        keypad = new NumericKeypadView(
                context, model.maxInputLength(), model.isMasked());
        keypad.setHint(method == UnlockMethod.PHONE
                ? "请输入11位手机号" : "请输入6位密码");
        inputPanel.addView(keypad, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        submitButton = UiKit.button(
                context, "确认解锁", 20,
                method == UnlockMethod.PHONE ? UiKit.BLUE : UiKit.GREEN,
                Color.WHITE, 10);
        submitButton.setEnabled(false);
        submitButton.setOnClickListener(view -> submitNumeric());
        LinearLayout.LayoutParams submitParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(context, 54));
        submitParams.topMargin = UiKit.dp(context, 10);
        inputPanel.addView(submitButton, submitParams);

        keypad.setListener((rawValue, displayValue) -> {
            if (!submissionPending) {
                submitButton.setEnabled(model.canSubmit(rawValue));
            }
        });
        content.addView(inputPanel, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 0.58f));
        return content;
    }

    private View buildRecognitionContent(Context context) {
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout illustrationPanel = panel(context);
        TextView instruction = UiKit.text(
                context, recognitionInstruction(method), 18,
                UiKit.NAVY, Typeface.BOLD);
        instruction.setGravity(Gravity.CENTER);
        illustrationPanel.addView(instruction, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(context, 42)));
        RecognitionIllustrationView illustration =
                new RecognitionIllustrationView(context, method, model.isAvailable());
        LinearLayout.LayoutParams illustrationParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        illustrationParams.topMargin = UiKit.dp(context, 8);
        illustrationPanel.addView(illustration, illustrationParams);
        LinearLayout.LayoutParams leftParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 0.63f);
        leftParams.rightMargin = UiKit.dp(context, 14);
        content.addView(illustrationPanel, leftParams);

        LinearLayout actionPanel = panel(context);
        actionPanel.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView title = UiKit.text(
                context, chineseTitle(method), 26, UiKit.NAVY, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        actionPanel.addView(title);

        TextView detail = UiKit.text(
                context, recognitionDescription(method), 16,
                UiKit.MUTED, Typeface.NORMAL);
        detail.setGravity(Gravity.CENTER);
        detail.setLineSpacing(0, 1.25f);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        detailParams.topMargin = UiKit.dp(context, 22);
        actionPanel.addView(detail, detailParams);

        TextView status = UiKit.text(
                context,
                model.isAvailable() ? "设备已就绪" : model.unavailableMessage(),
                19,
                model.isAvailable() ? UiKit.DARK_GREEN : UiKit.MUTED,
                Typeface.BOLD);
        status.setGravity(Gravity.CENTER);
        status.setPadding(
                UiKit.dp(context, 15), UiKit.dp(context, 10),
                UiKit.dp(context, 15), UiKit.dp(context, 10));
        status.setBackground(UiKit.roundedSolid(
                context,
                model.isAvailable()
                        ? Color.rgb(222, 249, 239) : Color.rgb(227, 234, 237),
                10, Color.TRANSPARENT, 0));
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = UiKit.dp(context, 28);
        actionPanel.addView(status, statusParams);

        submitButton = UiKit.button(
                context,
                model.isAvailable() ? model.recognitionActionLabel() : "设备暂未接入",
                19,
                model.isAvailable() ? UiKit.BLUE : Color.rgb(161, 176, 183),
                Color.WHITE, 10);
        submitButton.setEnabled(model.isAvailable());
        if (model.isAvailable()) {
            submitButton.setOnClickListener(view -> beginRecognition());
        }
        LinearLayout.LayoutParams submitParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(context, 54));
        submitParams.topMargin = UiKit.dp(context, 30);
        actionPanel.addView(submitButton, submitParams);

        content.addView(actionPanel, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 0.37f));
        return content;
    }

    private LinearLayout panel(Context context) {
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(
                UiKit.dp(context, 22), UiKit.dp(context, 18),
                UiKit.dp(context, 22), UiKit.dp(context, 18));
        panel.setBackground(UiKit.roundedSolid(
                context, Color.argb(245, 255, 255, 255), 15,
                Color.rgb(119, 205, 226), 1));
        return panel;
    }

    private void submitNumeric() {
        if (submissionPending || keypad == null) {
            return;
        }
        String raw = keypad.rawValue();
        if (!model.canSubmit(raw)) {
            return;
        }
        Listener current = listener;
        if (current == null) {
            return;
        }
        submissionPending = true;
        submitButton.setEnabled(false);
        current.onSubmit(method, raw);
    }

    private void beginRecognition() {
        if (submissionPending || !model.canSubmit("simulated")) {
            return;
        }
        submissionPending = true;
        submitButton.setEnabled(false);
        submitButton.setText(model.recognitionProgressLabel());
        handler.removeCallbacks(recognitionSubmit);
        handler.postDelayed(recognitionSubmit, SIMULATED_RECOGNITION_MILLIS);
    }

    private void scheduleInactivityReturn() {
        handler.removeCallbacks(inactivityReturn);
        if (attached && !navigationFinished) {
            handler.postDelayed(inactivityReturn, INACTIVITY_MILLIS);
        }
    }

    private void returnHome() {
        if (navigationFinished) {
            return;
        }
        navigationFinished = true;
        handler.removeCallbacks(inactivityReturn);
        handler.removeCallbacks(recognitionSubmit);
        submissionPending = false;
        Listener current = listener;
        if (current != null) {
            current.onReturnHome();
        }
    }

    private static String chineseTitle(UnlockMethod method) {
        switch (method) {
            case FACE:
                return "人脸解锁";
            case PALM:
                return "掌纹解锁";
            case PHONE:
                return "手机号解锁";
            case PASSWORD:
                return "密码解锁";
            case QR:
                return "二维码解锁";
            default:
                throw new IllegalArgumentException("unsupported method");
        }
    }

    private static String englishTitle(UnlockMethod method) {
        switch (method) {
            case FACE:
                return "FACE UNLOCK";
            case PALM:
                return "PALM UNLOCK";
            case PHONE:
                return "PHONE UNLOCK";
            case PASSWORD:
                return "PASSWORD UNLOCK";
            case QR:
                return "QR CODE UNLOCK";
            default:
                throw new IllegalArgumentException("unsupported method");
        }
    }

    private static String recognitionInstruction(UnlockMethod method) {
        if (method == UnlockMethod.FACE) {
            return "请正对识别区域，并保持面部清晰";
        }
        if (method == UnlockMethod.PALM) {
            return "请将手掌对准识别区域，并保持2-3秒";
        }
        return "请将二维码对准扫描框";
    }

    private static String recognitionDescription(UnlockMethod method) {
        if (method == UnlockMethod.FACE) {
            return "识别设备接入后，将在此处显示实时人脸取景画面。";
        }
        if (method == UnlockMethod.PALM) {
            return "掌纹设备接入后，将在此处完成掌纹或掌静脉采集。";
        }
        return "扫码设备接入后，将在此处识别用户二维码。";
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private static final class RecognitionIllustrationView extends View {
        private final UnlockMethod method;
        private final boolean available;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF bounds = new RectF();

        RecognitionIllustrationView(
                Context context, UnlockMethod method, boolean available) {
            super(context);
            this.method = method;
            this.available = available;
            setBackground(UiKit.roundedSolid(
                    context,
                    available ? Color.rgb(231, 249, 251) : Color.rgb(236, 241, 243),
                    14,
                    available ? Color.rgb(82, 206, 226) : Color.rgb(180, 193, 199),
                    1));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (method == UnlockMethod.FACE) {
                drawFace(canvas);
            } else if (method == UnlockMethod.PALM) {
                drawPalm(canvas);
            } else {
                drawQr(canvas);
            }
            if (!available) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.argb(35, 88, 105, 112));
                canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
            }
        }

        private void drawFace(Canvas canvas) {
            float width = getWidth();
            float height = getHeight();
            float centerX = width * 0.5f;
            int color = available ? Color.rgb(26, 161, 218) : Color.rgb(128, 145, 151);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(4f, width / 90f));
            paint.setStrokeCap(Paint.Cap.SQUARE);
            paint.setColor(color);
            float left = width * 0.18f;
            float right = width * 0.82f;
            float top = height * 0.10f;
            float bottom = height * 0.90f;
            float corner = Math.min(width, height) * 0.11f;
            drawCorner(canvas, left, top, corner, 1, 1);
            drawCorner(canvas, right, top, corner, -1, 1);
            drawCorner(canvas, left, bottom, corner, 1, -1);
            drawCorner(canvas, right, bottom, corner, -1, -1);

            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(Math.max(5f, width / 78f));
            bounds.set(
                    centerX - height * 0.16f, height * 0.23f,
                    centerX + height * 0.16f, height * 0.55f);
            canvas.drawOval(bounds, paint);
            bounds.set(
                    centerX - height * 0.27f, height * 0.49f,
                    centerX + height * 0.27f, height * 0.87f);
            canvas.drawArc(bounds, 200, 140, false, paint);

            paint.setStrokeWidth(Math.max(2f, width / 180f));
            paint.setColor(available
                    ? Color.argb(180, 31, 196, 157) : Color.argb(150, 150, 160, 164));
            float scanY = height * (0.25f + 0.46f
                    * ((SystemClock.uptimeMillis() % 1_600L) / 1_600f));
            canvas.drawLine(width * 0.23f, scanY, width * 0.77f, scanY, paint);
            if (available) {
                postInvalidateDelayed(32L);
            }
        }

        private void drawCorner(
                Canvas canvas, float x, float y, float length, int directionX, int directionY) {
            canvas.drawLine(x, y, x + length * directionX, y, paint);
            canvas.drawLine(x, y, x, y + length * directionY, paint);
        }

        private void drawPalm(Canvas canvas) {
            float width = getWidth();
            float height = getHeight();
            int color = available ? Color.rgb(42, 119, 219) : Color.rgb(132, 146, 152);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(Math.max(5f, width / 85f));
            paint.setColor(color);

            path.reset();
            path.moveTo(width * 0.35f, height * 0.82f);
            path.cubicTo(width * 0.25f, height * 0.69f,
                    width * 0.23f, height * 0.54f,
                    width * 0.28f, height * 0.47f);
            path.lineTo(width * 0.34f, height * 0.58f);
            path.lineTo(width * 0.31f, height * 0.23f);
            path.cubicTo(width * 0.31f, height * 0.15f,
                    width * 0.40f, height * 0.15f,
                    width * 0.40f, height * 0.23f);
            path.lineTo(width * 0.42f, height * 0.49f);
            path.lineTo(width * 0.43f, height * 0.14f);
            path.cubicTo(width * 0.43f, height * 0.06f,
                    width * 0.52f, height * 0.06f,
                    width * 0.52f, height * 0.14f);
            path.lineTo(width * 0.52f, height * 0.48f);
            path.lineTo(width * 0.55f, height * 0.18f);
            path.cubicTo(width * 0.56f, height * 0.11f,
                    width * 0.64f, height * 0.13f,
                    width * 0.64f, height * 0.21f);
            path.lineTo(width * 0.62f, height * 0.51f);
            path.lineTo(width * 0.66f, height * 0.30f);
            path.cubicTo(width * 0.68f, height * 0.23f,
                    width * 0.75f, height * 0.27f,
                    width * 0.73f, height * 0.35f);
            path.lineTo(width * 0.69f, height * 0.65f);
            path.cubicTo(width * 0.66f, height * 0.79f,
                    width * 0.56f, height * 0.88f,
                    width * 0.35f, height * 0.82f);
            canvas.drawPath(path, paint);

            paint.setStrokeWidth(Math.max(2f, width / 180f));
            paint.setColor(available
                    ? Color.rgb(29, 197, 151) : Color.rgb(155, 166, 170));
            float scanY = height * (0.30f + 0.43f
                    * ((SystemClock.uptimeMillis() % 1_500L) / 1_500f));
            for (int offset = -2; offset <= 2; offset++) {
                paint.setAlpha(165 - Math.abs(offset) * 40);
                canvas.drawLine(
                        width * 0.19f, scanY + offset * 5f,
                        width * 0.81f, scanY + offset * 5f, paint);
            }
            paint.setAlpha(255);
            if (available) {
                postInvalidateDelayed(32L);
            }
        }

        private void drawQr(Canvas canvas) {
            float width = getWidth();
            float height = getHeight();
            float size = Math.min(width, height) * 0.62f;
            float left = (width - size) * 0.5f;
            float top = (height - size) * 0.5f;
            int color = available ? Color.rgb(24, 159, 209) : Color.rgb(130, 146, 151);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.SQUARE);
            paint.setStrokeWidth(Math.max(6f, width / 75f));
            paint.setColor(color);
            float corner = size * 0.22f;
            drawCorner(canvas, left, top, corner, 1, 1);
            drawCorner(canvas, left + size, top, corner, -1, 1);
            drawCorner(canvas, left, top + size, corner, 1, -1);
            drawCorner(canvas, left + size, top + size, corner, -1, -1);

            paint.setStyle(Paint.Style.FILL);
            float module = size / 13f;
            for (int row = 0; row < 13; row++) {
                for (int column = 0; column < 13; column++) {
                    boolean marker = isQrMarker(row, column);
                    boolean data = ((row * 7 + column * 5 + row * column) % 6) < 2;
                    if (marker || data) {
                        canvas.drawRect(
                                left + column * module,
                                top + row * module,
                                left + (column + 0.78f) * module,
                                top + (row + 0.78f) * module,
                                paint);
                    }
                }
            }

            paint.setColor(available
                    ? Color.rgb(29, 201, 149) : Color.rgb(156, 166, 170));
            paint.setAlpha(190);
            float scanY = top + size
                    * ((SystemClock.uptimeMillis() % 1_700L) / 1_700f);
            canvas.drawRect(left - module, scanY - 2f, left + size + module, scanY + 2f, paint);
            paint.setAlpha(255);
            if (available) {
                postInvalidateDelayed(32L);
            }
        }

        private static boolean isQrMarker(int row, int column) {
            boolean topLeft = row <= 3 && column <= 3;
            boolean topRight = row <= 3 && column >= 9;
            boolean bottomLeft = row >= 9 && column <= 3;
            return topLeft || topRight || bottomLeft;
        }
    }
}
