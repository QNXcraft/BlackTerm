package com.qnxcraft.blackterm.service;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;

/**
 * Background service to keep the terminal process alive when the app is in the background.
 */
public class TerminalService extends Service {

    private final IBinder binder = new LocalBinder();

    public class LocalBinder extends Binder {
        public TerminalService getService() {
            return TerminalService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }
}
