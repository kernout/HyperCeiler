"""Narrow source contracts, not device execution or Android lifecycle emulation."""
from pathlib import Path


def check_source_contracts(production: Path) -> None:
    recording = (production / 'VideoRecording.java').read_text()
    service = (production / 'VolumeVideoService.java').read_text()
    config = (production / 'VideoCameraConfig.java').read_text()

    # No privileged privacy observation in ordinary app UID. FGS exemption is separate.
    assert 'addSensorPrivacyListener(' not in service
    assert 'isSensorPrivacyEnabled(' not in service
    assert 'freshPrivacy(intent, EXTRA_CAMERA_PRIVACY_CHECKED' in service
    assert 'freshPrivacy(intent, EXTRA_MIC_PRIVACY_CHECKED' in service
    assert 'own.isClientSilenced()' in recording

    # Session keeps physical output pinning even when physical request overrides are absent.
    assert 'outputConfig.setPhysicalCameraId(config.physicalId)' in recording
    assert 'CaptureRequest.Builder request = !config.physicalFpsKey' in recording
    assert 'physicalKeys.contains(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE)' in config
    assert 'carrierResults.contains(CaptureResult.CONTROL_AE_TARGET_FPS_RANGE)' in config
    assert 'targetResults.contains(CaptureResult.CONTROL_AE_TARGET_FPS_RANGE)' in config
    assert '.getKeys(' not in config  # ROM getKeys traverses broken extended-scene metadata.
    assert 'roles == null ? scalarRole(c) : null' in config
    assert 'VideoCameraConfig.select(cameras, diagnostics)' in recording
    assert 'config.physicalFpsKey && physicalRange == null' not in recording
    assert 'fpsEchoValid(targetResult, config.physicalFpsResultKey)' in recording
    assert 'getPhysicalCameraTotalResults()' in recording
    assert 'VideoRecordingPolicy.measured60(sensorFrames' in recording
    assert 'VideoRecordingPolicy.measured60(samples' in recording

    # Preserve native-start timing, stop-only-after-start and output FD ownership order.
    native_start = recording.index('recorder.start();')
    assert native_start < recording.index('nativeStartedAt = SystemClock.elapsedRealtime();', native_start)
    assert native_start < recording.index('nativeStarted = true;', native_start)
    assert 'if (recorder != null && nativeStarted)' in recording
    cleanup = recording[recording.index('private void finish('):]
    assert cleanup.index('finished = true;') < cleanup.index('recorder.stop();')
    assert cleanup.index('recorder.stop();') < cleanup.index('recorder::release') < cleanup.index('validateFile();')
    assert cleanup.index('validateFile();') < cleanup.index('MediaStore.Video.Media.IS_PENDING, 0') < cleanup.index('output::close')
    assert 'if (!saved && pending != null)' in cleanup
    assert 'gate.cleanedUp();' in cleanup
    assert 'policy.toggle(at)' in service  # gesture time, not delayed intent delivery time
    assert 'policy.started(actualStartElapsed)' in service
    print('PASS video source contracts (structural checks only, not device lifecycle execution)', flush=True)
