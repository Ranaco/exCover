package com.ranaco.razrcoverwallpaper;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.UserManager;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.util.Calendar;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * exCover's Motorola clock-face surface. Motorola composites third-party faces as an opaque
 * surface and uses the lock-screen choice for AOD too, so this one view draws both states.
 * Awake, it shows the selected lock GIF with a clock and date in the manner of Motorola's
 * Digital face. In AOD the same GIF stays, toned down for an OLED panel, with no clock, so the
 * lock screen dims into its always-on state. Motorola's burn-in offset moves the AOD image,
 * and a small overscan keeps shifted edges covered.
 *
 * Going into AOD the clock fades out while the GIF dims and grows slightly, all in one short
 * ease-out; waking plays it back a little faster. Motorola holds the cover awake for about half a
 * second when it changes into AOD, so the transition is kept inside that.
 *
 * Coming from the cover home screen, Motorola fades to black itself and shows the design already
 * in AOD, so there is nothing to transition from. The AOD then fades up out of black while the
 * GIF grows into its AOD size.
 *
 * The awake clock is drawn by {@link ClockFace}: the face is the design chosen in Motorola's
 * picker, and its font and colour come from Motorola's design editor as they change.
 *
 * The AOD frame is toned rather than covered with a flat scrim, in one of the looks in
 * {@link AodLook} chosen in exCover. Each takes shadows to true black, which is off on OLED.
 *
 * About two seconds into AOD, Android suspends the cover panel and it holds its last frame, so the
 * GIF pauses once AOD settles rather than drawing frames nobody sees. AOD can also be turned off
 * in exCover, which dims all the way to black.
 *
 * Motorola's clock face app starts before the phone is first unlocked after a restart, while
 * exCover's settings and GIF are still encrypted. Until then the design draws the default clock
 * on black, and it loads everything as soon as the phone is unlocked.
 */
final class AodClockView extends View implements SharedPreferences.OnSharedPreferenceChangeListener {
    /** How much larger the GIF is in AOD; also covers the edges when burn-in shifts it. */
    private static final float AOD_SCALE = 1.06f;
    private static final long TO_AOD_MS = 450L;
    private static final long FROM_AOD_MS = 320L;
    private static final long REVEAL_MS = 500L;
    private static final long LOCK_TICK_MS = 100L;
    private static final int MAX_DECODE_SIDE = 2048;
    /** The clock is gone by this point of the transition, before the GIF settles. */
    private static final float CLOCK_FADE_END = 0.6f;

    private final Paint scrim = new Paint();
    private final AodLook look = new AodLook();
    private String lookKey = AodLook.VIGNETTE;
    /** The progress the wallpaper's tone was last set for; NaN forces it to be set again. */
    private float toneProgress = Float.NaN;
    private final ClockFace clockFace = new ClockFace(getContext());
    private String clockStyle;
    private final Calendar calendar = Calendar.getInstance();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final AtomicInteger loadGeneration = new AtomicInteger();
    /** Null until the phone has been unlocked once since it started. */
    private SharedPreferences preferences;
    private BroadcastReceiver unlockReceiver;
    private final Runnable tick = this::tick;

    private Drawable wallpaper;
    private WallpaperStore.Crop crop = new WallpaperStore.Crop(1f, 0.5f, 0.5f);
    private boolean aod;
    private boolean shown = true;
    private boolean listening;
    private boolean loading;
    private float shiftX;
    private float shiftY;
    /** 0 on the lock screen, 1 in AOD; everything that differs between them follows this. */
    private float aodProgress;
    private ValueAnimator transition;
    /** 0 is black, 1 fully shown; only runs when the design appears straight into AOD. */
    private float reveal = 1f;
    private boolean revealPending;
    private ValueAnimator revealing;
    private boolean aodEnabled;

    AodClockView(Context context) {
        super(context);
        setBackgroundColor(Color.BLACK);
        scrim.setColor(Color.BLACK);
        aodEnabled = true;
        clockStyle = ClockFace.AIRY;
        clockFace.setColor(Color.WHITE);
        if (isUnlocked()) {
            loadSettings();
        }
    }

