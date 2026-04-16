package com.akylas.enforcedoze;

import static com.akylas.enforcedoze.Utils.logToLogcat;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.DialogFragment;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragment;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceManager;
import androidx.preference.SwitchPreferenceCompat;
import androidx.recyclerview.widget.RecyclerView;

import android.util.Log;
import android.view.MenuItem;
import android.view.View;

import com.afollestad.materialdialogs.MaterialDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.jakewharton.processphoenix.ProcessPhoenix;
import com.nanotasks.Completion;
import com.nanotasks.Tasks;

import java.util.List;

import eu.chainfire.libsuperuser.Shell;

public class SettingsActivity extends AppCompatActivity {
    public static String TAG = "EnforceDoze";
    static MaterialDialog progressDialog1 = null;
    private static Shell.Interactive rootSession;
    private static Shell.Interactive nonRootSession;

    private static void log(String message) {
            logToLogcat(TAG, message);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_settings);
        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.settings, new SettingsFragment())
                    .commit();
        }
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

    }

    public static void reloadSettings(Context context) {
        if (Utils.isMyServiceRunning(ForceDozeService.class, context)) {
            Intent intent = new Intent("reload-settings");
            LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (rootSession != null) {
            if (rootSession.isRunning()) {
                rootSession.close();
            }
            rootSession = null;
        }
        if (nonRootSession != null) {
            nonRootSession.close();
            nonRootSession = null;
        }
        reloadSettings(this);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        switch (id) {
            case android.R.id.home:
                onBackPressed();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    public static class SettingsFragment extends PreferenceFragmentCompat implements SharedPreferences.OnSharedPreferenceChangeListener {

        boolean isSuAvailable = false;
        boolean isShizukuAvailable = false;
        private ShizukuHandler shizukuHandler;

        private void removeIconSpace(PreferenceGroup group) {
            for (int i = 0; i < group.getPreferenceCount(); i++) {
                Preference pref = group.getPreference(i);
                pref.setIconSpaceReserved(false);

                if (pref instanceof PreferenceGroup) {
                    removeIconSpace((PreferenceGroup) pref);
                }
            }
        }
        @Override
        public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
            super.onViewCreated(view, savedInstanceState);

            ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
                RecyclerView recyclerView =
                        v.findViewById(androidx.preference.R.id.recycler_view);

                if (recyclerView != null) {
                    int bottomInset = insets
                            .getInsets(WindowInsetsCompat.Type.systemBars())
                            .bottom;

                    recyclerView.setPadding(
                            recyclerView.getPaddingLeft(),
                            recyclerView.getPaddingTop(),
                            recyclerView.getPaddingRight(),
                            bottomInset
                    );
                    recyclerView.setClipToPadding(false);
                }
                return insets;
            });
        }
        @Override
        public void onDisplayPreferenceDialog(@NonNull androidx.preference.Preference preference) {
            if (preference instanceof ListPreference) {
                showListPreferenceDialog((ListPreference)preference);
            } else {
                super.onDisplayPreferenceDialog(preference);
            }
        }

        private void showListPreferenceDialog(ListPreference preference) {
            DialogFragment dialogFragment = new MaterialListPreference();
            Bundle bundle = new Bundle(1);
            bundle.putString("key", preference.getKey());
            dialogFragment.setArguments(bundle);
            dialogFragment.setTargetFragment(this, 0);
            dialogFragment.show(getParentFragmentManager(), "androidx.preference.PreferenceFragment.DIALOG");
        }

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
        }

        private void initializeShizuku() {
            shizukuHandler.checkShizukuAvailability();
            shizukuHandler.setOnAvailibilityChangeListener(value -> {
                isShizukuAvailable = value;
                toggleRootFeatures(isShizukuAvailable || isSuAvailable);
            });
            isShizukuAvailable = shizukuHandler.isShizukuAvailable();
            log("Shizuku mode enabled, available: " + isShizukuAvailable);
        }


        @Override
        public void onCreatePreferences(@Nullable Bundle savedInstanceState, String rootKey) {
            shizukuHandler = ShizukuHandler.getInstance(getActivity());
            boolean useShizuku = Utils.isShizukuMode(getActivity());
            isShizukuAvailable = false;
            if (useShizuku) {
                initializeShizuku();
            }
            // Initialize root and non-root shell
            executeCommandWithRoot("whoami");
            executeCommandWithoutRoot("whoami");

            addPreferencesFromResource(R.xml.prefs);
            removeIconSpace(getPreferenceScreen());
//            PreferenceScreen preferenceScreen = (PreferenceScreen) findPreference("preferenceScreen");
//            PreferenceCategory mainSettings = (PreferenceCategory) findPreference("mainSettings");
//            PreferenceCategory dozeSettings = (PreferenceCategory) findPreference("dozeSettings");
            Preference resetForceDozePref = (Preference) findPreference("resetForceDoze");
            Preference clearDozeStats = (Preference) findPreference("resetDozeStats");
            Preference dozeDelay = (Preference) findPreference("dozeEnterDelay");
            Preference showPersistentNotif = (Preference) findPreference("showPersistentNotif");
            Preference usePermanentDoze = (Preference) findPreference("usePermanentDoze");
            Preference dozeNotificationBlocklist = (Preference) findPreference("blacklistAppNotifications");
            Preference dozeAppBlocklist = (Preference) findPreference("blacklistApps");
            final Preference nonRootSensorWorkaround = (Preference) findPreference("useNonRootSensorWorkaround");
            final Preference executionMode = (Preference) findPreference("executionMode");
            final Preference disableMotionSensors = (Preference) findPreference("disableMotionSensors");
            Preference turnOffDataInDoze = (Preference) findPreference("turnOffDataInDoze");
            Preference whitelistMusicAppNetwork = (Preference) findPreference("whitelistMusicAppNetwork");
            Preference whitelistCurrentApp = (Preference) findPreference("whitelistCurrentApp");
            final Preference autoRotateBrightnessFix = (Preference) findPreference("autoRotateAndBrightnessFix");
            SwitchPreferenceCompat autoRotateFixPref = (SwitchPreferenceCompat) findPreference("autoRotateAndBrightnessFix");

            SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getActivity());
            sharedPreferences.registerOnSharedPreferenceChangeListener(this);

            resetForceDozePref.setOnPreferenceClickListener(preference -> {
                MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(getActivity());
                builder.setTitle(getString(R.string.forcedoze_reset_initial_dialog_title));
                builder.setMessage(getString(R.string.forcedoze_reset_initial_dialog_text));
                builder.setPositiveButton(getString(R.string.yes_button_text), (dialogInterface, i) -> {
                    dialogInterface.dismiss();
                    resetForceDoze();
                });
                builder.setNegativeButton(getString(R.string.no_button_text), (dialogInterface, i) -> dialogInterface.dismiss());
                builder.show();
                return true;
            });
            showPersistentNotif.setOnPreferenceChangeListener((preference, value) -> {
                if ((boolean)value) {
                    if (!Utils.isPostNotificationPermissionGranted(getActivity())) {
                        requestNotificationPermission();
                        return false;
                    }
                }
                return true;
            });


            executionMode.setOnPreferenceChangeListener((preference, value) -> {
                if (value.equals("shizuku")) {
                    initializeShizuku();
                    ShizukuHandler.getInstance(getActivity()).requestShizukuPermission();
                    Utils.grantPermissionsViaShizuku(getActivity());
                    toggleRootFeatures(isSuAvailable || isShizukuAvailable);
                } else {
                    toggleRootFeatures(isSuAvailable);
                }
                boolean serviceEnabled = sharedPreferences.getBoolean("serviceEnabled", false);
                if (serviceEnabled) {
                    Context context = getActivity();
                    Intent intent = new Intent(context, ForceDozeService.class);
                    context.stopService(intent);
                    context.startService(intent);
                }
                return true;
            });

            dozeDelay.setOnPreferenceChangeListener((preference, o) -> {
                int delay = (int) o;
                if (delay >= 5 * 60) {
                    MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(getActivity());
                    builder.setTitle(getString(R.string.doze_delay_warning_dialog_title));
                    builder.setMessage(getString(R.string.doze_delay_warning_dialog_text));
                    builder.setPositiveButton(getString(R.string.okay_button_text), (dialogInterface, i) -> dialogInterface.dismiss());
                    builder.show();
                }
                return true;
            });

            autoRotateFixPref.setOnPreferenceChangeListener((preference, o) -> {
                if (!Utils.isWriteSettingsPermissionGranted(getActivity())) {
                    requestWriteSettingsPermission();
                    return false;
                } else return true;
            });

            clearDozeStats.setOnPreferenceClickListener(preference -> {
                progressDialog1 = new MaterialDialog.Builder(getActivity())
                        .title(getString(R.string.please_wait_text))
                        .cancelable(false)
                        .autoDismiss(false)
                        .content(getString(R.string.clearing_doze_stats_text))
                        .progress(true, 0)
                        .show();
                Tasks.executeInBackground(getActivity(), () -> {
                    log("Clearing Doze stats");
                    SharedPreferences sharedPreferences13 = PreferenceManager.getDefaultSharedPreferences(getContext());
                    SharedPreferences.Editor editor = sharedPreferences13.edit();
                    editor.remove("dozeUsageDataAdvanced");
                    return editor.commit();
                }, new Completion<Boolean>() {
                    @Override
                    public void onSuccess(Context context, Boolean result) {
                        if (progressDialog1 != null) {
                            progressDialog1.dismiss();
                        }
                        if (result) {
                            log("Doze stats successfully cleared");
                            if (Utils.isMyServiceRunning(ForceDozeService.class, context)) {
                                Intent intent = new Intent("reload-settings");
                                LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
                            }
                            MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context);
                            builder.setTitle(getString(R.string.cleared_text));
                            builder.setMessage(getString(R.string.doze_battery_stats_clear_msg));
                            builder.setPositiveButton(getString(R.string.close_button_text), (dialogInterface, i) -> dialogInterface.dismiss());
                            builder.show();
                        }

                    }

                    @Override
                    public void onError(Context context, Exception e) {
                        Log.e(TAG, "Error clearing Doze stats: " + e.getMessage());

                    }
                });
                return true;
            });

            nonRootSensorWorkaround.setOnPreferenceChangeListener((preference, o) -> {
                boolean newValue = (boolean) o;
                SharedPreferences sharedPreferences1 = PreferenceManager.getDefaultSharedPreferences(getActivity());
                SharedPreferences.Editor editor = sharedPreferences1.edit();
                if (newValue) {
                    editor.putBoolean("disableMotionSensors", false);
                    editor.apply();
                    autoRotateBrightnessFix.setEnabled(false);
                    disableMotionSensors.setEnabled(false);
                } else {
                    autoRotateBrightnessFix.setEnabled(true);
                    disableMotionSensors.setEnabled(true);
                }
                return true;
            });

            turnOffDataInDoze.setOnPreferenceChangeListener((preference, o) -> {
                final boolean newValue = (boolean) o;
                if (!newValue) {
                    return true;
                } else {
                    if (isSuAvailable) {
                        log("Phone is rooted and SU permission granted");
                        log("Granting android.permission.READ_PHONE_STATE to com.akylas.enforcedoze");
                        executeCommand("pm grant com.akylas.enforcedoze android.permission.READ_PHONE_STATE");
                        return true;
                    } else {
                        log("SU permission denied or not available");
                        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(getActivity());
                        builder.setTitle(getString(R.string.error_text));
                        builder.setMessage(getString(R.string.su_perm_denied_msg));
                        builder.setPositiveButton(getString(R.string.close_button_text), (dialogInterface, i) -> dialogInterface.dismiss());
                        builder.show();
                        return false;
                    }
                }
            });

            whitelistMusicAppNetwork.setOnPreferenceChangeListener((preference, o) -> {
                final boolean newValue = (boolean) o;
                if (newValue) {
                    // we need to check if we have notifications permissions
                    Boolean hasPermission = NotificationService.Companion.getInstance() != null;
                    if (!hasPermission) {
                        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(getActivity());
                        builder.setTitle(getString(R.string.notifications_permission));
                        builder.setMessage(getString(R.string.notifications_permission_explanation));
                        builder.setPositiveButton(getString(R.string.open_button_text), (dialogInterface, i) -> {
                            Intent settingsIntent = null;
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                settingsIntent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        .putExtra(Settings.EXTRA_APP_PACKAGE, getActivity().getPackageName());
                            }
                            getActivity().startActivity(settingsIntent);
                            dialogInterface.dismiss();
                        });
                        builder.show();
                    }
                }
                return true;
            });

            whitelistCurrentApp.setOnPreferenceChangeListener((preference, o) -> {
                final boolean newValue = (boolean) o;
                if (newValue) {
                    // we need to check if we have notifications permissions
                    if (!isSuAvailable && !Utils.isUsageStatsPermissionGranted(getContext())) {
                        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(getActivity());
                        builder.setTitle(getString(R.string.usage_access_permission));
                        builder.setMessage(getString(R.string.usage_access_explanation));
                        builder.setPositiveButton(getString(R.string.open_button_text), (dialogInterface, i) -> {
                            getActivity().startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
                            dialogInterface.dismiss();
                        });
                        builder.show();
                    }
                }
                return true;
            });

