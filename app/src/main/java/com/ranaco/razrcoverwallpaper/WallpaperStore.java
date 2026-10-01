package com.ranaco.razrcoverwallpaper;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Stores one wallpaper per target. The draft is what the editor shows; applying copies the draft
 * to the home and/or lock targets, which the matching cover-screen engines render.
 */
public final class WallpaperStore {
    public static final String PREFS = "cover_wallpaper";

    public static final String TARGET_DRAFT = "draft";
    public static final String TARGET_HOME = "home";
    public static final String TARGET_LOCK = "lock";
    /** The phone's own (inner) display, set through Android's live-wallpaper picker. */
    public static final String TARGET_MAIN_HOME = "main";
    public static final String TARGET_MAIN_LOCK = "mainlock";
    /** Draft framing for the taller main-screen shape, kept apart from the cover framing. */
    private static final String MAIN_CROP = "main_";

    public static final String KEY_SOURCE = "source";
    public static final String KEY_REVISION = "revision";
    public static final String KEY_NAME = "name";
    public static final String KEY_BYTES = "bytes";
    public static final String KEY_WIDTH = "width";
    public static final String KEY_HEIGHT = "height";
    public static final String KEY_DURATION = "duration";
    public static final String KEY_ZOOM = "crop_zoom";
    public static final String KEY_FOCUS_X = "crop_focus_x";
    public static final String KEY_FOCUS_Y = "crop_focus_y";
    private static final String KEY_ORIGIN = "from_revision";
    public static final String KEY_RECENT_ID = "recent_id";
    /** When set, the draft reads a recent's file in place instead of a copy. */
    private static final String KEY_FILE = "file";
    private static final String KEY_RECENTS = "recents";
    private static final String RECENTS_FOLDER = "recents";
    private static final int MAX_RECENTS = 5;

    public static final String SOURCE_CITY = "bundled_city";
    public static final String SOURCE_MICRO = "bundled_micro";
    public static final String SOURCE_CUSTOM = "custom";

    public static final long MAX_GIF_BYTES = 50L * 1024L * 1024L;
    private static final String CUSTOM_FOLDER = "wallpapers";
    private static final String LEGACY_FILE = "active.gif";
    private static final String KEY_SCHEMA = "schema";
    private static final int SCHEMA = 3;
    private static final String[] TARGETS = {TARGET_DRAFT, TARGET_HOME, TARGET_LOCK};
    private static final String[] FIELDS = {
            KEY_SOURCE, KEY_NAME, KEY_BYTES, KEY_WIDTH, KEY_HEIGHT, KEY_DURATION,
            KEY_ZOOM, KEY_FOCUS_X, KEY_FOCUS_Y, KEY_RECENT_ID};

    private WallpaperStore() {}

    public static SharedPreferences preferences(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        migrate(context, prefs);
        return prefs;
    }

    public static String key(String target, String field) {
        return target + "_" + field;
    }

    public static String selectedSource(Context context, String target) {
        return preferences(context).getString(key(target, KEY_SOURCE), SOURCE_CITY);
    }

    public static File gifFile(Context context, String target) {
        if (TARGET_DRAFT.equals(target)) {
            String path = preferences(context).getString(key(TARGET_DRAFT, KEY_FILE), null);
            if (path != null && new File(path).exists()) {
                return new File(path);
            }
        }
        return new File(new File(context.getFilesDir(), CUSTOM_FOLDER), target + ".gif");
    }

    /** The one built-in loop, an original animation used until the first GIF is imported. */
    static int bundledResource(String source) {
        return R.raw.default_loop;
    }

