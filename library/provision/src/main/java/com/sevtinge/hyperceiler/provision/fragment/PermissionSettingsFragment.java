/*
 * This file is part of HyperCeiler.

 * HyperCeiler is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.

 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.

 * Copyright (C) 2023-2026 HyperCeiler Contributions
 */
package com.sevtinge.hyperceiler.provision.fragment;

import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.sevtinge.hyperceiler.common.utils.PermissionUtils;
import com.sevtinge.hyperceiler.provision.R;
import com.sevtinge.hyperceiler.provision.widget.PermissionItemView;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PermissionSettingsFragment extends BaseFragment {
    private static final int REQUEST_GET_INSTALLED_APPS = 1101;

    PermissionItemView mRootPermissionItem;
    PermissionItemView mLspPermissionItem;
    PermissionItemView mInstalledAppsPermissionItem;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public static boolean isModuleActive = false;

    @Override
    protected int getLayoutId() {
        return R.layout.provision_permission_layout;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mRootPermissionItem = view.findViewById(R.id.root);
        mLspPermissionItem = view.findViewById(R.id.lsp);
        mInstalledAppsPermissionItem = view.findViewById(R.id.installed_apps);

        mRootPermissionItem.setItemTitle(R.string.provision_permission_root);
        mLspPermissionItem.setItemTitle(R.string.provision_permission_lsp);
        mInstalledAppsPermissionItem.setItemTitle(R.string.provision_permission_installed_apps);

        mRootPermissionItem.setEnabled(false);
        mLspPermissionItem.setEnabled(false);
        mInstalledAppsPermissionItem.setOnClickListener(v -> requestInstalledAppsPermission());

        checkRooted();
        checkLsp();
        checkInstalledAppsPermission();
    }

    private void checkRooted() {
        executor.execute(() -> {
            if (!isAdded()) return;

            requireActivity().runOnUiThread(() -> {
                if (mRootPermissionItem != null) {
                    mRootPermissionItem.setChecked(isDeviceRooted());
                }
            });
        });
    }

    private void checkLsp() {
        executor.execute(() -> {
            if (!isAdded()) return;

            requireActivity().runOnUiThread(() -> {
                if (mLspPermissionItem != null) {
                    mLspPermissionItem.setChecked(isModuleActive);
                }
            });
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        checkRooted();
        checkLsp();
        checkInstalledAppsPermission();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        executor.shutdownNow();
    }

    private boolean isDeviceRooted() {
        String[] paths = {
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/data/local/su"
        };

        for (String path : paths) {
            if (new File(path).exists()) {
                return true;
            }
        }

        try {
            Process process = Runtime.getRuntime().exec(new String[]{ "su", "-c", "id" });
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception ignored) {
        }

        return false;
    }

    private void checkInstalledAppsPermission() {
        if (!isAdded() || mInstalledAppsPermissionItem == null) {
            return;
        }
        mInstalledAppsPermissionItem.setChecked(
            PermissionUtils.hasInstalledAppsPermission(requireContext())
        );
    }

    private void requestInstalledAppsPermission() {
        if (!isAdded()) {
            return;
        }
        if (PermissionUtils.hasInstalledAppsPermission(requireContext())) {
            checkInstalledAppsPermission();
            return;
        }
        requestPermissions(
            new String[]{PermissionUtils.PERMISSION_GET_INSTALLED_APPS},
            REQUEST_GET_INSTALLED_APPS
        );
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_GET_INSTALLED_APPS) {
            return;
        }

        if (PermissionUtils.isInstalledAppsPermissionGranted(permissions, grantResults)
            || PermissionUtils.hasInstalledAppsPermission(requireContext())) {
            checkInstalledAppsPermission();
            return;
        }
        checkInstalledAppsPermission();
    }

}
