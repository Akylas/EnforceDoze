package com.akylas.enforcedoze;

import static com.akylas.enforcedoze.Utils.logToLogcat;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.preference.Preference;
import android.preference.PreferenceManager;
import android.service.quicksettings.TileService;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.Toolbar;
import androidx.coordinatorlayout.widget.CoordinatorLayout;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import androidx.core.app.ActivityCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import android.text.SpannableString;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.TextView;

import com.afollestad.materialdialogs.MaterialDialog;
import com.nanotasks.Completion;
import com.nanotasks.Tasks;

import java.util.List;

//import de.cketti.library.changelog.ChangeLog;
import eu.chainfire.libsuperuser.Shell;
import eu.chainfire.libsuperuser.StreamGobbler;
import rikka.shizuku.Shizuku;

public class MainActivity extends AppCompatActivity implements CompoundButton.OnCheckedChangeListener,  SharedPreferences.OnSharedPreferenceChangeListener {

    private int mLastExitCode = -1;
    private boolean mCommandRunning = false;
    private HandlerThread mCallbackThread = null;
    private static Shell.Interactive rootSession;
    private static Shell.Interactive nonRootSession;
    private UpdateForceDozeEnabledState updateStateFromTile;
    public static String TAG = "EnforceDoze";
    SharedPreferences settings;
    SharedPreferences.Editor editor;
    boolean isDozeEnabledByOEM = true;
    boolean isSuAvailable = false;
    boolean isShizukuAvailable = false;
    boolean isDozeDisabled = false;
    boolean serviceEnabled = false;
    boolean isDumpPermGranted = false;
    boolean isWriteSecureSettingsPermGranted = false;
    boolean ignoreLockscreenTimeout = true;
    SwitchCompat toggleForceDozeSwitch;
    MaterialDialog progressDialog = null;
    TextView textViewStatus;
    CoordinatorLayout coordinatorLayout;
    ShizukuHandler shizukuHandler;
    com.google.android.material.button.MaterialButton btnEnableDozeUnsupported;

    private static void log(String message) {
        logToLogcat(TAG, message);
    }

