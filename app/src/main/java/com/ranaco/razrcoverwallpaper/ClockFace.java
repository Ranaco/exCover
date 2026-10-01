package com.ranaco.razrcoverwallpaper;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.format.DateFormat;
import android.util.SparseArray;

import java.util.Calendar;
import java.util.Locale;

/**
 * Draws exCover's clock faces over the lock GIF. Each face is its own design in Motorola's cover
 * lock screen picker; its font and colour are set in Motorola's design editor and reach the
 * design as it runs. The editor's thumbnails are drawn here too, so they match the cover.
 *
 * Each face pairs a bundled font with its own layout: Airy (Outfit, light and centred), Classic
 * (Fraunces, a soft serif), Poster (Bricolage Grotesque, heavy stacked digits) and Mono (DM Mono,
 * compact at the top). Layouts come from measured glyphs inside a cover-shaped box and shrink to
 * fit, so they stay clean whatever size the host gives the surface.
 */
final class ClockFace {
    static final String AIRY = "airy";
    static final String CLASSIC = "classic";
    static final String POSTER = "poster";
    static final String MONO = "mono";
    static final String NONE = "none";
    static final String[] KEYS = {AIRY, CLASSIC, POSTER, MONO, NONE};
    static final String[] LABELS = {"Airy", "Classic", "Poster", "Mono", "GIF only"};
    /**
     * Each face's design id in Motorola's picker. Airy keeps the id exCover's single design had,
     * so a design chosen before faces existed carries on as Airy.
     */
    static final String[] TEMPLATES = {
            "excover_color", "excover_classic", "excover_poster", "excover_mono", "excover_gif"};

    /** The editor's font setting and its choices; each face starts in its own font. */
    static final String FONT_OPTION = "font";
    static final String[] FONT_KEYS = {"outfit", "fraunces", "bricolage", "dm_mono"};
    private static final String[] FACE_FONTS = {"outfit", "fraunces", "bricolage", "dm_mono", "outfit"};

    /** The cover lock screen's shape; layouts are designed against it. */
    private static final float DESIGN_ASPECT = 1272f / 1080f;
    private static final int SHADOW_ALPHA = 0x59;
    private static final SparseArray<Typeface> TYPEFACES = new SparseArray<>();

    /** How heavy a face draws its time; each font has its own settings for each. */
    private static final int LIGHT = 0;
    private static final int REGULAR = 1;
    private static final int HEAVY = 2;

    /**
     * A bundled font with variable-font settings for large digits at each weight, and for the
     * small date and labels. A face keeps its weight whichever font it is drawn in.
     */
    private static final class Font {
        final int resource;
        final String[] timeAxes;
        final String labelAxes;

        Font(int resource, String light, String regular, String heavy, String labelAxes) {
            this.resource = resource;
            this.timeAxes = new String[]{light, regular, heavy};
            this.labelAxes = labelAxes;
        }
    }

    private static final Font OUTFIT = new Font(R.font.outfit,
            "'wght' 230", "'wght' 380", "'wght' 800", "'wght' 500");
    private static final Font FRAUNCES = new Font(R.font.fraunces,
            "'wght' 300, 'opsz' 144, 'SOFT' 100, 'WONK' 0",
            "'wght' 360, 'opsz' 144, 'SOFT' 100, 'WONK' 0",
            "'wght' 820, 'opsz' 144, 'SOFT' 100, 'WONK' 0",
            "'wght' 450, 'opsz' 14, 'SOFT' 50, 'WONK' 0");
    private static final Font BRICOLAGE = new Font(R.font.bricolage_grotesque,
            "'wght' 300, 'wdth' 100, 'opsz' 96",
            "'wght' 460, 'wdth' 90, 'opsz' 96",
            "'wght' 780, 'wdth' 75, 'opsz' 96",
            "'wght' 560, 'wdth' 100, 'opsz' 14");
    private static final Font DM_MONO = new Font(R.font.dm_mono, "", "", "", "");
    /** In the same order as FONT_KEYS. */
    private static final Font[] FONTS = {OUTFIT, FRAUNCES, BRICOLAGE, DM_MONO};
    /** How heavy each face draws its time, in the order of KEYS. */
    private static final int[] FACE_WEIGHTS = {LIGHT, REGULAR, HEAVY, REGULAR, LIGHT};

