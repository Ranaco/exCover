package com.ranaco.razrcoverwallpaper;

import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.service.wallpaper.WallpaperService;
import android.util.Log;
import android.view.Display;
import android.view.SurfaceHolder;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class GifWallpaperService extends WallpaperService {
    private static final String TAG = "RazrGifWallpaper";
    private static final int FLAG_CLI_HOME = 4;
    private static final int FLAG_CLI_LOCK = 16;
    private static final java.util.Map<String, Integer> RUNNING_ENGINES =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Whether a cover engine for this target is alive in this process. Android sometimes fails to
     * rebind the lock slot after an app update, leaving the slot assigned but with no engine.
     */
    public static boolean isEngineRunning(String target) {
        Integer count = RUNNING_ENGINES.get(target);
        return count != null && count > 0;
    }

    @Override
    public Engine onCreateEngine() {
        return new GifEngine();
    }

    private final class GifEngine extends Engine
            implements SharedPreferences.OnSharedPreferenceChangeListener {
        private static final long FRAME_DELAY_MS = 33L;
        /** Enough for the main screen at full zoom-out without holding huge frames in memory. */
        private static final int MAX_DECODE_SIDE = 2048;

        private final Handler handler = new Handler(Looper.getMainLooper());
        private final SharedPreferences preferences = WallpaperStore.preferences(
                GifWallpaperService.this);
        private final ExecutorService decoder = Executors.newSingleThreadExecutor();
        private final AtomicInteger loadGeneration = new AtomicInteger();
        private final Runnable drawFrame = this::draw;

        // Decoded with ImageDecoder: frames decode on a background thread, not in draw().
        private volatile Drawable image;
        private float cropZoom = 1f;
        private float cropFocusX = 0.5f;
        private float cropFocusY = 0.5f;
        private boolean visible;
        private boolean surfaceReady;
        private String target = WallpaperStore.TARGET_HOME;
        private boolean mainShape;

        @Override
        public void onCreate(SurfaceHolder surfaceHolder) {
            super.onCreate(surfaceHolder);
            Display display = getDisplayContext().getDisplay();
            int displayId = display == null ? Display.INVALID_DISPLAY : display.getDisplayId();
            // Motorola binds this service separately to the cover home (4) and lock (16) slots, and
            // Android can bind it to the main screen (system/lock flags); each engine renders the
            // wallpaper chosen for its own slot. Pickers' previews show what is being set.
            mainShape = displayId == Display.DEFAULT_DISPLAY;
            int flags = android.os.Build.VERSION.SDK_INT >= 34 ? getWallpaperFlags() : 0;
            if (isPreview()) {
                target = WallpaperStore.TARGET_DRAFT;
            } else if ((flags & FLAG_CLI_LOCK) != 0) {
                target = WallpaperStore.TARGET_LOCK;
            } else if ((flags & FLAG_CLI_HOME) != 0 || !mainShape) {
                target = WallpaperStore.TARGET_HOME;
            } else if ((flags & android.app.WallpaperManager.FLAG_LOCK) != 0
                    && (flags & android.app.WallpaperManager.FLAG_SYSTEM) == 0) {
                target = WallpaperStore.TARGET_MAIN_LOCK;
            } else {
                // Main home, or one engine shared by main home and lock.
                target = WallpaperStore.TARGET_MAIN_HOME;
            }
            Log.i(TAG, "Wallpaper engine created for display " + displayId + " target " + target);
            if (!isPreview()) {
                RUNNING_ENGINES.merge(target, 1, Integer::sum);
            }
            preferences.registerOnSharedPreferenceChangeListener(this);
            loadCrop();
            requestMovieReload();
        }

        @Override
        public void onVisibilityChanged(boolean isVisible) {
            visible = isVisible;
            Drawable current = image;
            if (current instanceof Animatable) {
                if (isVisible) {
                    ((Animatable) current).start();
                } else {
                    ((Animatable) current).stop();
                }
            }
            if (isVisible) {
                scheduleImmediateDraw();
            } else {
                handler.removeCallbacks(drawFrame);
            }
        }

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);
            surfaceReady = true;
            scheduleImmediateDraw();
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            scheduleImmediateDraw();
        }

        @Override
        public void onSurfaceRedrawNeeded(SurfaceHolder holder) {
            super.onSurfaceRedrawNeeded(holder);
            draw();
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            surfaceReady = false;
            handler.removeCallbacks(drawFrame);
            super.onSurfaceDestroyed(holder);
        }

        @Override
        public void onDestroy() {
            if (!isPreview()) {
                RUNNING_ENGINES.merge(target, -1, Integer::sum);
            }
            preferences.unregisterOnSharedPreferenceChangeListener(this);
            handler.removeCallbacks(drawFrame);
            decoder.shutdownNow();
            if (image instanceof Animatable) {
                ((Animatable) image).stop();
            }
            image = null;
            super.onDestroy();
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
            if (key == null) {
                return;
            }
            if (key.equals(WallpaperStore.key(target, WallpaperStore.KEY_REVISION))) {
                loadCrop();
                requestMovieReload();
            } else if (key.startsWith(target + "_") && key.contains("crop")) {
                loadCrop();
                scheduleImmediateDraw();
            }
        }

        private void loadCrop() {
            WallpaperStore.Crop crop = WallpaperStore.crop(GifWallpaperService.this, target, mainShape);
            cropZoom = crop.zoom;
            cropFocusX = crop.focusX;
            cropFocusY = crop.focusY;
        }

        private void requestMovieReload() {
            int generation = loadGeneration.incrementAndGet();
            String loadTarget = target;
            decoder.execute(() -> {
                Drawable decoded = null;
                try {
                    decoded = GifDecoder.decode(
                            GifDecoder.source(GifWallpaperService.this, loadTarget), MAX_DECODE_SIDE);
                } catch (Exception error) {
                    Log.e(TAG, "Could not decode selected wallpaper", error);
                }
                Drawable result = decoded;
                handler.post(() -> {
                    if (generation != loadGeneration.get()) {
                        return;
                    }
                    if (image instanceof Animatable) {
                        ((Animatable) image).stop();
                    }
                    image = result;
                    if (result != null) {
                        result.setBounds(0, 0, result.getIntrinsicWidth(), result.getIntrinsicHeight());
                        if (result instanceof Animatable && visible) {
                            ((Animatable) result).start();
                        }
                    }
                    scheduleImmediateDraw();
                });
            });
        }

        private void scheduleImmediateDraw() {
            handler.removeCallbacks(drawFrame);
            if (visible && surfaceReady) {
                handler.post(drawFrame);
            }
        }

        private void draw() {
            if (!visible || !surfaceReady) {
                return;
            }

            Canvas canvas = null;
            try {
                // A hardware canvas lets the animated drawable draw its latest decoded frame on the GPU.
                canvas = getSurfaceHolder().lockHardwareCanvas();
                if (canvas == null) {
                    return;
                }
                canvas.drawColor(Color.BLACK);

                Drawable current = image;
                if (current == null || current.getIntrinsicWidth() <= 0 || current.getIntrinsicHeight() <= 0) {
                    return;
                }

                CropPreviewView.Transform transform = CropPreviewView.calculateTransform(
                        canvas.getWidth(), canvas.getHeight(),
                        current.getIntrinsicWidth(), current.getIntrinsicHeight(),
                        cropZoom, cropFocusX, cropFocusY);

                canvas.save();
                canvas.translate(transform.left, transform.top);
                canvas.scale(transform.scale, transform.scale);
                current.draw(canvas);
                canvas.restore();
            } catch (Exception error) {
                Log.e(TAG, "Frame render failed", error);
            } finally {
                if (canvas != null) {
                    getSurfaceHolder().unlockCanvasAndPost(canvas);
                }
            }

            // Still images need no redraw loop; animations are sampled at ~30 fps.
            if (visible && surfaceReady && image instanceof Animatable) {
                handler.postDelayed(drawFrame, FRAME_DELAY_MS);
            }
        }
    }
}