//            if (sharedPreferences.getBoolean("useNonRootSensorWorkaround", false)) {
//                autoRotateBrightnessFix.setEnabled(true);
//                disableMotionSensors.setEnabled(true);
//                sharedPreferences.edit().putBoolean("autoRotateAndBrightnessFix", false).apply();
//                sharedPreferences.edit().putBoolean("disableMotionSensors", true).apply();
//            }

            turnOffDataInDoze.setEnabled(false);
            turnOffDataInDoze.setSummary(getString(R.string.root_required_text));
            dozeNotificationBlocklist.setEnabled(false);
            dozeNotificationBlocklist.setSummary(getString(R.string.root_required_text));
            dozeAppBlocklist.setEnabled(false);
            dozeAppBlocklist.setSummary(getString(R.string.root_required_text));
            
            Preference turnOffBluetoothInDoze = (Preference) findPreference("turnOffBluetoothInDoze");
            turnOffBluetoothInDoze.setEnabled(false);
            turnOffBluetoothInDoze.setSummary(getString(R.string.root_required_text));
            
            Preference turnOffGPSInDoze = (Preference) findPreference("turnOffGPSInDoze");
            turnOffGPSInDoze.setEnabled(false);
            turnOffGPSInDoze.setSummary(getString(R.string.root_required_text));

            Preference sponsorPref = findPreference("sponsorProject");
            if (sponsorPref != null) {
                sponsorPref.setOnPreferenceClickListener(preference -> {
                    Utils.openUrl(getActivity(), "https://github.com/sponsors/farfromrefug");
                    return true;
                });
            }

        }

        public void requestWriteSettingsPermission() {
            MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(getActivity());
            builder.setTitle(getString(R.string.auto_rotate_brightness_fix_dialog_title));
            builder.setMessage(getString(R.string.auto_rotate_brightness_fix_dialog_text));
            builder.setPositiveButton(getString(R.string.authorize_button_text), (dialogInterface, i) -> {
                Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
                intent.setData(Uri.parse("package:" + getActivity().getPackageName()));
                startActivity(intent);
            });
            builder.setNegativeButton(getString(R.string.deny_button_text), (dialogInterface, i) -> dialogInterface.dismiss());
            builder.show();
        }

        final int POST_NOTIF_PERMISSION_REQUEST_CODE =112;
        public void requestNotificationPermission(){
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ActivityCompat.requestPermissions(getActivity(),
                            new String[]{"android.permission.POST_NOTIFICATIONS"},
                            POST_NOTIF_PERMISSION_REQUEST_CODE);
                }
            } catch (Exception e){

            }
        }

        @Override
        public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);

            switch (requestCode) {
                case POST_NOTIF_PERMISSION_REQUEST_CODE:
                    Preference showPersistentNotif = (Preference) findPreference("showPersistentNotif");
                    showPersistentNotif.setEnabled(false);
                    // If request is cancelled, the result arrays are empty.
                    if (grantResults.length > 0 &&
                            grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                        showPersistentNotif.setEnabled(true);

                    }  else {
                        showPersistentNotif.setEnabled(false);
                        PreferenceManager.getDefaultSharedPreferences(getContext())
                                .edit()
                                .putBoolean("showPersistentNotif", false)
                                .apply();
                    }

            }

        }

        public void resetForceDoze() {
            log("Starting ForceDoze reset procedure");
            if (Utils.isMyServiceRunning(ForceDozeService.class, getActivity())) {
                log("Stopping ForceDozeService");
                getActivity().stopService(new Intent(getActivity(), ForceDozeService.class));
            }
            log("Enabling sensors, just in case they are disabled");
            executeCommand("dumpsys sensorservice enable");
            log("Disabling and re-enabling Doze mode");
            if (Utils.isDeviceRunningOnN()) {
                executeCommand("dumpsys deviceidle disable all");
                executeCommand("dumpsys deviceidle enable all");
            } else {
                executeCommand("dumpsys deviceidle disable");
                executeCommand("dumpsys deviceidle enable");
            }
            log("Resetting app preferences");
            PreferenceManager.getDefaultSharedPreferences(getActivity()).edit().clear().apply();
            log("Trying to revoke android.permission.DUMP");
            executeCommand("pm revoke com.akylas.enforcedoze android.permission.DUMP");
            executeCommand("pm revoke com.akylas.enforcedoze android.permission.READ_LOGS");
            executeCommand("pm revoke com.akylas.enforcedoze android.permission.READ_PHONE_STATE");
            executeCommand("pm revoke com.akylas.enforcedoze android.permission.WRITE_SECURE_SETTINGS");
            executeCommand("pm revoke com.akylas.enforcedoze android.permission.WRITE_SETTINGS");
            log("ForceDoze reset procedure complete");
            MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(getActivity());
            builder.setTitle(getString(R.string.reset_complete_dialog_title));
            builder.setMessage(getString(R.string.reset_complete_dialog_text));
            builder.setPositiveButton(getString(R.string.okay_button_text), (dialogInterface, i) -> {
                dialogInterface.dismiss();
                ProcessPhoenix.triggerRebirth(getActivity());
            });
            builder.show();
        }

        public void toggleRootFeatures(final boolean enabled) {
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    Preference turnOffDataInDoze = (Preference) findPreference("turnOffDataInDoze");
                    Preference dozeNotificationBlocklist = (Preference) findPreference("blacklistAppNotifications");
                    Preference dozeAppBlocklist = (Preference) findPreference("blacklistApps");
                    Preference turnOffAllSensorsInDoze = (Preference) findPreference("turnOffAllSensorsInDoze");
                    Preference turnOnBatterySaverInDoze = (Preference) findPreference("turnOnBatterySaverInDoze");
                    Preference turnOffBiometricsInDoze = (Preference) findPreference("turnOffBiometricsInDoze");
                    Preference turnOnAirplaneInDoze = (Preference) findPreference("turnOnAirplaneInDoze");
                    Preference turnOffBluetoothInDoze = (Preference) findPreference("turnOffBluetoothInDoze");
                    Preference turnOffGPSInDoze = (Preference) findPreference("turnOffGPSInDoze");
                    Preference whitelistAppsFromDozeMode = (Preference) findPreference("whitelistAppsFromDozeMode");
                    if (enabled) {
                        turnOffDataInDoze.setEnabled(true);
                        turnOffDataInDoze.setSummary(getString(R.string.disable_data_during_doze_setting_summary));
                        dozeNotificationBlocklist.setEnabled(true);
                        dozeNotificationBlocklist.setSummary(getString(R.string.notif_blocklist_setting_summary));
                        dozeAppBlocklist.setEnabled(true);
                        dozeAppBlocklist.setSummary(getString(R.string.app_blocklist_setting_summary));
                        turnOffAllSensorsInDoze.setEnabled(true);
                        turnOffAllSensorsInDoze.setSummary(getString(R.string.disable_all_sensors_setting_summary));
                        turnOnBatterySaverInDoze.setEnabled(true);
                        turnOnBatterySaverInDoze.setSummary(getString(R.string.enable_battery_saver_setting_summary));
                        turnOffBiometricsInDoze.setEnabled(true);
                        turnOffBiometricsInDoze.setSummary(getString(R.string.disable_biometrics_setting_summary));
                        turnOnAirplaneInDoze.setEnabled(true);
                        turnOnAirplaneInDoze.setSummary(getString(R.string.enable_airplane_setting_summary));
                        turnOffBluetoothInDoze.setEnabled(true);
                        turnOffBluetoothInDoze.setSummary(getString(R.string.disable_bluetooth_setting_summary));
                        turnOffGPSInDoze.setEnabled(true);
                        turnOffGPSInDoze.setSummary(getString(R.string.disable_gps_setting_summary));
                        whitelistAppsFromDozeMode.setEnabled(true);
                        whitelistAppsFromDozeMode.setSummary(getString(R.string.whitelist_apps_setting_summary));
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            Preference turnOffWiFiInDoze = (Preference) findPreference("turnOffWiFiInDoze");
                            turnOffWiFiInDoze.setEnabled(true);
                            turnOffWiFiInDoze.setSummary(getString(R.string.disable_wifi_during_doze_setting_summary));
                        }
                    } else {
                        turnOffDataInDoze.setEnabled(false);
                        turnOffDataInDoze.setSummary(getString(R.string.root_required_text));
                        dozeNotificationBlocklist.setEnabled(false);
                        dozeNotificationBlocklist.setSummary(getString(R.string.root_required_text));
                        dozeAppBlocklist.setEnabled(false);
                        dozeAppBlocklist.setSummary(getString(R.string.root_required_text));
                        turnOffAllSensorsInDoze.setEnabled(false);
                        turnOffAllSensorsInDoze.setSummary(getString(R.string.root_required_text));
                        turnOnBatterySaverInDoze.setEnabled(false);
                        turnOnBatterySaverInDoze.setSummary(getString(R.string.root_required_text));
                        turnOffBiometricsInDoze.setEnabled(false);
                        turnOffBiometricsInDoze.setSummary(getString(R.string.root_required_text));
                        turnOnAirplaneInDoze.setEnabled(false);
                        turnOnAirplaneInDoze.setSummary(getString(R.string.root_required_text));
                        turnOffBluetoothInDoze.setEnabled(false);
                        turnOffBluetoothInDoze.setSummary(getString(R.string.root_required_text));
                        turnOffGPSInDoze.setEnabled(false);
                        turnOffGPSInDoze.setSummary(getString(R.string.root_required_text));
                        whitelistAppsFromDozeMode.setEnabled(false);
                        whitelistAppsFromDozeMode.setSummary(getString(R.string.root_required_text));
                        PreferenceManager.getDefaultSharedPreferences(getContext())
                                .edit()
                                .putBoolean("turnOnBatterySaverInDoze", false)
                                .putBoolean("turnOffAllSensorsInDoze", false)
                                .putBoolean("turnOffBiometricsInDoze", false)
                                .putBoolean("turnOnAirplaneInDoze", false)
                                .putBoolean("turnOffBluetoothInDoze", false)
                                .putBoolean("turnOffGPSInDoze", false)
                                .apply();

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            Preference turnOffWiFiInDoze = (Preference) findPreference("turnOffWiFiInDoze");
                            turnOffWiFiInDoze.setEnabled(false);
                            turnOffWiFiInDoze.setSummary(getString(R.string.root_required_text));
                            PreferenceManager.getDefaultSharedPreferences(getContext())
                                    .edit()
                                    .putBoolean("turnOffWiFiInDoze", false)
                                    .apply();
                        }

                    }
                });
            }
        }

        public void executeCommand(final String command) {
            if (isSuAvailable) {
                executeCommandWithRoot(command);
            } else {
                executeCommandWithoutRoot(command);
            }
        }


        public void executeCommandWithRoot(final String command) {
            boolean useShizuku = Utils.isShizukuMode(getActivity());
            if (useShizuku && isShizukuAvailable) {
                shizukuHandler.executeCommand(command, (commandCode, exitCode, stdout, stderr) -> {
                    if (exitCode == 0) {
                        toggleRootFeatures(true);
                    } else {
                        toggleRootFeatures(false);
                    }
                }, false);
                return;
            }
            AsyncTask.execute(() -> {
                if (rootSession != null) {
                    rootSession.addCommand(command, 0, (Shell.OnCommandResultListener2) (commandCode, exitCode, STDOUT, STDERR) -> printShellOutput(STDOUT));
                } else {
                    rootSession = new Shell.Builder().
                            useSU().
                            setWatchdogTimeout(5).
                            setMinimalLogging(true).
                            open((success, reason) -> {
                                if (reason != Shell.OnShellOpenResultListener.SHELL_RUNNING) {
                                    log("Error opening root shell: exitCode " + reason);
                                    isSuAvailable = false;
                                    toggleRootFeatures(false);
                                } else {
                                    rootSession.addCommand(command, 0, (Shell.OnCommandResultListener2) (commandCode, exitCode, STDOUT, STDERR) -> {
                                        printShellOutput(STDOUT);
                                        isSuAvailable = true;
                                        toggleRootFeatures(true);
                                    });
                                }
                            });
                }
            });
        }

        public void executeCommandWithoutRoot(final String command) {
            AsyncTask.execute(() -> {
                if (nonRootSession != null) {
                    nonRootSession.addCommand(command, 0, (Shell.OnCommandResultListener2) (commandCode, exitCode, STDOUT, STDERR) -> printShellOutput(STDOUT));
                } else {
                    nonRootSession = new Shell.Builder().
                            useSH().
                            setWatchdogTimeout(5).
                            setMinimalLogging(true).
                            open((success, reason) -> {
                                if (reason != Shell.OnShellOpenResultListener.SHELL_RUNNING) {
                                    log("Error opening shell: exitCode " + reason);
//                                    isSuAvailable = false;
                                } else {
                                    nonRootSession.addCommand(command, 0, (Shell.OnCommandResultListener2) (commandCode, exitCode, STDOUT, STDERR) -> {
                                        printShellOutput(STDOUT);
//                                        isSuAvailable = false;
                                    });
                                }
                            });
                }
            });
        }

        public void printShellOutput(List<String> output) {
            if (!output.isEmpty()) {
                for (String s : output) {
                    log(s);
                }
            }
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, @Nullable String key) {
            if (getActivity() != null) {
                reloadSettings(getActivity());
            }
        }
    }
}