    private void updateToggleState() {
        serviceEnabled = settings.getBoolean("serviceEnabled", false);
        toggleForceDozeSwitch.setOnCheckedChangeListener(null);
        toggleForceDozeSwitch.setChecked(serviceEnabled);
        toggleForceDozeSwitch.setOnCheckedChangeListener(this);

        if (serviceEnabled) {
            textViewStatus.setText(R.string.service_active);
        } else {
            textViewStatus.setText(R.string.service_inactive);
        }
    }
    private void updateToggleEnabled() {
        serviceEnabled = settings.getBoolean("serviceEnabled", false);
        toggleForceDozeSwitch.setOnCheckedChangeListener(null);
        toggleForceDozeSwitch.setChecked(serviceEnabled);
        toggleForceDozeSwitch.setOnCheckedChangeListener(this);

        if (serviceEnabled) {
            textViewStatus.setText(R.string.service_active);
        } else {
            textViewStatus.setText(R.string.service_inactive);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);


        setContentView(R.layout.activity_main);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        CustomTabs.with(getApplicationContext()).warm();
        settings = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        isDozeEnabledByOEM = Utils.checkForAutoPowerModesFlag();
        isDozeDisabled = settings.getBoolean("isDozeDisabled", false);
        isSuAvailable = settings.getBoolean("isSuAvailable", false);
        ignoreLockscreenTimeout = settings.getBoolean("ignoreLockscreenTimeout", true);
        toggleForceDozeSwitch = (SwitchCompat) findViewById(R.id.switch1);
        isDumpPermGranted = Utils.isDumpPermissionGranted(getApplicationContext());
        isWriteSecureSettingsPermGranted = Utils.isSecureSettingsPermissionGranted(getApplicationContext());
        textViewStatus = (TextView) findViewById(R.id.textView2);
        updateStateFromTile = new UpdateForceDozeEnabledState();
        LocalBroadcastManager.getInstance(this).registerReceiver(updateStateFromTile, new IntentFilter("update-state-from-tile"));
        toggleForceDozeSwitch.setOnCheckedChangeListener(null);

        // Wire action buttons
        findViewById(R.id.btnSettings).setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, SettingsActivity.class)));
        findViewById(R.id.btnDozeTunables).setOnClickListener(v -> showDozeTunablesActivity());
        findViewById(R.id.btnMoreInfo).setOnClickListener(v -> showMoreInfoDialog());
        findViewById(R.id.btnBatteryStats).setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, DozeBatteryStatsActivity.class)));
        btnEnableDozeUnsupported = findViewById(R.id.btnEnableDozeUnsupported);
        btnEnableDozeUnsupported.setOnClickListener(v -> showEnableDozeOnUnsupportedDeviceDialog());
        // Show the unsupported-device button only when Doze is OEM-disabled
        if (!isDozeEnabledByOEM) {
            btnEnableDozeUnsupported.setVisibility(android.view.View.VISIBLE);
        }

        if (!Utils.isPostNotificationPermissionGranted(this)) {
            requestNotificationPermission();
        } else if (!Utils.isReadPhoneStatePermissionGranted(this)) {
            requestReadPhoneStatePermission();
        }

        updateToggleState();

        toggleForceDozeSwitch.setOnCheckedChangeListener(this);
        
        // Initialize Shizuku handler
        shizukuHandler = ShizukuHandler.getInstance(getApplicationContext());

        // Check execution mode
        boolean useShizuku = Utils.isShizukuMode(getApplicationContext());
        
        if (useShizuku) {
            // Shizuku mode - check if Shizuku is available
            log("Shizuku mode selected");
            shizukuHandler.checkShizukuAvailability();
            isShizukuAvailable = shizukuHandler.isShizukuAvailable();
            
            if (!isShizukuAvailable) {
                // Request Shizuku permission
                try {
                    shizukuHandler.requestShizukuPermission();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            
            if (!Utils.isDeviceRunningOnN() && isDumpPermGranted) {
                log("android.permission.DUMP already granted and user not on Nougat, skipping setup checks");
                doAfterSuCheckSetup();
            } else if (Utils.isDeviceRunningOnN() && isDumpPermGranted && isWriteSecureSettingsPermGranted) {
                log("android.permission.DUMP & android.permission.WRITE_SECURE_SETTINGS already granted and user on Nougat, skipping setup checks");
                doAfterSuCheckSetup();
            } else if (isShizukuAvailable) {
                log("Shizuku is available, granting permissions");
                Utils.grantPermissionsViaShizuku(getApplicationContext());
                doAfterSuCheckSetup();
            } else {
                log("Shizuku is not available");
                toggleForceDozeSwitch.setChecked(false);
                toggleForceDozeSwitch.setEnabled(false);
                textViewStatus.setText(R.string.service_disabled);
                MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
                builder.setTitle(getString(R.string.error_text));
                builder.setMessage(getString(R.string.shizuku_not_available_error));
                builder.setPositiveButton(getString(R.string.close_button_text), null);
                builder.show();
            }
        } else {
            // Root mode - use existing logic
            if (!Utils.isDeviceRunningOnN() && isDumpPermGranted) {
                log("android.permission.DUMP already granted and user not on Nougat, skipping SU check");
                doAfterSuCheckSetup();
            } else if (Utils.isDeviceRunningOnN() && isDumpPermGranted && isWriteSecureSettingsPermGranted) {
                log("android.permission.DUMP & android.permission.WRITE_SECURE_SETTINGS already granted and user on Nougat, skipping SU check");
                doAfterSuCheckSetup();
            } else {
                progressDialog = new MaterialDialog.Builder(this)
                    .title(R.string.please_wait_text)
                    .autoDismiss(false)
                    .cancelable(false)
                    .content(R.string.requesting_su_access_text)
                    .progress(true, 0)
                    .show();
            log("Check if SU is available, and request SU permission if it is");
            Tasks.executeInBackground(MainActivity.this, () -> {
                if (rootSession != null) {
                    if (rootSession.isRunning()) {
                        return true;
                    } else {
                        dispose();
                    }
                }

                mCallbackThread = new HandlerThread("SU callback");
                mCallbackThread.start();

                mCommandRunning = true;
                rootSession = new Shell.Builder().useSU()
                        .setHandler(new Handler(mCallbackThread.getLooper()))
                        .setOnSTDERRLineListener(mStderrListener)
                        .open(mOpenListener);

                waitForCommandFinished();

                if (mLastExitCode != Shell.OnShellOpenResultListener.SHELL_RUNNING) {
                    dispose();
                    return false;
                }

                return true;
            }, new Completion<Boolean>() {
                @Override
                public void onSuccess(Context context, Boolean result) {
                    if (progressDialog != null) {
                        progressDialog.dismiss();
                    }
                    isSuAvailable = result;
                    log("SU available: " + Boolean.toString(result));
                    if (isSuAvailable) {
                        log("Phone is rooted and SU permission granted");
                        editor = settings.edit();
                        editor.putBoolean("isSuAvailable", true);
                        editor.apply();
                        if (!Utils.isDumpPermissionGranted(getApplicationContext())) {
                                log("Granting android.permission.DUMP to com.akylas.enforcedoze");
                                executeCommand("pm grant com.akylas.enforcedoze android.permission.DUMP");
                        }
                        if (!Utils.isReadPhoneStatePermissionGranted(getApplicationContext())) {
                            log("Granting android.permission.READ_PHONE_STATE to com.akylas.enforcedoze");
                            executeCommand("pm grant com.akylas.enforcedoze android.permission.READ_PHONE_STATE");
                        }
                        if (!Utils.isSecureSettingsPermissionGranted(getApplicationContext()) && Utils.isDeviceRunningOnN()) {
                            log("Granting android.permission.WRITE_SECURE_SETTINGS to com.akylas.enforcedoze");
                            executeCommand("pm grant com.akylas.enforcedoze android.permission.WRITE_SECURE_SETTINGS");
                        }
                        if (serviceEnabled) {
                            toggleForceDozeSwitch.setChecked(true);
                            if (!Utils.isMyServiceRunning(ForceDozeService.class, MainActivity.this)) {
                                log("Starting ForceDozeService");
                                Utils.startForceDozeService(context);
                            } else {
                                log("Service already running");
                            }
                        } else {
                            log("Service not enabled");
                        }

//                        ChangeLog cl = new ChangeLog(MainActivity.this);
//                        if (cl.isFirstRun()) {
//                            cl.getFullLogDialog().show();
//                        }
                    } else {
                        log("SU permission denied or not available");
                        toggleForceDozeSwitch.setChecked(false);
                        toggleForceDozeSwitch.setEnabled(false);
                        textViewStatus.setText(R.string.service_disabled);
                        editor = settings.edit();
                        editor.putBoolean("isSuAvailable", false);
                        editor.apply();
                        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context);
                        builder.setTitle(getString(R.string.error_text));
                        builder.setMessage(getString(R.string.root_workaround_text));
                        builder.setPositiveButton(getString(R.string.close_button_text), null);
                        builder.setNegativeButton(getString(R.string.root_workaround_button_text), new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialogInterface, int i) {
                                dialogInterface.dismiss();
                                showRootWorkaroundInstructions();
                            }
                        });
                        builder.show();
                    }
                }

                @Override
                public void onError(Context context, Exception e) {
                    Log.e(TAG, "Error querying SU: " + e.getMessage());
                    log("SU permission denied or not available");
                    toggleForceDozeSwitch.setChecked(false);
                    toggleForceDozeSwitch.setEnabled(false);
                    textViewStatus.setText(R.string.service_disabled);
                    editor = settings.edit();
                    editor.putBoolean("isSuAvailable", false);
                    editor.apply();
                    MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context);
                    builder.setTitle(getString(R.string.error_text));
                    builder.setMessage(getString(R.string.root_workaround_text));
                    builder.setPositiveButton(getString(R.string.close_button_text), null);
                    builder.setNegativeButton(getString(R.string.root_workaround_button_text), new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            dialogInterface.dismiss();
                            showRootWorkaroundInstructions();
                        }
                    });
                    builder.show();
                }
            });
            }
        }

        if (Utils.isLockscreenTimeoutValueTooHigh(getContentResolver())) {
            if (!ignoreLockscreenTimeout) {
                coordinatorLayout = (CoordinatorLayout) findViewById(R.id.coordinatorLayout);
                Snackbar.make(coordinatorLayout, R.string.lockscreen_timeout_snackbar_text, Snackbar.LENGTH_INDEFINITE)
                        .setAction(R.string.more_info_text, new View.OnClickListener() {
                            @Override
                            public void onClick(View view) {
                                showLockScreenTimeoutInfoDialog();
                            }
                        })
                        .setActionTextColor(Color.RED)
                        .show();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (settings != null) {
            settings.registerOnSharedPreferenceChangeListener(this);
        }
        updateToggleState();
        // Show disabled notification if EnforceDoze is disabled
        if (!serviceEnabled) {
            Utils.showDisabledNotification(getApplicationContext());
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (settings != null) {
            settings.unregisterOnSharedPreferenceChangeListener(this);
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if ("serviceEnabled".equals(key)) {
            // update UI on main thread
            runOnUiThread(this::updateToggleState);
        } else if ("executionMode".equals(key)) {
            // update UI on main thread
            runOnUiThread(this::updateToggleState);
        }
    }

    final int POST_NOTIF_PERMISSION_REQUEST_CODE =112;
    final int READ_PHONE_STATE_PERMISSION_REQUEST_CODE =113;
    public void requestNotificationPermission(){
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ActivityCompat.requestPermissions(this,
                        new String[]{"android.permission.POST_NOTIFICATIONS"},
                        POST_NOTIF_PERMISSION_REQUEST_CODE);
            }
        } catch (Exception e){
            e.printStackTrace();
        }
    }
    public void requestReadPhoneStatePermission(){
        try {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_PHONE_STATE},
                    READ_PHONE_STATE_PERMISSION_REQUEST_CODE);
        } catch (Exception e){
            e.printStackTrace();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        switch (requestCode) {
            case POST_NOTIF_PERMISSION_REQUEST_CODE:
                if (grantResults.length > 0 &&
                        grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                }  else {
                    PreferenceManager.getDefaultSharedPreferences(getApplicationContext())
                            .edit()
                            .putBoolean("showPersistentNotif", false)
                            .apply();
                }
                if (!Utils.isReadPhoneStatePermissionGranted(this)) {
                    requestReadPhoneStatePermission();
                }
                break;

            case READ_PHONE_STATE_PERMISSION_REQUEST_CODE:
                if (grantResults.length > 0 &&
                        grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                }  else {
                    PreferenceManager.getDefaultSharedPreferences(getApplicationContext())
                            .edit()
                            .putBoolean("showPersistentNotif", false)
                            .apply();
                }
                break;

        }

    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (shizukuHandler != null) {
            shizukuHandler.removePermissionResultListener();
        }
        if (rootSession != null) {
            rootSession.close();
            rootSession = null;
        }
        if (nonRootSession != null) {
            nonRootSession.close();
            nonRootSession = null;
        }
    }

    public void doAfterSuCheckSetup() {
        if (serviceEnabled) {
            toggleForceDozeSwitch.setChecked(true);
            textViewStatus.setText(R.string.service_active);
            if (!Utils.isMyServiceRunning(ForceDozeService.class, MainActivity.this)) {
                log("Starting ForceDozeService");
                Utils.startForceDozeService(this);
            } else {
                log("Service already running");
            }
            boolean useShizuku = Utils.isShizukuMode(getApplicationContext());
            if (isSuAvailable || (useShizuku && isShizukuAvailable)) {
                executeCommand("chmod 664 /data/data/com.akylas.enforcedoze/shared_prefs/com.akylas.enforcedoze_preferences.xml");
                executeCommand("chmod 755 /data/data/com.akylas.enforcedoze/shared_prefs");
            }
        } else {
            textViewStatus.setText(R.string.service_inactive);
            log("Service not enabled");
        }
//        ChangeLog cl = new ChangeLog(this);
//        if (cl.isFirstRun()) {
//            cl.getFullLogDialog().show();
//        } else {
//            if (showDonateDevDialog) {
//                showDonateDevDialog();
//            }
//        }
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        isDumpPermGranted = Utils.isDumpPermissionGranted(getApplicationContext());

        if (isDozeEnabledByOEM || (Utils.isDeviceRunningOnN() && !isSuAvailable)) {
            menu.getItem(0).setVisible(false);
        }

        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_toggle_doze) {
            showEnableDozeOnUnsupportedDeviceDialog();
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
        if (b) {
            editor = settings.edit();
            editor.putBoolean("serviceEnabled", true);
            editor.apply();
            serviceEnabled = true;
            textViewStatus.setText(R.string.service_active);
            if (!Utils.isMyServiceRunning(ForceDozeService.class, MainActivity.this)) {
                log("Enabling ForceDoze");
                Utils.startForceDozeService(MainActivity.this);
            }
            showForceDozeActiveDialog();
        } else {
            editor = settings.edit();
            editor.putBoolean("serviceEnabled", false);
            editor.apply();
            serviceEnabled = false;
            textViewStatus.setText(R.string.service_inactive);
            if (Utils.isMyServiceRunning(ForceDozeService.class, MainActivity.this)) {
                log("Disabling ForceDoze");
                Utils.stopForceDozeService(MainActivity.this);
            }
        }

        if (Utils.isDeviceRunningOnN()) {
            TileService.requestListeningState(this, new ComponentName(this, ForceDozeTileService.class.getName()));
        }
    }

    public void showDozeTunablesActivity() {
        if (serviceEnabled) {
            MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
            builder.setTitle("Warning");
            builder.setMessage("Modified Doze tunables will be overriden when not using root, as ForceDoze overrides Doze tunables by default in order to put your device immediately into Doze mode.\n\nAre you sure you want to continue?");
            builder.setPositiveButton(getString(R.string.yes_button_text), new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialogInterface, int i) {
                    dialogInterface.dismiss();
//                    toggleForceDozeSwitch.setChecked(false);
                    startActivity(new Intent(MainActivity.this, DozeTunablesActivity.class));
                }
            });
            builder.setNegativeButton(getString(R.string.no_button_text), new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialogInterface, int i) {
                    dialogInterface.dismiss();
                }
            });
            builder.show();
        } else {
            startActivity(new Intent(MainActivity.this, DozeTunablesActivity.class));
        }
    }

    public void showEnableDozeOnUnsupportedDeviceDialog() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setTitle(getString(R.string.doze_unsupported_more_info_title));
        builder.setMessage(getString(R.string.doze_unsupported_more_info));
        builder.setPositiveButton(getString(R.string.close_button_text), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                dialogInterface.dismiss();
            }
        });
        builder.setNegativeButton(getString(R.string.enable_doze_button_text), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                executeCommand("setprop persist.sys.doze_powersave true");
                if (Utils.isDeviceRunningOnN() && isSuAvailable) {
                    executeCommandWithRoot("dumpsys deviceidle disable all");
                    executeCommandWithRoot("dumpsys deviceidle enable all");
                } else if (!Utils.isDeviceRunningOnN()) {
                    executeCommand("dumpsys deviceidle disable");
                    executeCommand("dumpsys deviceidle enable");
                }
                dialogInterface.dismiss();
            }
        });
        builder.show();
    }

    public void showRootWorkaroundInstructions() {
        View customAlertDialogView = LayoutInflater.from(this)
                .inflate(R.layout.non_root_workaround, null, false);

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setTitle(getString(R.string.no_root_workaround_dialog_title));
        builder.setView(customAlertDialogView);
        builder.setMessage(getString(R.string.no_root_workaround_dialog_text));
        builder.setPositiveButton(getString(R.string.okay_button_text), null);
        customAlertDialogView.findViewById(R.id.copyBtn1).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SpannableString command = (SpannableString) ((TextView)customAlertDialogView.findViewById(R.id.commandTxt1)).getText();
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("Copied text", command);
                clipboard.setPrimaryClip(clip);
            }
        });
        customAlertDialogView.findViewById(R.id.copyBtn2).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SpannableString command = (SpannableString) ((TextView)customAlertDialogView.findViewById(R.id.commandTxt2)).getText();
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("Copied text", command);
                clipboard.setPrimaryClip(clip);
            }
        });
        customAlertDialogView.findViewById(R.id.shareBtn1).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SpannableString command = (SpannableString) ((TextView)customAlertDialogView.findViewById(R.id.commandTxt1)).getText();
                Intent sendIntent = new Intent();
                sendIntent.setAction(Intent.ACTION_SEND);
                sendIntent.putExtra(Intent.EXTRA_TEXT, command);
                sendIntent.setType("text/plain");
                startActivity(sendIntent);
            }
        });
        customAlertDialogView.findViewById(R.id.shareBtn2).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SpannableString command = (SpannableString) ((TextView)customAlertDialogView.findViewById(R.id.commandTxt2)).getText();
                Intent sendIntent = new Intent();
                sendIntent.setAction(Intent.ACTION_SEND);
                sendIntent.putExtra(Intent.EXTRA_TEXT, command);
                sendIntent.setType("text/plain");
                startActivity(sendIntent);
            }
        });
