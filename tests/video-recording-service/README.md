# Video service audit / tests

Run (does not open a camera or microphone):

```sh
python3 tests/video-recording-service/run.py --android-sdk /workspace/camera-service-check/android-35-classes
```

The runner compiles every production `video/*.java` against real API 35 classes, then runs Android-free policy, resource-gate, main-camera metadata fixture and diagnostic rotation tests. `source_contracts.py` checks selected source wiring; it is explicitly **not** an Android lifecycle execution test.

## Confirmed code/metadata fixes

- Actual static metadata lists `CONTROL_AE_TARGET_FPS_RANGE` among request keys, but omits it from result keys of both logical 0 and physical MAIN 2. The old unconditional equality test rejected a legal missing result as soon as frames arrived. Advertised result-key support is now checked separately for parent and target. A missing unadvertised echo is legal; any provided wrong echo or missing advertised echo still fails. Physical result ID, increasing physical sensor timestamps, measured 60 fps cadence and encoded MP4 timestamp validation remain mandatory.
- The physical request-builder overload clones template metadata before later logical `set()` calls. The old code created physical settings even when no physical FPS override key was available (the actual metadata case). Now a per-physical request settings block is allocated only for an advertised override; `OutputConfiguration.setPhysicalCameraId()` always remains responsible for pinning MAIN. This avoids sending an untouched template clone and is not a claim that the actual vendor HAL had been observed to run at 30 fps.

Minimal metadata fixture fields contain no client names, event history or identifiers. Camera 0 result-key list is at `camera-metadata.txt:408-431`, camera 2 at `33023-33046`; `65541 (aeTargetFpsRange)` is absent and `917520 (timestamp)` present.

## Privacy observation is not a FGS exemption

Read-only reference files in `/workspace/camera-service-check/`:

- `SensorPrivacyManager-device.java:283` forwards listener registration to `addToggleSensorPrivacyListener()`.
- `SensorPrivacyService-device.java:898-909,944-945` enforce `OBSERVE_SENSOR_PRIVACY` on queries/listeners. A normal app must not add this listener merely because system_server started its foreground service.
- `ActiveServices-device.java:8626` (`shouldAllowFgsWhileInUsePermissionLocked`) separately recognizes system caller appId 1000 as reason 51. This supports the system-server caller strategy but is **not a runtime proof** that a particular intent was started with that identity or that this device admitted the service.

The app retains fresh system-server camera/MIC privacy attestations, permissions/AppOps checks, microphone mute and own recording-session silencing checks, camera errors and frame-stall checks. No privacy API setting, listener bypass, permission grant or camera/microphone operation is performed by these tests.

## Persistent breadcrumbs

App-private files relative to `Context.getFilesDir()`:

- `video-diagnostics/current.log`
- `video-diagnostics/previous.log`

Each file is capped at 32 KiB; only one previous file is retained (64 KiB total). Records are a monotonic millisecond clock, allowlisted lifecycle event and allowlisted exception category. No arbitrary message, stack trace, request UUID, user/device/camera identifier, URI, output filename or audio/video contents are accepted. Logging failure is best-effort and cannot abort admission/cleanup. The first frame and first own-audio verification are logged only once per recording, not every frame or health poll.

Events cover service creation/intent, foreground promotion, rejection/ignored toggle, privacy/permission/AppOps admission failure, worker/preflight/config selection, pending output, recorder prepare, camera open, session query/configuration, repeating request, recorder start, first frame/own MIC verification, health failures, cleanup/stop, file validation and publish result. The absence of SERVICE_CREATED/INTENT_RECEIVED after a gesture points to delivery/hook/FGS startup before app admission; it alone does not identify which one failed.

## Preserved lifecycle rules and limits

- User minimum remains exactly 10,000 ms from successful native `MediaRecorder.start()` return, evaluated against gesture elapsed time, not intent delivery time. Early stops are ignored, never queued. Fault/privacy/destroy stops bypass the user minimum.
- `finish()` is idempotent; `stop()` is called only after native start success; release follows stop; MediaExtractor validates after recorder release while the file descriptor is still open; publish follows validation; rejected pending rows are deleted. Camera onClosed and cleanup jointly own resource-gate release.
- Actual regular-session encoder-only physical MAIN 1080p60 configuration, MediaRecorder device behavior, foreground admission, live privacy changes and vendor close callbacks still require an expressly authorized real-device test. Static support metadata and successful SDK compilation do not prove successful recording.

## Actual selector regression: ROM getKeys derived-metadata failure

After the real-SDK production compile, `select_fixture.py` compiles a **separate isolated host executable API fixture** and the unchanged production `VideoCameraConfig`, policy and diagnostics classes. `fixtures/VideoCameraConfigSelectTest.java` directly invokes production `select`; it is not merely a policy test or Android stub compatibility claim. Fixture `getKeys()` deliberately throws NPE through `CameraMetadataNative.getExtendedSceneModeCapabilities`, reproducing the installed R8 `jy0.a` preflight failure. Alternative SecurityException/IllegalArgumentException/LinkageError are injected as possible UID-dependent enumeration errors. Every production path must leave getKeys call count zero.

75 executable selector checks cover the physical MAIN route, direct MAIN short-circuit (no hidden query), cameraIds array precedence including empty/excluding MAIN, absent-array scalar fallback (Integer and int[1]), malformed scalar rejection, optional unrelated metadata faults, hidden physical MAIN access denial, MAIN/carrier ambiguity, strict [60,60]/1080p/frame-duration/encoder requirements, selected metadata failures, and privacy-safe diagnostic stages/fixed causes. The fixture CameraManager intentionally has **no openCamera API**; no media recorder or microphone API is present.

Typed vendor schema is grounded in the existing read-only camera metadata: `cameraId` is int32[1]; `cameraIds` is int32[N]. No getKeys traversal is needed to discover either known tag. An inaccessible physical MAIN still rejects as MAIN_UNAVAILABLE; this patch does not claim hidden metadata access, physical session support or actual recording success for the app UID.

### Distinct numerical-app-UID failure: filtered vendor-key enumeration

New local read-only evidence must be distinguished from the root getKeys NPE. `role-app-result.txt` reports IDs 0..5 with direct rear MAIN 2 (`cameraIds=[0]`), logical carrier 0 (`[64]`) and logical carrier 5 (`[60,62,61,63]`), both containing physical 2. In the setuid(10266) app_process + target-package Context probe, vendor roles are absent from getKeys enumeration but present in direct typed reads. The old installed R8 selector therefore fails NO_MAIN (`old-app-preflight-result.txt`); this is enumeration filtering, not absence of vendor metadata. The historical dumpsys fixture publicIds=0,1 remains a separate physical-only route case and is not the current probe's ID list.

`filteredEnumerationRegression()` models both empty and standard-only getKeys results. A test-only copy of the old role-discovery algorithm demonstrably fails NO_MAIN; the real production selector passes in both cases, chooses direct 2 rather than either logical carrier, preserves orientation 90, never calls getKeys, and does not inspect unrelated carrier streams. Camera IDs may appear in the test fixture; production diagnostics still accepts only enumerated stages and causes.

`typed-preflight-app.txt` and `typed-preflight-root.txt` report new production select (D8-compiled) PREFLIGHT_OK, openId=2, physicalId=null, orientation=90, fps=1080p60 for numerical UID 10266 and root respectively. The numerical-UID process retains root SELinux context: it is **not a full app sandbox**. These are metadata+codec-only preflights without openCamera, MediaRecorder construction or MIC operation, not successful FGS admission, live session or recording tests. This follow-up only edits host tests/docs and does not repeat or expand device probing.
