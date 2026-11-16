# Screen-off camera hook regression

Run from the repository root:

```sh
python3 tests/screen-off-camera-hook/run.py
python3 tests/screen-off-camera-hook/run.py --android-classes /path/to/extracted/android-35-classes
python3 tests/screen-off-camera-hook/run.py --android-jar /path/to/sdk/platforms/android-35/android.jar
```

Requires a JDK on `PATH`. The script uses temporary directories and does not download anything or access/change an Android device. The optional public SDK compile omits Android/OEM fixtures, so direct calls to hidden APIs fail compilation. The classes-directory option avoids the locally truncated SDK jar.

The behavioral suite compiles the production `ScreenOffCamera.java`, installs its actual `IMethodHook` callbacks in a deterministic harness, and invokes those callbacks around representative OEM methods. The OEM fixture deliberately places the native camera action directly inside `onMiuiMultiPress` and has no private `volumeDownKeyDoubleClick` method: the test still intercepts double presses, simulating an inlined private camera callee. Single/non-double callbacks and interactive double presses retain their original behavior.

The suite checks the exact deoptimization caller set and rejects installation when any required deoptimization fails or throws. It also checks disabled defaults, SDK/class/signature gates, partial-install rollback, independent double-press recognition, asynchronous explicit service dispatch to the current user, Binder identity restoration, physical-key policy flag filtering, OEM audio/subscreen gates, queued-state rechecks, busy/cooldown behavior, and suppression of the original action on simulated failures. Shadowed subclass fields deliberately remain null to verify that the hook reads the real base-rule Context/Handler.

The public SDK compile uses real Android classes but fixtures for BaseHook/EzHookTool/logging/prefs dependencies. The BaseHook signatures were checked against this repository: `deoptimize(Method): boolean` delegates to `Deoptimizers.deoptimize(Method)`; `hookMethod(Method, IMethodHook)` returns `XposedInterface.HookHandle`; class lookup and classloader methods are present. This is not a full Gradle/dependency build or a runtime ART test.

## Detector evidence

The review used the actual device `/system/framework/services.jar`, SHA-256 `bfd88ca65771d9e6c4229f9dc30c2b5916c0fe1b310afa77819c637de777a0f8`, decompiled with JADX 1.5.6 and `--no-inline-methods`. The inspected `SingleKeyGestureDetector.java` is an external review input; firmware and its full source are not added to this test directory.

- `addRule()` (lines 257–262) only adds the rule object; it does not read/cache maximum press count.
- `interceptKeyDown()` (line 354) dynamically calls `mActiveRule.getMaxMultiPressCount()` for subsequent presses.
- `interceptKeyUp()` (lines 414–423) dynamically calls it on first release and before scheduling delayed presses. This prevents clearing `mActiveRule` as a one-press rule when the hook returns 2.
- `MiuiSingleKeyRule.getMaxMultiPressCount()` delegates to `getMiuiMaxMultiPressCount()`; `onKeyGesture()` dispatches `onMiuiMultiPress()` for count >1.
- The supplied `VolumeDownKeyRule` calls its native camera method only for count ==2. Its original maximum is 1 when the camera setting is 0; the hook overrides this result while non-interactive, without reading or changing that setting.
- `MiuiSingleKeyRuleManager.getMiuiSingleKeyRule()` constructs `VolumeDownKeyRule` for key 25 without a camera-setting condition. `MiuiKeyShortcutRuleManager.initSingleKeyRule()` registers a non-null configured rule with the detector without that condition.

The exact deoptimized methods cover `MiuiSingleKeyRule.onKeyGesture`, detector `KeyHandler.handleMessage`, detector `startDelayedPress` / `cancelDelayedPress` (the other direct type-0 gesture dispatchers), `MiuiSingleKeyRule.getMaxMultiPressCount`, and detector `interceptKeyDown` / `interceptKeyUp` / `interceptKey`. Only the two volume-rule methods receive hooks; generic detector methods receive deoptimization only.

These checks establish the inspected ROM's dynamic call path, not successful capture on a device. Future ROM ABI changes, framework deoptimization support, actual callback delivery, and Android/OEM foreground-service/camera policy still require final APK/device verification.

## System-side sensor privacy gate

Immediately before service dispatch, after clearing Binder identity, the production hook reflects the inspected `SensorPrivacyManager.getInstance(Context)`, `isAllSensorPrivacyEnabled()` and `areAnySensorPrivacyTogglesEnabled(2)` APIs. The local `SensorPrivacyManager-device.java` shows that these call service `isSensorPrivacyEnabled()` and `isCombinedToggleSensorPrivacyEnabled(CAMERA)` respectively. `SensorPrivacyService-device.java` lines 888–910 enforce `OBSERVE_SENSOR_PRIVACY`; the combined method ORs software toggle type 1 with hardware type 2 for the privacy service's current user.

The hook requires both queries to return false and rejects absent class/API/manager, permission/Binder errors, or foreground-user changes across the check. Resolving the privacy APIs at dispatch time keeps the wake/UI interception installed even if privacy queries are unavailable. It never changes, suppresses or bypasses privacy state and does not register a long-lived listener. Camera2/platform camera privacy enforcement remains necessary if state changes after this point-in-time check.

Successful explicit service Intents carry boolean `hyperceiler.sensor_privacy_checked=true` and long `hyperceiler.sensor_privacy_checked_at=SystemClock.elapsedRealtime()` sampled at dispatch. The receiving non-exported service must enforce the marker's limited age; the marker does not disable platform camera restrictions. Tests cover global/software/hardware blocks (including simultaneous software+hardware), exceptions from both queries, absent manager/class/method, a user switch during the check, system calling identity, fresh retry timestamps, release of pending state on rejection, and preservation of the interactive path. The API compile continues using only public Android SDK classes; all privileged privacy calls are reflective.
