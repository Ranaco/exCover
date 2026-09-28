package com.ranaco.razrcoverwallpaper;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

public final class CropPreviewView extends View {
    public interface CropListener {
        void onCropCommitted(float zoom, float focusX, float focusY);
    }

    private final Paint guidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ScaleGestureDetector scaleDetector;
    private Drawable image;
    private CropListener cropListener;
    private float zoom = 1f;
    private float focusX = 0.5f;
    private float focusY = 0.5f;
    private float lastX;
    private float lastY;
    private boolean dragging;
    private float guideAlpha;
    private boolean cropEnabled = true;

    public CropPreviewView(Context context) {
        this(context, null);
    }

    public CropPreviewView(Context context, AttributeSet attributes) {
        super(context, attributes);
        setBackgroundColor(0xFF1C1C1E);
        guidePaint.setColor(Color.WHITE);
        guidePaint.setStrokeWidth(Math.max(1f, dp(1) * 0.75f));
        guidePaint.setStyle(Paint.Style.STROKE);
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                zoom = clamp(zoom * detector.getScaleFactor(), 1f, 5f);
                invalidate();
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector detector) {
                commitCrop();
            }
        });
        setContentDescription("Animated cover wallpaper crop preview. Pinch to zoom and drag to reposition.");
    }

    /** Shows a decoded GIF; animated drawables advance on their own and invalidate this view. */
    public void setImage(Drawable drawable, WallpaperStore.Crop crop) {
        if (image != drawable) {
            if (image != null) {
                image.setCallback(null);
                if (image instanceof Animatable) {
                    ((Animatable) image).stop();
                }
            }
            image = drawable;
            if (image != null) {
                image.setCallback(this);
                image.setBounds(0, 0, image.getIntrinsicWidth(), image.getIntrinsicHeight());
                if (image instanceof Animatable && isShown()) {
                    ((Animatable) image).start();
                }
            }
        }
        zoom = crop.zoom;
        focusX = crop.focusX;
        focusY = crop.focusY;
        invalidate();
    }

    /** When off, touches fall through to the parent so it can run the carousel swipe. */
    public void setCropEnabled(boolean enabled) {
        cropEnabled = enabled;
        invalidate();
    }

    public void setCropListener(CropListener listener) {
        cropListener = listener;
    }

    public void resetCrop() {
        zoom = 1f;
        focusX = 0.5f;
        focusY = 0.5f;
        commitCrop();
        invalidate();
    }

    @Override
    protected boolean verifyDrawable(Drawable who) {
        return who == image || super.verifyDrawable(who);
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        // Only animate while on screen; a paused preview costs no decoding.
        if (image instanceof Animatable) {
            if (isVisible) {
                ((Animatable) image).start();
            } else {
                ((Animatable) image).stop();
            }
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int desiredHeight = Math.round(width * (1272f / 1080f));
        int height = resolveSize(desiredHeight, heightMeasureSpec);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Drawable current = image;
        if (current != null && current.getIntrinsicWidth() > 0 && current.getIntrinsicHeight() > 0) {
            Transform transform = calculateTransform(getWidth(), getHeight(),
                    current.getIntrinsicWidth(), current.getIntrinsicHeight(), zoom, focusX, focusY);
            canvas.save();
            canvas.translate(transform.left, transform.top);
            canvas.scale(transform.scale, transform.scale);
            current.draw(canvas);
            canvas.restore();
        }

        // Rule-of-thirds guides fade in only while the user is framing.
        float targetAlpha = dragging || scaleDetector.isInProgress() ? 1f : cropEnabled ? 0.45f : 0f;
        guideAlpha += (targetAlpha - guideAlpha) * 0.2f;
        if (guideAlpha > 0.01f) {
            guidePaint.setAlpha(Math.round(guideAlpha * 90));
            float thirdX = getWidth() / 3f;
            float thirdY = getHeight() / 3f;
            canvas.drawLine(thirdX, 0, thirdX, getHeight(), guidePaint);
            canvas.drawLine(thirdX * 2, 0, thirdX * 2, getHeight(), guidePaint);
            canvas.drawLine(0, thirdY, getWidth(), thirdY, guidePaint);
            canvas.drawLine(0, thirdY * 2, getWidth(), thirdY * 2, guidePaint);
        }
        if (Math.abs(targetAlpha - guideAlpha) > 0.01f) {
            postInvalidateOnAnimation();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (image == null || !cropEnabled) {
            return false;
        }
        getParent().requestDisallowInterceptTouchEvent(true);
        scaleDetector.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = event.getX();
                lastY = event.getY();
                dragging = true;
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging && !scaleDetector.isInProgress()) {
                    float dx = event.getX() - lastX;
                    float dy = event.getY() - lastY;
                    int sourceWidth = image.getIntrinsicWidth();
                    int sourceHeight = image.getIntrinsicHeight();
                    float base = Math.max(
                            getWidth() / (float) sourceWidth,
                            getHeight() / (float) sourceHeight);
                    focusX = clamp(focusX - dx / (sourceWidth * base * zoom), 0f, 1f);
                    focusY = clamp(focusY - dy / (sourceHeight * base * zoom), 0f, 1f);
                    invalidate();
                }
                lastX = event.getX();
                lastY = event.getY();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                getParent().requestDisallowInterceptTouchEvent(false);
                commitCrop();
                performClick();
                invalidate();
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private void commitCrop() {
        if (cropListener != null) {
            cropListener.onCropCommitted(zoom, focusX, focusY);
        }
    }

    public static Transform calculateTransform(
            int targetWidth,
            int targetHeight,
            int sourceWidth,
            int sourceHeight,
            float zoom,
            float focusX,
            float focusY) {
        float baseScale = Math.max(
                targetWidth / (float) sourceWidth,
                targetHeight / (float) sourceHeight);
        float scale = baseScale * clamp(zoom, 1f, 5f);
        float renderedWidth = sourceWidth * scale;
        float renderedHeight = sourceHeight * scale;
        float left = targetWidth / 2f - clamp(focusX, 0f, 1f) * renderedWidth;
        float top = targetHeight / 2f - clamp(focusY, 0f, 1f) * renderedHeight;
        left = clamp(left, targetWidth - renderedWidth, 0f);
        top = clamp(top, targetHeight - renderedHeight, 0f);
        return new Transform(scale, left, top);
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private float dp(int value) {
        return value * getResources().getDisplayMetrics().density;
    }

    public static final class Transform {
        public final float scale;
        public final float left;
        public final float top;

        Transform(float scale, float left, float top) {
            this.scale = scale;
            this.left = left;
            this.top = top;
        }
    }
}