    private final Context context;
    private final Paint time = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint date = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint rule = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect bounds = new Rect();
    /** Null keeps the face's own font. */
    private String font;
    private int color = Color.WHITE;

    ClockFace(Context context) {
        this.context = context;
        time.setFontFeatureSettings("tnum");
        rule.setStrokeCap(Paint.Cap.ROUND);
    }

    /** The face a design id stands for; unknown ids draw Airy. */
    static String faceForTemplate(String templateId) {
        for (int i = 0; i < TEMPLATES.length; i++) {
            if (TEMPLATES[i].equals(templateId)) {
                return KEYS[i];
            }
        }
        return AIRY;
    }

    static String defaultFont(String face) {
        return FACE_FONTS[indexOf(face)];
    }

    private static int indexOf(String face) {
        for (int i = 0; i < KEYS.length; i++) {
            if (KEYS[i].equals(face)) {
                return i;
            }
        }
        return 0;
    }

    void setFont(String font) {
        this.font = font;
    }

    void setColor(int color) {
        this.color = color | 0xFF000000;
    }

    private static Font chosenFont(String key) {
        for (int i = 0; i < FONT_KEYS.length; i++) {
            if (FONT_KEYS[i].equals(key)) {
                return FONTS[i];
            }
        }
        return null;
    }

    /**
     * A "123" sample of {@code fontKey} at {@code face}'s weight, white on transparent, for the
     * editor's font row, the way Motorola shows its own fonts.
     */
    Bitmap fontSample(String fontKey, String face, int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Font font = chosenFont(fontKey);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        paint.setColor(Color.WHITE);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(typeface(context, font));
        String axes = font.timeAxes[FACE_WEIGHTS[indexOf(face)]];
        paint.setFontVariationSettings(axes.isEmpty() ? null : axes);
        fit(paint, "123", height * 0.62f, width * 0.86f);
        paint.getTextBounds("0", 0, 1, bounds);
        canvas.drawText("123", width / 2f, (height - bounds.top) / 2f, paint);
        return bitmap;
    }

    private static Typeface typeface(Context context, Font font) {
        synchronized (TYPEFACES) {
            Typeface cached = TYPEFACES.get(font.resource);
            if (cached == null) {
                try {
                    cached = context.getResources().getFont(font.resource);
                } catch (RuntimeException error) {
                    cached = Typeface.DEFAULT;
                }
                TYPEFACES.put(font.resource, cached);
            }
            return cached;
        }
    }

    /**
     * Sets both paints to the chosen font, or the face's own until one is chosen, with the time
     * at the face's weight.
     */
    private void useFont(Font faceFont, int weight) {
        Font chosen = chosenFont(font);
        Font use = chosen != null ? chosen : faceFont;
        Typeface typeface = typeface(context, use);
        time.setTypeface(typeface);
        date.setTypeface(typeface);
        String timeAxes = use.timeAxes[weight];
        time.setFontVariationSettings(timeAxes.isEmpty() ? null : timeAxes);
        date.setFontVariationSettings(use.labelAxes.isEmpty() ? null : use.labelAxes);
    }

