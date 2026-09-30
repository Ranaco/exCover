package com.ranaco.razrcoverwallpaper;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;

/**
 * Motorola's clock face app binds this first, per app. It announces each session it wants
 * (clockFaceStarted) and we ask for it back (createClockFaceSession); the host then calls
 * {@link AodSessionService} to actually build it.
 */
public final class AodClockFaceService extends Service {
    private final Binder binder = new Binder() {
        private IBinder controller;

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws android.os.RemoteException {
            if (code < FIRST_CALL_TRANSACTION || code > LAST_CALL_TRANSACTION) {
                return super.onTransact(code, data, reply, flags);
            }
            data.enforceInterface(ClockFaceProtocol.SERVICE);
            switch (code) {
                case ClockFaceProtocol.SERVICE_CONNECTED:
                    controller = data.readStrongBinder();
                    Log.i(ClockFaceProtocol.TAG, "clock face host connected");
                    return true;
                case ClockFaceProtocol.SERVICE_DISCONNECTED:
                    controller = null;
                    Log.i(ClockFaceProtocol.TAG, "clock face host disconnected");
                    return true;
                case ClockFaceProtocol.SERVICE_CLOCK_FACE_STARTED:
                    ClockFaceProtocol.SessionId id = ClockFaceProtocol.SessionId.readWrapped(data);
                    Log.i(ClockFaceProtocol.TAG, "clock face started: " + id);
                    if (id != null && controller != null) {
                        ClockFaceProtocol.requestSession(controller, id);
                    }
                    return true;
                default:
                    return true;
            }
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        boolean accepted = ClockFaceProtocol.ACTION_SERVICE.equals(intent.getAction());
        Log.i(ClockFaceProtocol.TAG, "discovery service bind: " + intent.getAction()
                + (accepted ? " accepted" : " rejected"));
        return accepted ? binder : null;
    }
}
