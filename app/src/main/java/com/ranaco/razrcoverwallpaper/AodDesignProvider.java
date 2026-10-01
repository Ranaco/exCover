package com.ranaco.razrcoverwallpaper;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.util.Log;

import com.motorola.clockface.sdk.options.ClockFaceConfigOptions;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;

/**
 * Lists exCover's designs to Motorola's clock face app, which asks every provider with the
 * CLOCK_FACE_OPTIONS action and its read permission for "list_options". Each clock face is its
 * own design for the cover lock screen, whose always-on state is the same design in its AOD
 * style.
 *
 * In Motorola's design editor each face offers a font row and Motorola's own colour picker. The
 * editor reports the chosen font and colour back to the running design.
 */
public final class AodDesignProvider extends ContentProvider {
    private static final String METHOD_LIST_OPTIONS = "list_options";
    /** The cover's 1080 x 1272 shape, small enough to keep the reply well under binder limits. */
    private static final int THUMB_WIDTH = 162;
    private static final int THUMB_HEIGHT = 191;
    private static final int SAMPLE_WIDTH = 150;
    private static final int SAMPLE_HEIGHT = 72;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (!METHOD_LIST_OPTIONS.equals(method)) {
            return super.call(method, arg, extras);
        }
        Log.i(ClockFaceProtocol.TAG, "clock-face options requested by " + getCallingPackage());
        ClockFace face = new ClockFace(getContext());
        boolean is24Hour = DateFormat.is24HourFormat(getContext());
        ArrayList<Bundle> designs = new ArrayList<>();
        for (int i = 0; i < ClockFace.KEYS.length; i++) {
            String key = ClockFace.KEYS[i];
            Icon thumbnail = png(face.thumbnail(key, THUMB_WIDTH, THUMB_HEIGHT, is24Hour, null));
            ClockFaceConfigOptions.IconAndTextConfigOptions options =
                    ClockFace.NONE.equals(key) ? null : fontAndColour(face, key);
            ArrayList<Bundle> variants = new ArrayList<>();
            for (int style : new int[]{ClockFaceProtocol.STYLE_AOD, ClockFaceProtocol.STYLE_LOCK}) {
                Bundle variant = new Bundle();
                variant.putInt("style", style);
                variant.putString("name", "exCover " + ClockFace.LABELS[i]);
                variant.putParcelable("thumbnail", thumbnail);
                variant.putBoolean("enabled", true);
                if (options != null) {
                    variant.putParcelable("config_options", options);
                }
                variants.add(variant);
            }
            Bundle design = new Bundle();
            design.putString("template_id", ClockFace.TEMPLATES[i]);
            design.putInt("order", i);
            design.putParcelableArrayList("variants", variants);
            designs.add(design);
        }

        Bundle result = new Bundle();
        result.putInt("version", 1);
        result.putParcelableArrayList("data", designs);
        Log.i(ClockFaceProtocol.TAG, "published " + designs.size() + " exCover designs");
        return result;
    }

    /** The font row, with each font as a "123" sample at this face's weight, plus colour. */
    private ClockFaceConfigOptions.IconAndTextConfigOptions fontAndColour(ClockFace face, String key) {
        Icon[] samples = new Icon[ClockFace.FONT_KEYS.length];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = png(face.fontSample(ClockFace.FONT_KEYS[i], key, SAMPLE_WIDTH, SAMPLE_HEIGHT));
        }
        return new ClockFaceConfigOptions.IconAndTextConfigOptions(ClockFace.FONT_OPTION, "Font",
                true, ClockFace.defaultFont(key), ClockFace.FONT_KEYS, samples);
    }

    /** Sent as PNG data so the reply stays small. */
    private static Icon png(Bitmap bitmap) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        bitmap.recycle();
        byte[] data = out.toByteArray();
        return Icon.createWithData(data, 0, data.length);
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.dir/clock_faces";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
