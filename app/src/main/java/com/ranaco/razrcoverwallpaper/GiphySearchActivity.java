package com.ranaco.razrcoverwallpaper;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Searches GIPHY and returns the chosen GIF's download URL to {@link MainActivity}. */
public final class GiphySearchActivity extends Activity {
    public static final String EXTRA_URL = "giphy_url";
    public static final String EXTRA_NAME = "giphy_name";

    private static final String API = "https://api.giphy.com/v1/gifs/";
    private static final int PAGE_SIZE = 30;
    private static final long SEARCH_DEBOUNCE_MS = 350;

    private static final int LABEL = Color.WHITE;
    private static final int SECONDARY = 0x99EBEBF5;
    private static final int TERTIARY = 0x4DEBEBF5;
    private static final int ACCENT = 0xFF0A84FF;
    private static final int FIELD = 0x3D767680;
    private static final int[] PLACEHOLDERS = {0x1FFFFFFF, 0x14FFFFFF, 0x29FFFFFF};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService api = Executors.newSingleThreadExecutor();
    private final ExecutorService images = Executors.newFixedThreadPool(4);
    private final List<AnimatedImageDrawable> animations = new ArrayList<>();
    private final Runnable runSearch = () -> startQuery(currentQuery());

    private EditText searchField;
    private ScrollView scroll;
    private LinearLayout content;
    private LinearLayout leftColumn;
    private LinearLayout rightColumn;
    private int leftHeight;
    private int rightHeight;
    private int columnWidth;
    private TextView statusText;
    private ProgressBar spinner;
    private String query = "";
    private int offset;
    private int generation;
    private boolean loading;
    private boolean exhausted;
    private boolean destroyed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        render();
        updateEmptyState();
        if (BuildConfig.GIPHY_API_KEY.isEmpty()) {
            showStatus("Add a GIPHY API key to local.properties as giphy.apiKey, then rebuild.",
                    false);
            return;
        }
        startQuery("");
    }

    @Override
    protected void onResume() {
        super.onResume();
        for (AnimatedImageDrawable animation : animations) {
            animation.start();
        }
    }

    @Override
    protected void onPause() {
        // Dozens of animated previews are costly; only play them while the grid is visible.
        for (AnimatedImageDrawable animation : animations) {
            animation.stop();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        api.shutdownNow();
        images.shutdownNow();
        super.onDestroy();
    }

    private void render() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        root.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(8), dp(8), dp(10));
        page.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout field = new LinearLayout(this);
        field.setOrientation(LinearLayout.HORIZONTAL);
        field.setGravity(Gravity.CENTER_VERTICAL);
        field.setPadding(dp(10), 0, dp(10), 0);
        field.setBackground(rounded(FIELD, dp(10)));
        bar.addView(field, new LinearLayout.LayoutParams(0, dp(40), 1f));

        ImageView magnifier = new ImageView(this);
        magnifier.setImageResource(R.drawable.ic_search);
        magnifier.setImageTintList(ColorStateList.valueOf(SECONDARY));
        LinearLayout.LayoutParams magnifierParams = new LinearLayout.LayoutParams(dp(20), dp(20));
        magnifierParams.rightMargin = dp(6);
        field.addView(magnifier, magnifierParams);

        searchField = new EditText(this);
        searchField.setBackground(null);
        searchField.setPadding(0, 0, 0, 0);
        searchField.setHint("Search GIPHY");
        searchField.setHintTextColor(SECONDARY);
        searchField.setTextColor(LABEL);
        searchField.setTextSize(17);
        searchField.setSingleLine(true);
        searchField.setInputType(InputType.TYPE_CLASS_TEXT);
        searchField.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchField.setOnEditorActionListener((view, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH) {
                handler.removeCallbacks(runSearch);
                runSearch.run();
                hideKeyboard();
                return true;
            }
            return false;
        });
        searchField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable text) {
                handler.removeCallbacks(runSearch);
                handler.postDelayed(runSearch, SEARCH_DEBOUNCE_MS);
            }
        });
        field.addView(searchField, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        TextView cancel = text("Cancel", 17, ACCENT, Typeface.NORMAL);
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(dp(12), 0, dp(8), 0);
        cancel.setOnClickListener(view -> finish());
        bar.addView(cancel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));

        scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOnScrollChangeListener((view, x, y, oldX, oldY) -> maybeLoadMore());
        page.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        int gap = dp(8);
        int side = dp(12);
        columnWidth = (getResources().getDisplayMetrics().widthPixels - side * 2 - gap) / 2;
        LinearLayout columns = new LinearLayout(this);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        columns.setPadding(side, 0, side, 0);
        content.addView(columns, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        leftColumn = column();
        columns.addView(leftColumn, new LinearLayout.LayoutParams(
                columnWidth, ViewGroup.LayoutParams.WRAP_CONTENT));
        rightColumn = column();
        LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(
                columnWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
        rightParams.leftMargin = gap;
        columns.addView(rightColumn, rightParams);

        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setGravity(Gravity.CENTER_HORIZONTAL);
        footer.setPadding(dp(24), dp(20), dp(24), dp(12));
        content.addView(footer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        spinner = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(SECONDARY));
        spinner.setVisibility(View.GONE);
        footer.addView(spinner, new LinearLayout.LayoutParams(dp(22), dp(22)));
        statusText = text("", 15, SECONDARY, Typeface.NORMAL);
        statusText.setGravity(Gravity.CENTER);
        statusText.setVisibility(View.GONE);
        footer.addView(statusText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView attribution = text("Powered by GIPHY", 12, TERTIARY, Typeface.BOLD);
        attribution.setLetterSpacing(0.04f);
        LinearLayout.LayoutParams attributionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        attributionParams.topMargin = dp(16);
        footer.addView(attribution, attributionParams);

        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int top;
            int bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                top = bars.top;
                bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            page.setPadding(0, top, 0, 0);
            content.setPadding(0, dp(4), 0, bottom + dp(16));
            return insets;
        });
        setContentView(root);
    }

    private String currentQuery() {
        return searchField.getText().toString().trim();
    }

    private void startQuery(String value) {
        if (BuildConfig.GIPHY_API_KEY.isEmpty()) {
            return;
        }
        if (value.equals(query) && (offset > 0 || loading)) {
            return;
        }
        query = value;
        offset = 0;
        exhausted = false;
        generation++;
        loading = false;
        for (AnimatedImageDrawable animation : animations) {
            animation.stop();
        }
        animations.clear();
        leftColumn.removeAllViews();
        rightColumn.removeAllViews();
        leftHeight = 0;
        rightHeight = 0;
        scroll.scrollTo(0, 0);
        updateEmptyState();
        loadPage();
    }

    /** With no results yet, the spinner, messages and attribution sit in the middle of the page. */
    private void updateEmptyState() {
        boolean empty = leftColumn.getChildCount() + rightColumn.getChildCount() == 0;
        content.setGravity(empty ? Gravity.CENTER_VERTICAL : Gravity.TOP);
    }

    private void maybeLoadMore() {
        View child = scroll.getChildAt(0);
        if (child == null || loading || exhausted) {
            return;
        }
        int remaining = child.getHeight() - (scroll.getScrollY() + scroll.getHeight());
        if (remaining < getResources().getDisplayMetrics().heightPixels) {
            loadPage();
        }
    }

    private void loadPage() {
        loading = true;
        int requestGeneration = generation;
        int requestOffset = offset;
        String requestQuery = query;
        spinner.setVisibility(View.VISIBLE);
        statusText.setVisibility(View.GONE);
        api.execute(() -> {
            try {
                List<Gif> page = fetch(requestQuery, requestOffset);
                runOnUiThread(() -> {
                    if (destroyed || requestGeneration != generation) {
                        return;
                    }
                    loading = false;
                    spinner.setVisibility(View.GONE);
                    offset = requestOffset + page.size();
                    exhausted = page.size() < PAGE_SIZE;
                    for (Gif gif : page) {
                        addTile(gif, requestGeneration);
                    }
                    updateEmptyState();
                    if (offset == 0) {
                        showStatus(requestQuery.isEmpty()
                                ? "Nothing trending right now."
                                : "No GIFs found for “" + requestQuery + "”.", false);
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (destroyed || requestGeneration != generation) {
                        return;
                    }
                    loading = false;
                    spinner.setVisibility(View.GONE);
                    showStatus("Couldn't reach GIPHY. Check your connection.", false);
                });
            }
        });
    }

    private List<Gif> fetch(String searchQuery, int pageOffset) throws Exception {
        StringBuilder url = new StringBuilder(API)
                .append(searchQuery.isEmpty() ? "trending" : "search")
                .append("?api_key=").append(URLEncoder.encode(BuildConfig.GIPHY_API_KEY, "UTF-8"))
                .append("&limit=").append(PAGE_SIZE)
                .append("&offset=").append(pageOffset)
                .append("&rating=pg-13");
        if (!searchQuery.isEmpty()) {
            url.append("&q=").append(URLEncoder.encode(searchQuery, "UTF-8"));
        }
        JSONObject response = new JSONObject(new String(download(url.toString()), StandardCharsets.UTF_8));
        JSONArray data = response.getJSONArray("data");
        List<Gif> result = new ArrayList<>();
        for (int index = 0; index < data.length(); index++) {
            JSONObject item = data.getJSONObject(index);
            JSONObject renditions = item.getJSONObject("images");
            JSONObject preview = renditions.getJSONObject("fixed_width");
            JSONObject original = renditions.getJSONObject("original");
            int width = preview.optInt("width", 200);
            int height = preview.optInt("height", 200);
            String previewUrl = preview.optString("webp", preview.optString("url"));
            // Prefer full quality, but fall back when the original exceeds the import limit.
            String downloadUrl = original.optString("url");
            long originalBytes = original.optLong("size", 0);
            if (downloadUrl.isEmpty() || originalBytes > WallpaperStore.MAX_GIF_BYTES) {
                downloadUrl = renditions.getJSONObject("downsized_large").optString("url");
            }
            if (previewUrl.isEmpty() || downloadUrl.isEmpty() || width <= 0 || height <= 0) {
                continue;
            }
            String title = item.optString("title", "").trim();
            result.add(new Gif(title.isEmpty() ? "GIPHY GIF" : title,
                    previewUrl, downloadUrl, width, height));
        }
        return result;
    }

    private void addTile(Gif gif, int tileGeneration) {
        int height = Math.max(dp(80), Math.round(columnWidth * gif.height / (float) gif.width));
        boolean left = leftHeight <= rightHeight;
        LinearLayout column = left ? leftColumn : rightColumn;
        if (left) {
            leftHeight += height;
        } else {
            rightHeight += height;
        }

        ImageView tile = new ImageView(this);
        tile.setScaleType(ImageView.ScaleType.CENTER_CROP);
        tile.setBackgroundColor(PLACEHOLDERS[(leftColumn.getChildCount()
                + rightColumn.getChildCount()) % PLACEHOLDERS.length]);
        tile.setClipToOutline(true);
        tile.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(10));
            }
        });
        tile.setContentDescription(gif.title);
        tile.setOnClickListener(view -> choose(gif));
        tile.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).start();
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    view.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
                    break;
                default:
                    break;
            }
            return false;
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, height);
        params.bottomMargin = dp(8);
        column.addView(tile, params);

        int targetHeight = height;
        images.execute(() -> {
            try {
                byte[] bytes = download(gif.previewUrl);
                Drawable drawable = ImageDecoder.decodeDrawable(
                        ImageDecoder.createSource(ByteBuffer.wrap(bytes)),
                        (decoder, info, source) -> decoder.setTargetSize(columnWidth, targetHeight));
                runOnUiThread(() -> {
                    if (destroyed || tileGeneration != generation) {
                        return;
                    }
                    tile.setImageDrawable(drawable);
                    tile.setAlpha(0f);
                    tile.animate().alpha(1f).setDuration(180).start();
                    if (drawable instanceof AnimatedImageDrawable) {
                        AnimatedImageDrawable animation = (AnimatedImageDrawable) drawable;
                        animations.add(animation);
                        animation.start();
                    }
                });
            } catch (Exception ignored) {
                // A missing preview leaves the placeholder; the GIF itself can still be chosen.
            }
        });
    }

    private void choose(Gif gif) {
        hideKeyboard();
        Intent result = new Intent();
        result.putExtra(EXTRA_URL, gif.downloadUrl);
        result.putExtra(EXTRA_NAME, gif.title);
        setResult(RESULT_OK, result);
        finish();
    }

    private void showStatus(String message, boolean busy) {
        spinner.setVisibility(busy ? View.VISIBLE : View.GONE);
        statusText.setText(message);
        statusText.setVisibility(View.VISIBLE);
    }

    private static byte[] download(String address) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        try {
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(20_000);
            connection.setRequestProperty("User-Agent", "exCover/1.0");
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code);
            }
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
                return output.toByteArray();
            }
        } finally {
            connection.disconnect();
        }
    }

    private void hideKeyboard() {
        InputMethodManager inputMethod = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        inputMethod.hideSoftInputFromWindow(searchField.getWindowToken(), 0);
        searchField.clearFocus();
    }

    private LinearLayout column() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
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

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class Gif {
        final String title;
        final String previewUrl;
        final String downloadUrl;
        final int width;
        final int height;

        Gif(String title, String previewUrl, String downloadUrl, int width, int height) {
            this.title = title;
            this.previewUrl = previewUrl;
            this.downloadUrl = downloadUrl;
            this.width = width;
            this.height = height;
        }
    }
}
