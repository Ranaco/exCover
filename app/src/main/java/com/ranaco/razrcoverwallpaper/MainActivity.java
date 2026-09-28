package com.ranaco.razrcoverwallpaper;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Intent;
import android.app.Dialog;
import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowManager;
import android.view.WindowInsets;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int REQUEST_PICK_GIF = 4102;
    /** The preview is at most ~700 px wide; larger frames only cost decode time. */
    private static final int PREVIEW_DECODE_SIDE = 1440;
    private static final int REQUEST_GIPHY = 4103;
    private static final int REQUEST_EDIT = 4104;
    private static final int SLOT_HOME = 4;
    private static final int SLOT_LOCK = 16;
    private static final int WARNING = 0xFFFF9F0A;

    // iOS dark-mode system palette.
    private static final int BACKGROUND = Color.BLACK;
    // Flat translucent surfaces let the blurred backdrop show through without extra effects.
    private static final int GROUPED = 0x991C1C1E;
    private static final int GROUPED_PRESSED = 0x14FFFFFF;
    private static final int SEPARATOR = 0x1FFFFFFF;
    private static final int LABEL = Color.WHITE;
    private static final int SECONDARY = 0x99EBEBF5;
    private static final int TERTIARY = 0x4DEBEBF5;
    private static final int ACCENT = 0xFF0A84FF;
    private static final int ACCENT_PRESSED = 0xFF0060DF;
    private static final int SUCCESS = 0xFF30D158;
    private static final int ERROR = 0xFFFF453A;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<View> interactiveViews = new ArrayList<>();
    private GlassBackdropView backdrop;
    private CropPreviewView preview;
    private TextView nameText;
    private TextView detailText;
    private EditText linkInput;
    // Recent GIFs peek out on either side of the preview, like a wallpaper carousel.
    private static final int CAROUSEL_SIZE = 5;
    // Side cards two deep on each side, so the card beyond a neighbour slides in from the edge.
    private static final int[] PEEK_OFFSETS = {-2, -1, 1, 2};
    private static final float PEEK_SCALE = 0.84f;
    private final ImageView[] peeks = new ImageView[PEEK_OFFSETS.length];
    private ValueAnimator carouselAnimator;
    private float carouselPosition;
    private int queuedSwipe;
    private boolean longPressed;
    private final Runnable longPress = this::onCentreLongPress;
    private LinearLayout carouselDots;
    private int previewHeight;
    private int cardGap;
    private FrameLayout stage;
    private int previewWidth;
    private int carouselSpacing;
    private List<WallpaperStore.Recent> carouselItems = new ArrayList<>();
    private int carouselIndex = -1;
    private boolean switching;
    private float touchDownX;
    private float touchDownY;
    private int dragAxis;
    private VelocityTracker velocity;
    private ImageView favoriteButton;
    private ImageView saveButton;
    private final Map<String, Bitmap> recentThumbs = new HashMap<>();
    private TextView setButton;
    private LinearLayout hud;
    private ProgressBar hudSpinner;
    private TextView hudText;
    private final Runnable hideHud = this::hideHud;
    private boolean destroyed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        renderScreen();
        String requestedUrl = getIntent().getStringExtra("gif_url");
        if (requestedUrl != null && !requestedUrl.trim().isEmpty()) {
            linkInput.setText(requestedUrl.trim());
            linkInput.post(this::importFromLink);
        } else {
            refreshPreview(null, false);
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        super.onDestroy();
    }

    private void renderScreen() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BACKGROUND);

        backdrop = new GlassBackdropView(this);
        root.addView(backdrop, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        root.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), 0, dp(16), dp(32));
        content.setClipToPadding(false);
        content.setClipChildren(false);
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // Large-title header: name and caption on the left, one circular action on the right.
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams headerParams = matchWrap();
        headerParams.topMargin = dp(4);
        headerParams.bottomMargin = dp(14);
        content.addView(header, headerParams);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(4), 0, 0, 0);
        header.addView(titles, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView title = text("exCover", 34, LABEL, Typeface.BOLD);
        title.setLetterSpacing(-0.02f);
        title.setIncludeFontPadding(false);
        titles.addView(title, matchWrap());

        ImageView settings = new ImageView(this);
        settings.setImageResource(R.drawable.ic_settings);
        settings.setImageTintList(ColorStateList.valueOf(LABEL));
        settings.setScaleType(ImageView.ScaleType.CENTER);
        settings.setPadding(dp(9), dp(9), dp(9), dp(9));
        StateListDrawable settingsStates = new StateListDrawable();
        settingsStates.addState(new int[]{android.R.attr.state_pressed}, rounded(0x40FFFFFF, dp(20)));
        settingsStates.addState(new int[]{}, rounded(0x26FFFFFF, dp(20)));
        settingsStates.setExitFadeDuration(150);
        settings.setBackground(settingsStates);
        settings.setContentDescription("Cover screen settings");
        settings.setOnClickListener(view -> openExternalDisplaySettings());
        header.addView(settings, new LinearLayout.LayoutParams(dp(40), dp(40)));
        interactiveViews.add(settings);

        // The preview mirrors the cover panel's 1080 × 1272 aspect and rounded corners.
        preview = new CropPreviewView(this);
        preview.setClipToOutline(true);
        preview.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(30));
            }
        });
        GradientDrawable hairline = new GradientDrawable();
        hairline.setCornerRadius(dp(30));
        hairline.setStroke(Math.max(1, dp(1) / 2), 0x33FFFFFF);
        preview.setForeground(hairline);
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        previewWidth = Math.round(screenWidth * 0.64f);
        previewHeight = Math.round(previewWidth * (1272f / 1080f));

        // The stage spans the full screen width so side cards can run off the edges.
        stage = new FrameLayout(this);
        stage.setClipChildren(false);
        stage.setOnTouchListener(this::onStageTouch);
        LinearLayout.LayoutParams stageParams = new LinearLayout.LayoutParams(
                screenWidth, previewHeight);
        stageParams.leftMargin = -dp(16);
        stageParams.rightMargin = -dp(16);
        content.addView(stage, stageParams);

        // Every card is preview-sized and centred; position and scale come from layoutCarousel().
        cardGap = dp(12);
        carouselSpacing = Math.round(previewWidth * (1f + PEEK_SCALE) / 2f) + cardGap;
        for (int index = 0; index < peeks.length; index++) {
            peeks[index] = peekCard();
            stage.addView(peeks[index], new FrameLayout.LayoutParams(
                    previewWidth, previewHeight, Gravity.CENTER));
        }
        stage.addView(preview, new FrameLayout.LayoutParams(
                previewWidth, previewHeight, Gravity.CENTER));
        // The main preview only browses; framing happens in the dedicated editor.
        preview.setCropEnabled(false);

        carouselDots = new LinearLayout(this);
        carouselDots.setOrientation(LinearLayout.HORIZONTAL);
        carouselDots.setGravity(Gravity.CENTER);
        carouselDots.setVisibility(View.GONE);
        LinearLayout.LayoutParams dotsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(6));
        dotsParams.topMargin = dp(12);
        content.addView(carouselDots, dotsParams);

        LinearLayout infoRow = new LinearLayout(this);
        infoRow.setOrientation(LinearLayout.HORIZONTAL);
        infoRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams infoParams = matchWrap();
        infoParams.topMargin = dp(10);
        content.addView(infoRow, infoParams);

        favoriteButton = circleButton(R.drawable.ic_heart, "Favourite");
        favoriteButton.setOnClickListener(view -> toggleFavorite());
        infoRow.addView(favoriteButton, new LinearLayout.LayoutParams(dp(40), dp(40)));
        interactiveViews.add(favoriteButton);

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(8), 0, dp(8), 0);
        infoRow.addView(labels, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        nameText = text("", 17, LABEL, Typeface.NORMAL);
        nameText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        nameText.setGravity(Gravity.CENTER);
        nameText.setSingleLine(true);
        nameText.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(nameText, matchWrap());
        detailText = text("", 13, SECONDARY, Typeface.NORMAL);
        detailText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams detailParams = matchWrap();
        detailParams.topMargin = dp(2);
        labels.addView(detailText, detailParams);

        saveButton = circleButton(R.drawable.ic_more, "More");
        saveButton.setOnClickListener(view -> showMoreSheet());
        infoRow.addView(saveButton, new LinearLayout.LayoutParams(dp(40), dp(40)));
        interactiveViews.add(saveButton);

        setButton = primaryButton("Set Wallpaper");
        setButton.setOnClickListener(view -> showSetSheet());
        LinearLayout.LayoutParams setParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        setParams.topMargin = dp(16);
        content.addView(setButton, setParams);
        interactiveViews.add(setButton);

        content.addView(sectionHeader("Add an asset"), sectionHeaderParams());
        LinearLayout sources = group();
        content.addView(sources, matchWrap());
        View photosRow = sourceRow(R.drawable.ic_photos, "Photos");
        photosRow.setOnClickListener(view -> openGallery());
        sources.addView(photosRow);
        sources.addView(separator(0));
        View giphyRow = sourceRow(R.drawable.ic_search, "Search GIPHY");
        giphyRow.setOnClickListener(view -> startActivityForResult(
                new Intent(this, GiphySearchActivity.class), REQUEST_GIPHY));
        sources.addView(giphyRow);
        sources.addView(separator(0));

        LinearLayout linkRow = new LinearLayout(this);
        linkRow.setOrientation(LinearLayout.HORIZONTAL);
        linkRow.setGravity(Gravity.CENTER_VERTICAL);
        linkRow.setPadding(dp(16), 0, dp(8), 0);
        sources.addView(linkRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));
        linkRow.addView(rowIcon(R.drawable.ic_link), rowIconParams());
        linkInput = new EditText(this);
        linkInput.setBackground(null);
        linkInput.setPadding(0, 0, 0, 0);
        linkInput.setTextColor(LABEL);
        linkInput.setHintTextColor(TERTIARY);
        linkInput.setHint("Paste a GIF link");
        linkInput.setTextSize(17);
        linkInput.setSingleLine(true);
        linkInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        linkInput.setImeOptions(EditorInfo.IME_ACTION_GO);
        linkInput.setOnEditorActionListener((view, action, event) -> {
            if (action == EditorInfo.IME_ACTION_GO) {
                importFromLink();
                return true;
            }
            return false;
        });
        linkRow.addView(linkInput, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        interactiveViews.add(linkInput);
        TextView paste = textButton("Paste");
        paste.setOnClickListener(view -> pasteLink());
        linkRow.addView(paste, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        interactiveViews.add(paste);
        TextView importLink = textButton("Import");
        importLink.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        importLink.setOnClickListener(view -> importFromLink());
        linkRow.addView(importLink, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        interactiveViews.add(importLink);

        // The scroll view fills the viewport, so this spacer pushes the footer to the very bottom.
        content.addView(new View(this), new LinearLayout.LayoutParams(0, 0, 1f));
        content.addView(footer(), footerParams());

        // Keeps scrolled content from running under the status bar, like an iOS bar background.
        View statusScrim = new View(this);
        statusScrim.setBackgroundColor(0xCC000000);
        FrameLayout.LayoutParams scrimParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, Gravity.TOP);
        root.addView(statusScrim, scrimParams);

        hud = buildHud();
        FrameLayout.LayoutParams hudParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(44), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        root.addView(hud, hudParams);

        // targetSdk 35+ draws edge to edge, so pad around the status and navigation bars. The
        // keyboard pads the scroll view itself, shrinking what's visible so the page can scroll
        // the focused field above it (padding the content would just be absorbed by the spacer).
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int top;
            int bottom;
            int keyboard;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                top = bars.top;
                bottom = bars.bottom;
                keyboard = insets.getInsets(WindowInsets.Type.ime()).bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getStableInsetBottom();
                keyboard = insets.getSystemWindowInsetBottom();
            }
            content.setPadding(dp(16), top + dp(12), dp(16), bottom + dp(4));
            scroll.setPadding(0, 0, 0, Math.max(0, keyboard - bottom));
            scrimParams.height = top;
            statusScrim.setLayoutParams(scrimParams);
            hudParams.bottomMargin = Math.max(bottom, keyboard) + dp(24);
            hud.setLayoutParams(hudParams);
            if (keyboard > bottom && linkInput.hasFocus()) {
                scroll.post(() -> revealInScroll(scroll, linkInput));
            }
            return insets;
        });

        setContentView(root);
    }

    private TextView footer() {
        String author = "Ranaco";
        String prefix = "Made with love by ";
        android.text.SpannableString label = new android.text.SpannableString(prefix + author);
        int start = prefix.length();
        label.setSpan(new android.text.style.ForegroundColorSpan(ACCENT),
                start, label.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        label.setSpan(new android.text.style.StyleSpan(Typeface.BOLD),
                start, label.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        TextView footer = text("", 13, TERTIARY, Typeface.NORMAL);
        footer.setText(label);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(dp(16), dp(10), dp(16), dp(10));
        footer.setContentDescription("Made with love by " + author + ", opens GitHub");
        footer.setOnClickListener(view -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Ranaco")));
            } catch (Exception error) {
                showError("No browser available");
            }
        });
        return footer;
    }

    private LinearLayout.LayoutParams footerParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.CENTER_HORIZONTAL;
        params.topMargin = dp(16);
        return params;
    }

    /** Scrolls so {@code target} sits just above the keyboard-shortened viewport. */
    private void revealInScroll(ScrollView scroll, View target) {
        android.graphics.Rect rect = new android.graphics.Rect();
        target.getDrawingRect(rect);
        scroll.offsetDescendantRectToMyCoords(target, rect);
        int visibleBottom = scroll.getScrollY() + scroll.getHeight() - scroll.getPaddingBottom();
        int overlap = rect.bottom + dp(20) - visibleBottom;
        if (overlap > 0) {
            scroll.smoothScrollBy(0, overlap);
        }
    }

    private View sourceRow(int icon, String label) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), 0, dp(16), 0);
        row.setBackground(rowBackground());
        row.addView(rowIcon(icon), rowIconParams());
        row.addView(text(label, 17, LABEL, Typeface.NORMAL), new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(text("›", 24, TERTIARY, Typeface.NORMAL));
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));
        interactiveViews.add(row);
        return row;
    }

    private ImageView rowIcon(int icon) {
        ImageView image = new ImageView(this);
        image.setImageResource(icon);
        image.setImageTintList(ColorStateList.valueOf(ACCENT));
        return image;
    }

    private LinearLayout.LayoutParams rowIconParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(22), dp(22));
        params.rightMargin = dp(16);
        return params;
    }

    private void refreshRecents() {
        worker.execute(() -> {
            List<WallpaperStore.Recent> all = WallpaperStore.recents(this);
            List<WallpaperStore.Recent> recents = all.subList(0, Math.min(CAROUSEL_SIZE, all.size()));
            for (WallpaperStore.Recent recent : recents) {
                if (!recentThumbs.containsKey(recent.id)) {
                    Bitmap thumb = renderRecentThumb(recent.id);
                    if (thumb != null) {
                        recentThumbs.put(recent.id, thumb);
                    }
                }
            }
            String active = WallpaperStore.SOURCE_CUSTOM.equals(
                    WallpaperStore.selectedSource(this, WallpaperStore.TARGET_DRAFT))
                    ? WallpaperStore.preferences(this).getString(WallpaperStore.key(
                            WallpaperStore.TARGET_DRAFT, WallpaperStore.KEY_RECENT_ID), null)
                    : null;
            int current = -1;
            for (int index = 0; index < recents.size(); index++) {
                if (recents.get(index).id.equals(active)) {
                    current = index;
                }
            }
            int selected = current;
            runOnUiThread(() -> {
                if (!destroyed) {
                    bindCarousel(new ArrayList<>(recents), selected);
                }
            });
        });
    }

    /** Shows the neighbours of the current GIF as side cards; -1 means it isn't a recent. */
    private void bindCarousel(List<WallpaperStore.Recent> recents, int current) {
        carouselItems = recents;
        carouselIndex = current;
        if (!switching) {
            bindPeeks();
        }
        bindDots();
        boolean isRecent = current >= 0;
        boolean favorite = isRecent && recents.get(current).favorite;
        favoriteButton.setImageResource(favorite ? R.drawable.ic_heart_filled : R.drawable.ic_heart);
        favoriteButton.setImageTintList(ColorStateList.valueOf(favorite ? 0xFFFF375F : LABEL));
        favoriteButton.setContentDescription(favorite ? "Remove from favourites" : "Add to favourites");
        favoriteButton.setVisibility(isRecent ? View.VISIBLE : View.INVISIBLE);
        saveButton.setVisibility(isRecent ? View.VISIBLE : View.INVISIBLE);
    }

    /** The recent {@code offset} places away from the centre, or null past either end. */
    private WallpaperStore.Recent neighbour(int offset) {
        int index = carouselIndex + offset;
        if (carouselIndex < 0) {
            // A built-in loop is showing: recents start on the right.
            index = offset > 0 ? offset - 1 : -1;
        }
        return index >= 0 && index < carouselItems.size() ? carouselItems.get(index) : null;
    }

    private void bindDots() {
        carouselDots.removeAllViews();
        carouselDots.setVisibility(carouselItems.size() > 1 ? View.VISIBLE : View.GONE);
        for (int index = 0; index < carouselItems.size(); index++) {
            View dot = new View(this);
            boolean active = index == carouselIndex;
            dot.setBackground(rounded(active ? LABEL : 0x4DFFFFFF, dp(3)));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    active ? dp(16) : dp(6), dp(6));
            params.leftMargin = dp(3);
            params.rightMargin = dp(3);
            carouselDots.addView(dot, params);
        }
    }

    private void bindPeeks() {
        for (int index = 0; index < peeks.length; index++) {
            WallpaperStore.Recent recent = neighbour(PEEK_OFFSETS[index]);
            ImageView peek = peeks[index];
            peek.setTag(recent);
            if (recent != null) {
                peek.setImageBitmap(recentThumbs.get(recent.id));
                peek.setContentDescription(recent.name);
            }
        }
        layoutCarousel(0f);
    }

    /**
     * Places every card for a carousel position measured in cards: 0 is at rest, -1 is one card
     * towards the next GIF. Cards shrink and dim as they move away from the centre.
     */
    private void layoutCarousel(float position) {
        carouselPosition = position;
        placeCard(preview, position, true);
        for (int index = 0; index < peeks.length; index++) {
            placeCard(peeks[index], PEEK_OFFSETS[index] + position, peeks[index].getTag() != null);
        }
    }

    private void placeCard(View card, float slot, boolean present) {
        float distance = Math.abs(slot);
        float near = Math.min(1f, distance);
        float far = Math.max(0f, distance - 1f);
        float x = near * carouselSpacing + far * (previewWidth * PEEK_SCALE + cardGap);
        card.setTranslationX(Math.signum(slot) * x);
        float scale = 1f - (1f - PEEK_SCALE) * near;
        card.setScaleX(scale);
        card.setScaleY(scale);
        card.setAlpha(present ? 1f - 0.5f * near : 0f);
    }

    private ImageView peekCard() {
        ImageView card = new ImageView(this);
        card.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.setBackground(rounded(0x26FFFFFF, dp(30)));
        card.setAlpha(0f);
        card.setClipToOutline(true);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(30));
            }
        });
        // Side cards are purely visual; the stage handles every touch so a swipe can start anywhere.
        card.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return card;
    }

    /**
     * A horizontal drag anywhere on the stage moves the carousel with the finger and snaps to the
     * neighbour; a tap on the centre opens the editor, a tap on a side card selects it.
     */
    private boolean onStageTouch(View view, MotionEvent event) {
        int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        android.util.Log.d("exCoverCarousel", "touch " + MotionEvent.actionToString(event.getAction())
                + " x=" + event.getX() + " axis=" + dragAxis + " switching=" + switching + " idx=" + carouselIndex);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (carouselAnimator != null && carouselAnimator.isRunning() && !switching) {
                    carouselAnimator.cancel();
                }
                touchDownX = event.getX();
                touchDownY = event.getY();
                dragAxis = 0;
                longPressed = false;
                float centreLeft = (stage.getWidth() - previewWidth) / 2f;
                if (touchDownX >= centreLeft && touchDownX <= centreLeft + previewWidth) {
                    handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                }
                if (velocity != null) {
                    velocity.recycle();
                }
                velocity = VelocityTracker.obtain();
                velocity.addMovement(event);
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (velocity != null) {
                    velocity.addMovement(event);
                }
                float dx = event.getX() - touchDownX;
                float dy = event.getY() - touchDownY;
                if (dragAxis == 0) {
                    if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy)) {
                        dragAxis = 1;
                        view.getParent().requestDisallowInterceptTouchEvent(true);
                    } else if (Math.abs(dy) > slop) {
                        dragAxis = 2;
                    }
                }
                if (dragAxis != 0) {
                    handler.removeCallbacks(longPress);
                }
                if (dragAxis == 1 && !switching) {
                    applyDrag(dx - Math.signum(dx) * slop);
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                float dx = event.getX() - touchDownX;
                handler.removeCallbacks(longPress);
                if (longPressed) {
                    recycleVelocity();
                    return true;
                }
                if (switching) {
                    if (dragAxis == 1) {
                        queuedSwipe = dx < 0 ? 1 : -1;
                    }
                } else if (dragAxis == 1) {
                    velocity.computeCurrentVelocity(1000);
                    settle(velocity.getXVelocity());
                } else if (dragAxis == 0) {
                    onStageTap(event.getX());
                }
                recycleVelocity();
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(longPress);
                if (dragAxis == 1 && !switching) {
                    animateCarouselTo(0f, 0);
                }
                recycleVelocity();
                return true;
            default:
                return true;
        }
    }

    private void recycleVelocity() {
        if (velocity != null) {
            velocity.recycle();
            velocity = null;
        }
    }

    private void onStageTap(float x) {
        float previewLeft = (stage.getWidth() - previewWidth) / 2f;
        if (x < previewLeft) {
            commitSwipe(-1);
        } else if (x > previewLeft + previewWidth) {
            commitSwipe(1);
        } else {
            openEditor();
        }
    }

    private void openEditor() {
        if (switching) {
            return;
        }
        startActivityForResult(new Intent(this, CropEditorActivity.class), REQUEST_EDIT);
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    /** Follows the finger, with rubber-band resistance past either end. */
    private void applyDrag(float dx) {
        float position = dx / carouselSpacing;
        if ((position > 0 && neighbour(-1) == null) || (position < 0 && neighbour(1) == null)) {
            position *= 0.3f;
        }
        layoutCarousel(Math.max(-1.2f, Math.min(1.2f, position)));
    }

    private void settle(float velocityX) {
        int direction = 0;
        boolean flungNext = velocityX < -900;
        boolean flungPrevious = velocityX > 900;
        if ((carouselPosition < -1f / 3f || flungNext) && !flungPrevious && neighbour(1) != null) {
            direction = 1;
        } else if ((carouselPosition > 1f / 3f || flungPrevious) && !flungNext && neighbour(-1) != null) {
            direction = -1;
        }
        android.util.Log.d("exCoverCarousel", "settle pos=" + carouselPosition + " v=" + velocityX + " dir=" + direction);
        animateCarouselTo(-direction, direction);
    }

    private void commitSwipe(int direction) {
        android.util.Log.d("exCoverCarousel", "commit dir=" + direction + " switching=" + switching);
        if (switching) {
            queuedSwipe = direction;
            return;
        }
        if (neighbour(direction) == null) {
            animateCarouselTo(0f, 0);
            return;
        }
        animateCarouselTo(-direction, direction);
    }

    /**
     * Animates to a carousel position. When it lands on a neighbour, that card's thumbnail stands
     * in for the preview until the GIF is decoded, then the stage resets around the live preview.
     */
    private void animateCarouselTo(float target, int direction) {
        if (carouselAnimator != null) {
            carouselAnimator.cancel();
        }
        WallpaperStore.Recent destination = direction == 0 ? null : neighbour(direction);
        if (destination != null) {
            switching = true;
        }
        float start = carouselPosition;
        float distance = Math.abs(target - start);
        carouselAnimator = ValueAnimator.ofFloat(start, target);
        carouselAnimator.setDuration(Math.max(140, Math.round(280 * Math.min(1f, distance))));
        carouselAnimator.setInterpolator(new DecelerateInterpolator(1.6f));
        carouselAnimator.addUpdateListener(animation ->
                layoutCarousel((float) animation.getAnimatedValue()));
        carouselAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(android.animation.Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (cancelled || destination == null) {
                    return;
                }
                carouselIndex = carouselItems.indexOf(destination);
                android.util.Log.d("exCoverCarousel", "landed idx=" + carouselIndex);
                bindDots();
                loadRecent(destination);
            }
        });
        carouselAnimator.start();
    }

    private void resetStage() {
        switching = false;
        bindPeeks();
        int queued = queuedSwipe;
        queuedSwipe = 0;
        if (queued != 0 && neighbour(queued) != null) {
            stage.post(() -> commitSwipe(queued));
        }
    }

    /**
     * Renders the framed GIF into a looping video in Photos, then opens Motorola's lock screen
     * editor, where the design's photo button accepts videos and Motorola plays them itself.
     */
    private void exportLockVideo() {
        setBusy(true, "Making video…");
        worker.execute(() -> {
            try {
                VideoExporter.exportDraftForCover(this, fraction -> runOnUiThread(() -> {
                    if (!destroyed) {
                        showHud(String.format(Locale.US, "Making video… %d%%", Math.round(fraction * 100)),
                                LABEL, true);
                    }
                }));
                runOnUiThread(() -> {
                    setBusy(false, null);
                    try {
                        startActivity(new Intent("com.motorola.intent.action.SECONDARY_CLOCKFACE_PICKER"));
                        flash("Saved to Photos · pick it with the lock screen's photo button", SUCCESS);
                    } catch (Exception error) {
                        flash("Saved to Photos in Movies/exCover", SUCCESS);
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> showError(readableError(error)));
            }
        });
    }

    private void toggleFavorite() {
        if (carouselIndex < 0 || carouselIndex >= carouselItems.size()) {
            return;
        }
        WallpaperStore.Recent recent = carouselItems.get(carouselIndex);
        boolean favorite = !recent.favorite;
        favoriteButton.animate().scaleX(1.25f).scaleY(1.25f).setDuration(110)
                .withEndAction(() -> favoriteButton.animate().scaleX(1f).scaleY(1f)
                        .setDuration(140).start()).start();
        worker.execute(() -> {
            WallpaperStore.setFavorite(this, recent.id, favorite);
            runOnUiThread(() -> {
                refreshRecents();
                flash(favorite ? "Added to Favourites" : "Removed from Favourites", LABEL);
            });
        });
    }

    private void saveToPhotos() {
        setBusy(true, "Saving…");
        worker.execute(() -> {
            try {
                WallpaperStore.saveDraftToPhotos(this);
                runOnUiThread(() -> {
                    setBusy(false, null);
                    flash("Saved to Photos", SUCCESS);
                });
            } catch (Exception error) {
                runOnUiThread(() -> showError(readableError(error)));
            }
        });
    }

    private ImageView circleButton(int icon, String description) {
        ImageView button = new ImageView(this);
        button.setImageResource(icon);
        button.setImageTintList(ColorStateList.valueOf(LABEL));
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setPadding(dp(9), dp(9), dp(9), dp(9));
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, rounded(0x40FFFFFF, dp(20)));
        states.addState(new int[]{}, rounded(0x26FFFFFF, dp(20)));
        states.setExitFadeDuration(150);
        button.setBackground(states);
        button.setContentDescription(description);
        return button;
    }

    private void loadRecent(WallpaperStore.Recent recent) {
        setBusy(true, null);
        worker.execute(() -> {
            try {
                WallpaperStore.loadRecent(this, recent.id);
                runOnUiThread(() -> refreshPreview(null, true));
            } catch (Exception error) {
                runOnUiThread(() -> {
                    showError(readableError(error));
                    refreshRecents();
                });
            }
        });
    }

    private void onCentreLongPress() {
        if (dragAxis == 0 && !switching && carouselIndex >= 0) {
            longPressed = true;
            stage.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            showMoreSheet();
        }
    }

    private void showMoreSheet() {
        if (carouselIndex < 0 || carouselIndex >= carouselItems.size()) {
            return;
        }
        WallpaperStore.Recent recent = carouselItems.get(carouselIndex);
        if (supportsCoverVideo()) {
            actionSheet(recent.name,
                    new String[]{"Save to Photos", "Save as Lock Screen Video", "Delete"},
                    new int[]{ACCENT, ACCENT, ERROR},
                    new Runnable[]{this::saveToPhotos, this::exportLockVideo, () -> deleteRecent(recent)});
        } else {
            actionSheet(recent.name,
                    new String[]{"Save to Photos", "Delete"},
                    new int[]{ACCENT, ERROR},
                    new Runnable[]{this::saveToPhotos, () -> deleteRecent(recent)});
        }
    }

    /** Motorola's cover lock screen only accepts videos where its video wallpaper is installed. */
    private boolean supportsCoverVideo() {
        return getPackageManager().hasSystemFeature("com.motorola.motolivewallpaper3.cli.videowallpaper");
    }

    /** Removes a GIF from the carousel and shows its neighbour. Cover screens keep their own copy. */
    private void deleteRecent(WallpaperStore.Recent recent) {
        int index = carouselItems.indexOf(recent);
        WallpaperStore.Recent next = index + 1 < carouselItems.size() ? carouselItems.get(index + 1)
                : index > 0 ? carouselItems.get(index - 1) : null;
        setBusy(true, null);
        worker.execute(() -> {
            try {
                WallpaperStore.removeRecent(this, recent.id);
                recentThumbs.remove(recent.id);
                if (next != null) {
                    WallpaperStore.loadRecent(this, next.id);
                } else {
                    WallpaperStore.resetDraft(this);
                }
                runOnUiThread(() -> refreshPreview("Deleted", true));
            } catch (Exception error) {
                runOnUiThread(() -> showError(readableError(error)));
            }
        });
    }

    private Bitmap renderRecentThumb(String id) {
        // Only the first frame, decoded at thumbnail size.
        return GifDecoder.frame(
                android.graphics.ImageDecoder.createSource(WallpaperStore.recentFile(this, id)),
                previewWidth, previewHeight, new WallpaperStore.Crop(1f, 0.5f, 0.5f));
    }

    private void showSetSheet() {
        hideKeyboard();
        boolean[] onMain = {WallpaperStore.lastSetOnMain(this)};
        Dialog sheet = new Dialog(this);
        sheet.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFF1C1C1E);
        float radius = dp(18);
        background.setCornerRadii(new float[]{radius, radius, radius, radius, 0, 0, 0, 0});
        panel.setBackground(background);
        panel.setPadding(dp(16), dp(10), dp(16), dp(12));

        View grabber = new View(this);
        grabber.setBackground(rounded(0x5CFFFFFF, dp(3)));
        LinearLayout.LayoutParams grabberParams = new LinearLayout.LayoutParams(dp(36), dp(5));
        grabberParams.bottomMargin = dp(14);
        panel.addView(grabber, grabberParams);

        TextView title = text("Set Wallpaper", 17, LABEL, Typeface.NORMAL);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        panel.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout segments = new LinearLayout(this);
        segments.setOrientation(LinearLayout.HORIZONTAL);
        segments.setPadding(dp(2), dp(2), dp(2), dp(2));
        segments.setBackground(rounded(0x3D767680, dp(9)));
        LinearLayout.LayoutParams segmentsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(36));
        segmentsParams.topMargin = dp(16);
        panel.addView(segments, segmentsParams);
        TextView coverSegment = segmentButton("Cover Screen");
        TextView mainSegment = segmentButton("Main Screen");
        segments.addView(coverSegment, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        segments.addView(mainSegment, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        // The preview keeps one height; its width follows the chosen screen's shape.
        int previewHeight = dp(150);
        int coverWidth = Math.round(previewHeight * (1080f / 1272f));
        int mainWidth = Math.round(previewHeight / mainScreenAspect());
        ImageView shapePreview = new ImageView(this);
        shapePreview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        shapePreview.setBackground(rounded(0x26FFFFFF, dp(14)));
        shapePreview.setClipToOutline(true);
        shapePreview.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(14));
            }
        });
        GradientDrawable hairline = new GradientDrawable();
        hairline.setCornerRadius(dp(14));
        hairline.setStroke(Math.max(1, dp(1) / 2), 0x33FFFFFF);
        shapePreview.setForeground(hairline);
        LinearLayout.LayoutParams shapeParams = new LinearLayout.LayoutParams(coverWidth, previewHeight);
        shapeParams.topMargin = dp(18);
        shapeParams.bottomMargin = dp(18);
        panel.addView(shapePreview, shapeParams);

        LinearLayout options = new LinearLayout(this);
        options.setOrientation(LinearLayout.VERTICAL);
        options.setBackground(rounded(0xFF2C2C2E, dp(12)));
        options.setClipToOutline(true);
        panel.addView(options, matchWrap());
        String[] labels = {"Home & Lock", "Home Screen", "Lock Screen"};
        boolean[][] places = {{true, true}, {true, false}, {false, true}};
        for (int index = 0; index < labels.length; index++) {
            if (index > 0) {
                options.addView(separator(0));
            }
            boolean[] place = places[index];
            TextView option = sheetButton(labels[index], false);
            option.setTextSize(17);
            option.setOnClickListener(view -> {
                sheet.dismiss();
                WallpaperStore.saveLastSetOnMain(this, onMain[0]);
                boolean[] selection = onMain[0]
                        ? new boolean[]{false, false, place[0], place[1]}
                        : new boolean[]{place[0], place[1], false, false};
                applySelection(selection);
            });
            options.addView(option, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        }

        TextView cancel = textButton("Cancel");
        cancel.setOnClickListener(view -> sheet.dismiss());
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(46));
        cancelParams.topMargin = dp(6);
        panel.addView(cancel, cancelParams);

        Bitmap[] frames = new Bitmap[2];
        Runnable showChoice = () -> {
            coverSegment.setSelected(!onMain[0]);
            mainSegment.setSelected(onMain[0]);
            styleSegmentButton(coverSegment);
            styleSegmentButton(mainSegment);
            shapeParams.width = onMain[0] ? mainWidth : coverWidth;
            shapePreview.setLayoutParams(shapeParams);
            shapePreview.setImageBitmap(frames[onMain[0] ? 1 : 0]);
        };
        coverSegment.setOnClickListener(view -> {
            onMain[0] = false;
            showChoice.run();
        });
        mainSegment.setOnClickListener(view -> {
            onMain[0] = true;
            showChoice.run();
        });
        showChoice.run();

        // Previews of the GIF in each shape, with that shape's framing.
        worker.execute(() -> {
            frames[0] = renderDraftFrame(coverWidth, previewHeight,
                    WallpaperStore.crop(this, WallpaperStore.TARGET_DRAFT, false));
            frames[1] = renderDraftFrame(mainWidth, previewHeight,
                    WallpaperStore.crop(this, WallpaperStore.TARGET_DRAFT, true));
            runOnUiThread(showChoice);
        });

        sheet.setContentView(panel);
        Window window = sheet.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.BOTTOM);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.45f);
            window.setWindowAnimations(android.R.style.Animation_InputMethod);
            panel.setOnApplyWindowInsetsListener((view, insets) -> {
                int bottom = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                        ? insets.getInsets(WindowInsets.Type.navigationBars()).bottom
                        : insets.getSystemWindowInsetBottom();
                view.setPadding(dp(16), dp(10), dp(16), bottom + dp(12));
                return insets;
            });
        }
        sheet.show();
    }

    private TextView segmentButton(String label) {
        TextView segment = text(label, 14, LABEL, Typeface.NORMAL);
        segment.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        segment.setGravity(Gravity.CENTER);
        segment.setClickable(true);
        return segment;
    }

    private void styleSegmentButton(TextView segment) {
        segment.setBackground(segment.isSelected() ? rounded(0xFF636366, dp(7)) : null);
        segment.setAlpha(segment.isSelected() ? 1f : 0.7f);
    }

    private float mainScreenAspect() {
        android.view.Display main = ((android.hardware.display.DisplayManager)
                getSystemService(DISPLAY_SERVICE)).getDisplay(android.view.Display.DEFAULT_DISPLAY);
        if (main != null) {
            android.graphics.Point size = new android.graphics.Point();
            main.getRealSize(size);
            if (Math.min(size.x, size.y) > 0) {
                return Math.max(size.x, size.y) / (float) Math.min(size.x, size.y);
            }
        }
        return 2640f / 1080f;
    }

    private Bitmap renderDraftFrame(int width, int height, WallpaperStore.Crop crop) {
        return GifDecoder.frame(GifDecoder.source(this, WallpaperStore.TARGET_DRAFT), width, height, crop);
    }

    private void actionSheet(String title, String[] labels, int[] colors, Runnable[] actions) {
        hideKeyboard();
        Dialog sheet = new Dialog(this);
        sheet.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(8), 0, dp(8), dp(8));

        LinearLayout options = new LinearLayout(this);
        options.setOrientation(LinearLayout.VERTICAL);
        options.setBackground(rounded(0xFF2C2C2E, dp(14)));
        options.setClipToOutline(true);
        column.addView(options, matchWrap());

        TextView heading = text(title, 13, SECONDARY, Typeface.NORMAL);
        heading.setGravity(Gravity.CENTER);
        heading.setSingleLine(true);
        heading.setEllipsize(TextUtils.TruncateAt.END);
        heading.setPadding(dp(16), dp(14), dp(16), dp(14));
        options.addView(heading, matchWrap());
        for (int index = 0; index < labels.length; index++) {
            options.addView(separator(0));
            Runnable action = actions[index];
            TextView option = sheetButton(labels[index], false);
            option.setTextColor(colors[index]);
            option.setOnClickListener(view -> {
                sheet.dismiss();
                action.run();
            });
            options.addView(option, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        }

        TextView cancel = sheetButton("Cancel", true);
        cancel.setBackground(rounded(0xFF2C2C2E, dp(14)));
        cancel.setOnClickListener(view -> sheet.dismiss());
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        cancelParams.topMargin = dp(8);
        column.addView(cancel, cancelParams);

        sheet.setContentView(column);
        Window window = sheet.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.BOTTOM);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.45f);
            window.setWindowAnimations(android.R.style.Animation_InputMethod);
            column.setOnApplyWindowInsetsListener((view, insets) -> {
                int bottom = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                        ? insets.getInsets(WindowInsets.Type.navigationBars()).bottom
                        : insets.getSystemWindowInsetBottom();
                view.setPadding(dp(8), 0, dp(8), bottom + dp(8));
                return insets;
            });
        }
        sheet.show();
    }

    private TextView sheetButton(String label, boolean bold) {
        TextView button = text(label, 19, ACCENT, Typeface.NORMAL);
        if (bold) {
            button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }
        button.setGravity(Gravity.CENTER);
        button.setBackground(rowBackground());
        button.setClickable(true);
        button.setFocusable(true);
        return button;
    }

    /**
     * Saves the draft to every ticked screen, then makes sure each will actually show it: cover
     * slots Motorola hasn't given exCover open its editor; main screens that aren't exCover yet
     * open Android's live-wallpaper preview, where the user confirms home, lock or both.
     */
    private void applySelection(boolean[] selection) {
        setBusy(true, "Setting wallpaper…");
        worker.execute(() -> {
            try {
                WallpaperStore.apply(this, selection[0], selection[1]);
                if (selection[2]) {
                    WallpaperStore.applyMain(this, WallpaperStore.TARGET_MAIN_HOME);
                }
                if (selection[3]) {
                    WallpaperStore.applyMain(this, WallpaperStore.TARGET_MAIN_LOCK);
                }
                runOnUiThread(() -> {
                    setBusy(false, null);
                    int coverMissing = selection[0] && !isLive(SLOT_HOME, WallpaperStore.TARGET_HOME)
                            ? SLOT_HOME
                            : selection[1] && !isLive(SLOT_LOCK, WallpaperStore.TARGET_LOCK) ? SLOT_LOCK
                            : 0;
                    boolean[] main = mainScreensUsingExCover();
                    boolean mainMissing = (selection[2] && !main[0]) || (selection[3] && !main[1]);
                    if (coverMissing != 0) {
                        openMotorolaEditor(coverMissing);
                    } else if (mainMissing) {
                        openMainPicker();
                    } else {
                        flash("Wallpaper set", SUCCESS);
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> showError(readableError(error)));
            }
        });
    }

    private void openMainPicker() {
        Intent intent = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
        intent.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                new android.content.ComponentName(this, GifWallpaperService.class));
        try {
            startActivity(intent);
            flash("Saved · choose Home, Lock or both", WARNING);
        } catch (Exception error) {
            showError("Android's wallpaper picker isn't available");
        }
    }

    /** Whether exCover is the main home and main lock wallpaper; lock follows home when unset. */
    private boolean[] mainScreensUsingExCover() {
        try {
            WallpaperManager manager = WallpaperManager.getInstance(this);
            WallpaperInfo home = manager.getWallpaperInfo();
            boolean homeOurs = home != null && getPackageName().equals(home.getPackageName());
            if (Build.VERSION.SDK_INT < 34) {
                return new boolean[]{homeOurs, homeOurs};
            }
            WallpaperInfo lock = manager.getWallpaperInfo(WallpaperManager.FLAG_LOCK);
            boolean lockOurs = lock == null ? homeOurs : getPackageName().equals(lock.getPackageName());
            return new boolean[]{homeOurs, lockOurs};
        } catch (Exception error) {
            return new boolean[]{false, false};
        }
    }

    private void updateSetButton() {
        if (setButton != null) {
            setButton.setText("Set Wallpaper");
        }
    }

    /**
     * Whether a slot is assigned to exCover and its engine is running. Android occasionally fails
     * to rebind the lock slot after an app update, leaving it assigned but with no engine.
     */
    private boolean isLive(int slot, String target) {
        return isAssigned(slot) && GifWallpaperService.isEngineRunning(target);
    }

    /** Whether Motorola currently has exCover assigned to a cover-screen slot. */
    private boolean isAssigned(int slot) {
        if (Build.VERSION.SDK_INT < 34) {
            return true;
        }
        try {
            WallpaperInfo info = WallpaperManager.getInstance(this).getWallpaperInfo(slot);
            return info != null && getPackageName().equals(info.getPackageName());
        } catch (Exception error) {
            return true;
        }
    }

    /** Selecting exCover in Motorola's cover editor (re)binds that slot to this app. */
    private void openMotorolaEditor(int slot) {
        Intent intent = new Intent(SLOT_LOCK == slot
                ? "com.motorola.intent.action.SECONDARY_CLOCKFACE_PICKER"
                : "com.motorola.intent.action.CLI_SETTINGS");
        try {
            startActivity(intent);
            flash(SLOT_LOCK == slot
                    ? "Saved · choose exCover in a lock design's Wallpaper row"
                    : "Saved · choose exCover as the cover home wallpaper", WARNING);
        } catch (Exception error) {
            showError("Motorola's cover editor isn't available");
        }
    }

    private void openGallery() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/gif"});
        startActivityForResult(intent, REQUEST_PICK_GIF);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_EDIT) {
            if (resultCode == RESULT_OK) {
                refreshPreview(null, true);
            }
            return;
        }
        if (requestCode == REQUEST_GIPHY && resultCode == RESULT_OK && data != null) {
            String url = data.getStringExtra(GiphySearchActivity.EXTRA_URL);
            if (url != null) {
                downloadGif(URI.create(url), data.getStringExtra(GiphySearchActivity.EXTRA_NAME), false);
            }
            return;
        }
        if (requestCode != REQUEST_PICK_GIF || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            showError("No file was selected");
            return;
        }
        importFromUri(uri);
    }

    private void importFromUri(Uri uri) {
        setBusy(true, "Importing…");
        String name = queryDisplayName(uri);
        worker.execute(() -> {
            try {
                ContentResolver resolver = getContentResolver();
                InputStream input = resolver.openInputStream(uri);
                if (input == null) {
                    throw new IOException("The selected file could not be opened.");
                }
                WallpaperStore.ImportResult result = WallpaperStore.importGif(this, input, name);
                runOnUiThread(() -> finishImport(result));
            } catch (Exception error) {
                runOnUiThread(() -> showError(readableError(error)));
            }
        });
    }

    private void pasteLink() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0 || clip.getItemAt(0).getText() == null) {
            showError("Clipboard is empty");
            return;
        }
        linkInput.setText(clip.getItemAt(0).getText().toString().trim());
        linkInput.setSelection(linkInput.getText().length());
    }

    private void importFromLink() {
        String value = linkInput.getText().toString().trim();
        if (value.isEmpty()) {
            linkInput.requestFocus();
            showError("Paste a link to a GIF");
            return;
        }

        URI uri;
        try {
            uri = URI.create(value);
        } catch (Exception error) {
            showError("That link isn't valid");
            return;
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))) {
            showError("Use an http or https link");
            return;
        }

        hideKeyboard();
        downloadGif(uri, null, true);
    }

    private void downloadGif(URI uri, String displayName, boolean fromLinkField) {
        setBusy(true, "Downloading…");
        worker.execute(() -> {
            HttpURLConnection connection = null;
            try {
                URL url = uri.toURL();
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(12_000);
                connection.setReadTimeout(30_000);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("User-Agent", "exCover/1.0");
                int response = connection.getResponseCode();
                if (response < 200 || response >= 300) {
                    throw new IOException("Download failed (HTTP " + response + ")");
                }
                long length = connection.getContentLengthLong();
                if (length > WallpaperStore.MAX_GIF_BYTES) {
                    throw new IOException("GIF is larger than 50 MB");
                }
                String name = displayName != null ? displayName : fileNameFromUrl(connection.getURL());
                InputStream input = new BufferedInputStream(connection.getInputStream());
                WallpaperStore.ImportResult result = WallpaperStore.importGif(this, input, name);
                runOnUiThread(() -> {
                    if (fromLinkField) {
                        linkInput.setText("");
                    }
                    finishImport(result);
                });
            } catch (Exception error) {
                runOnUiThread(() -> showError(readableError(error)));
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }

    private void finishImport(WallpaperStore.ImportResult result) {
        if (destroyed) {
            return;
        }
        refreshPreview("Imported · tap Set Wallpaper", true);
    }

    private void refreshPreview(String message, boolean success) {
        setBusy(true, null);
        worker.execute(() -> {
            try {
                android.graphics.ImageDecoder.Source source =
                        GifDecoder.source(this, WallpaperStore.TARGET_DRAFT);
                Drawable image = GifDecoder.decode(source, PREVIEW_DECODE_SIDE);
                // The blurred backdrop plays its own tiny decode of the animation.
                Drawable ambient = GifDecoder.decode(source, GlassBackdropView.DECODE_SIDE);
                WallpaperStore.Crop crop = WallpaperStore.crop(this, WallpaperStore.TARGET_DRAFT);
                String name = WallpaperStore.selectionName(this, WallpaperStore.TARGET_DRAFT);
                String detail = WallpaperStore.selectionDetail(this, WallpaperStore.TARGET_DRAFT);
                runOnUiThread(() -> {
                    if (destroyed) {
                        return;
                    }
                    preview.setImage(image, crop);
                    if (switching) {
                        resetStage();
                    }
                    backdrop.setImage(ambient);
                    nameText.setText(name);
                    detailText.setText(detail);
                    setBusy(false, null);
                    updateSetButton();
                    refreshRecents();
                    if (message != null) {
                        flash(message, success ? SUCCESS : LABEL);
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> showError(readableError(error)));
            }
        });
    }

    private void setBusy(boolean busy, String message) {
        if (destroyed) {
            return;
        }
        for (View view : interactiveViews) {
            view.setEnabled(!busy);
            view.animate().alpha(busy ? 0.4f : 1f).setDuration(150).start();
        }
        if (busy && message != null) {
            showHud(message, LABEL, true);
        } else if (!busy) {
            hudSpinner.setVisibility(View.GONE);
        }
    }

    private void showError(String message) {
        if (destroyed) {
            return;
        }
        setBusy(false, null);
        if (switching) {
            resetStage();
        }
        flash(message, ERROR);
    }

    // A small floating capsule stands in for iOS-style transient status instead of a permanent card.
    private LinearLayout buildHud() {
        LinearLayout capsule = new LinearLayout(this);
        capsule.setOrientation(LinearLayout.HORIZONTAL);
        capsule.setGravity(Gravity.CENTER_VERTICAL);
        capsule.setPadding(dp(18), 0, dp(18), 0);
        capsule.setBackground(rounded(0xE62C2C2E, dp(22)));
        capsule.setElevation(dp(12));
        capsule.setAlpha(0f);
        capsule.setVisibility(View.GONE);

        hudSpinner = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        hudSpinner.setIndeterminateTintList(ColorStateList.valueOf(SECONDARY));
        LinearLayout.LayoutParams spinnerParams = new LinearLayout.LayoutParams(dp(18), dp(18));
        spinnerParams.rightMargin = dp(10);
        capsule.addView(hudSpinner, spinnerParams);

        hudText = text("", 15, LABEL, Typeface.NORMAL);
        hudText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        hudText.setSingleLine(true);
        hudText.setEllipsize(TextUtils.TruncateAt.END);
        hudText.setMaxWidth(Math.round(getResources().getDisplayMetrics().widthPixels * 0.75f));
        hudText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        capsule.addView(hudText);
        return capsule;
    }

    private void showHud(String message, int color, boolean spinning) {
        handler.removeCallbacks(hideHud);
        hudText.setText(message);
        hudText.setTextColor(color);
        hudSpinner.setVisibility(spinning ? View.VISIBLE : View.GONE);
        if (hud.getVisibility() != View.VISIBLE) {
            hud.setVisibility(View.VISIBLE);
            hud.setTranslationY(dp(12));
            hud.setScaleX(0.96f);
            hud.setScaleY(0.96f);
        }
        hud.animate().alpha(1f).translationY(0).scaleX(1f).scaleY(1f).setDuration(220).start();
    }

    private void flash(String message, int color) {
        showHud(message, color, false);
        handler.postDelayed(hideHud, color == ERROR ? 3200 : 1600);
    }

    private void hideHud() {
        hud.animate().alpha(0f).translationY(dp(8)).setDuration(200)
                .withEndAction(() -> hud.setVisibility(View.GONE)).start();
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0) {
                    return cursor.getString(column);
                }
            }
        } catch (Exception ignored) {
        }
        String segment = uri.getLastPathSegment();
        return segment == null ? "Imported GIF" : segment;
    }

    private String fileNameFromUrl(URL url) {
        String path = url.getPath();
        if (path == null || path.isEmpty() || path.endsWith("/")) {
            return "Downloaded GIF";
        }
        String name = path.substring(path.lastIndexOf('/') + 1);
        return name.isEmpty() ? "Downloaded GIF" : name;
    }

    private String readableError(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? "Import failed. Check the file or link."
                : message;
    }

    private void hideKeyboard() {
        View focused = getCurrentFocus();
        if (focused == null) {
            return;
        }
        InputMethodManager inputMethod = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        inputMethod.hideSoftInputFromWindow(focused.getWindowToken(), 0);
        focused.clearFocus();
    }

    private void openExternalDisplaySettings() {
        Intent intent = new Intent("com.motorola.intent.action.CLI_SETTINGS");
        try {
            startActivity(intent);
        } catch (Exception error) {
            showError("Cover screen settings aren't available");
        }
    }

    private LinearLayout group() {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setBackground(rounded(GROUPED, dp(12)));
        group.setClipToOutline(true);
        return group;
    }

    private View separator(int inset) {
        View line = new View(this);
        line.setBackgroundColor(SEPARATOR);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2));
        params.leftMargin = inset;
        line.setLayoutParams(params);
        return line;
    }

    private StateListDrawable rowBackground() {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, new ColorDrawable(GROUPED_PRESSED));
        states.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        states.setExitFadeDuration(200);
        return states;
    }

    private TextView sectionHeader(String value) {
        TextView header = text(value.toUpperCase(Locale.US), 13, SECONDARY, Typeface.NORMAL);
        header.setPadding(dp(16), 0, dp(16), dp(6));
        return header;
    }

    private LinearLayout.LayoutParams sectionHeaderParams() {
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(22);
        return params;
    }

    private TextView primaryButton(String label) {
        TextView button = text(label, 17, Color.WHITE, Typeface.NORMAL);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setGravity(Gravity.CENTER);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, rounded(ACCENT_PRESSED, dp(14)));
        states.addState(new int[]{}, rounded(ACCENT, dp(14)));
        states.setExitFadeDuration(150);
        button.setBackground(states);
        button.setClickable(true);
        button.setFocusable(true);
        return button;
    }

    private TextView textButton(String label) {
        TextView button = text(label, 17, ACCENT, Typeface.NORMAL);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setClickable(true);
        button.setFocusable(true);
        button.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    view.setAlpha(0.4f);
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    view.animate().alpha(view.isEnabled() ? 1f : 0.4f).setDuration(150).start();
                    break;
                default:
                    break;
            }
            return false;
        });
        return button;
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private TextView text(String value, int sp, int color, int style) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans-serif", style));
        return view;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