    /**
     * Draws the clock face {@code face} at {@code alpha} (0 to 1). The shadows fade with the
     * text; left at full strength they would linger as a dark copy of the clock.
     */
    void draw(Canvas canvas, String face, int width, int height, float alpha,
            Calendar calendar, boolean is24Hour) {
        if (alpha <= 0f || NONE.equals(face)) {
            return;
        }
        // One unit is the width of the cover-shaped box that fits the surface.
        float unit = Math.min(width, height / DESIGN_ASPECT);
        int textAlpha = Math.round(255 * alpha);
        int shadow = Color.argb(Math.round(SHADOW_ALPHA * alpha), 0, 0, 0);
        time.setColor(color);
        date.setColor(color);
        rule.setColor(color);
        time.setAlpha(textAlpha);
        date.setAlpha(Math.round(textAlpha * 0.9f));
        rule.setAlpha(Math.round(textAlpha * 0.55f));
        time.setShadowLayer(unit * 0.014f, 0f, unit * 0.002f, shadow);
        date.setShadowLayer(unit * 0.008f, 0f, unit * 0.001f, shadow);
        String hours = DateFormat.format(is24Hour ? "HH" : "h", calendar).toString();
        String minutes = DateFormat.format("mm", calendar).toString();

        if (CLASSIC.equals(face)) {
            drawClassic(canvas, width, height, unit, hours + ":" + minutes, calendar);
        } else if (POSTER.equals(face)) {
            String paddedHours = DateFormat.format(is24Hour ? "HH" : "hh", calendar).toString();
            drawPoster(canvas, width, height, unit, paddedHours, minutes, calendar);
        } else if (MONO.equals(face)) {
            drawMono(canvas, width, height, unit, hours + ":" + minutes, calendar);
        } else {
            drawAiry(canvas, width, height, unit, hours + ":" + minutes, calendar);
        }
    }

    /** Airy: a very light, large time with a small spaced-out date above, centred. */
    private void drawAiry(Canvas canvas, int width, int height, float unit, String clock,
            Calendar calendar) {
        useFont(OUTFIT, LIGHT);
        String day = upper(DateFormat.format("EEE d MMM", calendar));
        time.setTextAlign(Paint.Align.CENTER);
        date.setTextAlign(Paint.Align.CENTER);
        time.setLetterSpacing(-0.01f);
        date.setLetterSpacing(0.16f);
        fit(time, clock, unit * 0.33f, width * 0.86f);
        fit(date, day, unit * 0.042f, width * 0.8f);

        float dateHeight = capHeight(date);
        float timeHeight = capHeight(time);
        float gap = unit * 0.07f;
        float top = height * 0.40f - (dateHeight + gap + timeHeight) / 2f;
        float cx = width / 2f;
        canvas.drawText(day, cx, top + dateHeight, date);
        canvas.drawText(clock, cx, top + dateHeight + gap + timeHeight, time);
    }

    /** Classic: a soft serif time with the full date beneath, centred. */
    private void drawClassic(Canvas canvas, int width, int height, float unit, String clock,
            Calendar calendar) {
        useFont(FRAUNCES, REGULAR);
        String day = DateFormat.format("EEEE d MMMM", calendar).toString();
        time.setTextAlign(Paint.Align.CENTER);
        date.setTextAlign(Paint.Align.CENTER);
        time.setLetterSpacing(-0.02f);
        date.setLetterSpacing(0.01f);
        fit(time, clock, unit * 0.31f, width * 0.84f);
        fit(date, day, unit * 0.056f, width * 0.84f);

        float timeHeight = capHeight(time);
        float dateHeight = capHeight(date);
        float gap = unit * 0.075f;
        float top = height * 0.40f - (timeHeight + gap + dateHeight) / 2f;
        float cx = width / 2f;
        canvas.drawText(clock, cx, top + timeHeight, time);
        canvas.drawText(day, cx, top + timeHeight + gap + dateHeight, date);
    }

    /** Poster: heavy condensed hours over minutes on the left, with the date above. */
    private void drawPoster(Canvas canvas, int width, int height, float unit, String hours,
            String minutes, Calendar calendar) {
        useFont(BRICOLAGE, HEAVY);
        String day = upper(DateFormat.format("EEE d MMM", calendar));
        time.setTextAlign(Paint.Align.LEFT);
        date.setTextAlign(Paint.Align.LEFT);
        time.setLetterSpacing(-0.02f);
        date.setLetterSpacing(0.1f);
        date.setTextSize(unit * 0.044f);

        // Two lines of digits and the date must fit in the middle two thirds of the screen.
        float lineGap = 0.1f;
        float dateGap = unit * 0.05f;
        float size = unit * 0.5f;
        time.setTextSize(size);
        float block = capHeight(time) * (2f + lineGap);
        float maxBlock = height * 0.66f - capHeight(date) - dateGap;
        if (block > maxBlock) {
            size *= maxBlock / block;
        }
        time.setTextSize(size);
        float widest = Math.max(time.measureText(hours), time.measureText(minutes));
        if (widest > width * 0.8f) {
            time.setTextSize(size * width * 0.8f / widest);
        }

        float digits = capHeight(time);
        float dateHeight = capHeight(date);
        float total = dateHeight + dateGap + digits * (2f + lineGap);
        float left = width / 2f - unit * 0.40f;
        float top = height * 0.45f - total / 2f;
        canvas.drawText(day, left, top + dateHeight, date);
        float first = top + dateHeight + dateGap + digits;
        canvas.drawText(hours, left - leftBearing(time, hours), first, time);
        canvas.drawText(minutes, left - leftBearing(time, minutes), first + digits * (1f + lineGap), time);
    }

