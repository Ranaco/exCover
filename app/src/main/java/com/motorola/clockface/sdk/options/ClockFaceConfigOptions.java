package com.motorola.clockface.sdk.options;

import android.graphics.drawable.Icon;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Writes one design setting in the form Motorola's clock face app reads from a design's
 * "config_options". The host unparcels it by class name, so this class and its nested class
 * carry the names the host expects; only the parcel layout matters, and exCover never reads
 * one back.
 */
public final class ClockFaceConfigOptions {
    private ClockFaceConfigOptions() {}

    /**
     * A keyed list of choices shown as a row of thumbnails. With {@code customColor} set, the
     * editor also offers its own colour picker and reports the colour alongside the choice.
     */
    public static final class IconAndTextConfigOptions implements Parcelable {
        public static final Creator<IconAndTextConfigOptions> CREATOR = new Creator<IconAndTextConfigOptions>() {
            @Override
            public IconAndTextConfigOptions createFromParcel(Parcel in) {
                throw new UnsupportedOperationException("only written for the clock face app");
            }

            @Override
            public IconAndTextConfigOptions[] newArray(int size) {
                return new IconAndTextConfigOptions[size];
            }
        };

        private final String key;
        private final String description;
        private final boolean customColor;
        private final String defaultKey;
        private final String[] itemKeys;
        private final Icon[] itemIcons;

        public IconAndTextConfigOptions(String key, String description, boolean customColor,
                String defaultKey, String[] itemKeys, Icon[] itemIcons) {
            this.key = key;
            this.description = description;
            this.customColor = customColor;
            this.defaultKey = defaultKey;
            this.itemKeys = itemKeys;
            this.itemIcons = itemIcons;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            // Common option fields: key, icon, description, custom colour support.
            out.writeString(key);
            out.writeValue(null);
            out.writeValue(description);
            out.writeByte((byte) (customColor ? 1 : 0));
            // Keyed choices: the default key, then a typed array of items (key, icon, label).
            out.writeString(defaultKey);
            out.writeInt(itemKeys.length);
            for (int i = 0; i < itemKeys.length; i++) {
                out.writeInt(1); // non-null item
                out.writeString(itemKeys[i]);
                out.writeValue(itemIcons[i]);
                out.writeValue(null); // no label; the icon shows the font
            }
            out.writeBoolean(false); // not round icons
        }
    }
}