    void setAod(boolean aod) {
        if (this.aod != aod) {
            Log.i(ClockFaceProtocol.TAG, "design now " + (aod ? "AOD" : "lock screen"));
        }
        boolean changed = this.aod != aod;
        this.aod = aod;
        if (changed) {
            if (!aod) {
                cancelReveal();
            }
            animateTo(aod ? 1f : 0f);
        }
        updateWallpaperAnimation();
        invalidate();
        scheduleTick();
    }

    private void animateTo(float target) {
        if (transition != null) {
            transition.cancel();
        }
        if (!isAttachedToWindow() || !shown) {
            aodProgress = target; // nothing on screen to animate
            if (target >= 1f) {
                revealPending = true; // it will appear already in AOD
                reveal = 0f;
            }
            return;
        }
        transition = ValueAnimator.ofFloat(aodProgress, target);
        transition.setDuration(target > aodProgress ? TO_AOD_MS : FROM_AOD_MS);
        transition.setInterpolator(new DecelerateInterpolator(1.6f));
        transition.addUpdateListener(animation -> {
            aodProgress = (float) animation.getAnimatedValue();
            invalidate();
        });
        transition.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                updateWallpaperAnimation(); // AOD pauses once it has settled
            }
        });
        transition.start();
    }

    /** Fades the AOD up from black; waits for the GIF so it doesn't fade in an empty frame. */
    private void startRevealIfReady() {
        if (!revealPending || !shown || !isAttachedToWindow() || loading) {
            return;
        }
        revealPending = false;
        if (revealing != null) {
            revealing.cancel();
        }
        revealing = ValueAnimator.ofFloat(reveal, 1f);
        revealing.setDuration(REVEAL_MS);
        revealing.setInterpolator(new DecelerateInterpolator(1.6f));
        revealing.addUpdateListener(animation -> {
            reveal = (float) animation.getAnimatedValue();
            invalidate();
        });
        revealing.start();
    }

    private void cancelReveal() {
        revealPending = false;
        if (revealing != null) {
            revealing.cancel();
            revealing = null;
        }
        reveal = 1f;
    }

    private boolean isUnlocked() {
        UserManager users = getContext().getSystemService(UserManager.class);
        return users == null || users.isUserUnlocked();
    }

    /** Reads exCover's settings; only possible once the phone has been unlocked. */
    private void loadSettings() {
        preferences = WallpaperStore.preferences(getContext());
        aodEnabled = WallpaperStore.aodEnabled(getContext());
        lookKey = WallpaperStore.aodLook(getContext());
        toneProgress = Float.NaN;
    }

    void setFace(String face) {
        clockStyle = face;
        invalidate();
    }

    /** The font and colour chosen in Motorola's design editor; null keeps the face's own. */
    void setClockOptions(String font, Integer color) {
        clockFace.setFont(font);
        clockFace.setColor(color != null ? color : Color.WHITE);
        invalidate();
    }

    /** Picks up the settings and GIF the moment the phone is first unlocked. */
    private void watchForUnlock() {
        if (unlockReceiver != null) {
            return;
        }
        unlockReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                stopWatchingForUnlock();
                if (preferences == null && isAttachedToWindow()) {
                    loadSettings();
                    startListening();
                    loadWallpaper();
                    updateWallpaperAnimation();
                    invalidate();
                }
            }
        };
        IntentFilter unlocked = new IntentFilter(Intent.ACTION_USER_UNLOCKED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getContext().registerReceiver(unlockReceiver, unlocked, Context.RECEIVER_NOT_EXPORTED);
        } else {
            getContext().registerReceiver(unlockReceiver, unlocked);
        }
    }

    private void stopWatchingForUnlock() {
        if (unlockReceiver != null) {
            getContext().unregisterReceiver(unlockReceiver);
            unlockReceiver = null;
        }
    }

    private void startListening() {
        if (!listening && preferences != null) {
            preferences.registerOnSharedPreferenceChangeListener(this);
            listening = true;
        }
    }

    void setShown(boolean shown) {
        boolean appearing = shown && !this.shown;
        this.shown = shown;
        if (!shown && aod) {
            // Next time it shows it will already be in AOD, so fade it back up then.
            if (revealing != null) {
                revealing.cancel();
            }
            revealPending = true;
            reveal = 0f;
        } else if (appearing) {
            startRevealIfReady();
        }
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
        if (preferences == null && isUnlocked()) {
            loadSettings();
        }
        if (preferences == null) {
            watchForUnlock();
        } else {
            startListening();
            loadWallpaper();
        }
        scheduleTick();
        startRevealIfReady();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (transition != null) {
            transition.cancel();
            aodProgress = aod ? 1f : 0f;
        }
        if (revealing != null) {
            revealing.cancel();
        }
        main.removeCallbacks(tick);
        stopWatchingForUnlock();
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
        } else if (key.equals(WallpaperStore.KEY_AOD_LOOK)) {
            lookKey = WallpaperStore.aodLook(getContext());
            toneProgress = Float.NaN;
            invalidate();
        } else if (key.equals(WallpaperStore.KEY_AOD_ENABLED)) {
            aodEnabled = WallpaperStore.aodEnabled(getContext());
            updateWallpaperAnimation();
            invalidate();
        }
    }

    private void loadWallpaper() {
        crop = WallpaperStore.crop(getContext(), WallpaperStore.TARGET_LOCK, false);
        int generation = loadGeneration.incrementAndGet();
        loading = true;
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
                toneProgress = Float.NaN; // the new GIF needs its tone set
                if (result != null) {
                    result.setBounds(0, 0, result.getIntrinsicWidth(), result.getIntrinsicHeight());
                    result.setCallback(this);
                }
                loading = false;
                updateWallpaperAnimation();
                invalidate();
                startRevealIfReady();
            });
        });
    }

    /** Keeps the awake clock current. AOD has no clock, and the GIF redraws itself. */
    private void tick() {
        if (!shown || !isAttachedToWindow() || aod) {
            return;
        }
        invalidate();
        main.postDelayed(tick, LOCK_TICK_MS);
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
        boolean still = aod && aodProgress >= 1f;
        if (shown && isAttachedToWindow() && !still) {
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
        float progress = aodProgress;
        canvas.drawColor(Color.BLACK);
        applyTone(progress);
        drawWallpaper(canvas, w, h, progress * reveal);
        look.drawOverlay(canvas, lookKey, w, h, progress);
        // Fading up from black, or a turned-off AOD dimming the rest of the way to black.
        float black = Math.max(1f - reveal, aodEnabled ? 0f : progress);
        if (black > 0f) {
            scrim.setAlpha(Math.round(255 * black));
            canvas.drawRect(0, 0, w, h, scrim);
        }
        // The always-on display is just the dimmed GIF; the clock fades out on the way there.
        float clockAlpha = 1f - Math.min(1f, progress / CLOCK_FADE_END);
        if (clockAlpha > 0f) {
            calendar.setTimeInMillis(System.currentTimeMillis());
            clockFace.draw(canvas, clockStyle, w, h, clockAlpha, calendar,
                    DateFormat.is24HourFormat(getContext()));
        }
    }

    /** Tones the GIF along the chosen look's curve for {@code progress}. */
    private void applyTone(float progress) {
        Drawable current = wallpaper;
        if (current == null || progress == toneProgress) {
            return;
        }
        toneProgress = progress;
        current.setColorFilter(new ColorMatrixColorFilter(AodLook.curve(lookKey, progress)));
    }

    private void drawWallpaper(Canvas canvas, int width, int height, float progress) {
        Drawable current = wallpaper;
        if (current == null || current.getIntrinsicWidth() <= 0 || current.getIntrinsicHeight() <= 0) {
            return;
        }
        CropPreviewView.Transform transform = CropPreviewView.calculateTransform(
                width, height, current.getIntrinsicWidth(), current.getIntrinsicHeight(),
                crop.zoom, crop.focusX, crop.focusY);
        int save = canvas.save();
        if (progress > 0f) { // AOD size, reached as the transition or reveal runs
            float scale = lerp(1f, AOD_SCALE, progress);
            canvas.translate(width / 2f + shiftX * progress, height / 2f + shiftY * progress);
            canvas.scale(scale, scale);
            canvas.translate(-width / 2f, -height / 2f);
        }
        canvas.translate(transform.left, transform.top);
        canvas.scale(transform.scale, transform.scale);
        current.draw(canvas);
        canvas.restoreToCount(save);
    }

    private static float lerp(float from, float to, float t) {
        return from + (to - from) * t;
    }
}
