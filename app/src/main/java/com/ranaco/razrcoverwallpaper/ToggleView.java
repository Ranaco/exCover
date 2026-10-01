package com.ranaco.razrcoverwallpaper;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.DecelerateInterpolator;
import android.widget.Switch;

/** An iOS-style on/off switch: a rounded track with a white knob that slides across. */
final class ToggleView extends View {
    interface Listener {
        void onChanged(boolean checked);
    }

    private static final int TRACK_OFF = 0x5C787880;

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private final int onColor;
    private boolean checked;
    /** 0 off, 1 on; drawn from this so the knob and colour move together. */
    private float position;
    private ValueAnimator animator;
    private Listener listener;

    ToggleView(Context context, int onColor) {
        super(context);
        this.onColor = onColor;
        knob.setColor(Color.WHITE);
        knob.setShadowLayer(dp(3), 0, dp(1.5f), 0x40000000);
        setLayerType(LAYER_TYPE_SOFTWARE, null); // the knob's shadow needs it
        setClickable(true);
        setFocusable(true);
        setOnClickListener(view -> setChecked(!this.checked, true));
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    boolean isChecked() {
        return checked;
    }

    void setChecked(boolean checked, boolean animate) {
        if (this.checked == checked && animator == null) {
            position = checked ? 1f : 0f;
            invalidate();
            return;
        }
        boolean changed = this.checked != checked;
        this.checked = checked;
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        if (animate && isAttachedToWindow()) {
            animator = ValueAnimator.ofFloat(position, checked ? 1f : 0f);
            animator.setDuration(220);
            animator.setInterpolator(new DecelerateInterpolator(1.5f));
            animator.addUpdateListener(animation -> {
                position = (float) animation.getAnimatedValue();
                invalidate();
            });
            animator.start();
        } else {
            position = checked ? 1f : 0f;
            invalidate();
        }
        if (changed && listener != null) {
            listener.onChanged(checked);
        }
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        setAlpha(enabled ? 1f : 0.4f);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(Math.round(dp(51) + dp(4)), Math.round(dp(31) + dp(6)));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = dp(51);
        float h = dp(31);
        float left = (getWidth() - w) / 2f;
        float top = (getHeight() - h) / 2f;
        bounds.set(left, top, left + w, top + h);
        track.setColor(blend(TRACK_OFF, onColor, position));
        canvas.drawRoundRect(bounds, h / 2f, h / 2f, track);

        float inset = dp(2);
        float radius = h / 2f - inset;
        float start = left + inset + radius;
        float end = left + w - inset - radius;
        canvas.drawCircle(start + (end - start) * position, top + h / 2f, radius, knob);
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(Switch.class.getName());
        info.setCheckable(true);
        info.setChecked(checked);
    }

    private static int blend(int from, int to, float t) {
        int a = Math.round(Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * t);
        int r = Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * t);
        int g = Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * t);
        int b = Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t);
        return Color.argb(a, r, g, b);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
