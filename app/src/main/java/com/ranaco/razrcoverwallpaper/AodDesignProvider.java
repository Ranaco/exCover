package com.ranaco.razrcoverwallpaper;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import java.util.ArrayList;

/**
 * Lists exCover's design to Motorola's clock face app, which asks every provider with the
 * CLOCK_FACE_OPTIONS action and its read permission for "list_options". The design is offered
 * for the cover lock screen, whose always-on state is the same design in its AOD style.
 */
public final class AodDesignProvider extends ContentProvider {
    static final String TEMPLATE_ID = "excover_color";
    private static final String METHOD_LIST_OPTIONS = "list_options";

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
        Icon thumbnail = Icon.createWithResource(getContext(), R.drawable.wallpaper_thumbnail);
        ArrayList<Bundle> variants = new ArrayList<>();
        for (int style : new int[]{ClockFaceProtocol.STYLE_AOD, ClockFaceProtocol.STYLE_LOCK}) {
            Bundle variant = new Bundle();
            variant.putInt("style", style);
            variant.putString("name", "exCover");
            variant.putParcelable("thumbnail", thumbnail);
            variant.putBoolean("enabled", true);
            variants.add(variant);
        }
        Bundle design = new Bundle();
        design.putString("template_id", TEMPLATE_ID);
        design.putInt("order", 0);
        design.putParcelableArrayList("variants", variants);
        ArrayList<Bundle> designs = new ArrayList<>();
        designs.add(design);

        Bundle result = new Bundle();
        result.putInt("version", 1);
        result.putParcelableArrayList("data", designs);
        Log.i(ClockFaceProtocol.TAG, "published exCover AOD + lock variants");
        return result;
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
