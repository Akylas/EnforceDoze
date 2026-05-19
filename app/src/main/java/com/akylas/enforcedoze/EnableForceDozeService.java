package com.akylas.enforcedoze;

import static com.akylas.enforcedoze.Utils.logToLogcat;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.preference.PreferenceManager;

public class EnableForceDozeService extends BroadcastReceiver {
    public static String TAG = "EnforceDoze";
    private static void log(String message) {
        logToLogcat(TAG, message);
    }
    
    @Override
    public void onReceive(Context context, Intent intent) {
        log("com.akylas.enforcedoze.ENABLE_FORCEDOZE broadcast intent received started: ");
        PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean("serviceEnabled", true).apply();
        Utils.startForceDozeService(context);
    }
}
