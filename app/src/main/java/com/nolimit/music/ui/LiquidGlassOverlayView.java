package com.nolimit.music.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * Lightweight optical overlay placed above a real BlurView.
 * It adds a moving specular highlight and edge light without obscuring the blurred content.
 */
public final class LiquidGlassOverlayView extends View {
    private final Paint wash = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint focus = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private float focusX = -1f;
    private float focusY = -1f;
    private float focusAlpha = 0f;
    private float radiusDp = 24f;

    public LiquidGlassOverlayView(Context context) { super(context); init(); }
    public LiquidGlassOverlayView(Context context, @Nullable AttributeSet attrs) { super(context, attrs); init(); }
    public LiquidGlassOverlayView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); init(); }

    private void init() {
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(dp(0.65f));
    }

    public void setCornerRadiusDp(float value) {
        radiusDp = value;
        invalidate();
    }

    public void setTouchHighlight(float x, float y, boolean pressed) {
        focusX = x;
        focusY = y;
        animate().cancel();
        if (pressed) {
            focusAlpha = 1f;
            setAlpha(1f);
            invalidate();
        } else {
            focusAlpha = 0f;
            animate().alpha(0.78f).setDuration(260).withEndAction(() -> {
                setAlpha(1f);
                invalidate();
            }).start();
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getWidth() <= 0 || getHeight() <= 0) return;

        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        float w = getWidth(), h = getHeight();
        float r = dp(radiusDp);
        rect.set(dp(0.5f), dp(0.5f), w - dp(0.5f), h - dp(0.5f));

        int top = dark ? 0x32FFFFFF : 0x66FFFFFF;
        int middle = dark ? 0x12FFFFFF : 0x24FFFFFF;
        int clear = 0x00FFFFFF;
        wash.setShader(new LinearGradient(0, 0, w * 0.86f, h,
                new int[]{top, middle, clear},
                new float[]{0f, 0.42f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(rect, r, r, wash);

        edge.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{dark ? 0x58FFFFFF : 0xA8FFFFFF, dark ? 0x18FFFFFF : 0x36FFFFFF, 0x08FFFFFF},
                new float[]{0f, 0.48f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(rect, r, r, edge);

        if (focusX >= 0f && focusAlpha > 0f) {
            int center = Color.argb((int)(dark ? 52 * focusAlpha : 82 * focusAlpha), 255, 255, 255);
            focus.setShader(new RadialGradient(focusX, focusY, Math.max(w, h) * 0.54f,
                    new int[]{center, 0x00FFFFFF},
                    new float[]{0f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(rect, r, r, focus);
        }
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
