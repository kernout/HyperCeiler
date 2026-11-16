/*
 * This file is part of HyperCeiler.
 *
 * HyperCeiler is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 */
package com.sevtinge.hyperceiler.hooker.framework;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.preference.SwitchPreference;

import com.sevtinge.hyperceiler.core.R;
import com.sevtinge.hyperceiler.dashboard.DashboardFragment;
import com.sevtinge.hyperceiler.common.log.AndroidLog;
import com.sevtinge.hyperceiler.common.utils.ShellUtils;

import fan.preference.DropDownPreference;

public class VolumeSettings extends DashboardFragment {

    private static final String SCREEN_OFF_CAMERA_KEY =
        "prefs_key_system_framework_volume_screen_off_camera";
    private static final int REQUEST_SCREEN_OFF_CAMERA = 1001;
    private static final String SCREEN_OFF_RECORDER_KEY =
        "prefs_key_system_framework_volume_screen_off_recorder";
    private static final int REQUEST_SCREEN_OFF_RECORDER = 1002;
    private static final int REQUEST_SCREEN_OFF_RECORDER_NOTIFICATIONS = 1003;
    private static final String RECORDING_CHANNEL = "screen_off_recording_v1";

    DropDownPreference mDefaultVolumeStream;
    private SwitchPreference mScreenOffCamera;
    private SwitchPreference mScreenOffRecorder;
    private boolean mRecorderEnablePending;
    private int mRecorderPermissionRequest;

    @Override
    public int getPreferenceScreenResId() {
        return R.xml.framework_volume;
    }

    @Override
    public void initPrefs() {
        mScreenOffCamera = findPreference(SCREEN_OFF_CAMERA_KEY);
        mScreenOffCamera.setOnPreferenceChangeListener((preference, newValue) -> {
            if (!Boolean.TRUE.equals(newValue) || hasCameraPermission()) {
                return true;
            }
            // Keep the preference off until the permission result confirms access.
            mScreenOffCamera.setChecked(false);
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_SCREEN_OFF_CAMERA);
            return false;
        });

        mScreenOffRecorder = findPreference(SCREEN_OFF_RECORDER_KEY);
        mScreenOffRecorder.setOnPreferenceChangeListener((preference, newValue) -> {
            if (!Boolean.TRUE.equals(newValue)) {
                mRecorderEnablePending = false;
                mRecorderPermissionRequest = 0;
                return true;
            }
            mScreenOffRecorder.setChecked(false);
            mRecorderEnablePending = true;
            mRecorderPermissionRequest = 0;
            if (!hasMicrophonePermission()) {
                mRecorderPermissionRequest = REQUEST_SCREEN_OFF_RECORDER;
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_SCREEN_OFF_RECORDER);
            } else {
                finishRecorderEnable(true);
            }
            // Only finishRecorderEnable persists an enabled recorder after all gates pass.
            return false;
        });

        mDefaultVolumeStream = findPreference("prefs_key_system_framework_default_volume_stream");

        assert mDefaultVolumeStream != null;
        mDefaultVolumeStream.setOnPreferenceChangeListener((preference, o) -> {
            try {
                String command = "settings put secure system_framework_default_volume_stream " + Integer.parseInt((String) o);
                ShellUtils.execCommand(command, true);
            } catch (Throwable e) {
                AndroidLog.e("VolumeSettings", "Throwable: " + e.getMessage());
            }
            return true;
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mScreenOffCamera != null && !hasCameraPermission()) {
            mScreenOffCamera.setChecked(false);
        }
        if (mScreenOffRecorder != null && mScreenOffRecorder.isChecked()
                && (!hasMicrophonePermission() || !hasNotificationPermission()
                    || !hasVisibleRecordingNotification())) {
            mScreenOffRecorder.setChecked(false);
        }
    }

    private boolean hasMicrophonePermission() {
        return ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasNotificationPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
            || ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasVisibleRecordingNotification() {
        NotificationManager manager = requireContext().getSystemService(NotificationManager.class);
        if (manager == null || !manager.areNotificationsEnabled()) {
            return false;
        }
        NotificationChannel channel = manager.getNotificationChannel(RECORDING_CHANNEL);
        // The service creates the channel on first use; do not create or overwrite it here.
        return channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    private void finishRecorderEnable(boolean fromUserClick) {
        if (!mRecorderEnablePending || mScreenOffRecorder == null) {
            return;
        }
        mScreenOffRecorder.setChecked(false);
        if (!hasMicrophonePermission()) {
            mRecorderEnablePending = false;
            return;
        }
        if (!hasNotificationPermission()) {
            mRecorderPermissionRequest = REQUEST_SCREEN_OFF_RECORDER_NOTIFICATIONS;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                REQUEST_SCREEN_OFF_RECORDER_NOTIFICATIONS);
            return;
        }
        mRecorderEnablePending = false;
        mRecorderPermissionRequest = 0;
        if (!hasVisibleRecordingNotification()) {
            // Permission callbacks and onResume must never unexpectedly navigate away.
            if (fromUserClick) {
                showRecordingNotificationSettings();
            }
            return;
        }
        mScreenOffRecorder.setChecked(true);
    }

    private void showRecordingNotificationSettings() {
        Toast.makeText(requireContext(), R.string.system_framework_volume_screen_off_recorder_notifications,
            Toast.LENGTH_LONG).show();
        NotificationManager manager = requireContext().getSystemService(NotificationManager.class);
        NotificationChannel channel = manager == null ? null : manager.getNotificationChannel(RECORDING_CHANNEL);
        boolean blockedChannel = manager != null && manager.areNotificationsEnabled()
            && channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE;
        Intent intent = new Intent(blockedChannel ? Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS
            : Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().getPackageName());
        if (blockedChannel) {
            intent.putExtra(Settings.EXTRA_CHANNEL_ID, RECORDING_CHANNEL);
        }
        try {
            startActivity(intent);
        } catch (RuntimeException e) {
            AndroidLog.e("VolumeSettings", "Cannot open notification settings: " + e.getMessage());
        }
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                          @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_SCREEN_OFF_RECORDER
                || requestCode == REQUEST_SCREEN_OFF_RECORDER_NOTIFICATIONS) {
            if (mScreenOffRecorder == null || !mRecorderEnablePending
                    || requestCode != mRecorderPermissionRequest) {
                return;
            }
            String expected = requestCode == REQUEST_SCREEN_OFF_RECORDER
                ? Manifest.permission.RECORD_AUDIO : Manifest.permission.POST_NOTIFICATIONS;
            boolean granted = permissions.length == 1 && grantResults.length == 1
                && expected.equals(permissions[0])
                && grantResults[0] == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(requireContext(), expected)
                    == PackageManager.PERMISSION_GRANTED;
            mRecorderPermissionRequest = 0;
            mScreenOffRecorder.setChecked(false);
            if (granted) {
                finishRecorderEnable(false);
            } else {
                mRecorderEnablePending = false;
            }
            return;
        }
        if (requestCode != REQUEST_SCREEN_OFF_CAMERA || mScreenOffCamera == null) {
            return;
        }
        boolean granted = permissions.length == 1 && grantResults.length == 1
            && Manifest.permission.CAMERA.equals(permissions[0])
            && grantResults[0] == PackageManager.PERMISSION_GRANTED
            && hasCameraPermission();
        // setChecked persists through the page's PrefsDataStore / PrefsBridge.
        mScreenOffCamera.setChecked(granted);
    }
}
