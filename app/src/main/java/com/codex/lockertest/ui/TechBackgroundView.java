package com.codex.lockertest.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

public final class TechBackgroundView extends View {
    private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint beamPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path beam = new Path();

    private LinearGradient backgroundGradient;
    private RadialGradient upperGlow;
    private RadialGradient lowerGlow;

    public TechBackgroundView(Context context) {
        this(context, null);
    }

    public TechBackgroundView(Context context, AttributeSet attrs) {
        super(context, attrs);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(1f);
        dotPaint.setStyle(Paint.Style.FILL);
        beamPaint.setStyle(Paint.Style.FILL);
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (width <= 0 || height <= 0) {
            return;
        }
        backgroundGradient = new LinearGradient(
                0, 0, width, height,
                new int[]{
                        Color.rgb(3, 48, 129),
                        Color.rgb(18, 125, 211),
                        Color.rgb(187, 239, 246)},
                new float[]{0f, 0.55f, 1f},
                Shader.TileMode.CLAMP);
        upperGlow = new RadialGradient(
                width * 0.72f, height * 0.04f, width * 0.56f,
                new int[]{Color.argb(110, 66, 217, 255), Color.TRANSPARENT},
                null, Shader.TileMode.CLAMP);
        lowerGlow = new RadialGradient(
                width * 0.53f, height * 0.86f, width * 0.50f,
                new int[]{Color.argb(95, 220, 255, 251), Color.TRANSPARENT},
                null, Shader.TileMode.CLAMP);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0 || backgroundGradient == null) {
            return;
        }

        backgroundPaint.setShader(backgroundGradient);
        canvas.drawRect(0, 0, width, height, backgroundPaint);
        drawGlows(canvas, width, height);
        drawLightBeams(canvas, width, height);
        drawWorldDots(canvas, width, height);
        drawFloorGrid(canvas, width, height);
    }

    private void drawGlows(Canvas canvas, int width, int height) {
        glowPaint.setShader(upperGlow);
        canvas.drawRect(0, 0, width, height, glowPaint);
        glowPaint.setShader(lowerGlow);
        canvas.drawRect(0, 0, width, height, glowPaint);
        glowPaint.setShader(null);
    }

    private void drawLightBeams(Canvas canvas, int width, int height) {
        beamPaint.setColor(Color.argb(20, 203, 247, 255));
        beam.reset();
        beam.moveTo(width * 0.60f, 0);
        beam.lineTo(width * 0.79f, 0);
        beam.lineTo(width * 0.50f, height);
        beam.lineTo(width * 0.34f, height);
        beam.close();
        canvas.drawPath(beam, beamPaint);

        beamPaint.setColor(Color.argb(13, 255, 255, 255));
        beam.reset();
        beam.moveTo(width * 0.10f, 0);
        beam.lineTo(width * 0.20f, 0);
        beam.lineTo(width * 0.43f, height);
        beam.lineTo(width * 0.33f, height);
        beam.close();
        canvas.drawPath(beam, beamPaint);
    }

    private void drawWorldDots(Canvas canvas, int width, int height) {
        float left = width * 0.035f;
        float top = height * 0.025f;
        float mapWidth = width * 0.52f;
        float mapHeight = height * 0.34f;
        float step = Math.max(7f, width / 155f);
        float radius = Math.max(1.1f, width / 920f);
        dotPaint.setColor(Color.argb(70, 164, 236, 255));

        for (float y = 0; y <= mapHeight; y += step) {
            float normalizedY = y / mapHeight;
            for (float x = 0; x <= mapWidth; x += step) {
                float normalizedX = x / mapWidth;
                if (isLandDot(normalizedX, normalizedY)) {
                    canvas.drawCircle(left + x, top + y, radius, dotPaint);
                }
            }
        }
    }

    private static boolean isLandDot(float x, float y) {
        boolean northAmerica = ellipse(x, y, 0.17f, 0.36f, 0.15f, 0.24f)
                || ellipse(x, y, 0.27f, 0.28f, 0.13f, 0.12f);
        boolean southAmerica = ellipse(x, y, 0.32f, 0.68f, 0.09f, 0.27f);
        boolean europeAsia = ellipse(x, y, 0.66f, 0.33f, 0.29f, 0.18f)
                || ellipse(x, y, 0.84f, 0.43f, 0.16f, 0.16f);
        boolean africa = ellipse(x, y, 0.58f, 0.62f, 0.13f, 0.25f);
        boolean australia = ellipse(x, y, 0.86f, 0.75f, 0.10f, 0.09f);
        boolean cutout = ellipse(x, y, 0.48f, 0.42f, 0.06f, 0.09f)
                || ellipse(x, y, 0.73f, 0.57f, 0.08f, 0.11f);
        return (northAmerica || southAmerica || europeAsia || africa || australia)
                && !cutout;
    }

    private static boolean ellipse(
            float x, float y, float centerX, float centerY, float radiusX, float radiusY) {
        float dx = (x - centerX) / radiusX;
        float dy = (y - centerY) / radiusY;
        return dx * dx + dy * dy <= 1f;
    }

    private void drawFloorGrid(Canvas canvas, int width, int height) {
        float horizon = height * 0.53f;
        float centerX = width * 0.50f;
        gridPaint.setColor(Color.argb(42, 160, 239, 255));
        gridPaint.setStrokeWidth(Math.max(1f, width / 1800f));

        int verticalLines = 28;
        for (int index = 0; index <= verticalLines; index++) {
            float bottomX = width * index / verticalLines;
            float topX = centerX + (bottomX - centerX) * 0.12f;
            canvas.drawLine(topX, horizon, bottomX, height, gridPaint);
        }

        int horizontalLines = 13;
        for (int index = 0; index <= horizontalLines; index++) {
            float ratio = index / (float) horizontalLines;
            float y = horizon + (height - horizon) * ratio * ratio;
            canvas.drawLine(0, y, width, y, gridPaint);
        }

        gridPaint.setColor(Color.argb(27, 187, 244, 255));
        float upperStep = Math.max(38f, width / 30f);
        for (float x = 0; x < width; x += upperStep) {
            canvas.drawLine(x, 0, x, horizon, gridPaint);
        }
        for (float y = 0; y < horizon; y += upperStep) {
            canvas.drawLine(0, y, width, y, gridPaint);
        }
    }
}
