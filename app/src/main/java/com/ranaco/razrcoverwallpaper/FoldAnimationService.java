package com.ranaco.razrcoverwallpaper;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.animation.AnimatorListenerAdapter;
import android.animation.Animator;
import android.animation.ValueAnimator;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.SparseArray;
import android.view.Choreographer;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.animation.DecelerateInterpolator;

/**
 * The iPhone Duo style fold for the Razr. While the phone opens or closes, the half that moves
 * becomes a pane of glass: the content looks like it stays flat behind it while the glass tilts,
 * blurring and darkening the farther it lifts, then settles into focus as it lands.
 *
 * - Inner screen: the top half is the glass, over a picture of the screen's own content (see
 *   FoldGlass). The picture is taken as a close starts and kept in memory for the next fold; it
 *   is never saved or read for its content.
 * - Cover screen: the same glass over a picture of the cover's own screen, heaviest by the
 *   cameras. Opening, it frosts and goes dark for the handover; closing, it settles back into
 *   focus and dissolves into the live cover.
 * - Stopping partway fades the effect so the screen is back to normal; moving again picks it up. Runs as an accessibility service, the only kind of app allowed
 * to draw above every window on both panels. Inspired by duo-open (github.com/marcoazeem/duo-open).
 *
 * Measured on a Razr 50 Ultra: the hinge angle arrives in 1 to 5 degree steps at 25 to 40 Hz;
 * opening turns the cover off near 90 degrees and lights the inner screen 0.2 to 0.4 s later
 * (anywhere from 87 to 140 degrees); closing turns the inner screen off between 50 and 86 degrees.
 * A flat phone reads 173 to 180 degrees.
 */
public final class FoldAnimationService extends AccessibilityService implements SensorEventListener {
    private static final String TAG = "exCoverFold";

    /** Hinge angle treated as fully open. */
    private static final float FLAT_HINGE = 172f;
    /** Roughly where the Razr swaps panels. */
    private static final float SWAP_HINGE = 90f;
    /** A closed Razr idles a few degrees off zero. */
    private static final float CLOSED_HINGE = 6f;
    /** Pane tilt at full frost. */
    private static final float MAX_TILT = 45f;

    /** Hinge jitter smaller than this doesn't count as moving. */
    private static final float MOVE_STEP = 2f;
    /** No movement for this long means the fold stopped partway: let the screen through. */
    private static final long STALL_MS = 600;
    /** A panel that lights up mid-fold holds a tilt, then settles out of it (see settleTilt). */
    private static final float COVER_SETTLE_TILT = MAX_TILT * 0.8f;
    private static final long COVER_SETTLE_HOLD_MS = 250;
    private static final long COVER_SETTLE_MS = 1_200;
    private static final float INNER_SETTLE_TILT = MAX_TILT * 0.5f;
    private static final long INNER_SETTLE_HOLD_MS = 120;
    private static final long INNER_SETTLE_MS = 450;
    /** How long a fold's first frame waits for its picture. */
    private static final long CAPTURE_WAIT_MS = 250;
    /** A fold this soon after the last picture reuses it (screenshots are rate limited). */
    private static final long RECAPTURE_AFTER_MS = 1_000;
    /** A cover lit on a close has drawn its content by about now. */
    private static final long COVER_REFRESH_AFTER_MS = 150;

    /** Hinge angles over which a panel passes into shadow before the handover. */
    // Fully dark well before the cover turns with gravity (it starts turning shortly before it
    // reports 90 degrees), so the turn happens on a black screen and can't be seen.
    private static final float SHADOW_COVER_FROM = 42f;
    private static final float SHADOW_COVER_TO = 70f;
    /** A panel that has just lit comes up out of black over this long. */
    private static final long WAKE_FADE_MS = 220;

    private static volatile FoldAnimationService instance;

