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
import android.content.pm.PackageManager;

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

    DropDownPreference mDefaultVolumeStream;
    private SwitchPreference mScreenOffCamera;

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
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                          @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
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
