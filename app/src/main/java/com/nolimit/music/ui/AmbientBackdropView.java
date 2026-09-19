package com.nolimit.music.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * Low-contrast color field behind the floating glass controls.
 * It gives the blur surface real color/depth to refract even on otherwise black screens.
 */
public final class AmbientBackdropView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int primary = 0xFFFF375F;
    private int secondary = 0xFF5E5CE6;

    public AmbientBackdropView(Context context) { super(context); init(); }
    public AmbientBackdropView(Context context, @Nullable AttributeSet attrs) { super(context, attrs); init(); }
    public AmbientBackdropView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); init(); }

    private void init() {
        setWillNotDraw(false);
        setClickable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public void setColors(int first, int second) {
        primary = ensureOpaque(first);
        secondary = ensureOpaque(second);
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;

        float y = h * 0.92f;
        float radius = Math.max(w, h * 0.42f);

        paint.setShader(new RadialGradient(
                w * 0.30f, y, radius,
                new int[]{withAlpha(primary, 82), withAlpha(primary, 28), Color.TRANSPARENT},
                new float[]{0f, 0.46f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, h * 0.58f, w, h, paint);

        paint.setShader(new RadialGradient(
                w * 0.84f, h * 0.88f, radius * 0.86f,
                new int[]{withAlpha(secondary, 66), withAlpha(secondary, 20), Color.TRANSPARENT},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, h * 0.58f, w, h, paint);
        paint.setShader(null);
    }

    private static int ensureOpaque(int c) {
        return Color.argb(255, Color.red(c), Color.green(c), Color.blue(c));
    }

    private static int withAlpha(int c, int a) {
        return Color.argb(a, Color.red(c), Color.green(c), Color.blue(c));
    }
}