//        builder.setNeutralButton(getString(R.string.copy_command_button_text), new DialogInterface.OnClickListener() {
//            @Override
//            public void onClick(DialogInterface dialogInterface, int i) {
//                dialogInterface.dismiss();
//                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
//                ClipData clip = ClipData.newPlainText("Copied text", command);
//                clipboard.setPrimaryClip(clip);
//
//            }
//        });
//        builder.setNegativeButton(getString(R.string.share_command_button_text), new DialogInterface.OnClickListener() {
//            @Override
//            public void onClick(DialogInterface dialogInterface, int i) {
//                dialogInterface.dismiss();
//                Intent sendIntent = new Intent();
//                sendIntent.setAction(Intent.ACTION_SEND);
//                sendIntent.putExtra(Intent.EXTRA_TEXT, command);
//                sendIntent.setType("text/plain");
//                startActivity(sendIntent);
//
//            }
//        });
        builder.show();
    }

    public void showLockScreenTimeoutInfoDialog() {
        String lockscreenTimeout = Float.toHexString(Utils.getLockscreenTimeoutValue(getContentResolver()));
        if (Float.valueOf(lockscreenTimeout) < 1.0f) {
            lockscreenTimeout = Float.toString(Float.valueOf(lockscreenTimeout) * 60.0f) + " seconds & 0";
        }
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setTitle(getString(R.string.lockscreen_timeout_dialog_title));
        builder.setMessage(getString(R.string.lockscreen_timeout_dialog_text_p1) + lockscreenTimeout + getString(R.string.lockscreen_timeout_dialog_text_p2) +
                getString(R.string.lockscreen_timeout_dialog_text_p3) + lockscreenTimeout + getString(R.string.lockscreen_timeout_dialog_text_p4) +
                getString(R.string.lockscreen_timeout_dialog_text_p5) + lockscreenTimeout + getString(R.string.lockscreen_timeout_dialog_text_p6));
        builder.setPositiveButton(getString(R.string.okay_button_text), null);
        builder.setNegativeButton(getString(R.string.open_security_settings_button_text), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                dialogInterface.dismiss();
                Intent securitySettingsIntent = new Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS);
                startActivity(securitySettingsIntent);

            }
        });
        builder.show();
    }

    public void showMoreInfoDialog() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setTitle(getString(R.string.more_info_text));
        builder.setMessage(getString(R.string.how_doze_works_dialog_text));
        builder.setPositiveButton(getString(R.string.okay_button_text), (dialogInterface, i) -> dialogInterface.dismiss());
        builder.show();
    }

    public void showForceDozeActiveDialog() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setTitle(getString(R.string.forcedoze_active_dialog_title));
        builder.setMessage(getString(R.string.forcedoze_active_dialog_text));
        builder.setPositiveButton(getString(R.string.okay_button_text), (dialogInterface, i) -> dialogInterface.dismiss());
        builder.show();
    }

