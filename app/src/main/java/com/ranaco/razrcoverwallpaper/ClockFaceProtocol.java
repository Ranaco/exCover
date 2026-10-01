package com.ranaco.razrcoverwallpaper;

import android.content.res.Configuration;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;
import android.view.SurfaceControlViewHost;

/**
 * The binder protocol Motorola's clock face app (com.motorola.clockface) uses to host a design
 * from another app on the cover screen. Written from the interface's names, call codes and data
 * layouts so exCover can take part without any of Motorola's code.
 *
 * All calls are one-way. Each starts with the interface descriptor; "typed" objects are an int
 * 1 (0 for null) followed by the object's fields; a nested Parcelable is its class name followed
 * by its fields.
 */
final class ClockFaceProtocol {
    static final String TAG = "exCoverAod";

    static final String SERVICE = "com.motorola.clockface.sdk.session.IClockFaceService";
    static final String SERVICE_CONTROLLER = "com.motorola.clockface.sdk.session.IClockFaceServiceController";
    static final String SESSION_SERVICE = "com.motorola.clockface.sdk.session.IClockFaceSessionService";
    static final String SESSION = "com.motorola.clockface.sdk.session.IClockFaceSession";
    static final String FUTURE = "com.motorola.clockface.sdk.misc.IAndroidFuture";
    private static final String CREATE_RESULT_CLASS = "com.motorola.clockface.sdk.session.CreateClockFaceSessionResult";

    static final String ACTION_SERVICE = "com.motorola.service.action.CLOCK_FACE_SERVICE";
    static final String ACTION_SESSION_SERVICE = "com.motorola.service.action.CLOCK_FACE_SESSION_SERVICE";

    /** Design styles: the cover lock screen's always-on state, the cover lock screen, the cover home screen. */
    static final int STYLE_AOD = 1;
    static final int STYLE_LOCK = 2;
    static final int STYLE_HOME = 3;

    // IClockFaceService
    static final int SERVICE_CONNECTED = 1;
    static final int SERVICE_DISCONNECTED = 2;
    static final int SERVICE_CLOCK_FACE_STARTED = 3;
    // IClockFaceSession
    static final int SESSION_VISIBILITY = 1;
    static final int SESSION_VIEW_CONFIGURATION = 2;
    static final int SESSION_OPTION_VALUES = 3;
    static final int SESSION_DESTROYED = 4;
    static final int SESSION_TIME_TICK = 6;
    static final int SESSION_STYLE = 7;
    static final int SESSION_STYLE_ANIMATION = 11;
    static final int SESSION_LOCK_STATE = 13;

    private static final int VAL_PARCELABLE = 4;

    private ClockFaceProtocol() {}

    static final class SessionId {
        String uniqueId;
        String templateId;
        String instanceId;
        int style;

        /** A typed object whose only field is a nested ClockFaceSessionId (the started event, the create request). */
        static SessionId readWrapped(Parcel in) {
            if (in.readInt() == 0) {
                return null;
            }
            in.readString(); // nested class name
            SessionId id = new SessionId();
            id.uniqueId = in.readString();
            id.templateId = in.readString();
            id.instanceId = in.readString();
            id.style = in.readInt();
            return id;
        }

        /** Written back exactly as received: the host matches on all four fields. */
        void writeTyped(Parcel out) {
            out.writeInt(1);
            out.writeString(uniqueId);
            out.writeString(templateId);
            out.writeString(instanceId);
            out.writeInt(style);
        }

        @Override
        public String toString() {
            return templateId + "/" + instanceId + " style " + style;
        }
    }

    static final class ViewConfig {
        int displayId;
        int width;
        int height;
        int modeFlags;

        static ViewConfig readTyped(Parcel in) {
            if (in.readInt() == 0) {
                return null;
            }
            ViewConfig config = new ViewConfig();
            config.displayId = in.readInt();
            config.width = in.readInt();
            config.height = in.readInt();
            in.readParcelable(Configuration.class.getClassLoader()); // preview configuration, unused
            config.modeFlags = in.readInt();
            return config;
        }

        boolean inPreview() {
            return (modeFlags & 2) != 0;
        }

        @Override
        public String toString() {
            return "display " + displayId + " " + width + "x" + height + " flags " + modeFlags;
        }
    }

    /** A design's current setting values as the editor reports them. */
    static final class OptionValues {
        /** The value of each keyed setting, by key. */
        final java.util.Map<String, String> keyed = new java.util.HashMap<>();
        /** The colour chosen in the editor's colour picker, or null if none has been chosen. */
        Integer color;

        @Override
        public String toString() {
            return keyed + " color " + (color == null ? "none" : Integer.toHexString(color));
        }
    }

    /**
     * Reads a typed ClockFaceOptionRuntimeValue: the template id, an array of indexed values, an
     * optional colour, then an array of key/value pairs. Each array element and the colour are
     * written with their class name first.
     */
    static OptionValues readOptionValues(Parcel in) {
        OptionValues values = new OptionValues();
        try {
            if (in.readInt() == 0) {
                return values;
            }
            in.readString(); // template id
            int indexed = in.readInt();
            for (int i = 0; i < indexed; i++) {
                if (in.readString() != null) { // IndexedRuntimeValue: key, index
                    in.readString();
                    in.readInt();
                }
            }
            if (in.readString() != null) { // ColorRuntimeValue: static flag, colour
                in.readByte();
                values.color = in.readInt();
            }
            int pairs = in.readInt();
            for (int i = 0; i < pairs; i++) {
                if (in.readString() != null) { // KeyValueRuntimeValue: key, value
                    String key = in.readString();
                    values.keyed.put(key, in.readString());
                }
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "could not read design setting values", error);
        }
        return values;
    }

    /** Asks the host to go ahead and create the session it just announced. */
    static void requestSession(IBinder controller, SessionId id) {
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(SERVICE_CONTROLLER);
            id.writeTyped(data);
            controller.transact(1, data, null, IBinder.FLAG_ONEWAY);
        } catch (RemoteException error) {
            Log.w(TAG, "host went away before the session was created", error);
        } finally {
            data.recycle();
        }
    }

    /** Hands the host our session and its surface, completing the future it passed to create(). */
    static void completeCreate(IBinder future, IBinder session, SurfaceControlViewHost.SurfacePackage surface) {
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(FUTURE);
            data.writeInt(1); // non-null future
            data.writeInt(1); // done
            data.writeInt(0); // not exceptional
            // Parcel.writeValue of a Parcelable: type, length, then class name and fields.
            data.writeInt(VAL_PARCELABLE);
            int lengthAt = data.dataPosition();
            data.writeInt(-1);
            int start = data.dataPosition();
            data.writeString(CREATE_RESULT_CLASS);
            data.writeStrongBinder(session);
            data.writeParcelable(surface, 0);
            int end = data.dataPosition();
            data.setDataPosition(lengthAt);
            data.writeInt(end - start);
            data.setDataPosition(end);
            boolean delivered = future.transact(1, data, null, IBinder.FLAG_ONEWAY);
            Log.i(TAG, "session completion sent to host: " + delivered);
        } catch (RemoteException | RuntimeException error) {
            Log.w(TAG, "session could not be handed to the host", error);
        } finally {
            data.recycle();
        }
    }
}
