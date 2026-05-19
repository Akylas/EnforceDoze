package com.akylas.enforcedoze;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.provider.Settings;

import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Scanner;
import java.util.Set;
import java.util.SortedMap;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import eu.chainfire.libsuperuser.Shell;

import static android.preference.PreferenceManager.getDefaultSharedPreferences;
import static com.akylas.enforcedoze.Utils.isAirplaneEnabled;
import static com.akylas.enforcedoze.Utils.logToLogcat;

public class ForceDozeService extends Service {

    private static final String CHANNEL_STATS = "CHANNEL_STATS";
    private static final String CHANNEL_TIPS = "CHANNEL_TIPS";
    private static final String CHANNEL_SILENT = "CHANNEL_SILENT";
    private static final int PERSISTENT_NOTIF_ID = 1234;

    private static Shell.Interactive rootSession;
    private static Shell.Interactive nonRootSession;
    private static Shell.OnCommandResultListener2 onCommandResultListener2;
    boolean isSuAvailable = false;
    boolean isShizukuAvailable = false;
    ShizukuHandler shizukuHandler;
    boolean disableWhenCharging = true;
    boolean disableMotionSensors = true;
    boolean useAutoRotateAndBrightnessFix = false;
    boolean showPersistentNotif = false;
    boolean ignoreLockscreenTimeout = false;
    boolean waitForUnlock = false;
    boolean turnOffAllSensorsInDoze = false;
    boolean turnOffBiometricsInDoze = false;
    boolean turnOnBatterySaverInDoze = false;
    boolean turnOnAirplaneInDoze = false;
    boolean turnOffBluetoothInDoze = false;
    boolean turnOffGPSInDoze = false;
    boolean turnOffWiFiInDoze = false;
    boolean ignoreIfHotspot = false;
    boolean turnOffDataInDoze = false;
    boolean whitelistMusicAppNetwork = false;
    boolean whitelistCurrentApp = false;
    boolean wasBatterSaverOn = false;
    boolean wasWiFiTurnedOn = false;
    boolean wasMobileDataTurnedOn = false;
    boolean wasAirplaneOn = false;
    boolean wasBluetoothOn = false;
    boolean wasGPSOn = false;
    boolean wasHotSpotTurnedOn = false;
    boolean maintenance = false;
    boolean setPendingDozeEnterAlarm = false;
    boolean disableStats = false;
    boolean disableLogcat = false;
    int dozeEnterDelay = 0;
    Timer enterDozeTimer;
    Timer disableSensorsTimer;
    Timer enableSensorsTimer;
    DozeReceiver localDozeReceiver;
    ReloadSettingsReceiver reloadSettingsReceiver;
    ReloadNotificationBlocklistReceiver reloadNotificationBlocklistReceiver;
    ReloadAppsBlocklistReceiver reloadAppsBlocklistReceiver;
    NotificationCompat.Builder mStatsBuilder;
    PowerManager pm;
    PowerManager.WakeLock tempWakeLock;
    Set<String> dozeUsageData;
    Set<String> dozeNotificationBlocklist;
    Set<String> dozeAppBlocklist;
    String sensorWhitelistPackage = "";
    String state = "";
    Long timeEnterDoze = 0L;
    Long timeExitDoze = 0L;
    String lastScreenOff = "Unknown";
    int lastDozeEnterBatteryLife = 0;
    int lastDozeExitBatteryLife = 0;
    String TAG = "ForceDozeService";
    String lastKnownState = "null";

    // Add near the top of the class
    private static final String ACTION_IGNORE_RESULT = "com.akylas.enforcedoze.ACTION_IGNORE_BATTERY_OPTIMIZATION_RESULT";
    private static final String EXTRA_IGNORED = "com.akylas.enforcedoze.EXTRA_IGNORED";

    private BroadcastReceiver ignoreBatteryResultReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean ignored = intent.getBooleanExtra(EXTRA_IGNORED, false);
            String packageName = getPackageName();
            if (!ignored && !pm.isIgnoringBatteryOptimizations(packageName)) {
                log("Service still optimized after user prompt, showing notification...");
                Intent notificationIntent = new Intent();
                notificationIntent.setAction(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                PendingIntent pi = PendingIntent.getActivity(getApplicationContext(), 0,
                        notificationIntent, PendingIntent.FLAG_IMMUTABLE);
                Notification n = new NotificationCompat.Builder(ForceDozeService.this, CHANNEL_TIPS)
                        .setContentTitle("EnforceDoze")
                        .setStyle(new NotificationCompat.BigTextStyle().bigText(
                                "EnforceDoze needs to be added to the Doze whitelist in order to work reliably. Please open the battery optimisation view and select 'Don't optimize' for EnforceDoze."))
                        .setSmallIcon(R.drawable.ic_battery_health)
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                        .setContentIntent(pi)
                        .setOngoing(false)
                        .build();
                NotificationManager notificationManager =
                        (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                notificationManager.notify(8765, n);
            } else {
                log("User granted ignore battery optimizations for service.");
            }
        }
    };
    private final ExecutorService rootShellExecutor = Executors.newSingleThreadExecutor();

    private void log(String message) {
        logToLogcat(TAG, message);
    }

    public ForceDozeService() {
    }

    @Override
    public void onCreate() {
        super.onCreate();
        localDozeReceiver = new DozeReceiver();
        reloadSettingsReceiver = new ReloadSettingsReceiver();
        reloadNotificationBlocklistReceiver = new ReloadNotificationBlocklistReceiver();
        reloadAppsBlocklistReceiver = new ReloadAppsBlocklistReceiver();
        enterDozeTimer = new Timer();
        enableSensorsTimer = new Timer();
        disableSensorsTimer = new Timer();


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence statsName = getString(R.string.notification_channel_stats_name);
            String statsDescription = getString(R.string.notification_channel_stats_description);
            int statsImportance = NotificationManager.IMPORTANCE_MIN;
            NotificationChannel statsChannel = new NotificationChannel(CHANNEL_STATS, statsName, statsImportance);
            statsChannel.setDescription(statsDescription);

            CharSequence tipsName = getString(R.string.notification_channel_tips_name);
            String tipsDescription = getString(R.string.notification_channel_tips_description);
            int tipsImportance = NotificationManager.IMPORTANCE_DEFAULT;
            NotificationChannel tipsChannel = new NotificationChannel(CHANNEL_TIPS, tipsName, tipsImportance);
            tipsChannel.setDescription(tipsDescription);
            
            // Create a silent channel for Android 12+ foreground service requirement
            CharSequence silentName = getString(R.string.notification_channel_silent_name);
            String silentDescription = getString(R.string.notification_channel_silent_description);
            int silentImportance = NotificationManager.IMPORTANCE_MIN;
            NotificationChannel silentChannel = new NotificationChannel(CHANNEL_SILENT, silentName, silentImportance);
            silentChannel.setDescription(silentDescription);
            silentChannel.setSound(null, null);
            silentChannel.setShowBadge(false);
            
            // Register the channel with the system; you can't change the importance
            // or other notification behaviors after this
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(statsChannel);
            notificationManager.createNotificationChannel(tipsChannel);
            notificationManager.createNotificationChannel(silentChannel);
        }