//    public void showDonateDevDialog() {
//        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
//        builder.setTitle(getString(R.string.donate_dialog_title));
//        builder.setMessage(getString(R.string.donate_dialog_text));
//        builder.setPositiveButton(getString(R.string.donate_dialog_button_text), new DialogInterface.OnClickListener() {
//            @Override
//            public void onClick(DialogInterface dialogInterface, int i) {
//                editor = settings.edit();
//                editor.putBoolean("showDonateDevDialog2", false);
//                editor.apply();
//                dialogInterface.dismiss();
//                openDonatePage();
//            }
//        });
//        builder.setNegativeButton(getString(R.string.close_button_text), new DialogInterface.OnClickListener() {
//            @Override
//            public void onClick(DialogInterface dialogInterface, int i) {
//                editor = settings.edit();
//                editor.putBoolean("showDonateDevDialog2", false);
//                editor.apply();
//                dialogInterface.dismiss();
//            }
//        });
//        builder.show();
//    }



    private final Shell.OnShellOpenResultListener mOpenListener = new Shell.OnShellOpenResultListener() {
        @Override
        public void onOpenResult(boolean success, int reason) {
            mLastExitCode = reason;
            synchronized (mCallbackThread) {
                mCommandRunning = false;
                mCallbackThread.notifyAll();
            }
        }

    };

