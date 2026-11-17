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
import android.app.Activity;
import android.app.StatusBarManager;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.preference.Preference;
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
                finishRecorderEnable();
            }
            // Persist only after the microphone permission has been confirmed.
            return false;
        });

        Preference addRecordingTile = findPreference("prefs_key_system_framework_add_recording_tile");
        if (addRecordingTile != null) {
            addRecordingTile.setOnPreferenceClickListener(preference -> {
                requestAddRecordingTile();
                return true;
            });
        }

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
                && !hasMicrophonePermission()) {
            mScreenOffRecorder.setChecked(false);
        }
    }

    private void requestAddRecordingTile() {
        Activity activity = requireActivity();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(activity, R.string.recording_tile_add_unsupported, Toast.LENGTH_LONG).show();
            return;
        }
        try {
            StatusBarManager statusBarManager = activity.getSystemService(StatusBarManager.class);
            if (statusBarManager == null) {
                Toast.makeText(activity, R.string.recording_tile_add_failed, Toast.LENGTH_LONG).show();
                return;
            }
            // Adding the tile does not depend on the volume/power shortcut switch.
            statusBarManager.requestAddTileService(
                new ComponentName(activity.getPackageName(),
                    "com.sevtinge.hyperceiler.recording.RecordingTileService"),
                activity.getString(R.string.recording_tile_label),
                Icon.createWithResource(activity, R.drawable.ic_recording_tile),
                activity.getMainExecutor(), result -> {
                    int message;
                    switch (result) {
                        case StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED:
                            message = R.string.recording_tile_add_success;
                            break;
                        case StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED:
                            message = R.string.recording_tile_add_already_added;
                            break;
                        case StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED:
                            message = R.string.recording_tile_add_not_added;
                            break;
                        default:
                            AndroidLog.e("VolumeSettings", "Add recording tile result: " + result);
                            message = R.string.recording_tile_add_failed;
                            break;
                    }
                    Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
                });
        } catch (RuntimeException e) {
            AndroidLog.e("VolumeSettings", "Unable to add recording tile: " + e.getMessage());
            Toast.makeText(activity, R.string.recording_tile_add_failed, Toast.LENGTH_LONG).show();
        }
    }

    private boolean hasMicrophonePermission() {
        return ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED;
    }

    private void finishRecorderEnable() {
        if (!mRecorderEnablePending || mScreenOffRecorder == null) {
            return;
        }
        mRecorderEnablePending = false;
        mRecorderPermissionRequest = 0;
        // Android does not require POST_NOTIFICATIONS to start a microphone FGS.
        // The service still posts its mandatory foreground notification; its visibility
        // and the microphone indicator remain under platform control.
        mScreenOffRecorder.setChecked(hasMicrophonePermission());
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                          @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_SCREEN_OFF_RECORDER) {
            if (mScreenOffRecorder == null || !mRecorderEnablePending
                    || requestCode != mRecorderPermissionRequest) {
                return;
            }
            boolean granted = permissions.length == 1 && grantResults.length == 1
                && Manifest.permission.RECORD_AUDIO.equals(permissions[0])
                && grantResults[0] == PackageManager.PERMISSION_GRANTED
                && hasMicrophonePermission();
            mRecorderPermissionRequest = 0;
            mScreenOffRecorder.setChecked(false);
            if (granted) {
                finishRecorderEnable();
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
