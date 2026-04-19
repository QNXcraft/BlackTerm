package com.qnxcraft.blackterm;

import android.app.Application;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

public class BlackTermApp extends Application {

    private static BlackTermApp instance;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);
    }

    public static BlackTermApp getInstance() {
        return instance;
    }

    public SharedPreferences getPrefs() {
        return PreferenceManager.getDefaultSharedPreferences(this);
    }
}
