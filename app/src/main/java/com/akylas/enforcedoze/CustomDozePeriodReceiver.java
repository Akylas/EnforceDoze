package com.akylas.enforcedoze;

import static com.akylas.enforcedoze.Utils.logToLogcat;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class CustomDozePeriodReceiver extends BroadcastReceiver {
    public static String TAG = "EnforceDoze";

    private static void log(String message) {
        logToLogcat(TAG, message);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        log("Custom Doze period boundary received");
        Utils.applyForceDozeSchedule(context);
    }
}