//    private final Shell.OnCommandLineListener mStdoutListener = new Shell.OnCommandLineListener() {
//
//        @Override
//        public void onSTDOUT(@NonNull String line) {
//            log(line);
//        }
//
//        @Override
//        public void onSTDERR(@NonNull String line) {
//            Log.e(TAG, line);
//        }
//
//        @Override
//        public void onCommandResult(int commandCode, int exitCode) {
//            mLastExitCode = exitCode;
//            synchronized (mCallbackThread) {
//                mCommandRunning = false;
//                mCallbackThread.notifyAll();
//            }
//        }
//    };

    private final StreamGobbler.OnLineListener mStderrListener = new StreamGobbler.OnLineListener() {
        @Override
        public void onLine(String line) {
            log(line);
        }
    };

    private void waitForCommandFinished() {
        synchronized (mCallbackThread) {
            while (mCommandRunning) {
                try {
                    mCallbackThread.wait();
                } catch (Exception e)  {
                    if (e instanceof InterruptedException) {
                        log("InterruptedException occurred while waiting for command to finish");
                        e.printStackTrace();
                    } else if (e instanceof NullPointerException) {
                        log("NPE occurred while waiting for command to finish");
                        e.printStackTrace();
                    }
                }
            }
        }

        if (mLastExitCode == Shell.OnCommandResultListener.WATCHDOG_EXIT || mLastExitCode == Shell.OnCommandResultListener.SHELL_DIED) {
            dispose();
        }
    }

    public synchronized void dispose() {
        if (rootSession == null) {
            return;
        }

        try {
            rootSession.close();
        } catch (Exception ignored) {
        }
        rootSession = null;

        mCallbackThread.quit();
        mCallbackThread = null;
    }

    public void executeCommand(final String command) {
        boolean useShizuku = Utils.isShizukuMode(getApplicationContext());
        
        if (useShizuku && isShizukuAvailable) {
            shizukuHandler.executeCommand(command, (commandCode, exitCode, stdout, stderr) -> {
                printShellOutput(stdout);
            }, true);
        } else if (isSuAvailable) {
            executeCommandWithRoot(command);
        } else {
            executeCommandWithoutRoot(command);
        }
    }

    public void executeCommandWithRoot(final String command) {
        AsyncTask.execute(new Runnable() {
            @Override
            public void run() {
                if (rootSession != null) {
                    rootSession.addCommand(command, 0, new Shell.OnCommandResultListener() {
                        @Override
                        public void onCommandResult(int commandCode, int exitCode, List<String> output) {
                            printShellOutput(output);
                        }
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
                                    rootSession.addCommand(command, 0, new Shell.OnCommandResultListener() {
                                        @Override
                                        public void onCommandResult(int commandCode, int exitCode, List<String> output) {
                                            printShellOutput(output);
                                        }
                                    });
                                }
                            });
                }
            }
        });
    }

    public void executeCommandWithoutRoot(final String command) {
        AsyncTask.execute(new Runnable() {
            @Override
            public void run() {
                if (nonRootSession != null) {
                    nonRootSession.addCommand(command, 0, new Shell.OnCommandResultListener() {
                        @Override
                        public void onCommandResult(int commandCode, int exitCode, List<String> output) {
                            printShellOutput(output);
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
                                        nonRootSession.addCommand(command, 0, new Shell.OnCommandResultListener2() {
                                            @Override
                                            public void onCommandResult(int commandCode, int exitCode, @NonNull List<String> STDOUT, @NonNull List<String> STDERR) {
                                                printShellOutput(STDOUT);
                                            }
                                        });
                                    }
                                }
                            );
                }
            }
        });
    }

    public void printShellOutput(List<String> output) {
        if (output != null && !output.isEmpty()) {
            for (String s : output) {
                log(s);
            }
        }
    }

    class UpdateForceDozeEnabledState extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            log("User toggled the QuickTile, now updating the state in app");
            toggleForceDozeSwitch.setChecked(intent.getBooleanExtra("isActive", false));
        }
    }
}