    /** Mono: a compact time, a short rule and the date, at the top left. */
    private void drawMono(Canvas canvas, int width, int height, float unit, String clock,
            Calendar calendar) {
        useFont(DM_MONO, REGULAR);
        String day = upper(DateFormat.format("EEE dd MMM", calendar));
        time.setTextAlign(Paint.Align.LEFT);
        date.setTextAlign(Paint.Align.LEFT);
        time.setLetterSpacing(-0.02f);
        date.setLetterSpacing(0.08f);
        fit(time, clock, unit * 0.15f, width * 0.6f);
        fit(date, day, unit * 0.04f, width * 0.6f);

        float left = width / 2f - unit * 0.40f;
        float top = height * 0.12f;
        float timeHeight = capHeight(time);
        float baseline = top + timeHeight;
        canvas.drawText(clock, left - leftBearing(time, clock), baseline, time);
        float ruleY = baseline + unit * 0.045f;
        rule.setStrokeWidth(Math.max(1f, unit * 0.004f));
        canvas.drawLine(left, ruleY, left + unit * 0.09f, ruleY, rule);
        canvas.drawText(day, left, ruleY + unit * 0.035f + capHeight(date), date);
    }

    private static String upper(CharSequence text) {
        return text.toString().toUpperCase(Locale.getDefault());
    }

    /** Sets {@code size}, shrinking it if {@code text} would be wider than {@code maxWidth}. */
    private static void fit(Paint paint, String text, float size, float maxWidth) {
        paint.setTextSize(size);
        float measured = paint.measureText(text);
        if (measured > maxWidth) {
            paint.setTextSize(size * maxWidth / measured);
        }
    }

    /** The height of a digit, which is what the eye reads as the text's size. */
    private float capHeight(Paint paint) {
        paint.getTextBounds("0", 0, 1, bounds);
        return -bounds.top;
    }

    /** The gap before the first glyph, so digits line up with the text above them. */
    private float leftBearing(Paint paint, String text) {
        paint.getTextBounds(text, 0, text.length(), bounds);
        return bounds.left;
    }

    /**
     * A preview of {@code face} for the face picker, over {@code background} (the lock GIF's
     * framed first frame, the same size) or a soft stand-in when there isn't one.
     */
    Bitmap thumbnail(String face, int width, int height, boolean is24Hour, Bitmap background) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        if (background != null) {
            canvas.drawBitmap(background, null, new android.graphics.RectF(0, 0, width, height), paint);
        } else {
            drawStandIn(canvas, width, height, paint);
        }
        paint.setColor(0x44000000);
        canvas.drawRect(0, 0, width, height, paint);

        Calendar sample = Calendar.getInstance();
        sample.set(Calendar.HOUR_OF_DAY, 10);
        sample.set(Calendar.MINUTE, 9);
        draw(canvas, face, width, height, 1f, sample, is24Hour);
        return bitmap;
    }

    private static void drawStandIn(Canvas canvas, int width, int height, Paint paint) {
        paint.setShader(new LinearGradient(0, 0, width * 0.4f, height,
                0xFF5B3FA8, 0xFF16304F, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height, paint);
        paint.setShader(new RadialGradient(width * 0.85f, height * 0.85f, width * 0.7f,
                0x99FF8A6E, 0x00FF8A6E, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height, paint);
        paint.setShader(null);
    }
}