        mStatsBuilder = new NotificationCompat.Builder(this, CHANNEL_STATS);
        pm = (PowerManager) getSystemService(POWER_SERVICE);
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
//        filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        filter.addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED);
        if (Utils.isDeviceRunningOnN()) {
            filter.addAction("android.os.action.LIGHT_DEVICE_IDLE_MODE_CHANGED");
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(reloadSettingsReceiver, new IntentFilter("reload-settings"));
        LocalBroadcastManager.getInstance(this).registerReceiver(reloadNotificationBlocklistReceiver, new IntentFilter("reload-notification-blocklist"));
        LocalBroadcastManager.getInstance(this).registerReceiver(reloadAppsBlocklistReceiver, new IntentFilter("reload-app-blocklist"));
        LocalBroadcastManager.getInstance(this).registerReceiver(ignoreBatteryResultReceiver, new IntentFilter(ACTION_IGNORE_RESULT));
        this.registerReceiver(localDozeReceiver, filter);
        turnOffDataInDoze = getDefaultSharedPreferences(getApplicationContext()).getBoolean("turnOffDataInDoze", false);
        ignoreIfHotspot = getDefaultSharedPreferences(getApplicationContext()).getBoolean("ignoreIfHotspot", true);
        turnOffWiFiInDoze = getDefaultSharedPreferences(getApplicationContext()).getBoolean("turnOffWiFiInDoze", false);
        turnOffAllSensorsInDoze = getDefaultSharedPreferences(getApplicationContext()).getBoolean("turnOffAllSensorsInDoze", false);
        turnOffBiometricsInDoze = getDefaultSharedPreferences(getApplicationContext()).getBoolean("turnOffBiometricsInDoze", false);
        turnOnBatterySaverInDoze = getDefaultSharedPreferences(getApplicationContext()).getBoolean("turnOnBatterySaverInDoze", false);
        turnOnAirplaneInDoze = getDefaultSharedPreferences(getApplicationContext()).getBoolean("turnOnAirplaneInDoze", false);
        turnOffBluetoothInDoze = getDefaultSharedPreferences(getApplicationContext()).getBoolean("turnOffBluetoothInDoze", false);
        turnOffGPSInDoze = getDefaultSharedPreferences(getApplicationContext()).getBoolean("turnOffGPSInDoze", false);
        whitelistMusicAppNetwork = getDefaultSharedPreferences(getApplicationContext()).getBoolean("whitelistMusicAppNetwork", false);
        whitelistCurrentApp = getDefaultSharedPreferences(getApplicationContext()).getBoolean("whitelistCurrentApp", false);
        ignoreLockscreenTimeout = getDefaultSharedPreferences(getApplicationContext()).getBoolean("ignoreLockscreenTimeout", true);
        waitForUnlock = getDefaultSharedPreferences(getApplicationContext()).getBoolean("waitForUnlock", false);
        dozeEnterDelay = getDefaultSharedPreferences(getApplicationContext()).getInt("dozeEnterDelay", 0);
        useAutoRotateAndBrightnessFix = getDefaultSharedPreferences(getApplicationContext()).getBoolean("autoRotateAndBrightnessFix", false);
        sensorWhitelistPackage = getDefaultSharedPreferences(getApplicationContext()).getString("sensorWhitelistPackage", "");
        disableMotionSensors = getDefaultSharedPreferences(getApplicationContext()).getBoolean("disableMotionSensors", true);
        disableStats = getDefaultSharedPreferences(getApplicationContext()).getBoolean("disableStats", false);
        disableLogcat = getDefaultSharedPreferences(getApplicationContext()).getBoolean("disableLogcat", false);
        disableWhenCharging = getDefaultSharedPreferences(getApplicationContext()).getBoolean("disableWhenCharging", true);
        isSuAvailable = getDefaultSharedPreferences(getApplicationContext()).getBoolean("isSuAvailable", false);
        showPersistentNotif = PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).getBoolean("showPersistentNotif", false);
        dozeUsageData = PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).getStringSet("dozeUsageDataAdvanced", new LinkedHashSet<String>());
        dozeNotificationBlocklist = PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).getStringSet("notificationBlockList", new LinkedHashSet<String>());
        dozeAppBlocklist = PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).getStringSet("dozeAppBlockList", new LinkedHashSet<String>());

        // Initialize Shizuku handler
        shizukuHandler = ShizukuHandler.getInstance(getApplicationContext());
        boolean useShizuku = Utils.isShizukuMode(getApplicationContext());
        isShizukuAvailable = false;
        if (useShizuku) {
            shizukuHandler.checkShizukuAvailability();
            shizukuHandler.setOnAvailibilityChangeListener(value -> {
                isShizukuAvailable = value;
            });
            isShizukuAvailable = shizukuHandler.isShizukuAvailable();
            log("Shizuku mode enabled, available: " + isShizukuAvailable);
        }

        if (!Utils.isDumpPermissionGranted(getApplicationContext())) {
            if (isShizukuAvailable) {
                grantDumpPermissionViaShizuku();
            } else if (isSuAvailable) {
                grantDumpPermission();
            }
        }

        if (Utils.isDeviceRunningOnN()) {
            if (!Utils.isSecureSettingsPermissionGranted(getApplicationContext())) {
                if (isShizukuAvailable) {
                    grantSecureSettingsPermissionViaShizuku();
                } else if (isSuAvailable) {
                    grantSecureSettingsPermission();
                }
            }
        }

        if (!Utils.isReadPhoneStatePermissionGranted(getApplicationContext())) {
            if (isShizukuAvailable) {
                grantReadPhoneStatePermissionViaShizuku();
            } else if (isSuAvailable) {
                grantReadPhoneStatePermission();
            }
        }

        // To initialize root shell/shell on service start
        if (useShizuku && isShizukuAvailable) {
            shizukuHandler.executeCommand("whoami", (commandCode, exitCode, stdout, stderr) -> {
                log("Shizuku test command executed");
            }, true);
        } else if (isSuAvailable) {
            executeCommandWithRoot("whoami");
        } else {
            executeCommand("whoami");
        }
        // ensure blocked apps are enable in case we were killed before we could enable them after doze
        if (dozeAppBlocklist.size() != 0) {
            log("Re-enabling apps that are in the Doze app blocklist");
            for (String pkg : dozeAppBlocklist) {
                setPackageState(getApplicationContext(), pkg, true);
            }
        }
    }


    @Override
    public IBinder onBind(Intent intent) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        log("Stopping service and enabling sensors");
        this.unregisterReceiver(localDozeReceiver);
        LocalBroadcastManager.getInstance(this).unregisterReceiver(reloadSettingsReceiver);
        LocalBroadcastManager.getInstance(this).unregisterReceiver(ignoreBatteryResultReceiver);        if (disableMotionSensors) {
            executeCommand("dumpsys sensorservice enable");
        }
        //ensure we exit doze if stopped from background
        exitDoze(getDeviceIdleState());
        if (rootSession != null) {
            rootSession.close();
            rootSession = null;
        }
        if (nonRootSession != null) {
            nonRootSession.close();
            nonRootSession = null;
        }
        // Show disabled notification only when the user disabled EnforceDoze, not when a schedule stops the service.
        if (!PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).getBoolean("serviceEnabled", false)) {
            Utils.showDisabledNotification(getApplicationContext());
        }
        // Update tile state
        Utils.updateTileState(getApplicationContext());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        log("Service has now started");
        // On Android 12+, we must call startForeground() immediately when service is started
        // as a foreground service, regardless of showPersistentNotif setting
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (showPersistentNotif) {
                // Show notification with stats if user enabled it
                showPersistentNotification();
            } else {
                // Show minimal silent notification on Android 12+ to comply with foreground service requirements
                showSilentNotification();
            }
        } else {
            // On older versions, respect the user's preference
            if (showPersistentNotif) {
                showPersistentNotification();
            } else {
                hidePersistentNotification();
            }
        }
        addSelfToDozeWhitelist();
        enterDoze(this);
        lastKnownState = getDeviceIdleState();
        // Hide disabled notification when service starts
        Utils.hideDisabledNotification(getApplicationContext());
        // Update tile state
        Utils.updateTileState(getApplicationContext());
        return START_STICKY;
    }

    public void reloadSettings() {
        log("EnforceDoze settings reloaded ----------------------------------");
        dozeUsageData = PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).getStringSet("dozeUsageDataAdvanced", new LinkedHashSet<String>());
        log("dozeUsageData: " + "Total Entries -> " + dozeUsageData.size());
        turnOffDataInDoze = getDefaultSharedPreferences(getApplicationContext()).getBoolean("turnOffDataInDoze", false);
        log("turnOffDataInDoze: " + turnOffDataInDoze);
        ignoreIfHotspot = getDefaultSharedPreferences(getApplicationContext()).getBoolean("ignoreIfHotspot", true);
        log("ignoreIfHotspot: " + ignoreIfHotspot);
        turnOffWiFiInDoze = getDefaultSharedPreferences(getApplicationContext()).getBoolean("turnOffWiFiInDoze", false);
        log("turnOffWiFiInDoze: " + turnOffWiFiInDoze);
        turnOffAllSensorsInDoze = getDefaultSharedPreferences(getApplicationContext()).getBoolean("turnOffAllSensorsInDoze", false);
        log("turnOffAllSensorsInDoze: " + turnOffAllSensorsInDoze);
        turnOffBiometricsInDoze = getDefaultSharedPreferences(getApplicationContext()).getBoolean("turnOffBiometricsInDoze", false);
        log("turnOffBiometricsInDoze: " + turnOffBiometricsInDoze);
        turnOnBatterySaverInDoze = getDefaultSharedPreferences(getApplicationContext()).getBoolean("turnOnBatterySaverInDoze", false);
        log("turnOnBatterySaverInDoze: " + turnOnBatterySaverInDoze);
        turnOnAirplaneInDoze = getDefaultSharedPreferences(getApplicationContext()).getBoolean("turnOnAirplaneInDoze", false);
        log("turnOnAirplaneInDoze: " + turnOnAirplaneInDoze);
        turnOffBluetoothInDoze = getDefaultSharedPreferences(getApplicationContext()).getBoolean("turnOffBluetoothInDoze", false);
        log("turnOffBluetoothInDoze: " + turnOffBluetoothInDoze);
        turnOffGPSInDoze = getDefaultSharedPreferences(getApplicationContext()).getBoolean("turnOffGPSInDoze", false);
        log("turnOffGPSInDoze: " + turnOffGPSInDoze);
        whitelistMusicAppNetwork = getDefaultSharedPreferences(getApplicationContext()).getBoolean("whitelistMusicAppNetwork", false);
        log("whitelistMusicAppNetwork: " + whitelistMusicAppNetwork);
        whitelistCurrentApp = getDefaultSharedPreferences(getApplicationContext()).getBoolean("whitelistCurrentApp", false);
        log("whitelistCurrentApp: " + whitelistCurrentApp);
        ignoreLockscreenTimeout = getDefaultSharedPreferences(getApplicationContext()).getBoolean("ignoreLockscreenTimeout", true);
        log("ignoreLockscreenTimeout: " + ignoreLockscreenTimeout);
        waitForUnlock = getDefaultSharedPreferences(getApplicationContext()).getBoolean("waitForUnlock", false);
        log("waitForUnlock: " + waitForUnlock);
        dozeEnterDelay = getDefaultSharedPreferences(getApplicationContext()).getInt("dozeEnterDelay", 0);
        log("dozeEnterDelay: " + dozeEnterDelay);
        useAutoRotateAndBrightnessFix = getDefaultSharedPreferences(getApplicationContext()).getBoolean("autoRotateAndBrightnessFix", false);
        log("useAutoRotateAndBrightnessFix: " + useAutoRotateAndBrightnessFix);
        sensorWhitelistPackage = getDefaultSharedPreferences(getApplicationContext()).getString("sensorWhitelistPackage", "");
        log("sensorWhitelistPackage: " + sensorWhitelistPackage);
        disableMotionSensors = getDefaultSharedPreferences(getApplicationContext()).getBoolean("disableMotionSensors", true);
        log("disableMotionSensors: " + disableMotionSensors);
        disableStats = getDefaultSharedPreferences(getApplicationContext()).getBoolean("disableStats", false);
        log("disableStats: " + disableStats);
        disableLogcat = getDefaultSharedPreferences(getApplicationContext()).getBoolean("disableLogcat", false);
        log("disableLogcat: " + disableLogcat);
        disableWhenCharging = getDefaultSharedPreferences(getApplicationContext()).getBoolean("disableWhenCharging", true);
        log("disableWhenCharging: " + disableWhenCharging);
        showPersistentNotif = getDefaultSharedPreferences(getApplicationContext()).getBoolean("showPersistentNotif", false);
        log("showPersistentNotif: " + showPersistentNotif);
        log("EnforceDoze settings reloaded ----------------------------------");
        // On Android 12+, we must keep the foreground notification
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (showPersistentNotif) {
                showPersistentNotification();
            } else {
                // Show minimal silent notification on Android 12+
                showSilentNotification();
            }
        } else {
            if (showPersistentNotif) {
                showPersistentNotification();
            } else {
                hidePersistentNotification();
            }
        }
    }

    public void reloadNotificationBlockList() {
        log("Notification blocklist reloaded ----------------------------------");
        dozeNotificationBlocklist = PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).getStringSet("notificationBlockList", new LinkedHashSet<String>());
        log("notificationBlockList: " + dozeNotificationBlocklist.size() + " items");
        log("Notification blocklist reloaded ----------------------------------");
    }

    public void reloadAppsBlockList() {
        log("Apps blocklist reloaded ----------------------------------");
        dozeAppBlocklist = PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).getStringSet("dozeAppBlockList", new LinkedHashSet<String>());
        log("dozeAppBlockList: " + dozeAppBlocklist.size() + " items");
        log("Apps blocklist reloaded ----------------------------------");
    }

    public void grantDumpPermission() {
        log("Granting android.permission.DUMP to com.akylas.enforcedoze");
        executeCommandWithRoot("pm grant com.akylas.enforcedoze android.permission.DUMP");
    }

    public void grantDumpPermissionViaShizuku() {
        log("Granting android.permission.DUMP to com.akylas.enforcedoze via Shizuku");
        shizukuHandler.executeCommand("pm grant com.akylas.enforcedoze android.permission.DUMP",
            (commandCode, exitCode, stdout, stderr) -> {
                if (exitCode == 0) {
                    log("DUMP permission granted successfully");
                }
            }, true);
    }

    public void grantSecureSettingsPermission() {
        log("Granting android.permission.WRITE_SECURE_SETTINGS to com.akylas.enforcedoze");
        executeCommandWithRoot("pm grant com.akylas.enforcedoze android.permission.WRITE_SECURE_SETTINGS");
    }

    public void grantSecureSettingsPermissionViaShizuku() {
        log("Granting android.permission.WRITE_SECURE_SETTINGS to com.akylas.enforcedoze via Shizuku");
        shizukuHandler.executeCommand("pm grant com.akylas.enforcedoze android.permission.WRITE_SECURE_SETTINGS",
            (commandCode, exitCode, stdout, stderr) -> {
                if (exitCode == 0) {
                    log("WRITE_SECURE_SETTINGS permission granted successfully");
                }
            }, true);
    }

    public void grantReadPhoneStatePermission() {
        log("Granting android.permission.READ_PHONE_STATE to com.akylas.enforcedoze");
        executeCommandWithRoot("pm grant com.akylas.enforcedoze android.permission.READ_PHONE_STATE");
    }

    public void grantReadPhoneStatePermissionViaShizuku() {
        log("Granting android.permission.READ_PHONE_STATE to com.akylas.enforcedoze via Shizuku");
        shizukuHandler.executeCommand("pm grant com.akylas.enforcedoze android.permission.READ_PHONE_STATE",
            (commandCode, exitCode, stdout, stderr) -> {
                if (exitCode == 0) {
                    log("READ_PHONE_STATE permission granted successfully");
                }
            }, true);
    }

    public void grantSensorPrivacyPermission() {
        log("Granting android.permission.MANAGE_SENSOR_PRIVACY to com.akylas.enforcedoze");
        executeCommandWithRoot("pm grant com.akylas.enforcedoze android.permission.MANAGE_SENSOR_PRIVACY");
    }

    public void addSelfToDozeWhitelist() {
        log("Checking self-whitelist capability....");
        log("Nougat: " + Utils.isDeviceRunningOnN());
        log("SU available: " + isSuAvailable);
        String packageName = getPackageName();
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            if (!Utils.isDeviceRunningOnN()) {
                log("Adding service to Doze whitelist for stability");
                executeCommand("dumpsys deviceidle whitelist +com.akylas.enforcedoze");
            } else if (Utils.isDeviceRunningOnN() && (isSuAvailable || isShizukuAvailable)) {
                log("Adding service to Doze whitelist for stability");
                executeCommandWithRoot("dumpsys deviceidle whitelist +com.akylas.enforcedoze");
            } else {
                log("Requesting user to disable battery optimizations via system dialog...");
                try {
                    Intent reqActivity = new Intent(this, RequestIgnoreBatteryActivity.class);
                    reqActivity.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(reqActivity);
                } catch (Exception e) {
                    log("Failed to launch RequestIgnoreBatteryActivity: " + e.getMessage());
                    // fallback: show the old notification immediately
                    // (optional) reuse existing notification code here
                    log("Service cannot be added to Doze whitelist because user is on Nougat. Showing notification...");
                    Intent notificationIntent = new Intent();
                    notificationIntent.setAction(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                    PendingIntent intent = PendingIntent.getActivity(getApplicationContext(), 0,
                            notificationIntent, PendingIntent.FLAG_IMMUTABLE);
                    Notification n = new NotificationCompat.Builder(this, CHANNEL_TIPS)
                            .setContentTitle("EnforceDoze")
                            .setStyle(new NotificationCompat.BigTextStyle().bigText("EnforceDoze needs to be added to the Doze whitelist in order to work reliably. Please click on this notification to open the battery optimisation view, click on 'EnforceDoze' and select 'Don't' Optimize'"))
                            .setSmallIcon(R.drawable.ic_battery_health)
                            .setPriority(1)
                            .setContentIntent(intent)
                            .setOngoing(false).build();
                    NotificationManager notificationManager =
                            (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                    notificationManager.notify(8765, n);
                }
            }

        } else {
            log("Service already in Doze whitelist for stability");
        }
    }

    public void applyDoze() {
        if (Utils.isDeviceRunningOnN()) {
            if (isSuAvailable || isShizukuAvailable) {
                executeCommandWithRoot("dumpsys deviceidle force-idle deep");
            } else {
                DozeTunableHandler handler = DozeTunableHandler.getInstance();
                log("Unrooted device, putting custom values in device_idle_constants...");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ArrayList<String> commands = handler.getCommandsList();
                    commands.forEach(this::executeCommand);
                } else {
                    Settings.Global.putString(getContentResolver(), "device_idle_constants", handler.getTunableString());
                }
            }
        } else {
            executeCommand("dumpsys deviceidle force-idle");
        }
    }

    public void leaveDoze() {
        if (Utils.isDeviceRunningOnN()) {
            if (isSuAvailable || isShizukuAvailable) {
                executeCommandWithRoot("dumpsys deviceidle unforce");
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    executeCommand("device_config reset trusted_defaults device_idle");
                    executeCommand("dumpsys deviceidle step");
                } else {
                    Settings.Global.putString(getContentResolver(), "device_idle_constants", null);

                }
            }
        } else {
            executeCommand("dumpsys deviceidle step");
        }
    }

    public void enterDoze(Context context) {
        if (!Utils.isInsideCustomDozePeriod(context)) {
            log("Outside custom Doze periods, skip entering Doze");
            return;
        }
        if (!getDeviceIdleState().equals("IDLE") || !lastKnownState.equals("IDLE")) {
            if (!Utils.isScreenOn(context)) {
                lastKnownState = "IDLE";
                if (tempWakeLock != null) {
                    if (tempWakeLock.isHeld()) {
                        log("Releasing ForceDozeTempWakelock");
                        tempWakeLock.release();
                    }
                }
                if (dozeAppBlocklist.size() != 0) {
                    log("Disabling apps that are in the Doze app blocklist");
                    if (whitelistCurrentApp) {
                        // when root is not available we use UsageStatsManager
                        // but i am not sure i can trust it as it does not really returns the front
                        // app but last one used (what about apps running in the background?)
                        if (isSuAvailable || isShizukuAvailable) {
                            try {
                                getFocusedApps((HashSet<String> packageNames) -> {
                                    for (String pkg : dozeAppBlocklist) {
                                        if (!packageNames.contains(pkg)) {
                                            setPackageState(context, pkg, false);
                                        }
                                    }
                                });
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        } else {
                            String currentlyFocused = getNonRootFocusedPackageName();
                            for (String pkg : dozeAppBlocklist) {
                                if (!pkg.equals(currentlyFocused)) {
                                    setPackageState(context, pkg, false);
                                }
                            }
                        }
                    } else {

                        for (String pkg : dozeAppBlocklist) {
                            setPackageState(context, pkg, false);
                        }
                    }

                }

                if (dozeNotificationBlocklist.size() != 0) {
                    log("Disabling notifications for apps in the Notification blocklist");
                    for (String pkg : dozeNotificationBlocklist) {
                        if (!dozeAppBlocklist.contains(pkg)) {
                            setNotificationEnabledForPackage(pkg, false);
                        }
                    }
                }
                timeEnterDoze = System.currentTimeMillis();
                if (Utils.isConnectedToCharger(getApplicationContext())) {
                    lastDozeEnterBatteryLife = 0;
                } else {
                    lastDozeEnterBatteryLife = Utils.getBatteryLevel(getApplicationContext());
                }
                log("Entering Doze");
                applyDoze();
                lastScreenOff = Utils.getDateCurrentTimeZone(System.currentTimeMillis());

                if (!disableStats) {
                    dozeUsageData.add(Long.toString(System.currentTimeMillis()).concat(",").concat(Float.toString(Utils.isConnectedToCharger(getApplicationContext()) ? 0.0f : Utils.getBatteryLevel(getApplicationContext()))).concat(",").concat("ENTER"));
                    saveDozeDataStats();
                }

                if (disableMotionSensors) {
                    disableSensorsTimer = new Timer();
                    disableSensorsTimer.schedule(new TimerTask() {
                        @Override
                        public void run() {
                            log("Disabling motion sensors");
                            if (sensorWhitelistPackage.equals("")) {
                                executeCommand("dumpsys sensorservice restrict");
                            } else {
                                log("Package " + sensorWhitelistPackage + " is whitelisted from sensorservice");
                                log("Note: Packages that get whitelisted are supposed to request sensor access again, if the app doesn't work, email the dev of that app!");
                                executeCommand("dumpsys sensorservice restrict " + sensorWhitelistPackage);
                            }
                        }
                    }, 2000);
                } else {
                    log("Not disabling motion sensors because disableMotionSensors=false");
                }
                enterDozeHandleNetwork(context);

            } else {
                log("Screen is on, skip entering Doze");
            }
        } else {
            log("enterDoze() received but skipping because device is already Dozing");
        }
    }

    private void reEnableBlockedAppsAndNotifications() {
        if (dozeAppBlocklist.size() != 0) {
            log("Re-enabling apps that are in the Doze app blocklist");
            for (String pkg : dozeAppBlocklist) {
                setPackageState(getApplicationContext(), pkg, true);
            }
        }

        if (dozeNotificationBlocklist.size() != 0) {
            log("Re-enabling notifications for apps in the Notification blocklist");
            for (String pkg : dozeNotificationBlocklist) {
                if (!dozeAppBlocklist.contains(pkg)) {
                    setNotificationEnabledForPackage(pkg, true);
                }
            }
        }
    }

    public void exitDoze(String newDeviceIdleState) {
        timeExitDoze = System.currentTimeMillis();
        if (Utils.isConnectedToCharger(getApplicationContext())) {
            lastDozeExitBatteryLife = 0;
        } else {
            lastDozeExitBatteryLife = Utils.getBatteryLevel(getApplicationContext());
        }
        lastKnownState = "ACTIVE";
        leaveDoze();

        log("exitDoze current Doze state: " + newDeviceIdleState);

        if (!disableStats) {
            dozeUsageData.add(Long.toString(System.currentTimeMillis()).concat(",").concat(Float.toString(Utils.isConnectedToCharger(getApplicationContext()) ? 0.0f : Utils.getBatteryLevel(getApplicationContext()))).concat(",").concat("EXIT"));
            saveDozeDataStats();
        }

        reEnableBlockedAppsAndNotifications();

        if (disableMotionSensors) {
            enableSensorsTimer = new Timer();
            enableSensorsTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    log("Re-enabling motion sensors");
                    executeCommand("dumpsys sensorservice enable");
                    autoRotateBrightnessFix();
                }
            }, 2000);
        }

        if (showPersistentNotif) {
            Timer updateNotif = new Timer();
            updateNotif.schedule(new TimerTask() {
                @Override
                public void run() {
                    updatePersistentNotification(lastScreenOff, Utils.diffInMins(timeEnterDoze, timeExitDoze), (lastDozeEnterBatteryLife - lastDozeExitBatteryLife));
                }
            }, 2000);
        }

    }

    public void executeCommand(final String command) {
        executeCommand(command, null, false);
    }
    public void executeCommand(final String command, Shell.OnCommandResultListener2 onResult, Boolean printOutput) {
        boolean useShizuku = Utils.isShizukuMode(getApplicationContext());
        
        if (useShizuku && isShizukuAvailable) {
            shizukuHandler.executeCommand(command, (commandCode, exitCode, stdout, stderr) -> {
                if (onResult != null) {
                    onResult.onCommandResult(commandCode, exitCode, stdout, stderr);
                }
                if (printOutput) {
                    printShellOutput(stdout);
                    printShellOutput(stderr);
                }
            }, printOutput);
            return;
        }

        rootShellExecutor.execute(() -> {
            if (nonRootSession != null) {
                nonRootSession.addCommand(command, 0, (Shell.OnCommandResultListener2) (commandCode, exitCode, STDOUT, STDERR) -> {
                    if (onResult != null) {
                        onResult.onCommandResult(commandCode, exitCode, STDOUT, STDERR);
                    }
                    if (printOutput){
                        printShellOutput(STDOUT);
                        printShellOutput(STDERR);
                    }
                });
            } else {
                nonRootSession = new Shell.Builder().
                        useSH().
                        setWatchdogTimeout(5).
                        setMinimalLogging(true).
                        open((success, reason) -> {
                            if (reason != Shell.OnShellOpenResultListener.SHELL_RUNNING) {
                                log("Error opening shell: exitCode " + reason);
                            } else {
                                nonRootSession.addCommand(command, 0, (Shell.OnCommandResultListener2) (commandCode, exitCode, STDOUT, STDERR) -> {
                                    if (onResult != null) {
                                        onResult.onCommandResult(commandCode, exitCode, STDOUT, STDERR);
                                    }
                                    if (printOutput){
                                        printShellOutput(STDOUT);
                                        printShellOutput(STDERR);
                                    }
                                });
                            }
                        });
            }
        });
    }

    public interface OnGetFocusedApp {
        void onGetFocusedApps(HashSet<String> result);
    }

    public HashSet<String> parseFocusedApps(String services) {
        if (!services.isEmpty()) {
            return new HashSet<String>(Arrays.asList(services.split("\\r?\\n")));
        }
        return new HashSet<String>();
    }

    public String getNonRootFocusedPackageName() {
        var usm = (UsageStatsManager) this.getSystemService(Context.USAGE_STATS_SERVICE);
        long time = System.currentTimeMillis();
        List<UsageStats> appList = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, time - 10000, time);
        if (appList != null && !appList.isEmpty()) {
            SortedMap<Long, UsageStats> mySortedMap = new TreeMap<>();
            for (UsageStats usageStats : appList) {
                mySortedMap.put(usageStats.getLastTimeUsed(), usageStats);
            }
            if (!mySortedMap.isEmpty()) {
                return Objects.requireNonNull(mySortedMap.get(mySortedMap.lastKey())).getPackageName();
            }
        }
        return null;
    }

    String FOCUSED_APP_REGEXP = "\\{[a-z0-9]+\\s[a-z0-9]+\\s(.*)\\/";

    public void getFocusedApps(OnGetFocusedApp callback) {
        executeCommandWithRoot("dumpsys activity activities | grep -E 'CurrentFocus|ResumedActivity|FocusedApp'", (commandCode, exitCode, STDOUT, STDERR) -> {
            String result = "";
            if (commandCode == 0) {
                if (!STDOUT.isEmpty()) {
                    Matcher m = Pattern.compile(FOCUSED_APP_REGEXP).matcher(STDOUT.get(0));
                    if (m.find()) {
                        result = m.group(1);
                    }
                }
            }
            callback.onGetFocusedApps(parseFocusedApps(result));
        });
    }

    public void executeCommandWithRoot(final String command) {
        executeCommandWithRoot(command, null);
    }

    public void executeCommandWithRoot(final String command, Shell.OnCommandResultListener2 onResult) {
        boolean useShizuku = Utils.isShizukuMode(getApplicationContext());
        
        if (useShizuku && isShizukuAvailable) {
            shizukuHandler.executeCommand(command, (commandCode, exitCode, stdout, stderr) -> {
                if (onResult != null) {
                    onResult.onCommandResult(commandCode, exitCode, stdout, stderr);
                }
                printShellOutput(stdout);
                printShellOutput(stderr);
            }, true);
            return;
        }

        rootShellExecutor.execute(() -> {
            if (rootSession != null) {
                rootSession.addCommand(command, 0, (Shell.OnCommandResultListener2) (commandCode, exitCode, STDOUT, STDERR) -> {
                    if (onResult != null) {
                        onResult.onCommandResult(commandCode, exitCode, STDOUT, STDERR);
                    }
                    printShellOutput(STDOUT);
                    printShellOutput(STDERR);
                });
            } else {
                rootSession = new Shell.Builder().
                        useSU().
                        setWatchdogTimeout(5).
                        setMinimalLogging(true).
                        open((success, reason) -> {
                            if (reason != Shell.OnShellOpenResultListener.SHELL_RUNNING) {
                                log("Error opening root shell: exitCode " + reason);
                            } else {
                                rootSession.addCommand(command, 0, (Shell.OnCommandResultListener2) (commandCode, exitCode, STDOUT, STDERR) -> {
                                    if (onResult != null) {
                                        onResult.onCommandResult(commandCode, exitCode, STDOUT, STDERR);
                                    }
                                    printShellOutput(STDOUT);
                                });
                            }
                        });
            }
        });
    }

    public void printShellOutput(List<String> output) {
        if (disableLogcat) {
            return;
        }
        if (output != null && !output.isEmpty()) {
            for (String s : output) {
                log(s);
            }
        }
    }

    public void saveDozeDataStats() {
        SharedPreferences sharedPreferences = getDefaultSharedPreferences(getApplicationContext());
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.remove("dozeUsageDataAdvanced");
        editor.apply();
        editor.putStringSet("dozeUsageDataAdvanced", dozeUsageData);
        editor.apply();
    }

    public void autoRotateBrightnessFix() {
        if (useAutoRotateAndBrightnessFix && Utils.isWriteSettingsPermissionGranted(getApplicationContext())) {
            log("Executing auto-rotate fix by doing a toggle");
            log("Current value: " + (Utils.isAutoRotateEnabled(getApplicationContext())) + " to " + (!Utils.isAutoRotateEnabled(getApplicationContext())));
            Utils.setAutoRotateEnabled(getApplicationContext(), !Utils.isAutoRotateEnabled(getApplicationContext()));
            try {
                log("Sleeping for 100ms");
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Log.e(TAG, e.toString());
            }
            log("Current value: " + (Utils.isAutoRotateEnabled(getApplicationContext())) + " to " + !Utils.isAutoRotateEnabled(getApplicationContext()));
            Utils.setAutoRotateEnabled(getApplicationContext(), !Utils.isAutoRotateEnabled(getApplicationContext()));
            try {
                log("Sleeping for 100ms");
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Log.e(TAG, e.toString());
            }
            log("Executing auto-brightness fix by doing a toggle");
            log("Current value: " + (Utils.isAutoBrightnessEnabled(getApplicationContext())) + " to " + (!Utils.isAutoBrightnessEnabled(getApplicationContext())));
            Utils.setAutoBrightnessEnabled(getApplicationContext(), !Utils.isAutoBrightnessEnabled(getApplicationContext()));
            try {
                log("Sleeping for 100ms");
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Log.e(TAG, e.toString());
            }
            log("Current value: " + (Utils.isAutoBrightnessEnabled(getApplicationContext())) + " to " + (!Utils.isAutoBrightnessEnabled(getApplicationContext())));
            Utils.setAutoBrightnessEnabled(getApplicationContext(), !Utils.isAutoBrightnessEnabled(getApplicationContext()));
        }
    }

    public void showPersistentNotification() {
        Context context = getApplicationContext();
        Intent notificationIntent = new Intent(context, MainActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent intent = PendingIntent.getActivity(getApplicationContext(), 0,
                notificationIntent, PendingIntent.FLAG_IMMUTABLE);
        Notification n = mStatsBuilder
                .setStyle(
                        new NotificationCompat.BigTextStyle()
                                .bigText(getString(R.string.stats_no_data)))
                .setSmallIcon(R.drawable.ic_battery_health)
                .setPriority(-2)
                .setContentIntent(intent)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            startForeground(PERSISTENT_NOTIF_ID, n);
        } else {
            startForeground(PERSISTENT_NOTIF_ID, n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        }
    }

    public void updatePersistentNotification(String lastScreenOff, int timeSpentDozing, int batteryUsage) {
        Intent notificationIntent = new Intent(getApplicationContext(), MainActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent intent = PendingIntent.getActivity(getApplicationContext(), 0,
                notificationIntent, PendingIntent.FLAG_IMMUTABLE);
        Notification n = mStatsBuilder
                .setStyle(
                        new NotificationCompat.BigTextStyle()
                                .bigText(getString(R.string.stats_long_text, lastScreenOff, timeSpentDozing, batteryUsage))
                                .setSummaryText(getString(R.string.stats_summary_text, batteryUsage)))
                .setShowWhen(false)
                .setSmallIcon(R.drawable.ic_battery_health)
                .setPriority(-2)
                .setContentIntent(intent)
                .setOngoing(true)
                .build();
        startForeground(PERSISTENT_NOTIF_ID, n);
    }

    public void hidePersistentNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
        }
    }

    public void showSilentNotification() {
        // On Android 12+, foreground services require a notification.
        // Clicking this notification opens the channel settings where the user can disable it
        // or minimize it further by setting it to "Silent" or "Minimized" importance.
        Intent notificationIntent = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS);
        notificationIntent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        notificationIntent.putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL_SILENT);
        notificationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        
        PendingIntent intent = PendingIntent.getActivity(getApplicationContext(), 0,
                notificationIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        
        Notification n = new NotificationCompat.Builder(this, CHANNEL_SILENT)
                .setSmallIcon(R.drawable.ic_battery_health)
                .setContentTitle(getString(R.string.silent_notification_title))
                .setContentText(getString(R.string.silent_notification_text))
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setContentIntent(intent)
                .setOngoing(true)
                .setSilent(true)
                .setShowWhen(false)
                .build();
        
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            startForeground(PERSISTENT_NOTIF_ID, n);
        } else {
            startForeground(PERSISTENT_NOTIF_ID, n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        }
    }

    public void setMobileNetwork(Context context, int targetState) {

        if (!Utils.isReadPhoneStatePermissionGranted(context)) {
            grantReadPhoneStatePermission();
        }

        String command;
        try {
            String transactionCode = getTransactionCode(context);
            SubscriptionManager mSubscriptionManager = (SubscriptionManager) context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
            for (int i = 0; i < mSubscriptionManager.getActiveSubscriptionInfoCountMax(); i++) {
                if (transactionCode != null && transactionCode.length() > 0) {
                    @SuppressLint("MissingPermission") int subscriptionId = mSubscriptionManager.getActiveSubscriptionInfoList().get(i).getSubscriptionId();
                    command = "service call phone " + transactionCode + " i32 " + subscriptionId + " i32 " + targetState;
                    List<String> output = new ArrayList<>();
                    List<String> err = new ArrayList<>();
                    Shell.Pool.SU.run(command, output, err, false);
                    if (err.isEmpty()) {
                        for (String s : output) {
                            log(s);
                        }
                    } else {
                        log("Error occurred while executing command (" + err + ")");
                    }
                }
            }
        } catch (Exception e) {
            log("Failed to toggle mobile data: " + e.getMessage());
        }
    }

    private static String getTransactionCode(Context context) {
        try {
            final TelephonyManager mTelephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            final Class<?> mTelephonyClass = Class.forName(mTelephonyManager.getClass().getName());
            final Method mTelephonyMethod = mTelephonyClass.getDeclaredMethod("getITelephony");
            mTelephonyMethod.setAccessible(true);
            final Object mTelephonyStub = mTelephonyMethod.invoke(mTelephonyManager);
            final Class<?> mTelephonyStubClass = Class.forName(mTelephonyStub.getClass().getName());
            final Class<?> mClass = mTelephonyStubClass.getDeclaringClass();
            final Field field = mClass.getDeclaredField("TRANSACTION_setDataEnabled");
            field.setAccessible(true);
            return String.valueOf(field.getInt(null));
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public void setNotificationEnabledForPackage(String packageName, boolean enabled) {
        int command = 0;
        try {
            @SuppressLint("PrivateApi") Field field = Class.forName("android.app.INotificationManager").getDeclaredClasses()[0].getDeclaredField("TRANSACTION_setNotificationsEnabledForPackage");
            field.setAccessible(true);
            command = field.getInt(null);
        } catch (ClassNotFoundException e) {
            log(e.toString());
        } catch (NoSuchFieldException e2) {
            log(e2.toString());
        } catch (IllegalAccessException e3) {
            log(e3.toString());
        }

        ArrayList<PackageInfo> packageInfos = new ArrayList<>(getPackageManager().getInstalledPackages(PackageManager.GET_META_DATA));

        for (PackageInfo p : packageInfos) {
            if (p.packageName.equals(packageName)) {
                log((enabled ? "Turning on " : "Turning off ") + "notifications for " + packageName);
                String exec = String.format(Locale.US, "service call notification %d s16 %s i32 %d i32 %d", command, packageName, p.applicationInfo.uid, enabled ? 1 : 0);
                executeCommandWithRoot(exec);
            }
        }
    }

    public void setPackageState(Context context, String packageName, boolean enabled) {
        log((enabled ? "Enabling " : "Disabling ") + packageName);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            executeCommandWithRoot("pm " + (enabled ? "unsuspend " : "suspend ") + packageName);
        } else {
            executeCommandWithRoot("pm " + (enabled ? "enable " : "disable ") + packageName);
        }
    }

    public String getDeviceIdleState() {
        log("Fetching Device Idle state...");
        if (Utils.isDeviceRunningOnN()) {
            if (isSuAvailable || isShizukuAvailable) {
                if (rootSession != null) {
                    rootSession.addCommand("dumpsys deviceidle", 0, (Shell.OnCommandResultListener2) (commandCode, exitCode, output, stderr) -> {
                        if (!output.isEmpty()) {
                            String outputString = TextUtils.join(", ", output);
                            if (outputString.contains("mState=ACTIVE")) {
                                state = "ACTIVE";
                            } else if (outputString.contains("mState=INACTIVE")) {
                                state = "INACTIVE";
                            } else if (outputString.contains("mState=IDLE_PENDING")) {
                                state = "IDLE_PENDING";
                            } else if (outputString.contains("mState=SENSING")) {
                                state = "SENSING";
                            } else if (outputString.contains("mState=LOCATING")) {
                                state = "LOCATING";
                            } else if (outputString.contains("mState=IDLE")) {
                                state = "IDLE";
                            } else if (outputString.contains("mState=IDLE_MAINTENANCE")) {
                                state = "IDLE_MAINTENANCE";
                            } else if (outputString.contains("mState=PRE_IDLE")) {
                                state = "PRE_IDLE";
                            } else if (outputString.contains("mState=WAITING_FOR_NETWORK")) {
                                state = "WAITING_FOR_NETWORK";
                            } else if (outputString.contains("mState=OVERRIDE")) {
                                state = "OVERRIDE";
                            }
                        } else {
                            if (pm.isDeviceIdleMode()) {
                                state = "IDLE";
                            } else {
                                state = "ACTIVE";
                            }
                        }
                    });
                }
            } else {
                if (pm.isDeviceIdleMode()) {
                    state = "IDLE";
                } else {
                    state = "ACTIVE";
                }
            }
        } else {
            List<String> output = new ArrayList<>();
            List<String> err = new ArrayList<>();
            try {
                Shell.Pool.SU.run("dumpsys deviceidle", output, err, false);
            } catch (Shell.ShellDiedException e) {
                e.printStackTrace();
            }
            String outputString = TextUtils.join(", ", output);
            if (outputString.contains("mState=ACTIVE")) {
                state = "ACTIVE";
            } else if (outputString.contains("mState=INACTIVE")) {
                state = "INACTIVE";
            } else if (outputString.contains("mState=IDLE_PENDING")) {
                state = "IDLE_PENDING";
            } else if (outputString.contains("mState=SENSING")) {
                state = "SENSING";
            } else if (outputString.contains("mState=LOCATING")) {
                state = "LOCATING";
            } else if (outputString.contains("mState=IDLE")) {
                state = "IDLE";
            } else if (outputString.contains("mState=IDLE_MAINTENANCE")) {
                state = "IDLE_MAINTENANCE";
            }
        }

        return state;
    }


    public void disableMobileData() {
        executeCommandWithRoot("svc data disable", (commandCode, exitCode, STDOUT, STDERR) -> {
            log("disableMobileData: " + Utils.isMobileDataEnabled(getApplicationContext()));
//            if (Utils.isMobileDataEnabled(getApplicationContext())) {
//                Log.e(TAG, "disableMobileData failed, data still active");
//            }
        });
    }

    public void enableMobileData() {
        executeCommandWithRoot("svc data enable", (commandCode, exitCode, STDOUT, STDERR) -> {
            log("enableMobileData: " + Utils.isMobileDataEnabled(getApplicationContext()));
//            if (Utils.isMobileDataEnabled(getApplicationContext())) {
//                Log.e(TAG, "enableMobileData failed, data still inactive");
//            }
        });
    }


    public void disableWiFi() {
        if (isSuAvailable || isShizukuAvailable) {
            executeCommandWithRoot("svc wifi disable", (commandCode, exitCode, STDOUT, STDERR) -> {
                log("disableWiFi: " + Utils.isWiFiEnabled(getApplicationContext()));
            });
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            wifi.setWifiEnabled(false);
            log("disableWiFi: " + Utils.isWiFiEnabled(getApplicationContext()));
        }
        if (Utils.isMobileDataEnabled(getApplicationContext())) {
            Log.e(TAG, "disableWiFi failed, wifi still active");
        }
    }

    public void setAllSensorsState(Context context, boolean enabled) {
        if (!isSuAvailable && !isShizukuAvailable) {
            return;
        }
//        if (!Utils.isSecureSensorPrivacyPermissionGranted(context)) {
//            grantSensorPrivacyPermission();
//        }

        String command;
        try {
            int transactionCode = 4;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                transactionCode = 9;
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                transactionCode = 8;
            }
//            executeCommandWithRoot("service call sensor_privacy " + transactionCode + " i32 " +(enabled? 0:1));
            command = "service call sensor_privacy " + transactionCode + " i32 " + (enabled ? 0 : 1);
            List<String> output = new ArrayList<>();
            List<String> err = new ArrayList<>();
            Shell.Pool.SU.run(command, output, err, false);
            if (err.isEmpty()) {
//                for (String s : output) {
//                    log(s);
//                }
            } else {
                log("Error occurred while executing command (" + err + ")");
            }
        } catch (Exception e) {
            log("Failed to toggle sensor off: " + e.getMessage());
        }
    }

    public void setBiometricsSensorState(Context context, boolean enabled) {
        if (!isSuAvailable && !isShizukuAvailable) {
            return;
        }
        if (!Utils.isSecureSettingsPermissionGranted(context)) {
            grantSecureSettingsPermission();
        }
        executeCommandWithRoot("settings put secure biometric_keyguard_enabled " + (enabled ? 1 : 0));
    }

    public void setBatterSaverState(Context context, boolean enabled) {
        if (!isSuAvailable && !isShizukuAvailable) {
            return;
        }
//        if (!Utils.isSecureSettingsPermissionGranted(context)) {
//            grantSecureSettingsPermission();
//        }
        executeCommandWithRoot("settings put global low_power " + (enabled ? 1 : 0));
    }

    public void setAirplaneState(Context context, boolean enabled) {
        if (!isSuAvailable && !isShizukuAvailable) {
            return;
        }
//        if (!Utils.isSecureSettingsPermissionGranted(context)) {
//            grantSecureSettingsPermission();
//        }
        executeCommandWithRoot("settings put global airplane_mode_on " + (enabled ? 1 : 0));
        executeCommandWithRoot("am broadcast -a android.intent.action.AIRPLANE_MODE --ez state " + (enabled ? "true" : "false"));
    }

    public void setBluetoothState(Context context, boolean enabled) {
        if (!isSuAvailable && !isShizukuAvailable) {
            return;
        }
        if (enabled) {
            executeCommandWithRoot("svc bluetooth enable", (commandCode, exitCode, STDOUT, STDERR) -> {
                log("enableBluetooth: " + Utils.isBluetoothEnabled(getContentResolver()));
            });
        } else {
            executeCommandWithRoot("svc bluetooth disable", (commandCode, exitCode, STDOUT, STDERR) -> {
                log("disableBluetooth: " + Utils.isBluetoothEnabled(getContentResolver()));
            });
        }
    }

    public void setGPSState(Context context, boolean enabled) {
        if (!isSuAvailable && !isShizukuAvailable) {
            return;
        }
        int locationMode = enabled ? Settings.Secure.LOCATION_MODE_HIGH_ACCURACY : Settings.Secure.LOCATION_MODE_OFF;
        executeCommandWithRoot("settings put secure location_mode " + locationMode);
    }

    public void enableWiFi() {
        if (isSuAvailable || isShizukuAvailable) {
            executeCommandWithRoot("svc wifi enable", (commandCode, exitCode, STDOUT, STDERR) -> {
                log("enableWiFi: " + Utils.isWiFiEnabled(getApplicationContext()));
            });
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            wifi.setWifiEnabled(true);
            log("enableWiFi: " + Utils.isWiFiEnabled(getApplicationContext()));
        }

        if (Utils.isMobileDataEnabled(getApplicationContext())) {
            Log.e(TAG, "enableWiFi failed, wifi still inactive");
        }
    }

    class ReloadSettingsReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            log("User changed a setting, loading new settings into service");
            reloadSettings();
        }
    }

    class ReloadNotificationBlocklistReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            log("User modified Notification blocklist, loading new packages into service");
            reloadNotificationBlockList();
        }
    }

    class ReloadAppsBlocklistReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            log("User modified Doze app blocklist, loading new packages into service");
            reloadAppsBlockList();
        }
    }

    class PendingIntentDozeReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            log("Pending intent broadcast received");
            setPendingDozeEnterAlarm = false;
            applyDoze();
        }
    }

    public void actualEnterDozeHandleNetwork(Context context, String packageName) {
        log("playingPackageName: " + packageName);
        // Capture the CURRENT device state at the moment screen turns off
        // These represent user's preference while screen was on
        wasWiFiTurnedOn = Utils.isWiFiEnabled(context);
        wasMobileDataTurnedOn = Utils.isMobileDataEnabled(context);
        wasAirplaneOn = Utils.isAirplaneEnabled(getContentResolver());
        wasBluetoothOn = Utils.isBluetoothEnabled(getContentResolver());
        wasGPSOn =  Utils.isLocationEnabled(getContentResolver());
        wasHotSpotTurnedOn = Utils.isHotspotEnabled(context);
        wasBatterSaverOn = Utils.isBatterSaverEnabled(getContentResolver());

        if (turnOffAllSensorsInDoze) {
            log("Disabling All sensors");
            setAllSensorsState(context, false);
        }
        if (turnOffBiometricsInDoze) {
            log("Disabling Biometrics");
            setBiometricsSensorState(context, false);
        }
        if (turnOnBatterySaverInDoze) {
            log("Enabling Battery Saver");
            setBatterSaverState(context, true);
        }

        if (turnOnAirplaneInDoze && (ignoreIfHotspot || !wasHotSpotTurnedOn) && !wasAirplaneOn && packageName == null) {
            log("Enabling airplane");
            setAirplaneState(context, true);
        }

        if (turnOffBluetoothInDoze && wasBluetoothOn && packageName == null) {
            log("Disabling Bluetooth");
            setBluetoothState(context, false);
        }

        if (turnOffGPSInDoze && wasGPSOn && packageName == null) {
            log("Disabling GPS/Location");
            setGPSState(context, false);
        }

        if (turnOffWiFiInDoze && (ignoreIfHotspot || !wasHotSpotTurnedOn) && wasWiFiTurnedOn && packageName == null) {
            log("Disabling WiFi");
            disableWiFi();
        }

        if (turnOffDataInDoze && wasMobileDataTurnedOn && (ignoreIfHotspot || !wasHotSpotTurnedOn) && (packageName == null || wasWiFiTurnedOn)) {
            log("Disabling mobile data");
            disableMobileData();
        }
    }

    public void enterDozeHandleNetwork(Context context) {
        if (whitelistMusicAppNetwork) {
            try {
                NotificationService notifService = NotificationService.Companion.getInstance();
                if (notifService != null) {
                    notifService.getPlayingPackageName((String packageName) -> {
                        actualEnterDozeHandleNetwork(context, packageName);
                        return null;
                    });
                    return;
                }

            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        actualEnterDozeHandleNetwork(context, null);
    }

    public void leaveDozeHandleNetwork(Context context) {

        if (turnOnAirplaneInDoze) {
            log("wasAirplaneOn: " + wasAirplaneOn);
            if (!wasAirplaneOn) {
                log("disabling Airplane");
                setAirplaneState(context, false);
            }
        }
        if (turnOffBluetoothInDoze) {
            log("wasBluetoothOn: " + wasBluetoothOn);
            if (wasBluetoothOn) {
                log("Enabling Bluetooth");
                setBluetoothState(context, true);
            }
        }
        if (turnOffGPSInDoze) {
            log("wasGPSOn: " + wasGPSOn);
            if (wasGPSOn) {
                log("Enabling GPS/Location");
                setGPSState(context, true);
            }
        }
        if (turnOffWiFiInDoze) {
            log("wasWiFiTurnedOn: " + wasWiFiTurnedOn);
            if (wasWiFiTurnedOn) {
                log("Enabling WiFi");
                enableWiFi();
            }

        }
        if (turnOffAllSensorsInDoze) {
            log("Enabling All sensors");
            setAllSensorsState(context, true);
        }
        // biometrics are re enabled directly on screen on
//        if (turnOffBiometricsInDoze) {
//            log("Enabling biometrics");
//            setBiometricsSensorState(context, true);
//        }
        if (turnOnBatterySaverInDoze) {
            log("Disabling battery saver");
            setBatterSaverState(context, false);
        }

        if (turnOffDataInDoze) {
            log("wasDataTurnedOn: " + wasMobileDataTurnedOn);
            if (wasMobileDataTurnedOn) {
                log("Enabling mobile data");
                enableMobileData();
            }
        }
        // Note: was... properties are NOT reset here anymore.
        // They will be reset when screen turns ON to track new user preferences.
    }

    public void handleScreenOn(Context context, int time, int delay) {
        log("handleScreenOn");
        log("Last known Doze state: " + lastKnownState);

        if (tempWakeLock != null) {
            if (tempWakeLock.isHeld()) {
                log("Releasing ForceDozeTempWakelock");
                tempWakeLock.release();
            }
        }

        // Restore settings based on was... properties, THEN reset them
        leaveDozeHandleNetwork(context);
        
        // Reset was... properties to track new user preferences while screen is on
        // This ensures we capture fresh state when screen next turns off
        wasWiFiTurnedOn = false;
        wasBatterSaverOn = false;
        wasMobileDataTurnedOn = false;
        wasAirplaneOn = false;
        wasBluetoothOn = false;
        wasGPSOn = false;
        
        String newDeviceIdleState = getDeviceIdleState();
        if (!newDeviceIdleState.equals("ACTIVE") || !lastKnownState.equals("ACTIVE")) {
            log("Exiting Doze");
            exitDoze(newDeviceIdleState);
        } else {
            if (ignoreLockscreenTimeout) {
                log("Cancelling enterDoze() because user turned on screen and " + (delay) + "ms has not passed OR disableWhenCharging=true");
            } else {
                log("Cancelling enterDoze() because user turned on screen and " + (time) + "ms has not passed OR disableWhenCharging=true");
            }
            enterDozeTimer.cancel();
            // Ensure apps in dozeAppBlocklist are re-enabled even when device is already ACTIVE
            reEnableBlockedAppsAndNotifications();
        }
    }

    class DozeReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(final Context context, Intent intent) {
            int time = Settings.Secure.getInt(getContentResolver(), "lock_screen_lock_after_timeout", 5000);
            if (time == 0) {
                time = 1000;
            }
            int delay = dozeEnterDelay * 1000;
            time = time + delay;

            if (intent.getAction().equals(Intent.ACTION_AIRPLANE_MODE_CHANGED)) {
                log("airplane mode changed " + Utils.isAirplaneEnabled(getContentResolver()));
            } else if (intent.getAction().equals(Intent.ACTION_USER_PRESENT)) {
                log("UNLOCK received " + waitForUnlock);
                if (waitForUnlock) {
                    handleScreenOn(context, time, delay);
                }
            } else if (intent.getAction().equals(Intent.ACTION_SCREEN_ON)) {
                log("Screen ON received" + waitForUnlock);
                if (!Utils.isDeviceLocked(context) || !waitForUnlock) {
                    handleScreenOn(context, time, delay);
                }
                // we always enable biometrics on screen on for the user to be able to unlock
                if (turnOffBiometricsInDoze) {
                    log("Enabling biometrics");
                    setBiometricsSensorState(context, true);
                }
            } else if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
                log("Screen OFF received");
                if (disableWhenCharging && Utils.isConnectedToCharger(getApplicationContext())) {
                    log("Connected to charger and disableWhenCharging=true, skip entering Doze");
                } else if (Utils.isUserInCommunicationCall(context)) {
                    log("User is in a VOIP call or an audio/video chat, skip entering Doze");
                } else if (Utils.isUserInCall(context)) {
                    log("User is in a phone call, skip entering Doze");
                } else {
                    log("Doze delay: " + delay + "ms");
                    if (ignoreLockscreenTimeout) {
                        if (dozeEnterDelay == 0) {
                            log("Ignoring lockscreen timeout value and entering Doze immediately");
                            enterDoze(context);
                        } else {
                            log("Waiting for " + (delay) + "ms and then entering Doze");
                            tempWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "forcedoze:tempWakelock");
                            log("Acquiring temporary wakelock (ForceDozeTempWakelock)");
                            tempWakeLock.acquire(10 * 60 * 1000L /*10 minutes*/);
                            enterDozeTimer = new Timer();
                            enterDozeTimer.schedule(new TimerTask() {
                                @Override
                                public void run() {
                                    enterDoze(context);
                                }
                            }, delay);
                        }
                    } else {
                        log("Waiting for " + (time) + "ms and then entering Doze");
                        if (Utils.isLockscreenTimeoutValueTooHigh(getContentResolver())) {
                            tempWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "forcedoze:tempWakelock");
                            log("Acquiring temporary wakelock (ForceDozeTempWakelock)");
                            tempWakeLock.acquire(10 * 60 * 1000L /*10 minutes*/);
                        }
                        enterDozeTimer = new Timer();
                        enterDozeTimer.schedule(new TimerTask() {
                            @Override
                            public void run() {
                                enterDoze(context);
                            }
                        }, time);
                    }

                }
            } else if (intent.getAction().equals(Intent.ACTION_POWER_CONNECTED)) {
                String newDeviceIdleState = getDeviceIdleState();
                if (disableWhenCharging && (newDeviceIdleState.equals("IDLE") || !Utils.isScreenOn(context)) ) {
                    log("Charger connected, exiting Doze mode");
                    enterDozeTimer.cancel();
                    exitDoze(newDeviceIdleState);
                }
            } else if (intent.getAction().equals(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)) {
                if (!Utils.isScreenOn(context)) {
                    log("ACTION_DEVICE_IDLE_MODE_CHANGED received");
                    lastKnownState = getDeviceIdleState();
                    log("Current (Deep) state: " + lastKnownState);
                    if (lastKnownState.equals("IDLE_MAINTENANCE")) {
                        if (!maintenance) {
                            log("Device exited Doze for maintenance");
                            if (!disableStats) {
                                dozeUsageData.add(Long.toString(System.currentTimeMillis()).concat(",").concat(Float.toString(Utils.getBatteryLevel(getApplicationContext()))).concat(",").concat("EXIT_MAINTENANCE"));
                                saveDozeDataStats();
                            }

                            leaveDozeHandleNetwork(context);
                            maintenance = true;
                        }
                    } else if (lastKnownState.equals("IDLE")) {
                        if (maintenance) {
                            log("Device entered Doze after maintenance");
                            if (!disableStats) {
                                dozeUsageData.add(Long.toString(System.currentTimeMillis()).concat(",").concat(Float.toString(Utils.getBatteryLevel(getApplicationContext()))).concat(",").concat("ENTER_MAINTENANCE"));
                                saveDozeDataStats();
                            }
                            enterDozeHandleNetwork(context);
                            maintenance = false;
                        }
                    }
                }
            } else if (intent.getAction().equals("android.os.action.LIGHT_DEVICE_IDLE_MODE_CHANGED")) {
                if (!Utils.isScreenOn(context)) {
                    log("LIGHT_DEVICE_IDLE_MODE_CHANGED received");
                    lastKnownState = getDeviceIdleState();
                    log("Current (Light) state: " + lastKnownState);
                }
            }
        }
    }

}
