package com.ranaco.razrcoverwallpaper;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * A picture seen through folding glass (res/raw/fold_glass.agsl), drawn over a whole panel in a
 * touch-transparent overlay. The shader samples up to 28 times per pixel, too much for a full
 * resolution panel at 120 Hz, so it renders at half size into a hardware layer and is scaled up;
 * the frost hides the upscale.
 */
final class FoldGlass {
    private static final String TAG = "exCoverFold";
    private static final float RENDER_SCALE = 2f;
    /** Blur radius at the pane's outer edge at full tilt, full-resolution px. */
    private static final float EDGE_BLUR_PX = 150f;
    /** Light lost at the outer edge at full tilt. */
    private static final float EDGE_SHADE = 0.85f;
    /** How far the viewer's eye is from the screen. */
    private static final float EYE_DISTANCE_MM = 450f;

    private static String source;

    private final WindowManager windowManager;
    private final FrameLayout root;
    private final GlassView glass;
    private final View shadow;
    private boolean attached;

    /** Plain black, for a panel waking with no picture to show: fade it out with {@link #apply}. */
    static FoldGlass plain(Context context, WindowManager windowManager, int width, int height) {
        return new FoldGlass(context, windowManager, width, height, null, null, false, 0, -1, 0, 1f, 1f);
    }

    boolean plain() {
        return glass == null;
    }

    /**
     * @param picture   what lies behind the glass
     * @param region    the part of the picture to lay over the whole panel
     * @param turned    lay the picture over the panel upside down
     * @param hinge     the hinge line, px from the panel's top
     * @param side      -1 when the pane above the hinge moves
     * @param eyeY      the line the viewer looks straight at, px from the top
     * @param maxTilt   the tilt, in degrees, that {@link #apply} treats as full frost
     */
    FoldGlass(Context context, WindowManager windowManager, int width, int height, Bitmap picture,
              Rect region, boolean turned, int hinge, int side, int eyeY, float pxPerMm, float maxTilt) {
        this.windowManager = windowManager;
        root = new FrameLayout(context);
        root.setBackgroundColor(Color.BLACK);
        if (picture != null) {
            RuntimeShader shader = createShader(context);
            if (shader == null) {
                glass = null;
                shadow = null;
                return;
            }
            glass = new GlassView(context, shader, picture, region, turned, width, height, hinge, side, eyeY,
                    pxPerMm, maxTilt);
            root.addView(glass, new FrameLayout.LayoutParams(
                    Math.round(width / RENDER_SCALE), Math.round(height / RENDER_SCALE)));
            glass.setPivotX(0f);
            glass.setPivotY(0f);
            glass.setScaleX(RENDER_SCALE);
            glass.setScaleY(RENDER_SCALE);
            glass.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        } else {
            glass = null;
        }
        shadow = new View(context);
        shadow.setBackgroundColor(Color.BLACK);
        shadow.setAlpha(0f);
        root.addView(shadow, new FrameLayout.LayoutParams(width, height));
        root.setAlpha(0f);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                width, height,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        params.setFitInsetsTypes(0);
        params.setTitle("exCoverFold");
        // If the panel turns with gravity mid-fold, cut to the new rotation rather than animating
        // the turn; the glass is rebuilt for it in the same frame.
        params.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED;
        params.rotationAnimation = WindowManager.LayoutParams.ROTATION_ANIMATION_JUMPCUT;
        try {
            windowManager.addView(root, params);
            attached = true;
        } catch (Exception error) {
            Log.e(TAG, "couldn't add the fold overlay", error);
        }
    }

    boolean attached() {
        return attached;
    }

    /**
     * @param alpha how much of the overlay shows over the live screen
     * @param dark  how far the glass has passed into shadow, 0 to 1 (the panel handover)
     */
    void apply(float tiltDegrees, float alpha, float dark) {
        if (glass != null) {
            glass.setTilt(tiltDegrees);
        }
        shadow.setAlpha(Math.max(0f, Math.min(1f, dark)));
        root.setAlpha(Math.max(0f, Math.min(1f, alpha)));
    }

    void detach() {
        if (attached) {
            attached = false;
            try {
                windowManager.removeViewImmediate(root);
            } catch (Exception ignored) {
            }
        }
    }

    private static RuntimeShader createShader(Context context) {
        try {
            if (source == null) {
                try (InputStream input = context.getResources().openRawResource(R.raw.fold_glass);
                     ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[4096];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                    source = out.toString("UTF-8");
                }
            }
            return new RuntimeShader(source);
        } catch (IOException | RuntimeException error) {
            Log.e(TAG, "fold shader unavailable", error);
            return null;
        }
    }

    private static final class GlassView extends View {
        private final RuntimeShader shader;
        private final BitmapShader image;
        private final Paint paint = new Paint();
        private final float hinge;
        private final float side;
        private final float eyeY;
        private final float eyeDistance;
        private final float blurPerLift;
        private final float shadePerRadius;
        private float tilt;

        GlassView(Context context, RuntimeShader shader, Bitmap picture, Rect region, boolean turned,
                  int width, int height, int hinge, int side, int eyeY, float pxPerMm, float maxTilt) {
            super(context);
            this.shader = shader;
            // Everything below is in the half-size render's pixels.
            this.hinge = hinge / RENDER_SCALE;
            this.side = side;
            this.eyeY = eyeY / RENDER_SCALE;
            this.eyeDistance = EYE_DISTANCE_MM * pxPerMm / RENDER_SCALE;
            float paneLength = Math.max(1f, (side < 0 ? hinge : height - hinge) / RENDER_SCALE);
            float edgeLift = paneLength * (float) Math.sin(Math.toRadians(maxTilt));
            float edgeBlur = EDGE_BLUR_PX / RENDER_SCALE;
            this.blurPerLift = edgeBlur / edgeLift;
            this.shadePerRadius = EDGE_SHADE / edgeBlur;
            image = new BitmapShader(picture, Shader.TileMode.DECAL, Shader.TileMode.DECAL);
            Matrix matrix = new Matrix();
            matrix.setTranslate(-region.left, -region.top);
            matrix.postScale(width / RENDER_SCALE / region.width(), height / RENDER_SCALE / region.height());
            if (turned) {
                matrix.postRotate(180f, width / RENDER_SCALE / 2f, height / RENDER_SCALE / 2f);
            }
            image.setLocalMatrix(matrix);
        }

        void setTilt(float degrees) {
            if (degrees != tilt) {
                tilt = degrees;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (shader == null || w <= 1f || h <= 1f) {
                return;
            }
            shader.setFloatUniform("size", w, h);
            shader.setFloatUniform("hinge", hinge);
            shader.setFloatUniform("side", side);
            shader.setFloatUniform("eyeY", eyeY);
            shader.setFloatUniform("eyeDistance", eyeDistance);
            shader.setFloatUniform("tilt", (float) Math.toRadians(tilt));
            shader.setFloatUniform("blurPerLift", blurPerLift);
            shader.setFloatUniform("shadePerRadius", shadePerRadius);
            shader.setInputShader("content", image);
            paint.setShader(shader);
            canvas.drawRect(0f, 0f, w, h, paint);
        }
    }
}
