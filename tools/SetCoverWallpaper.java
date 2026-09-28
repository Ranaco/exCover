import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.Bundle;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;

/** Runs under ADB's shell UID so it can target Motorola's dedicated CLI wallpaper slot. */
public final class SetCoverWallpaper {
    private static final int FLAG_CLI_HOME = 4;
    private static final int FLAG_CLI_LOCK = 16;

    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
        Object activityThread = activityThreadClass.getMethod("systemMain").invoke(null);
        Context systemContext = (Context) activityThreadClass
                .getMethod("getSystemContext")
                .invoke(activityThread);
        Context shellContext = new ContextWrapper(systemContext) {
            @Override
            public String getPackageName() {
                return "com.android.shell";
            }

            @Override
            public String getOpPackageName() {
                return "com.android.shell";
            }
        };
        WallpaperManager wallpaperManager = WallpaperManager.getInstance(shellContext);

        if (args.length == 3 && "backup".equals(args[0])) {
            backup(wallpaperManager, parseWhich(args[1]), args[2]);
            return;
        }
        if (args.length == 3 && "restore".equals(args[0])) {
            restore(parseWhich(args[1]), args[2]);
            return;
        }
        if (args.length == 2 && "clear".equals(args[0])) {
            int which = parseWhich(args[1]);
            clearWallpaper(which);
            System.out.println("wallpaper slot " + which + " cleared");
            return;
        }
        if (args.length == 2 && "transparent".equals(args[0])) {
            int which = parseWhich(args[1]);
            Bitmap transparent = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
            transparent.eraseColor(0x00000000);
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            if (!transparent.compress(Bitmap.CompressFormat.PNG, 100, encoded)) {
                throw new IllegalStateException("Could not encode transparent wallpaper");
            }
            transparent.recycle();
            writeStaticWallpaper(
                    which,
                    "excover-transparent",
                    new ByteArrayInputStream(encoded.toByteArray()));
            System.out.println("transparent wallpaper written to slot " + which);
            return;
        }
        if (args.length != 4 || !"set".equals(args[0])) {
            throw new IllegalArgumentException(
                    "Use: backup <home|lock> <path> | restore <home|lock> <path> | "
                            + "clear <home|lock> | transparent <home|lock> | "
                            + "set <home|lock|both|phone> <package> <service-class>");
        }

        if ("both".equals(args[1])) {
            setLiveWallpaper(FLAG_CLI_HOME, args[2], args[3]);
            clearWallpaper(FLAG_CLI_LOCK);
            System.out.println("external home set; lock now inherits the live wallpaper");
        } else {
            int which = parseWhich(args[1]);
            setLiveWallpaper(which, args[2], args[3]);
            System.out.println("wallpaper slot " + which + " set");
        }
    }

    private static int parseWhich(String value) {
        if ("home".equals(value) || "4".equals(value)) {
            return FLAG_CLI_HOME;
        }
        if ("lock".equals(value) || "16".equals(value)) {
            return FLAG_CLI_LOCK;
        }
        if ("phone".equals(value) || "3".equals(value)) {
            return 3;
        }
        throw new IllegalArgumentException("Wallpaper target must be home, lock, or phone");
    }

    private static void backup(WallpaperManager manager, int which, String path) throws Exception {
        ParcelFileDescriptor descriptor = manager.getWallpaperFile(which);
        if (descriptor == null) {
            System.out.println(
                    "wallpaper slot " + which + " has no dedicated static image; restore with clear "
                            + (which == FLAG_CLI_HOME ? "home" : "lock"));
            return;
        }
        try (ParcelFileDescriptor ignored = descriptor;
             InputStream input = new FileInputStream(descriptor.getFileDescriptor());
             OutputStream output = new FileOutputStream(path)) {
            input.transferTo(output);
        }
        System.out.println("wallpaper slot " + which + " backed up to " + path);
    }

    private static void restore(int which, String path) throws Exception {
        try (InputStream input = new FileInputStream(path)) {
            writeStaticWallpaper(which, "excover-backup", input);
        }
        System.out.println("wallpaper slot " + which + " restored");
    }

    private static void writeStaticWallpaper(int which, String name, InputStream input)
            throws Exception {
        Object service = getWallpaperService();
        Class<?> descriptionClass = Class.forName("android.app.wallpaper.WallpaperDescription");
        Object description = buildDescription(null);
        Class<?> callbackClass = Class.forName("android.app.IWallpaperManagerCallback");
        Method setWallpaper = Class.forName("android.app.IWallpaperManager").getMethod(
                "setWallpaper",
                String.class,
                String.class,
                descriptionClass,
                boolean.class,
                Bundle.class,
                int.class,
                callbackClass,
                int.class);
        Bundle result = new Bundle();
        ParcelFileDescriptor descriptor = (ParcelFileDescriptor) setWallpaper.invoke(
                service,
                name,
                "com.android.shell",
                description,
                false,
                result,
                which,
                null,
                0);
        if (descriptor == null) {
            throw new IllegalStateException("Wallpaper service did not return a writable file");
        }
        try (ParcelFileDescriptor ignored = descriptor;
             OutputStream output = new FileOutputStream(descriptor.getFileDescriptor())) {
            input.transferTo(output);
            output.flush();
        }
    }

    private static void setLiveWallpaper(int which, String packageName, String serviceClassName)
            throws Exception {
        Object service = getWallpaperService();
        Class<?> descriptionClass = Class.forName("android.app.wallpaper.WallpaperDescription");
        Object description = buildDescription(new ComponentName(packageName, serviceClassName));

        Class<?> managerInterface = Class.forName("android.app.IWallpaperManager");
        Method setChecked = managerInterface.getMethod(
                "setWallpaperComponentChecked",
                descriptionClass,
                String.class,
                int.class,
                int.class);
        setChecked.invoke(service, description, "com.android.shell", which, 0);
    }

    private static void clearWallpaper(int which) throws Exception {
        Object service = getWallpaperService();
        Method clear = Class.forName("android.app.IWallpaperManager").getMethod(
                "clearWallpaper",
                String.class,
                int.class,
                int.class);
        clear.invoke(service, "com.android.shell", which, 0);
    }

    private static Object getWallpaperService() throws Exception {
        Class<?> serviceManagerClass = Class.forName("android.os.ServiceManager");
        Object binder = serviceManagerClass
                .getMethod("getService", String.class)
                .invoke(null, Context.WALLPAPER_SERVICE);
        Class<?> stubClass = Class.forName("android.app.IWallpaperManager$Stub");
        return stubClass
                .getMethod("asInterface", Class.forName("android.os.IBinder"))
                .invoke(null, binder);
    }

    private static Object buildDescription(ComponentName component) throws Exception {
        Class<?> builderClass = Class.forName(
                "android.app.wallpaper.WallpaperDescription$Builder");
        Object builder = builderClass.getConstructor().newInstance();
        if (component != null) {
            builderClass
                    .getDeclaredMethod("setComponent", ComponentName.class)
                    .invoke(builder, component);
        }
        return builderClass.getMethod("build").invoke(builder);
    }
}