    /** Puts the default loop back in the editor, used once the last saved GIF is deleted. */
    public static void resetDraft(Context context) {
        preferences(context).edit()
                .remove(key(TARGET_DRAFT, KEY_FILE))
                .putString(key(TARGET_DRAFT, KEY_SOURCE), SOURCE_CITY)
                .putString(key(TARGET_DRAFT, KEY_NAME), bundledName(SOURCE_CITY))
                .remove(key(TARGET_DRAFT, KEY_RECENT_ID))
                .remove(key(TARGET_DRAFT, KEY_BYTES))
                .remove(key(TARGET_DRAFT, KEY_WIDTH))
                .remove(key(TARGET_DRAFT, KEY_HEIGHT))
                .remove(key(TARGET_DRAFT, KEY_DURATION))
                .putFloat(key(TARGET_DRAFT, KEY_ZOOM), 1f)
                .putFloat(key(TARGET_DRAFT, KEY_FOCUS_X), 0.5f)
                .putFloat(key(TARGET_DRAFT, KEY_FOCUS_Y), 0.5f)
                .putFloat(key(TARGET_DRAFT, MAIN_CROP + KEY_ZOOM), 1f)
                .putFloat(key(TARGET_DRAFT, MAIN_CROP + KEY_FOCUS_X), 0.5f)
                .putFloat(key(TARGET_DRAFT, MAIN_CROP + KEY_FOCUS_Y), 0.5f)
                .putLong(key(TARGET_DRAFT, KEY_REVISION), System.currentTimeMillis())
                .commit();
    }

    /** Copies the draft to each chosen target; the matching cover engines reload on their own. */
    public static void apply(Context context, boolean home, boolean lock) throws IOException {
        if (home) {
            copyTarget(context, TARGET_DRAFT, TARGET_HOME);
        }
        if (lock) {
            copyTarget(context, TARGET_DRAFT, TARGET_LOCK);
        }
    }

    /** Copies the draft to a main-screen target, using the framing made for that shape. */
    public static void applyMain(Context context, String target) throws IOException {
        copyTarget(context, TARGET_DRAFT, target);
        Crop crop = crop(context, TARGET_DRAFT, true);
        preferences(context).edit()
                .putFloat(key(target, KEY_ZOOM), crop.zoom)
                .putFloat(key(target, KEY_FOCUS_X), crop.focusX)
                .putFloat(key(target, KEY_FOCUS_Y), crop.focusY)
                .putLong(key(target, KEY_REVISION), System.currentTimeMillis())
                .commit();
    }

    /** Whether the Set Wallpaper sheet last targeted the main screen rather than the cover. */
    public static boolean lastSetOnMain(Context context) {
        return preferences(context).getBoolean("set_on_main", false);
    }

    public static void saveLastSetOnMain(Context context, boolean onMain) {
        preferences(context).edit().putBoolean("set_on_main", onMain).apply();
    }

    static final String KEY_AOD_ENABLED = "aod_enabled";
    static final String KEY_AOD_LOOK = "aod_look";

    /** The look the cover AOD gives the lock GIF's frame; see {@link AodLook}. */
    public static String aodLook(Context context) {
        String look = preferences(context).getString(KEY_AOD_LOOK, AodLook.VIGNETTE);
        return AodLook.isLook(look) ? look : AodLook.VIGNETTE;
    }

    public static void saveAodLook(Context context, String look) {
        preferences(context).edit().putString(KEY_AOD_LOOK, look).apply();
    }

    /** Whether the cover AOD shows the dimmed lock GIF; off leaves it black. */
    public static boolean aodEnabled(Context context) {
        return preferences(context).getBoolean(KEY_AOD_ENABLED, true);
    }

    public static void saveAodEnabled(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(KEY_AOD_ENABLED, enabled).apply();
    }

    public static ImportResult importGif(
            Context context,
            InputStream input,
            String displayName) throws IOException {
        File directory = new File(context.getFilesDir(), CUSTOM_FOLDER);
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Could not create local wallpaper storage.");
        }

        // Always the draft's own file: while browsing, gifFile() points at a recent, which an
        // import must never overwrite.
        File destination = new File(directory, TARGET_DRAFT + ".gif");
        File temporary = new File(directory, TARGET_DRAFT + ".gif.tmp");
        long total = 0;
        byte[] buffer = new byte[16 * 1024];
        try (InputStream source = input; FileOutputStream output = new FileOutputStream(temporary)) {
            int read;
            while ((read = source.read(buffer)) != -1) {
                total += read;
                if (total > MAX_GIF_BYTES) {
                    throw new IOException("GIF is larger than 50 MB");
                }
                output.write(buffer, 0, read);
            }
            output.getFD().sync();
        } catch (Exception error) {
            temporary.delete();
            if (error instanceof IOException) {
                throw (IOException) error;
            }
            throw new IOException("Could not save this GIF.", error);
        }

