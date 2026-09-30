package com.ranaco.razrcoverwallpaper;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PointF;
import android.hardware.display.DisplayManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;
import android.view.Display;
import android.view.SurfaceControlViewHost;
import android.view.WindowManager;
import android.os.Bundle;

/**
 * Builds exCover's design for Motorola's clock face app: it renders into its own
 * SurfaceControlViewHost on the cover display and hands the host the surface to embed. Each
 * session then hears about visibility, size, style (lock screen or AOD), lock state, minute
 * ticks and burn-in shifts.
 */
public final class AodSessionService extends Service {
    private final Handler main = new Handler(Looper.getMainLooper());

    private final Binder binder = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code != 1) {
                return super.onTransact(code, data, reply, flags);
            }
            data.enforceInterface(ClockFaceProtocol.SESSION_SERVICE);
            data.readStrongBinder(); // session controller: its only call is a no-op on the host
            ClockFaceProtocol.SessionId id = ClockFaceProtocol.SessionId.readWrapped(data);
            ClockFaceProtocol.ViewConfig config = ClockFaceProtocol.ViewConfig.readTyped(data);
            IBinder future = null;
            if (data.readInt() != 0) {
                data.readInt(); // isDone
                future = data.readStrongBinder();
            }
            Log.i(ClockFaceProtocol.TAG, "create session " + id + " on " + config);
            if (id == null || config == null || future == null) {
                return true;
            }
            IBinder hostFuture = future;
            main.post(() -> create(id, config, hostFuture));
            return true;
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        boolean accepted = ClockFaceProtocol.ACTION_SESSION_SERVICE.equals(intent.getAction());
        Log.i(ClockFaceProtocol.TAG, "session service bind: " + intent.getAction()
                + (accepted ? " accepted" : " rejected"));
        return accepted ? binder : null;
    }

    private void create(ClockFaceProtocol.SessionId id, ClockFaceProtocol.ViewConfig config, IBinder future) {
        Display display = getSystemService(DisplayManager.class).getDisplay(config.displayId);
        if (display == null) {
            Log.w(ClockFaceProtocol.TAG, "no display " + config.displayId);
            return;
        }
        // Match Motorola's SDK host setup. The scale-context-only flag matters on Razr's
        // differently-dense 1080x1272 cover display; a plain display context can be scaled twice.
        Bundle options = new Bundle();
        options.putBoolean("moto.window_scaling_on_cli.scale_context_only", true);
        Context windowContext = createWindowContext(display,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, options);
        if (config.inPreview()) {
            // ViewConfig already consumed the preview Configuration while unmarshalling. The
            // host dimensions remain authoritative and are enough for this no-settings face.
            Log.i(ClockFaceProtocol.TAG, "creating preview session");
        }
        SurfaceControlViewHost host = new SurfaceControlViewHost(this, display, new Binder());
        AodClockView view = new AodClockView(windowContext);
        view.setAod(id.style == ClockFaceProtocol.STYLE_AOD);
        host.setView(view, config.width, config.height);
        Session session = new Session(host, view, id.style);
        ClockFaceProtocol.completeCreate(future, session, host.getSurfacePackage());
        Log.i(ClockFaceProtocol.TAG, "session handed over");
    }

    /** One live design on screen, driven by the host. */
    private final class Session extends Binder {
        private final SurfaceControlViewHost host;
        private final AodClockView view;
        private volatile int style;

        Session(SurfaceControlViewHost host, AodClockView view, int style) {
            this.host = host;
            this.view = view;
            this.style = style;
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code < FIRST_CALL_TRANSACTION || code > LAST_CALL_TRANSACTION) {
                return super.onTransact(code, data, reply, flags);
            }
            data.enforceInterface(ClockFaceProtocol.SESSION);
            switch (code) {
                case ClockFaceProtocol.SESSION_VISIBILITY: {
                    boolean visible = data.readInt() != 0;
                    main.post(() -> view.setShown(visible));
                    break;
                }
                case ClockFaceProtocol.SESSION_VIEW_CONFIGURATION: {
                    ClockFaceProtocol.ViewConfig config = ClockFaceProtocol.ViewConfig.readTyped(data);
                    if (config != null && config.width > 0 && config.height > 0) {
                        main.post(() -> host.relayout(config.width, config.height));
                    }
                    break;
                }
                case ClockFaceProtocol.SESSION_DESTROYED:
                    main.post(() -> {
                        view.setShown(false);
                        host.release();
                    });
                    break;
                case ClockFaceProtocol.SESSION_TIME_TICK:
                    main.post(view::invalidate);
                    break;
                case ClockFaceProtocol.SESSION_STYLE: {
                    int nextStyle = data.readInt();
                    Log.i(ClockFaceProtocol.TAG, "style " + nextStyle);
                    style = nextStyle;
                    main.post(() -> view.setAod(nextStyle == ClockFaceProtocol.STYLE_AOD));
                    break;
                }
                case ClockFaceProtocol.SESSION_STYLE_ANIMATION: {
                    PointF shift = data.readInt() != 0 ? new PointF(data.readFloat(), data.readFloat()) : null;
                    if (shift != null) {
                        main.post(() -> view.setBurnInShift(shift.x, shift.y));
                    }
                    break;
                }
                case ClockFaceProtocol.SESSION_LOCK_STATE: {
                    if (data.readInt() != 0) {
                        int lockType = data.readInt();
                        boolean aod = data.readByte() != 0;
                        Log.i(ClockFaceProtocol.TAG, "lock state " + lockType + " aod " + aod);
                        // Motorola sends the lock-icon state independently from the face style.
                        // A newly-created LOCKED_AOD session can legitimately receive aod=false
                        // here while the panel is already in doze. Never let that generic icon
                        // update downgrade a style-1 session back to the awake lock layout.
                        main.post(() -> view.setAod(
                                style == ClockFaceProtocol.STYLE_AOD || aod));
                    }
                    break;
                }
                default:
                    break; // taps, edit mode, snapshots, scrim effects: not needed yet
            }
            return true;
        }
    }
}
