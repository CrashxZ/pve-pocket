// SPDX-License-Identifier: GPL-3.0-or-later
// Homelab Pocket — Native Canvas Radial Progress Gauge View
package com.homelabpocket.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

import java.util.Locale;

/**
 * Minimalist native Android Canvas radial gauge view.
 *
 * Renders a partly open 270-degree ring without needles, ticks, or clutter.
 * Features centered percentage display with responsive text auto-scaling,
 * TalkBack accessibility support, and safe handling of NaN/Infinity/unavailable metrics.
 */
public class RadialGaugeView extends View {

    public static final float START_ANGLE = 135.0f;
    public static final float SWEEP_ANGLE = 270.0f;
    public static final float DEFAULT_STROKE_DP = 9.0f;
    public static final float BASE_TEXT_SP = 16.0f;
    public static final float MIN_TEXT_SP = 9.0f;
    public static final float CHAR_WIDTH_FACTOR = 0.60f;
    public static final float INNER_DIAMETER_TEXT_MARGIN = 0.85f;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcBounds = new RectF();

    private double percent = 0.0;
    private String centerText = "—";
    private boolean isAvailable = false;
    private int progressColor = 0xFF00FF66; // Matrix phosphor green
    private int trackColor = 0xFF14261C;    // Subtle dark green track

    public RadialGaugeView(Context context) {
        super(context);
        init();
    }

    public RadialGaugeView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public RadialGaugeView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeCap(Paint.Cap.ROUND);
        trackPaint.setColor(trackColor);

        progressPaint.setStyle(Paint.Style.STROKE);
        progressPaint.setStrokeCap(Paint.Cap.ROUND);
        progressPaint.setColor(progressColor);

        textPaint.setAntiAlias(true);
        textPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setColor(0xFFE1EFE6);

