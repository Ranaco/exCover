package com.ranaco.razrcoverwallpaper;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;

/**
 * Full-screen, heavily blurred and saturated rendition of the selected GIF. It plays a tiny
 * (~64 px) decode of the animation: nearly free to decode, and the upscale plus blur hides the
 * low resolution. It only animates while visible.
 */
public final class GlassBackdropView extends View {
    /** Longest side the backdrop's copy of the GIF is decoded at. */
    public static final int DECODE_SIDE = 64;

    private final Paint dimPaint = new Paint();
    private final ColorMatrixColorFilter vibrancy;
    private Drawable image;

    public GlassBackdropView(Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        ColorMatrix saturation = new ColorMatrix();
        saturation.setSaturation(1.6f);
        vibrancy = new ColorMatrixColorFilter(saturation);
        dimPaint.setColor(0x80000000);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            float blur = 36 * getResources().getDisplayMetrics().density;
            setRenderEffect(RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP));
        }
    }

    public void setImage(Drawable drawable) {
        if (image != null) {
            image.setCallback(null);
            if (image instanceof Animatable) {
                ((Animatable) image).stop();
            }
        }
        image = drawable;
        if (image != null) {
            image.setCallback(this);
            image.setFilterBitmap(true);
            image.setColorFilter(vibrancy);
            image.setBounds(0, 0, image.getIntrinsicWidth(), image.getIntrinsicHeight());
            if (image instanceof Animatable && isShown()) {
                ((Animatable) image).start();
            }
        }
        invalidate();
    }

    @Override
    protected boolean verifyDrawable(Drawable who) {
        return who == image || super.verifyDrawable(who);
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        if (image instanceof Animatable) {
            if (isVisible) {
                ((Animatable) image).start();
            } else {
                ((Animatable) image).stop();
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(0xFF000000);
        Drawable current = image;
        if (current != null && current.getIntrinsicWidth() > 0 && current.getIntrinsicHeight() > 0) {
            // Centre-crop the tiny frame over the whole view, slightly overscaled so edges stay filled.
            float scale = Math.max(getWidth() / (float) current.getIntrinsicWidth(),
                    getHeight() / (float) current.getIntrinsicHeight()) * 1.15f;
            canvas.save();
            canvas.translate((getWidth() - current.getIntrinsicWidth() * scale) / 2f,
                    (getHeight() - current.getIntrinsicHeight() * scale) / 2f);
            canvas.scale(scale, scale);
            current.draw(canvas);
            canvas.restore();
        }
        canvas.drawRect(0, 0, getWidth(), getHeight(), dimPaint);
    }
}