        if (total < 6 || !hasGifHeader(temporary)) {
            temporary.delete();
            throw new IOException("That file isn't a valid GIF");
        }

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(temporary.getPath(), bounds);
        int width = bounds.outWidth;
        int height = bounds.outHeight;
        if (width <= 0 || height <= 0) {
            temporary.delete();
            throw new IOException("Android couldn't decode this GIF");
        }
        if (width > 4096 || height > 4096) {
            temporary.delete();
            throw new IOException("GIF is larger than 4096 × 4096");
        }
        int duration = GifDecoder.durationMs(temporary);
        moveReplacing(temporary, destination);

        String safeName = displayName == null || displayName.trim().isEmpty()
                ? "Imported GIF"
                : displayName.trim();
        preferences(context).edit()
                .remove(key(TARGET_DRAFT, KEY_FILE))
                .putString(key(TARGET_DRAFT, KEY_SOURCE), SOURCE_CUSTOM)
                .putString(key(TARGET_DRAFT, KEY_NAME), safeName)
                .putLong(key(TARGET_DRAFT, KEY_BYTES), total)
                .putInt(key(TARGET_DRAFT, KEY_WIDTH), width)
                .putInt(key(TARGET_DRAFT, KEY_HEIGHT), height)
                .putInt(key(TARGET_DRAFT, KEY_DURATION), duration)
                .putFloat(key(TARGET_DRAFT, KEY_ZOOM), 1f)
                .putFloat(key(TARGET_DRAFT, KEY_FOCUS_X), 0.5f)
                .putFloat(key(TARGET_DRAFT, KEY_FOCUS_Y), 0.5f)
                .putFloat(key(TARGET_DRAFT, MAIN_CROP + KEY_ZOOM), 1f)
                .putFloat(key(TARGET_DRAFT, MAIN_CROP + KEY_FOCUS_X), 0.5f)
                .putFloat(key(TARGET_DRAFT, MAIN_CROP + KEY_FOCUS_Y), 0.5f)
                .putLong(key(TARGET_DRAFT, KEY_REVISION), System.currentTimeMillis())
                .commit();
        remember(context, destination, TARGET_DRAFT, safeName, total, width, height, duration);
        return new ImportResult(safeName, total, width, height, duration);
    }

    public static void saveDraftCrop(Context context, float zoom, float focusX, float focusY) {
        preferences(context).edit()
                .putFloat(key(TARGET_DRAFT, KEY_ZOOM), clamp(zoom, 1f, 5f))
                .putFloat(key(TARGET_DRAFT, KEY_FOCUS_X), clamp(focusX, 0f, 1f))
                .putFloat(key(TARGET_DRAFT, KEY_FOCUS_Y), clamp(focusY, 0f, 1f))
                .apply();
    }

    public static Crop crop(Context context, String target) {
        return crop(context, target, false);
    }

    /** @param mainShape for the draft, read the framing made for the main screen's shape. */
    public static Crop crop(Context context, String target, boolean mainShape) {
        SharedPreferences prefs = preferences(context);
        String prefix = mainShape && TARGET_DRAFT.equals(target) ? MAIN_CROP : "";
        return new Crop(
                prefs.getFloat(key(target, prefix + KEY_ZOOM), 1f),
                prefs.getFloat(key(target, prefix + KEY_FOCUS_X), 0.5f),
                prefs.getFloat(key(target, prefix + KEY_FOCUS_Y), 0.5f));
    }

    public static void saveDraftMainCrop(Context context, float zoom, float focusX, float focusY) {
        preferences(context).edit()
                .putFloat(key(TARGET_DRAFT, MAIN_CROP + KEY_ZOOM), clamp(zoom, 1f, 5f))
                .putFloat(key(TARGET_DRAFT, MAIN_CROP + KEY_FOCUS_X), clamp(focusX, 0f, 1f))
                .putFloat(key(TARGET_DRAFT, MAIN_CROP + KEY_FOCUS_Y), clamp(focusY, 0f, 1f))
                .apply();
    }

    public static String selectionName(Context context, String target) {
        String source = selectedSource(context, target);
        if (!SOURCE_CUSTOM.equals(source)) {
            return bundledName(source);
        }
        return preferences(context).getString(key(target, KEY_NAME), "Imported GIF");
    }

    public static String selectionDetail(Context context, String target) {
        if (!SOURCE_CUSTOM.equals(selectedSource(context, target))) {
            return "Built-in loop";
        }
        SharedPreferences prefs = preferences(context);
        return String.format(
                Locale.US,
                "%d × %d · %.1f s · %.1f MB",
                prefs.getInt(key(target, KEY_WIDTH), 0),
                prefs.getInt(key(target, KEY_HEIGHT), 0),
                prefs.getInt(key(target, KEY_DURATION), 0) / 1000f,
                prefs.getLong(key(target, KEY_BYTES), 0) / (1024f * 1024f));
    }

    /** Recently imported GIFs, most recent first. */
    public static List<Recent> recents(Context context) {
        seedRecents(context);
        List<Recent> result = new ArrayList<>();
        try {
            JSONArray items = new JSONArray(preferences(context).getString(KEY_RECENTS, "[]"));
            for (int index = 0; index < items.length(); index++) {
                JSONObject item = items.getJSONObject(index);
                Recent recent = new Recent(
                        item.getString("id"),
                        item.optString("name", "GIF"),
                        item.optLong("bytes"),
                        item.optInt("width"),
                        item.optInt("height"),
                        item.optInt("duration"),
                        item.optBoolean("favorite"));
                if (recentFile(context, recent.id).exists()) {
                    result.add(recent);
                }
            }
        } catch (Exception ignored) {
            // A damaged list only costs the history, never the active wallpaper.
        }
        return result;
    }

    public static File recentFile(Context context, String id) {
        return new File(new File(context.getFilesDir(), RECENTS_FOLDER), id + ".gif");
    }

    /** Loads a recent GIF into the editor, leaving the order of the list unchanged. */
    public static void loadRecent(Context context, String id) throws IOException {
        Recent recent = null;
        for (Recent candidate : recents(context)) {
            if (candidate.id.equals(id)) {
                recent = candidate;
                break;
            }
        }
        if (recent == null) {
            throw new IOException("That GIF is no longer saved");
        }
        preferences(context).edit()
                .putString(key(TARGET_DRAFT, KEY_FILE), recentFile(context, id).getPath())
                .putString(key(TARGET_DRAFT, KEY_SOURCE), SOURCE_CUSTOM)
                .putString(key(TARGET_DRAFT, KEY_NAME), recent.name)
                .putString(key(TARGET_DRAFT, KEY_RECENT_ID), recent.id)
                .putLong(key(TARGET_DRAFT, KEY_BYTES), recent.bytes)
                .putInt(key(TARGET_DRAFT, KEY_WIDTH), recent.width)
                .putInt(key(TARGET_DRAFT, KEY_HEIGHT), recent.height)
                .putInt(key(TARGET_DRAFT, KEY_DURATION), recent.durationMs)
                .putFloat(key(TARGET_DRAFT, KEY_ZOOM), 1f)
                .putFloat(key(TARGET_DRAFT, KEY_FOCUS_X), 0.5f)
                .putFloat(key(TARGET_DRAFT, KEY_FOCUS_Y), 0.5f)
                .putFloat(key(TARGET_DRAFT, MAIN_CROP + KEY_ZOOM), 1f)
                .putFloat(key(TARGET_DRAFT, MAIN_CROP + KEY_FOCUS_X), 0.5f)
                .putFloat(key(TARGET_DRAFT, MAIN_CROP + KEY_FOCUS_Y), 0.5f)
                .putLong(key(TARGET_DRAFT, KEY_REVISION), System.currentTimeMillis())
                .commit();
        // Browsing must not reorder the list, or the carousel would jump under the user's finger.
    }

    public static boolean isFavorite(Context context, String id) {
        for (Recent recent : recents(context)) {
            if (recent.id.equals(id)) {
                return recent.favorite;
            }
        }
        return false;
    }

    /** Favourites stay in the carousel permanently and never count toward the history limit. */
    public static void setFavorite(Context context, String id, boolean favorite) {
        List<Recent> updated = new ArrayList<>();
        for (Recent recent : recents(context)) {
            updated.add(recent.id.equals(id) ? recent.withFavorite(favorite) : recent);
        }
        saveRecents(context, updated);
    }

    /** Copies the editor's GIF into the shared Pictures/exCover album so it shows up in Photos. */
    public static void saveDraftToPhotos(Context context) throws IOException {
        if (!SOURCE_CUSTOM.equals(selectedSource(context, TARGET_DRAFT))) {
            throw new IOException("Built-in loops can't be saved");
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw new IOException("Saving to Photos needs Android 10 or newer");
        }
        String name = preferences(context).getString(key(TARGET_DRAFT, KEY_NAME), "exCover");
        String fileName = name.replaceAll("[^A-Za-z0-9 _-]", "").trim();
        if (fileName.isEmpty()) {
            fileName = "exCover";
        }
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName + ".gif");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/gif");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/exCover");
        values.put(MediaStore.Images.Media.IS_PENDING, 1);
        ContentResolver resolver = context.getContentResolver();
        Uri item = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (item == null) {
            throw new IOException("Photos couldn't create the file");
        }
        try (InputStream input = new FileInputStream(gifFile(context, TARGET_DRAFT));
             OutputStream output = resolver.openOutputStream(item)) {
            if (output == null) {
                throw new IOException("Photos couldn't open the file");
            }
            input.transferTo(output);
        } catch (IOException error) {
            resolver.delete(item, null, null);
            throw error;
        }
        values.clear();
        values.put(MediaStore.Images.Media.IS_PENDING, 0);
        resolver.update(item, values, null, null);
    }

    public static void removeRecent(Context context, String id) {
        List<Recent> kept = new ArrayList<>();
        for (Recent recent : recents(context)) {
            if (recent.id.equals(id)) {
                recentFile(context, id).delete();
            } else {
                kept.add(recent);
            }
        }
        saveRecents(context, kept);
    }

    /** Before recents existed, imported GIFs lived only in the targets; start the list from them. */
    private static void seedRecents(Context context) {
        SharedPreferences prefs = preferences(context);
        if (prefs.contains(KEY_RECENTS)) {
            return;
        }
        prefs.edit().putString(KEY_RECENTS, "[]").commit();
        for (String target : new String[]{TARGET_LOCK, TARGET_HOME, TARGET_DRAFT}) {
            if (SOURCE_CUSTOM.equals(prefs.getString(key(target, KEY_SOURCE), SOURCE_CITY))
                    && gifFile(context, target).exists()) {
                remember(context, target);
            }
        }
    }

    /** Seeds a recent from a target's stored details (used for GIFs imported before recents existed). */
    private static void remember(Context context, String target) {
        SharedPreferences prefs = preferences(context);
        remember(context, gifFile(context, target), target,
                prefs.getString(key(target, KEY_NAME), "GIF"),
                prefs.getLong(key(target, KEY_BYTES), 0),
                prefs.getInt(key(target, KEY_WIDTH), 0),
                prefs.getInt(key(target, KEY_HEIGHT), 0),
                prefs.getInt(key(target, KEY_DURATION), 0));
    }

    /**
     * Keeps a content-addressed copy of {@code file} so it can be picked again without a download.
     * The details describe exactly this file, so an entry can never be relabelled by another GIF.
     */
    private static void remember(Context context, File file, String target, String name,
                                 long bytes, int width, int height, int duration) {
        try {
            File draft = file;
            String id = sha1(draft);
            File copy = recentFile(context, id);
            if (!copy.exists()) {
                copy.getParentFile().mkdirs();
                File temporary = new File(copy.getParentFile(), id + ".gif.tmp");
                Files.copy(draft.toPath(), temporary.toPath(), StandardCopyOption.REPLACE_EXISTING);
                moveReplacing(temporary, copy);
            }
            SharedPreferences prefs = preferences(context);
            Recent recent = new Recent(id, name, bytes, width, height, duration,
                    isFavorite(context, id));
            prefs.edit().putString(key(target, KEY_RECENT_ID), id).commit();
            saveRecents(context, moveToFront(recents(context), recent));
        } catch (Exception ignored) {
            // The import itself already succeeded; history is best effort.
        }
    }

    private static List<Recent> moveToFront(List<Recent> list, Recent recent) {
        List<Recent> ordered = new ArrayList<>();
        ordered.add(recent);
        for (Recent other : list) {
            if (!other.id.equals(recent.id)) {
                ordered.add(other);
            }
        }
        return ordered;
    }

    private static void saveRecents(Context context, List<Recent> list) {
        JSONArray items = new JSONArray();
        int kept = 0;
        for (Recent recent : list) {
            if (!recent.favorite && ++kept > MAX_RECENTS) {
                recentFile(context, recent.id).delete();
                continue;
            }
            try {
                items.put(new JSONObject()
                        .put("id", recent.id)
                        .put("name", recent.name)
                        .put("bytes", recent.bytes)
                        .put("width", recent.width)
                        .put("height", recent.height)
                        .put("duration", recent.durationMs)
                        .put("favorite", recent.favorite));
            } catch (Exception ignored) {
            }
        }
        preferences(context).edit().putString(KEY_RECENTS, items.toString()).commit();
    }

    private static String sha1(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) {
            hex.append(String.format(Locale.US, "%02x", value));
        }
        return hex.toString();
    }

    private static void copyTarget(Context context, String from, String to) throws IOException {
        SharedPreferences prefs = preferences(context);
        if (SOURCE_CUSTOM.equals(prefs.getString(key(from, KEY_SOURCE), SOURCE_CITY))) {
            File destination = gifFile(context, to);
            File temporary = new File(destination.getParentFile(), to + ".gif.tmp");
            Files.copy(gifFile(context, from).toPath(), temporary.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            // The engine may be reading the old file; swap in the new one atomically.
            moveReplacing(temporary, destination);
        }
        SharedPreferences.Editor editor = prefs.edit();
        copyFields(prefs, editor, from, to);
        long now = System.currentTimeMillis();
        if (TARGET_DRAFT.equals(to)) {
            // Editing a live target: the draft becomes a fresh revision that target already matches.
            editor.putLong(key(TARGET_DRAFT, KEY_REVISION), now);
            editor.putLong(key(from, KEY_ORIGIN), now);
        } else {
            editor.putLong(key(to, KEY_ORIGIN), prefs.getLong(key(from, KEY_REVISION), 0));
            editor.putLong(key(to, KEY_REVISION), now);
        }
        editor.commit();
    }

    private static void copyFields(
            SharedPreferences prefs, SharedPreferences.Editor editor, String from, String to) {
        java.util.Map<String, ?> all = prefs.getAll();
        for (String field : FIELDS) {
            Object value = all.get(key(from, field));
            String destination = key(to, field);
            if (value == null) {
                editor.remove(destination);
            } else if (value instanceof String) {
                editor.putString(destination, (String) value);
            } else if (value instanceof Float) {
                editor.putFloat(destination, (Float) value);
            } else if (value instanceof Integer) {
                editor.putInt(destination, (Integer) value);
            } else if (value instanceof Long) {
                editor.putLong(destination, (Long) value);
            }
        }
    }

    /** Moves the single-wallpaper layout from earlier versions onto all three targets. */
    private static void migrate(Context context, SharedPreferences prefs) {
        if (prefs.getInt(KEY_SCHEMA, 0) >= SCHEMA) {
            return;
        }
        synchronized (WallpaperStore.class) {
            if (prefs.getInt(KEY_SCHEMA, 0) >= SCHEMA) {
                return;
            }
            File directory = new File(context.getFilesDir(), CUSTOM_FOLDER);
            File legacy = new File(directory, LEGACY_FILE);
            String source = prefs.getString(KEY_SOURCE, SOURCE_CITY);
            if (SOURCE_CUSTOM.equals(source) && !legacy.exists()) {
                source = SOURCE_CITY;
            }
            long revision = System.currentTimeMillis();
            SharedPreferences.Editor editor = prefs.edit();
            for (String target : TARGETS) {
                if (SOURCE_CUSTOM.equals(source)) {
                    try {
                        Files.copy(legacy.toPath(), gifFile(context, target).toPath(),
                                StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException error) {
                        source = SOURCE_CITY;
                    }
                }
                editor.putString(key(target, KEY_SOURCE), source)
                        .putString(key(target, KEY_NAME), SOURCE_CUSTOM.equals(source)
                                ? prefs.getString(KEY_NAME, "Imported GIF")
                                : bundledName(source))
                        .putLong(key(target, KEY_BYTES), prefs.getLong(KEY_BYTES, 0))
                        .putInt(key(target, KEY_WIDTH), prefs.getInt(KEY_WIDTH, 0))
                        .putInt(key(target, KEY_HEIGHT), prefs.getInt(KEY_HEIGHT, 0))
                        .putInt(key(target, KEY_DURATION), prefs.getInt(KEY_DURATION, 0))
                        .putFloat(key(target, KEY_ZOOM), prefs.getFloat(KEY_ZOOM, 1f))
                        .putFloat(key(target, KEY_FOCUS_X), prefs.getFloat(KEY_FOCUS_X, 0.5f))
                        .putFloat(key(target, KEY_FOCUS_Y), prefs.getFloat(KEY_FOCUS_Y, 0.5f))
                        .putLong(key(target, KEY_REVISION), revision)
                        .putLong(key(target, KEY_ORIGIN), revision);
            }
            for (String field : FIELDS) {
                editor.remove(field);
            }
            editor.remove(KEY_REVISION).remove("scene").putInt(KEY_SCHEMA, SCHEMA).commit();
            legacy.delete();
        }
    }

    private static String bundledName(String source) {
        return "Aurora";
    }

    private static void moveReplacing(File from, File to) throws IOException {
        try {
            Files.move(from.toPath(), to.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean hasGifHeader(File file) throws IOException {
        byte[] header = new byte[6];
        try (InputStream input = new FileInputStream(file)) {
            int offset = 0;
            while (offset < header.length) {
                int read = input.read(header, offset, header.length - offset);
                if (read < 0) {
                    return false;
                }
                offset += read;
            }
        }
        String signature = new String(header, java.nio.charset.StandardCharsets.US_ASCII);
        return "GIF87a".equals(signature) || "GIF89a".equals(signature);
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    public static final class Recent {
        public final String id;
        public final String name;
        public final long bytes;
        public final int width;
        public final int height;
        public final int durationMs;
        public final boolean favorite;

        Recent(String id, String name, long bytes, int width, int height, int durationMs,
               boolean favorite) {
            this.id = id;
            this.name = name;
            this.bytes = bytes;
            this.width = width;
            this.height = height;
            this.durationMs = durationMs;
            this.favorite = favorite;
        }

        Recent withFavorite(boolean value) {
            return new Recent(id, name, bytes, width, height, durationMs, value);
        }
    }

    public static final class Crop {
        public final float zoom;
        public final float focusX;
        public final float focusY;

        public Crop(float zoom, float focusX, float focusY) {
            this.zoom = zoom;
            this.focusX = focusX;
            this.focusY = focusY;
        }
    }

    public static final class ImportResult {
        public final String name;
        public final long bytes;
        public final int width;
        public final int height;
        public final int durationMs;

        ImportResult(String name, long bytes, int width, int height, int durationMs) {
            this.name = name;
            this.bytes = bytes;
            this.width = width;
            this.height = height;
            this.durationMs = durationMs;
        }
    }
}
