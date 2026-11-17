/* SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.recording;

import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.util.Log;
import android.widget.Toast;

import com.sevtinge.hyperceiler.core.R;

import java.util.UUID;

/** A system-bound tile is a user interaction entry; it never relies on a cached tile state to toggle. */
public final class RecordingTileService extends TileService {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable changed = () -> main.post(this::refresh);
    private boolean listening;

    @Override public void onStartListening() {
        super.onStartListening();
        if (!listening) {
            listening = true;
            RecordingTileState.listen(changed);
        }
        refresh();
    }

    @Override public void onStopListening() {
        listening = false;
        RecordingTileState.unlisten(changed);
        super.onStopListening();
    }

    @Override public void onDestroy() {
        listening = false;
        RecordingTileState.unlisten(changed);
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override public void onClick() {
        super.onClick();
        // unlockAndRun preserves the platform's user-initiated QS launch path. No permission UI
        // is opened from a background service and no microphone permission is granted implicitly.
        if (isLocked()) unlockAndRun(this::toggle);
        else toggle();
    }

    private void toggle() {
        // Display state can be stale (including permission changes during a recording).
        // Always deliver the same toggle; only the service knows whether this is a stop
        // or a start and applies RECORD_AUDIO/platform admission to starts.
        Intent intent = new Intent(this, ScreenOffRecordingService.class)
            .setAction(ScreenOffRecordingService.ACTION_TILE_TOGGLE)
            .putExtra(ScreenOffRecordingService.EXTRA_REQUEST_ID, "tile:" + UUID.randomUUID());
        try {
            // Ordinary service delivery: the tile's user interaction grants a temporary
            // allowlist. The service promotes only a new recording; a stop must not create
            // an unfulfilled startForegroundService contract or use stale UI state.
            if (startService(intent) == null) throw new IllegalStateException("Recorder service unavailable");
        } catch (RuntimeException error) {
            Log.w("HCRecordingTile", "Tile recording request rejected", error);
            Toast.makeText(this, R.string.recording_tile_error, Toast.LENGTH_SHORT).show();
            refresh();
        }
    }

    private void refresh() {
        if (!listening) return;
        Tile tile = getQsTile();
        if (tile == null) return;
        RecordingTileState.State state = RecordingTileState.get();
        int subtitle = switch (state) {
            case STARTING -> R.string.recording_tile_starting;
            case RECORDING -> R.string.recording_tile_recording;
            case STOPPING -> R.string.recording_tile_stopping;
            case ERROR -> R.string.recording_tile_error;
            default -> R.string.recording_tile_idle;
        };
        boolean busy = state == RecordingTileState.State.STARTING
            || state == RecordingTileState.State.RECORDING || state == RecordingTileState.State.STOPPING;
        tile.setIcon(Icon.createWithResource(this, R.drawable.ic_recording_tile));
        tile.setLabel(getString(R.string.recording_tile_label));
        tile.setSubtitle(getString(subtitle));
        tile.setContentDescription(getString(R.string.recording_tile_label) + ": " + getString(subtitle));
        tile.setStateDescription(getString(subtitle));
        // Busy is presentation only: STATE_UNAVAILABLE would make SystemUI swallow
        // clicks instead of letting the recorder service decide whether to ignore them.
        tile.setState(busy ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.updateTile();
    }
}
