package com.nolimit.music.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * Optical layer above BlurView. It deliberately avoids a uniform outline:
 * the glass is defined by translucency, soft top sheen and touch refraction.
 */
public final class LiquidGlassOverlayView extends View {
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint focus = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path clip = new Path();

    private float focusX = -1f;
    private float focusY = -1f;
    private float focusAlpha = 0f;
    private float radiusDp = 24f;

    public LiquidGlassOverlayView(Context context) { super(context); init(); }
    public LiquidGlassOverlayView(Context context, @Nullable AttributeSet attrs) { super(context, attrs); init(); }
    public LiquidGlassOverlayView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr); init();
    }

    private void init() {
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public void setCornerRadiusDp(float value) {
        radiusDp = value;
        invalidate();
    }

    public void setTouchHighlight(float x, float y, boolean pressed) {
        focusX = x;
        focusY = y;
        focusAlpha = pressed ? 1f : 0f;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        float r = dp(radiusDp);
        rect.set(0f, 0f, w, h);
        clip.reset();
        clip.addRoundRect(rect, r, r, Path.Direction.CW);

        canvas.save();
        canvas.clipPath(clip);

        // Broad, soft sheen from upper-left. No hard stroke around the capsule.
        sheen.setShader(new LinearGradient(
                0, 0, w * 0.82f, h,
                new int[]{
                        dark ? 0x38FFFFFF : 0x72FFFFFF,
                        dark ? 0x16FFFFFF : 0x30FFFFFF,
                        0x00FFFFFF
                },
                new float[]{0f, 0.38f, 1f},
                Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, sheen);

        // Subtle lower shading gives the surface thickness without an outline.
        shade.setShader(new LinearGradient(
                0, h * 0.46f, 0, h,
                new int[]{0x00000000, dark ? 0x18000000 : 0x0A000000},
                null, Shader.TileMode.CLAMP));
        canvas.drawRect(0, h * 0.35f, w, h, shade);

        // Local highlight follows the finger and reads as moving optical reflection.
        if (focusX >= 0f && focusAlpha > 0f) {
            int center = Color.argb(dark ? 54 : 76, 255, 255, 255);
            focus.setShader(new RadialGradient(
                    focusX, focusY,
                    Math.max(w * 0.30f, h * 1.35f),
                    new int[]{center, 0x12FFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.42f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, focus);
        }

        canvas.restore();
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
