package com.ranaco.razrcoverwallpaper;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.View;

import java.util.Calendar;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * exCover's Motorola clock-face surface. Motorola composites third-party faces as an opaque
 * surface, so the selected lock GIF is rendered here as well as by the wallpaper service.
 * In AOD the same lock GIF keeps playing behind a heavy black scrim, so the lock screen appears
 * to dim into its always-on state instead of switching to unrelated artwork. Motorola's burn-in
 * offset moves the complete AOD composition, and a small overscan keeps shifted edges covered.
 */
final class AodClockView extends View implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final float AOD_CLOCK_DIM = 0.50f;
    private static final float AOD_WALLPAPER_OVERSCAN = 1.04f;
    private static final float HUE_PERIOD_S = 24f;
    private static final long LOCK_TICK_MS = 100L;
    private static final long AOD_TICK_MS = 1_000L;
    private static final int MAX_DECODE_SIDE = 2048;

    private final Paint time = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint date = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lockScrim = new Paint();
    private final Paint aodScrim = new Paint();
    private final Calendar calendar = Calendar.getInstance();
    private final float[] hsv = {0f, 0.75f, 1f};
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final AtomicInteger loadGeneration = new AtomicInteger();
    private final SharedPreferences preferences;
    private final Runnable tick = this::tick;

    private Drawable wallpaper;
    private WallpaperStore.Crop crop = new WallpaperStore.Crop(1f, 0.5f, 0.5f);
    private boolean aod;
    private boolean shown = true;
    private boolean listening;
    private float shiftX;
    private float shiftY;
    private final long animationStartMs = android.os.SystemClock.uptimeMillis();

    AodClockView(Context context) {
        super(context);
        setBackgroundColor(Color.BLACK);
        preferences = WallpaperStore.preferences(context);
        time.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        time.setTextAlign(Paint.Align.CENTER);
        date.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        date.setTextAlign(Paint.Align.CENTER);
        lockScrim.setColor(0x44000000);
        // Leave enough detail to recognise the GIF while keeping most OLED pixels near black.
        aodScrim.setColor(0xC4000000);
    }

    void setAod(boolean aod) {
        if (this.aod != aod) {
            Log.i(ClockFaceProtocol.TAG, "design now " + (aod ? "AOD" : "lock screen"));
        }
        this.aod = aod;
        updateWallpaperAnimation();
        invalidate();
        scheduleTick();
    }

    void setShown(boolean shown) {
        this.shown = shown;
        updateWallpaperAnimation();
        if (shown) {
            scheduleTick();
        } else {
            main.removeCallbacks(tick);
        }
    }

    void setBurnInShift(float x, float y) {
        shiftX = x;
        shiftY = y;
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!listening) {
            preferences.registerOnSharedPreferenceChangeListener(this);
            listening = true;
        }
        loadWallpaper();
        scheduleTick();
    }

    @Override
    protected void onDetachedFromWindow() {
        main.removeCallbacks(tick);
        if (listening) {
            preferences.unregisterOnSharedPreferenceChangeListener(this);
            listening = false;
        }
        stopWallpaper(wallpaper);
        if (wallpaper != null) {
            wallpaper.setCallback(null);
        }
        decoder.shutdownNow();
        super.onDetachedFromWindow();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (key == null) {
            return;
        }
        if (key.equals(WallpaperStore.key(WallpaperStore.TARGET_LOCK, WallpaperStore.KEY_REVISION))) {
            loadWallpaper();
        } else if (key.startsWith(WallpaperStore.TARGET_LOCK + "_") && key.contains("crop")) {
            crop = WallpaperStore.crop(getContext(), WallpaperStore.TARGET_LOCK, false);
            invalidate();
        }
    }

    private void loadWallpaper() {
        crop = WallpaperStore.crop(getContext(), WallpaperStore.TARGET_LOCK, false);
        int generation = loadGeneration.incrementAndGet();
        decoder.execute(() -> {
            Drawable decoded = null;
            try {
                decoded = GifDecoder.decode(
                        GifDecoder.source(getContext(), WallpaperStore.TARGET_LOCK), MAX_DECODE_SIDE);
            } catch (Exception error) {
                Log.e(ClockFaceProtocol.TAG, "could not decode lock GIF for clock face", error);
            }
            Drawable result = decoded;
            main.post(() -> {
                if (generation != loadGeneration.get() || !isAttachedToWindow()) {
                    stopWallpaper(result);
                    return;
                }
                stopWallpaper(wallpaper);
                if (wallpaper != null) {
                    wallpaper.setCallback(null);
                }
                wallpaper = result;
                if (result != null) {
                    result.setBounds(0, 0, result.getIntrinsicWidth(), result.getIntrinsicHeight());
                    result.setCallback(this);
                }
                updateWallpaperAnimation();
                invalidate();
            });
        });
    }

    private void tick() {
        if (!shown || !isAttachedToWindow()) {
            return;
        }
        invalidate();
        main.postDelayed(tick, aod ? AOD_TICK_MS : LOCK_TICK_MS);
    }

    private void scheduleTick() {
        main.removeCallbacks(tick);
        if (shown && isAttachedToWindow()) {
            main.post(tick);
        }
    }

    private void updateWallpaperAnimation() {
        Drawable current = wallpaper;
        if (!(current instanceof Animatable)) {
            return;
        }
        if (shown && isAttachedToWindow()) {
            ((Animatable) current).start();
        } else {
            ((Animatable) current).stop();
        }
    }

    private static void stopWallpaper(Drawable drawable) {
        if (drawable instanceof Animatable) {
            ((Animatable) drawable).stop();
        }
    }

    @Override
    protected boolean verifyDrawable(Drawable who) {
        return who == wallpaper || super.verifyDrawable(who);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        canvas.drawColor(Color.BLACK);
        drawWallpaper(canvas, w, h);
        canvas.drawRect(0, 0, w, h, aod ? aodScrim : lockScrim);

        float seconds = (android.os.SystemClock.uptimeMillis() - animationStartMs) / 1_000f;
        float hue = (seconds / HUE_PERIOD_S * 360f) % 360f;
        int first = color(hue, 1f);
        int second = color(hue + 120f, 1f);

        calendar.setTimeInMillis(System.currentTimeMillis());
        String pattern = DateFormat.is24HourFormat(getContext()) ? "HH:mm" : "h:mm";
        String clock = DateFormat.format(pattern, calendar).toString();
        String day = DateFormat.format("EEE d MMM", calendar).toString();

        float cx = w / 2f + (aod ? shiftX : 0f);
        float cy = h * 0.46f + (aod ? shiftY : 0f);
        time.setTextSize(w * 0.30f);
        date.setTextSize(w * 0.055f);
        time.setShader(new LinearGradient(cx - w * 0.35f, 0f, cx + w * 0.35f, 0f,
                aod ? dim(first) : first, aod ? dim(second) : second, Shader.TileMode.MIRROR));
        date.setShader(null);
        date.setColor(aod ? dim(color(hue + 60f, 0.6f)) : color(hue + 60f, 0.55f));
        canvas.drawText(clock, cx, cy, time);
        canvas.drawText(day.toUpperCase(), cx, cy + date.getTextSize() * 1.8f, date);
    }

    private void drawWallpaper(Canvas canvas, int width, int height) {
        Drawable current = wallpaper;
        if (current == null || current.getIntrinsicWidth() <= 0 || current.getIntrinsicHeight() <= 0) {
            return;
        }
        CropPreviewView.Transform transform = CropPreviewView.calculateTransform(
                width, height, current.getIntrinsicWidth(), current.getIntrinsicHeight(),
                crop.zoom, crop.focusX, crop.focusY);
        int save = canvas.save();
        if (aod) {
            canvas.translate(width / 2f + shiftX, height / 2f + shiftY);
            canvas.scale(AOD_WALLPAPER_OVERSCAN, AOD_WALLPAPER_OVERSCAN);
            canvas.translate(-width / 2f, -height / 2f);
        }
        canvas.translate(transform.left, transform.top);
        canvas.scale(transform.scale, transform.scale);
        current.draw(canvas);
        canvas.restoreToCount(save);
    }

    private int color(float hue, float saturation) {
        hsv[0] = ((hue % 360f) + 360f) % 360f;
        hsv[1] = saturation;
        hsv[2] = 1f;
        return Color.HSVToColor(hsv);
    }

    private static int dim(int color) {
        return Color.rgb(Math.round(Color.red(color) * AOD_CLOCK_DIM),
                Math.round(Color.green(color) * AOD_CLOCK_DIM),
                Math.round(Color.blue(color) * AOD_CLOCK_DIM));
    }
}
