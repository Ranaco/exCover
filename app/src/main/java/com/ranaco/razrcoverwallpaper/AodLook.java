package com.ranaco.razrcoverwallpaper;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;

/**
 * The looks the cover AOD can give the lock GIF's frozen frame, chosen in exCover. Each is a
 * colour curve on the GIF plus an optional black overlay. On OLED a black pixel is off, so every
 * look sends shadows to true black and gives off far less light than a flat dimming layer.
 *
 * The cover design and the previews in the app both draw through this class.
 */
final class AodLook {
    static final String NORMAL = "normal";
    static final String VIGNETTE = "vignette";
    static final String SHADOWS = "shadows";
    static final String MOONLIGHT = "moonlight";
    static final String HIGHLIGHTS = "highlights";
    static final String HORIZON = "horizon";
    static final String[] KEYS = {NORMAL, VIGNETTE, SHADOWS, MOONLIGHT, HIGHLIGHTS, HORIZON};
    static final String[] LABELS = {"Normal", "Vignette", "Shadows", "Moonlight", "Highlights", "Horizon"};
    static final String[] ABOUT = {
            "Your asset, evenly dimmed. Nothing else changes.",
            "A softly lit centre, with the edges fading into black.",
            "Full colour where it's lit, true black everywhere else.",
            "A cool silver-blue night version of your asset.",
            "Only the brightest parts glow out of the dark.",
            "Your asset rises out of black from the bottom."};
    /** Battery saving out of four, from how much light each look gives off. */
    static final int[] SAVING = {1, 3, 3, 2, 4, 2};

    /** How much of the GIF's brightness the awake lock screen keeps, under the clock. */
    private static final float LOCK_GAIN = 1f - 0x44 / 255f;

    private final Paint overlay = new Paint();
    private String overlayKey;
    private int overlayWidth;
    private int overlayHeight;

    static boolean isLook(String key) {
        for (String look : KEYS) {
            if (look.equals(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The colour curve for {@code look} at {@code progress}: the lock screen's light dimming at
     * 0, the look's AOD curve at 1, so the transition moves smoothly between them.
     */
    static ColorMatrix curve(String look, float progress) {
        float[] lock = gain(LOCK_GAIN, 0f).getArray();
        float[] aod = aodCurve(look).getArray();
        float[] mixed = new float[20];
        for (int i = 0; i < mixed.length; i++) {
            mixed[i] = lock[i] + (aod[i] - lock[i]) * progress;
        }
        return new ColorMatrix(mixed);
    }

    private static ColorMatrix aodCurve(String look) {
        switch (look) {
            case SHADOWS: // a hard curve: everything dark goes black, the lit parts keep colour
                return curve(0.95f, 0.32f, 34f);
            case MOONLIGHT: { // monochrome, tinted cool silver-blue
                ColorMatrix moon = curve(0f, 0.34f, 26f);
                ColorMatrix tint = new ColorMatrix();
                tint.setScale(0.66f, 0.80f, 1f, 1f);
                tint.preConcat(moon);
                return tint;
            }
            case HIGHLIGHTS: // only the brightest parts survive, glowing out of black
                return curve(1.05f, 0.66f, 118f);
            case NORMAL: // an even dim, colours and contrast untouched
                return gain(0.24f, 0f);
            case HORIZON: // gentle curve; the overlay does the rest
                return curve(0.85f, 0.36f, 14f);
            default: // VIGNETTE
                return curve(0.8f, 0.30f, 16f);
        }
    }

    /** Saturation first, then brightness kept and how much (of 255) is taken off. */
    private static ColorMatrix curve(float saturation, float gain, float cut) {
        ColorMatrix colour = new ColorMatrix();
        colour.setSaturation(saturation);
        ColorMatrix result = gain(gain, cut);
        result.preConcat(colour);
        return result;
    }

    private static ColorMatrix gain(float gain, float cut) {
        return new ColorMatrix(new float[]{
                gain, 0, 0, 0, -cut,
                0, gain, 0, 0, -cut,
                0, 0, gain, 0, -cut,
                0, 0, 0, 1, 0});
    }

    /** Draws {@code look}'s black overlay, if it has one, at {@code alpha} (0 to 1). */
    void drawOverlay(Canvas canvas, String look, int width, int height, float alpha) {
        if (alpha <= 0f || !(VIGNETTE.equals(look) || HORIZON.equals(look))) {
            return;
        }
        if (!look.equals(overlayKey) || width != overlayWidth || height != overlayHeight) {
            overlayKey = look;
            overlayWidth = width;
            overlayHeight = height;
            overlay.setShader(HORIZON.equals(look) ? horizon(height) : vignette(width, height));
        }
        overlay.setAlpha(Math.round(255 * alpha));
        canvas.drawRect(0, 0, width, height, overlay);
    }

    /** A soft elliptical falloff from just above the middle out to the corners. */
    private static Shader vignette(int width, int height) {
        float start = 0.55f;
        float end = 1.15f;
        RadialGradient gradient = new RadialGradient(0, 0, width / 2f * end,
                new int[]{0x00000000, 0x00000000, Color.argb(217, 0, 0, 0)},
                new float[]{0f, start / end, 1f}, Shader.TileMode.CLAMP);
        Matrix placement = new Matrix();
        placement.setScale(1f, height / (float) width); // stretched to the screen's shape
        placement.postTranslate(width / 2f, height * 0.45f);
        gradient.setLocalMatrix(placement);
        return gradient;
    }

    /** Black across the top, clearing towards the bottom, so the picture rises out of the dark. */
    private static Shader horizon(int height) {
        return new LinearGradient(0, 0, 0, height,
                new int[]{Color.argb(245, 0, 0, 0), Color.argb(245, 0, 0, 0), 0x00000000},
                new float[]{0f, 0.25f, 0.78f}, Shader.TileMode.CLAMP);
    }
}