    /** 0 at {@code from}, 1 at {@code to}, eased; either direction. */
    private static float smoothstep(float from, float to, float value) {
        float t = Math.max(0f, Math.min(1f, (value - from) / (to - from)));
        return t * t * (3f - 2f * t);
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SparseArray<Engine> engines = new SparseArray<>();
    private SensorManager sensors;
    private DisplayManager displays;
    private float angle = Float.NaN;
    private float anchor = Float.NaN;
    private long lastMoveMs;
    /** The inner screen as it last looked before a fold, in memory only. */
    private Bitmap innerPicture;
    private long innerPictureMs;
    /** Whether the lock screen was up when it was taken. */
    private boolean innerPictureLocked;
    /** The cover as it last looked, and the rotation it was showing then. */
    private Bitmap coverPicture;
    private long coverPictureMs;
    private int coverPictureRotation;

    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int displayId) { syncDisplays(); }
        @Override public void onDisplayRemoved(int displayId) { syncDisplays(); }
        @Override public void onDisplayChanged(int displayId) { syncDisplays(); }
    };

    static boolean isEnabled(Context context) {
        AccessibilityManager manager = context.getSystemService(AccessibilityManager.class);
        if (manager == null) {
            return false;
        }
        ComponentName self = new ComponentName(context, FoldAnimationService.class);
        for (AccessibilityServiceInfo info
                : manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            if (info.getResolveInfo() != null && info.getResolveInfo().serviceInfo != null
                    && self.equals(new ComponentName(info.getResolveInfo().serviceInfo.packageName,
                    info.getResolveInfo().serviceInfo.name))) {
                return true;
            }
        }
        return false;
    }

    /** Plays an unfold on the inner screen without moving the hinge. False when the service is off. */
    static boolean preview() {
        FoldAnimationService service = instance;
        if (service == null) {
            return false;
        }
        service.handler.post(() -> {
            Engine engine = service.engines.get(Display.DEFAULT_DISPLAY);
            if (engine != null) {
                engine.playPreview();
            }
        });
        return true;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return; // the glass shader needs Android 13
        }
        instance = this;
        sensors = getSystemService(SensorManager.class);
        displays = getSystemService(DisplayManager.class);
        Sensor hinge = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE);
        if (hinge == null) {
            Log.w(TAG, "no hinge angle sensor");
        } else {
            sensors.registerListener(this, hinge, 8_000, handler);
        }
        displays.registerDisplayListener(displayListener, handler);
        syncDisplays();
    }

    @Override
    public void onDestroy() {
        instance = null;
        if (sensors != null) {
            sensors.unregisterListener(this);
        }
        if (displays != null) {
            displays.unregisterDisplayListener(displayListener);
        }
        for (int i = 0; i < engines.size(); i++) {
            engines.valueAt(i).destroy();
        }
        engines.clear();
        innerPicture = null;
        super.onDestroy();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() {}
    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    @Override
    public void onSensorChanged(SensorEvent event) {
        float value = event.values[0];
        if (!Float.isFinite(value) || value < -5f || value > 185f) {
            return;
        }
        angle = Math.max(0f, Math.min(180f, value));
        if (Float.isNaN(anchor) || Math.abs(angle - anchor) >= MOVE_STEP) {
            anchor = angle;
            lastMoveMs = SystemClock.uptimeMillis();
        }
        for (int i = 0; i < engines.size(); i++) {
            engines.valueAt(i).kick();
        }
    }

    private boolean moving() {
        return SystemClock.uptimeMillis() - lastMoveMs < STALL_MS;
    }

    private static Rect fullRegion(Bitmap picture) {
        return new Rect(0, 0, picture.getWidth(), picture.getHeight());
    }

    /** A panel that has only just lit can still be black; such a picture is no use. */
    private static boolean isMostlyBlack(Bitmap hardware) {
        Bitmap small = null;
        try {
            small = Bitmap.createScaledBitmap(hardware.copy(Bitmap.Config.ARGB_8888, false), 24, 24, false);
            int brightest = 0;
            for (int y = 0; y < 24; y++) {
                for (int x = 0; x < 24; x++) {
                    int c = small.getPixel(x, y);
                    brightest = Math.max(brightest, ((c >> 16) & 0xFF) + ((c >> 8) & 0xFF) + (c & 0xFF));
                }
            }
            return brightest < 30;
        } catch (RuntimeException error) {
            return false;
        } finally {
            if (small != null) {
                small.recycle();
            }
        }
    }

    private boolean keyguardLocked() {
        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        return keyguard != null && keyguard.isKeyguardLocked();
    }

    /** One engine per built-in panel: the inner screen is display 0, the cover shares its name. */
    private void syncDisplays() {
        Display primary = displays.getDisplay(Display.DEFAULT_DISPLAY);
        String builtInName = primary == null ? null : primary.getName();
        SparseArray<Display> panels = new SparseArray<>();
        for (Display display : displays.getDisplays()) {
            boolean builtIn = display.getDisplayId() == Display.DEFAULT_DISPLAY
                    || (builtInName != null && builtInName.equals(display.getName())
                    && (display.getFlags() & Display.FLAG_PRIVATE) == 0);
            if (builtIn) {
                panels.put(display.getDisplayId(), display);
            }
        }
        for (int i = engines.size() - 1; i >= 0; i--) {
            if (panels.get(engines.keyAt(i)) == null) {
                engines.valueAt(i).destroy();
                engines.removeAt(i);
            }
        }
        for (int i = 0; i < panels.size(); i++) {
            int id = panels.keyAt(i);
            Engine engine = engines.get(id);
            if (engine == null) {
                engine = new Engine(panels.valueAt(i));
                engines.put(id, engine);
            }
            engine.onDisplayChanged();
        }
    }

    /** The fold on one panel: eases a pane tilt toward the hinge and draws the glass for it. */
    private final class Engine implements Choreographer.FrameCallback {
        private final Display display;
        private final boolean inner;
        private final WindowManager windowManager;
        private final float pxPerMm;
        private FoldGlass glass;
        private int glassRotation;
        private boolean lit;
        private float tilt;
        private float visibility;
        /** When this panel lit up mid-fold, or 0. */
        private long litMidFoldMs;
        private boolean scheduled;
        private long lastFrameNanos;
        private ValueAnimator preview;
        private float previewTilt = Float.NaN;
        private boolean capturing;
        private long captureStartMs;
        /** A fresh picture of the cover has been asked for since it last lit up. */
        private boolean coverRefreshed;

        Engine(Display display) {
            this.display = display;
            this.inner = display.getDisplayId() == Display.DEFAULT_DISPLAY;
            Context displayContext = createDisplayContext(display);
            this.windowManager = displayContext
                    .createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
                    .getSystemService(WindowManager.class);
            float xdpi = displayContext.getResources().getDisplayMetrics().xdpi;
            this.pxPerMm = xdpi > 0f ? xdpi / 25.4f : 16f;
        }

        /**
         * Inner screen: flat when open, full tilt around the panel swap. Cover screen: flat when
         * closed, full tilt at the swap. An open or a close is one tilt-up on the first panel and
         * one tilt-down on the second.
         */
        private float targetTilt() {
            if (!Float.isNaN(previewTilt)) {
                return previewTilt;
            }
            if (Float.isNaN(angle)) {
                return 0f;
            }
            float progress = inner
                    ? (FLAT_HINGE - angle) / (FLAT_HINGE - SWAP_HINGE)
                    : (angle - CLOSED_HINGE) / (SWAP_HINGE - CLOSED_HINGE);
            return Math.max(Math.max(0f, Math.min(1f, progress)) * MAX_TILT, settleTilt());
        }

        /**
         * A panel that lights up mid-fold shows its content a moment later, often when the hinge
         * has nearly landed, so it holds a tilt of its own and settles out of it on a timer.
         * The cover lights only when the phone is nearly shut, so its settle is the whole close
         * effect and runs long and gently; the inner screen follows the hinge while opening, so
         * its settle only bridges the wake and is kept short.
         */
        private float settleTilt() {
            float progress = settleProgress();
            if (progress < 0f) {
                return 0f;
            }
            if (inner) {
                float remaining = 1f - progress;
                return INNER_SETTLE_TILT * remaining * remaining;
            }
            return COVER_SETTLE_TILT * (1f - smoothstep(0f, 1f, progress));
        }

        /** -1 when not settling, 0 while holding, then up to 1 as the settle completes. */
        private float settleProgress() {
            if (litMidFoldMs == 0L) {
                return -1f;
            }
            long hold = inner ? INNER_SETTLE_HOLD_MS : COVER_SETTLE_HOLD_MS;
            long length = inner ? INNER_SETTLE_MS : COVER_SETTLE_MS;
            long elapsed = SystemClock.uptimeMillis() - litMidFoldMs;
            float progress = Math.max(0f, (elapsed - hold) / (float) length);
            if (progress >= 1f) {
                litMidFoldMs = 0L;
                return -1f;
            }
            return progress;
        }

        private boolean wanted() {
            return lit && (!Float.isNaN(previewTilt) || moving() || litMidFoldMs != 0L)
                    && targetTilt() > 0.05f;
        }

        void onDisplayChanged() {
            int state = display.getState();
            boolean nowLit = state == Display.STATE_ON || state == Display.STATE_ON_SUSPEND
                    || state == Display.STATE_VR;
            if (nowLit == lit) {
                kick();
                return;
            }
            lit = nowLit;
            if (!lit) {
                removeGlass();
                visibility = 0f;
                tilt = 0f;
                litMidFoldMs = 0L;
                return;
            }
            // A panel lighting up mid-fold starts tilted and settles, like the glass landing.
            if (moving()) {
                litMidFoldMs = SystemClock.uptimeMillis();
                coverRefreshed = false;
                tilt = targetTilt();
                visibility = 1f;
            }
            kick();
        }

        void kick() {
            if (!scheduled && (wanted() || visibility > 0f)) {
                scheduled = true;
                Choreographer.getInstance().postFrameCallback(this);
            }
        }

        @Override
        public void doFrame(long frameTimeNanos) {
            scheduled = false;
            float dt = lastFrameNanos == 0L ? 1f / 60f
                    : Math.max(0f, Math.min(0.1f, (frameTimeNanos - lastFrameNanos) / 1e9f));
            lastFrameNanos = frameTimeNanos;

            boolean want = wanted();
            float tauVisibility = want ? 0.05f : 0.12f;
            visibility += ((want ? 1f : 0f) - visibility) * (1f - (float) Math.exp(-dt / tauVisibility));
            tilt += (targetTilt() - tilt) * (1f - (float) Math.exp(-dt / 0.045f));

            if (!want && visibility < 0.01f) {
                visibility = 0f;
                lastFrameNanos = 0L;
                removeGlass();
                return;
            }
            if (tilt > 0.05f) {
                if (inner) {
                    drawInner();
                } else {
                    drawCover();
                }
            } else {
                removeGlass();
            }
            scheduled = true;
            Choreographer.getInstance().postFrameCallback(this);
        }

        private void drawInner() {
            if (glass != null && display.getRotation() != glassRotation) {
                removeGlass(); // turned mid-fold: lay the glass out again for the new rotation
            }
            if (glass == null && !waitingForPicture()) {
                glass = createGlass();
            }
            if (glass != null) {
                if (glass.plain()) {
                    glass.apply(tilt, shadow(), 0f);
                } else {
                    glass.apply(tilt, visibility, shadow());
                }
            }
        }

        /**
         * The cover frosts over a picture of its own screen, heaviest by its cameras, away from
         * the hinge. Measured on a Razr 50 Ultra: the cover reports rotation 0 when it lights up
         * on a close, with its hinge along the top of the screen, and turns to 180 with gravity
         * about 90 degrees into an open, putting the hinge along the bottom. When it turns
         * mid-fold the glass is laid out again in the same frame, so the frost never jumps on the
         * phone; by then the cover is already dark for the handover anyway.
         */
        private void drawCover() {
            int rotation = display.getRotation();
            if (glass != null && rotation != glassRotation) {
                FoldGlass turned = createCoverGlass(rotation);
                removeGlass();
                glass = turned;
            }
            if (glass == null && !waitingForCoverPicture()) {
                glass = createCoverGlass(rotation);
            }
            if (glass != null) {
                if (glass.plain()) {
                    glass.apply(tilt, shadow(), 0f);
                } else {
                    glass.apply(tilt, visibility * coverAlpha(), shadow());
                }
            }
            refreshCoverPicture();
        }

        /** After a close, the frost dissolves into the live cover late in the settle. */
        private float coverAlpha() {
            float progress = settleProgress();
            return progress >= 0f ? 1f - smoothstep(0.6f, 1f, progress) : 1f;
        }

        private FoldGlass createCoverGlass(int rotation) {
            Rect bounds = windowManager.getMaximumWindowMetrics().getBounds();
            int width = bounds.width();
            int height = bounds.height();
            if (width <= 0 || height <= width) {
                return null; // landscape: the fold isn't a horizontal line, skip
            }
            glassRotation = rotation;
            if (coverPicture == null) {
                if (litMidFoldMs == 0L) {
                    return null;
                }
                FoldGlass plain = FoldGlass.plain(FoldAnimationService.this, windowManager, width, height);
                return plain.attached() ? plain : null;
            }
            boolean hingeOnTop = rotation == Surface.ROTATION_0;
            int hinge = hingeOnTop ? 0 : height;
            FoldGlass created = new FoldGlass(FoldAnimationService.this, windowManager, width, height,
                    coverPicture, fullRegion(coverPicture), coverPictureRotation != rotation,
                    hinge, hingeOnTop ? 1 : -1, height / 2, pxPerMm, MAX_TILT);
            Log.i(TAG, "display " + display.getDisplayId() + ": glass on at hinge " + angle
                    + " rotation " + rotation + " picture rotation " + coverPictureRotation
                    + " attached=" + created.attached());
            return created.attached() ? created : null;
        }

        /**
         * Opening from closed, the cover is lit and settled, so it is pictured as the fold starts,
         * before any glass is up. True while that picture is on its way; the glass waits briefly.
         */
        private boolean waitingForCoverPicture() {
            if (litMidFoldMs != 0L) {
                return false; // just lit on a close: start from the last picture, refresh below
            }
            long now = SystemClock.uptimeMillis();
            if (capturing) {
                return now - captureStartMs < CAPTURE_WAIT_MS;
            }
            if (now - coverPictureMs < RECAPTURE_AFTER_MS) {
                return false;
            }
            captureCover();
            return true;
        }

        /**
         * A cover that lights up on a close has nothing drawn for a moment, so it starts on its
         * last picture; once it has drawn, a fresh picture is swapped in under the frost.
         */
        private void refreshCoverPicture() {
            if (litMidFoldMs == 0L || coverRefreshed || capturing
                    || SystemClock.uptimeMillis() - litMidFoldMs < COVER_REFRESH_AFTER_MS) {
                return;
            }
            coverRefreshed = true;
            captureCover();
        }

        private void captureCover() {
            capturing = true;
            captureStartMs = SystemClock.uptimeMillis();
            final int rotation = display.getRotation();
            takeScreenshot(display.getDisplayId(), getMainExecutor(), new TakeScreenshotCallback() {
                @Override
                public void onSuccess(ScreenshotResult result) {
                    HardwareBuffer buffer = result.getHardwareBuffer();
                    Bitmap picture = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                    buffer.close();
                    capturing = false;
                    if (picture == null || isMostlyBlack(picture)) {
                        kick();
                        return;
                    }
                    coverPicture = picture;
                    coverPictureMs = SystemClock.uptimeMillis();
                    coverPictureRotation = rotation;
                    if (glass != null && glass.plain()) {
                        removeGlass(); // woke from black with no picture: bring the glass in now
                    } else if (glass != null) {
                        glass.setPicture(picture, fullRegion(picture), rotation != glassRotation);
                    }
                    kick();
                }

                @Override
                public void onFailure(int errorCode) {
                    Log.i(TAG, "display " + display.getDisplayId() + ": picture unavailable: " + errorCode);
                    capturing = false;
                    coverPictureMs = SystemClock.uptimeMillis();
                    kick();
                }
            });
        }

        /**
         * The panels hand over near the swap angle and the one lighting up takes a moment, which
         * would read as a cut. Instead the glass passes through shadow there: the cover darkens as
         * it nears the swap, and a panel that has just lit comes up out of black. The inner screen
         * doesn't darken on the way down, since Motorola switches it off anywhere from 86 to 50
         * degrees and darkening early would only lose it sooner.
         */
        private float shadow() {
            if (!Float.isNaN(previewTilt) || Float.isNaN(angle)) {
                return 0f;
            }
            float approach = inner ? 0f : smoothstep(SHADOW_COVER_FROM, SHADOW_COVER_TO, angle);
            float waking = 0f;
            if (litMidFoldMs != 0L) {
                long elapsed = SystemClock.uptimeMillis() - litMidFoldMs;
                waking = Math.max(0f, 1f - elapsed / (float) WAKE_FADE_MS);
            }
            return Math.max(approach, waking);
        }

        /** The moving pane is the top half of the inner screen, the half that swings open. */
        private FoldGlass createGlass() {
            Rect bounds = windowManager.getMaximumWindowMetrics().getBounds();
            int width = bounds.width();
            int height = bounds.height();
            if (width <= 0 || height <= width) {
                return null; // landscape: the fold isn't a horizontal line, skip
            }
            Bitmap picture = innerPicture;
            // Opening onto a screen that has locked (or unlocked) since the picture was taken
            // would show the wrong thing, then pop: come up out of black instead.
            if (litMidFoldMs != 0L && picture != null && innerPictureLocked != keyguardLocked()) {
                picture = null;
            }
            if (picture == null) {
                if (litMidFoldMs == 0L) {
                    return null;
                }
                FoldGlass plain = FoldGlass.plain(FoldAnimationService.this, windowManager, width, height);
                Log.i(TAG, "display 0: waking from black at hinge " + angle);
                return plain.attached() ? plain : null;
            }
            Rect region = fullRegion(picture);
            int rotation = display.getRotation();
            glassRotation = rotation;
            int hinge = height / 2;
            int side = rotation == Surface.ROTATION_180 ? 1 : -1;
            int eyeY = hinge;
            FoldGlass created = new FoldGlass(FoldAnimationService.this, windowManager, width, height,
                    picture, region, false, hinge, side, eyeY, pxPerMm, MAX_TILT);
            Log.i(TAG, "display " + display.getDisplayId() + ": glass on at hinge " + angle + " rotation " + rotation
                    + " tilt " + tilt + " attached=" + created.attached());
            return created.attached() ? created : null;
        }

        /**
         * The inner screen is pictured as a fold starts on it while lit and settled, before any
         * glass is up, so the picture is of the screen itself. When it lights up mid-fold it has
         * nothing drawn yet and reuses the last picture, which is what comes back on opening.
         * True while a first picture is still on its way; the glass waits for it briefly.
         */
        private boolean waitingForPicture() {
            if (litMidFoldMs != 0L) {
                return false;
            }
            long now = SystemClock.uptimeMillis();
            if (capturing) {
                return now - captureStartMs < CAPTURE_WAIT_MS;
            }
            if (now - innerPictureMs < RECAPTURE_AFTER_MS) {
                return false;
            }
            capturing = true;
            captureStartMs = now;
            takeScreenshot(display.getDisplayId(), getMainExecutor(), new TakeScreenshotCallback() {
                @Override
                public void onSuccess(ScreenshotResult result) {
                    HardwareBuffer buffer = result.getHardwareBuffer();
                    Bitmap picture = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                    buffer.close();
                    innerPicture = picture;
                    innerPictureMs = SystemClock.uptimeMillis();
                    innerPictureLocked = keyguardLocked();
                    capturing = false;
                    kick();
                }

                @Override
                public void onFailure(int errorCode) {
                    // Secure screens (banking, DRM video) can't be pictured: no glass this time.
                    Log.i(TAG, "display " + display.getDisplayId() + ": picture unavailable: " + errorCode);
                    innerPicture = null;
                    innerPictureMs = SystemClock.uptimeMillis();
                    capturing = false;
                    kick();
                }
            });
            return true;
        }

        private void removeGlass() {
            if (glass != null) {
                Log.i(TAG, "display " + display.getDisplayId() + ": glass off at hinge " + angle);
                glass.detach();
                glass = null;
            }
        }

        void playPreview() {
            if (preview != null) {
                preview.cancel();
            }
            if (!lit) {
                return;
            }
            preview = ValueAnimator.ofFloat(MAX_TILT, 0f);
            preview.setStartDelay(250);
            preview.setDuration(1300);
            preview.setInterpolator(new DecelerateInterpolator(1.4f));
            preview.addUpdateListener(animation -> {
                previewTilt = (float) animation.getAnimatedValue();
                kick();
            });
            preview.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    previewTilt = Float.NaN;
                    preview = null;
                    kick();
                }
            });
            previewTilt = MAX_TILT;
            preview.start();
            kick();
        }

        void destroy() {
            if (preview != null) {
                preview.cancel();
            }
            Choreographer.getInstance().removeFrameCallback(this);
            scheduled = false;
            removeGlass();
        }
    }
}
