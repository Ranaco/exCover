package com.ranaco.razrcoverwallpaper;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.graphics.drawable.Drawable;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Full-screen framing editor for the GIF in the draft. A Cover | Main switch frames it separately
 * for the cover screen's shape and the taller main screen; Done saves both, Cancel discards them.
 */
public final class CropEditorActivity extends Activity {
    private static final int LABEL = Color.WHITE;
    private static final int TERTIARY = 0x4DEBEBF5;
    private static final int ACCENT = 0xFF0A84FF;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private GlassBackdropView backdrop;
    private CropPreviewView preview;
    private TextView resetButton;
    private static final float COVER_ASPECT = 1272f / 1080f;
    private final float[] coverCrop = {1f, 0.5f, 0.5f};
    private final float[] mainCrop = {1f, 0.5f, 0.5f};
    private float mainAspect = 2640f / 1080f;
    private boolean editingMain;
    private Drawable image;
    private FrameLayout canvas;
    private TextView coverSegment;
    private TextView mainSegment;
    private boolean destroyed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        render();
        worker.execute(() -> {
            try {
                ImageDecoder.Source source = GifDecoder.source(this, WallpaperStore.TARGET_DRAFT);
                // The editor may zoom 5×, so it keeps more resolution than the main preview.
                Drawable loaded = GifDecoder.decode(source, 2048);
                // The blurred backdrop plays its own tiny decode of the animation.
                Drawable ambient = GifDecoder.decode(source, GlassBackdropView.DECODE_SIDE);
                WallpaperStore.Crop cover = WallpaperStore.crop(this, WallpaperStore.TARGET_DRAFT, false);
                WallpaperStore.Crop main = WallpaperStore.crop(this, WallpaperStore.TARGET_DRAFT, true);
                runOnUiThread(() -> {
                    if (destroyed || loaded == null) {
                        return;
                    }
                    image = loaded;
                    store(coverCrop, cover.zoom, cover.focusX, cover.focusY);
                    store(mainCrop, main.zoom, main.focusX, main.focusY);
                    showShape(false);
                    backdrop.setImage(ambient);
                });
            } catch (Exception error) {
                runOnUiThread(this::finish);
            }
        });
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    private void render() {
        // The main screen's shape, measured from the phone's own display while it is open.
        Display main = ((DisplayManager) getSystemService(DISPLAY_SERVICE))
                .getDisplay(Display.DEFAULT_DISPLAY);
        if (main != null) {
            Point size = new Point();
            main.getRealSize(size);
            if (Math.min(size.x, size.y) > 0) {
                mainAspect = Math.max(size.x, size.y) / (float) Math.min(size.x, size.y);
            }
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        backdrop = new GlassBackdropView(this);
        root.addView(backdrop, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        root.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout bar = new FrameLayout(this);
        bar.setPadding(dp(8), 0, dp(8), 0);
        page.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        TextView cancel = barButton("Cancel", false);
        cancel.setOnClickListener(view -> finish());
        bar.addView(cancel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.START));
        TextView title = text("Edit", 17, LABEL);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        bar.addView(title, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        TextView done = barButton("Done", true);
        done.setOnClickListener(view -> save());
        bar.addView(done, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.END));

        // iOS-style segmented control choosing which screen's framing is being edited.
        LinearLayout segments = new LinearLayout(this);
        segments.setOrientation(LinearLayout.HORIZONTAL);
        segments.setPadding(dp(2), dp(2), dp(2), dp(2));
        segments.setBackground(rounded(0x3D767680, dp(9)));
        LinearLayout.LayoutParams segmentsParams = new LinearLayout.LayoutParams(dp(220), dp(34));
        segmentsParams.gravity = Gravity.CENTER_HORIZONTAL;
        segmentsParams.topMargin = dp(4);
        page.addView(segments, segmentsParams);
        coverSegment = segment("Cover");
        coverSegment.setOnClickListener(view -> showShape(false));
        segments.addView(coverSegment, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        mainSegment = segment("Main");
        mainSegment.setOnClickListener(view -> showShape(true));
        segments.addView(mainSegment, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        // The canvas centres the largest preview of the chosen shape that fits between the bars.
        canvas = new FrameLayout(this);
        page.addView(canvas, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        preview = new CropPreviewView(this);
        preview.setClipToOutline(true);
        preview.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(34));
            }
        });
        GradientDrawable hairline = new GradientDrawable();
        hairline.setCornerRadius(dp(34));
        hairline.setStroke(Math.max(1, dp(1) / 2), 0x40FFFFFF);
        preview.setForeground(hairline);
        preview.setCropListener((newZoom, newFocusX, newFocusY) -> {
            store(editingMain ? mainCrop : coverCrop, newZoom, newFocusX, newFocusY);
            updateReset();
        });
        canvas.addView(preview, new FrameLayout.LayoutParams(0, 0, Gravity.CENTER));
        canvas.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop,
                                          oldRight, oldBottom) -> fitPreview());

        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setGravity(Gravity.CENTER_HORIZONTAL);
        page.addView(footer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView caption = text("Pinch to zoom · Drag to move", 13, TERTIARY);
        caption.setGravity(Gravity.CENTER);
        footer.addView(caption, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        resetButton = barButton("Reset", false);
        resetButton.setTextColor(ACCENT);
        resetButton.setVisibility(View.INVISIBLE);
        resetButton.setOnClickListener(view -> preview.resetCrop());
        footer.addView(resetButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));

        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int top;
            int bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                top = bars.top;
                bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            page.setPadding(0, top, 0, bottom + dp(8));
            return insets;
        });
        setContentView(root);
    }

    private void save() {
        WallpaperStore.saveDraftCrop(this, coverCrop[0], coverCrop[1], coverCrop[2]);
        WallpaperStore.saveDraftMainCrop(this, mainCrop[0], mainCrop[1], mainCrop[2]);
        setResult(RESULT_OK);
        finish();
    }

    /** Switches the preview to the cover or main screen's shape and its own framing. */
    private void showShape(boolean main) {
        editingMain = main;
        coverSegment.setSelected(!main);
        mainSegment.setSelected(main);
        styleSegment(coverSegment);
        styleSegment(mainSegment);
        fitPreview();
        if (image != null) {
            float[] crop = main ? mainCrop : coverCrop;
            preview.setImage(image, new WallpaperStore.Crop(crop[0], crop[1], crop[2]));
        }
        updateReset();
    }

    private void fitPreview() {
        if (canvas == null || canvas.getWidth() == 0) {
            return;
        }
        float aspect = editingMain ? mainAspect : COVER_ASPECT;
        int width = canvas.getWidth() - dp(40);
        int height = canvas.getHeight() - dp(24);
        int fitWidth = Math.min(width, Math.round(height / aspect));
        int fitHeight = Math.round(fitWidth * aspect);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) preview.getLayoutParams();
        if (params.width != fitWidth || params.height != fitHeight) {
            params.width = fitWidth;
            params.height = fitHeight;
            preview.post(() -> preview.setLayoutParams(params));
        }
    }

    private void updateReset() {
        float[] crop = editingMain ? mainCrop : coverCrop;
        boolean framed = crop[0] > 1.01f || Math.abs(crop[1] - 0.5f) > 0.01f
                || Math.abs(crop[2] - 0.5f) > 0.01f;
        resetButton.setVisibility(framed ? View.VISIBLE : View.INVISIBLE);
    }

    private static void store(float[] crop, float zoom, float focusX, float focusY) {
        crop[0] = zoom;
        crop[1] = focusX;
        crop[2] = focusY;
    }

    private TextView segment(String label) {
        TextView segment = text(label, 13, LABEL);
        segment.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        segment.setGravity(Gravity.CENTER);
        segment.setClickable(true);
        return segment;
    }

    private void styleSegment(TextView segment) {
        segment.setBackground(segment.isSelected() ? rounded(0xFF636366, dp(7)) : null);
        segment.setAlpha(segment.isSelected() ? 1f : 0.7f);
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private TextView barButton(String label, boolean bold) {
        TextView button = text(label, 17, bold ? ACCENT : LABEL);
        if (bold) {
            button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setClickable(true);
        button.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                view.setAlpha(0.4f);
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                view.animate().alpha(1f).setDuration(150).start();
            }
            return false;
        });
        return button;
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