        setFocusable(true);
    }

    /**
     * Resolves the text string to draw in the center of the ring.
     * Unavailable metrics or "Unavailable" text are rendered as a clean short dash ("—"),
     * allowing the external subtitle and TalkBack to announce the full status without
     * crowding or clipping the gauge ring.
     */
    public static String resolveDisplayText(String centerText, boolean isAvailable) {
        if (!isAvailable) {
            return "—";
        }
        if (centerText == null || centerText.trim().isEmpty() || "Unavailable".equalsIgnoreCase(centerText.trim())) {
            return "—";
        }
        return centerText.trim();
    }

    /**
     * Calculates the effective font size in pixels for centered ring text, meaningfully honoring
     * accessibility font scaling (scaledDensity / fontScale) with responsive geometry and readable fallback.
     *
     * @param baseSp base text size in sp (e.g. 16sp)
     * @param density display density (dp scale factor)
     * @param scaledDensity font scale factor (sp scale factor, reflecting accessibility settings)
     * @param innerDiameterPx available diameter in pixels inside the stroke ring
     * @param text string to be rendered
     * @return effective font size in pixels
     */
    public static float calculateEffectiveTextSize(
            float baseSp,
            float density,
            float scaledDensity,
            float innerDiameterPx,
            String text) {
        if (density <= 0.0f) density = 1.0f;
        if (scaledDensity <= 0.0f) scaledDensity = density;
        if (innerDiameterPx <= 0.0f) innerDiameterPx = 80.0f * density;

        // Honor accessibility font scale: target size starts from scaledDensity
        float targetSizePx = baseSp * scaledDensity;

        String resolved = resolveDisplayText(text, true);
        int len = resolved.length();
        if (len == 0) len = 1;

        // Maximum horizontal text width permitted inside circular arc ring
        float maxAllowedWidthPx = innerDiameterPx * INNER_DIAMETER_TEXT_MARGIN;

        // In monospace fonts, char width is approximately 0.60 * textSize
        float estimatedTextWidthPx = len * CHAR_WIDTH_FACTOR * targetSizePx;

        // If the scaled accessibility text fits comfortably, keep the full enlarged size!
        if (estimatedTextWidthPx <= maxAllowedWidthPx) {
            return targetSizePx;
        }

        // If text would overflow ring bounds under extreme font scaling or longer string,
        // scale down gracefully to the maximum size that fits safely within the ring.
        float fittedSizePx = maxAllowedWidthPx / (len * CHAR_WIDTH_FACTOR);

        // Readable floor: never shrink below readable fallback
        float readableFloorPx = MIN_TEXT_SP * density;
        return Math.max(fittedSizePx, readableFloorPx);
    }

    /**
     * Safely clamps percentage to 0.0 .. 100.0, handling NaN as 0.0,
     * negative values as 0.0, and positive infinity/overflow as honest 100.0% saturation.
     */
    public static double clampPercent(double value) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        if (value <= 0.0) return 0.0;
        if (value >= 100.0 || Double.isInfinite(value)) {
            return 100.0;
        }
        return value;
    }

    /**
     * Computes the sweep angle (in degrees) for a given percentage.
     * Returns 0.0 if metric is unavailable or percent <= 0.
     */
    public static float calculateSweepAngle(double percent, boolean isAvailable) {
        if (!isAvailable) {
            return 0.0f;
        }
        double clamped = clampPercent(percent);
        return (float) ((clamped / 100.0) * SWEEP_ANGLE);
    }

    /**
     * Updates the gauge presentation state without flashing or jarring animation.
     */
    public void setValue(double percent, String centerText, boolean isAvailable) {
        this.percent = clampPercent(percent);
        this.centerText = centerText != null ? centerText : "";
        this.isAvailable = isAvailable;
        invalidate();
    }

    public void setProgressColor(int color) {
        this.progressColor = color;
        this.progressPaint.setColor(color);
        invalidate();
    }

    public void setTrackColor(int color) {
        this.trackColor = color;
        this.trackPaint.setColor(color);
        invalidate();
    }

    public double getPercent() {
        return percent;
    }

    public String getCenterText() {
        return centerText;
    }

    public boolean isAvailable() {
        return isAvailable;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        float density = getResources().getDisplayMetrics().density;
        float scaledDensity = getResources().getDisplayMetrics().scaledDensity;
        float fontScale = (scaledDensity > 0.0f && density > 0.0f) ? (scaledDensity / density) : 1.0f;

        // Responsive geometry: default diameter adapts gracefully when accessibility text is enlarged
        float baseDp = 100.0f + Math.max(0.0f, fontScale - 1.0f) * 20.0f;
        int defaultSize = (int) (baseDp * density + 0.5f);

        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);

        int width = (widthMode == MeasureSpec.EXACTLY) ? widthSize :
                (widthMode == MeasureSpec.AT_MOST) ? Math.min(defaultSize, widthSize) : defaultSize;

        int height = (heightMode == MeasureSpec.EXACTLY) ? heightSize :
                (heightMode == MeasureSpec.AT_MOST) ? Math.min(defaultSize, heightSize) : defaultSize;

        int finalDim = Math.min(width, height);
        setMeasuredDimension(finalDim, finalDim);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float density = getResources().getDisplayMetrics().density;
        float strokeWidth = DEFAULT_STROKE_DP * density;

        trackPaint.setStrokeWidth(strokeWidth);
        progressPaint.setStrokeWidth(strokeWidth);

        float padding = strokeWidth / 2.0f + (2.0f * density);
        float left = getPaddingLeft() + padding;
        float top = getPaddingTop() + padding;
        float right = w - getPaddingRight() - padding;
        float bottom = h - getPaddingBottom() - padding;

        float size = Math.min(right - left, bottom - top);
        float cx = (left + right) / 2.0f;
        float cy = (top + bottom) / 2.0f;

        arcBounds.set(cx - size / 2.0f, cy - size / 2.0f, cx + size / 2.0f, cy + size / 2.0f);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (arcBounds.isEmpty()) {
            return;
        }

        // 1. Background Arc Track (subtle open ring)
        trackPaint.setColor(trackColor);
        canvas.drawArc(arcBounds, START_ANGLE, SWEEP_ANGLE, false, trackPaint);

        // 2. Progress Arc (only when available and positive)
        if (isAvailable && percent > 0.0) {
            progressPaint.setColor(progressColor);
            float sweep = calculateSweepAngle(percent, isAvailable);
            canvas.drawArc(arcBounds, START_ANGLE, sweep, false, progressPaint);
        }

        // 3. Centered Metric / Status Text
        String text = resolveDisplayText(centerText, isAvailable);
        if (text.isEmpty() && isAvailable) {
            text = String.format(Locale.US, "%.0f%%", percent);
        }

        float density = getResources().getDisplayMetrics().density;
        float scaledDensity = getResources().getDisplayMetrics().scaledDensity;
        float innerDiameter = arcBounds.width() - trackPaint.getStrokeWidth();
        float maxTextWidth = innerDiameter * INNER_DIAMETER_TEXT_MARGIN;

        // Meaningfully honor accessibility font scaling (scaledDensity)
        float effectiveSize = calculateEffectiveTextSize(BASE_TEXT_SP, density, scaledDensity, innerDiameter, text);
        textPaint.setTextSize(effectiveSize);

        // Fine-tune with runtime Paint measurements if custom font glyphs are wider
        while (textPaint.measureText(text) > maxTextWidth && textPaint.getTextSize() > (MIN_TEXT_SP * density)) {
            textPaint.setTextSize(textPaint.getTextSize() - (0.5f * density));
        }

        textPaint.setColor(isAvailable ? 0xFFE1EFE6 : 0xFF738E82);
        float cx = arcBounds.centerX();
        float cy = arcBounds.centerY();
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float baseline = cy - (fm.ascent + fm.descent) / 2.0f;

        canvas.drawText(text, cx, baseline, textPaint);
    }
}
