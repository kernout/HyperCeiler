# Physical single volume chord hook regression

Run from this checkout:

```sh
python3 tests/video-chord-hook/run.py
python3 tests/video-chord-hook/old_split_regression.py
python3 tests/video-chord-hook/real_api.py
```

`run.py` compiles the five actual production classes (video chord/state, recorder/state, camera) with deterministic Android/ROM fixtures, then executes `VolumeVideoChordTest`. It reads only the literal `sources` declaration in the existing screen-off-camera runner; it does not execute that runner. All generated sources/classes live in a temporary directory.

`split-input.json` records the reported device evidence: Linux `event0` is `gpio-keys` with only `KEY_VOLUMEUP`; `event3` is `pmic_resin` with only `KEY_VOLUMEDOWN`. Runtime Android IDs and descriptors are synthetic fixture values (1 / 4), deliberately independent of event node numbers. The same fixture feeds the new hook suite and old-source reproduction.

The recognizer now accepts one overlapping physical press, with DOWN timestamps 0–120ms apart, and triggers once after both matching releases. The service handles start/stop and the ten-second recording minimum; the hook sends every accepted toggle. No device, camera, microphone, Activity, wake-lock or input-injection operations occur in this test suite.

Coverage includes both key orders, 0ms alignment, slop/long-press boundaries, per-device source/release ownership, repeat/duplicate/injected/virtual/external/wrong-source/key-capability rejection, user and device changes, callback order, screen on/off, OEM detector reset, recorder pressed-map retirement for both split devices, ordinary camera deferral/replay/failure fallback, sensor privacy, current-user FGS, Binder identity, queue freshness/failure and actual physical-event-to-elapsedRealtime conversion under input delivery delay.

`old_split_regression.py` reads the actual hook/state/recorder sources at `1e90e4a3fc5f9b65fd406246fab791a1a38ca3c5` using read-only `git show` and compiles them only in a temporary directory. It gives that old double-chord recognizer its full two chords using the split-device fixture, confirms installation, and reproduces **zero claims / zero foreground-service dispatches**. It does not change any checkout.

`real_api.py` compiles all five current production classes against actual Android 35, EzHook and Xposed 102 jars, rather than Android/EzHook stubs. Its four dependency paths match this workspace; supply `VIDEO_CHORD_API_CP` to use another classpath. Project-local BaseHook/PrefsBridge/XposedLog collaborators are minimal compile fixtures. This is API compilation, not an APK build or an on-device recording test.

Runtime logs use a fixed reason vocabulary with independent five-second throttling. Look for `Installed single simultaneous volume chord`, `accepted/physical-pair`, `accepted/full-release`, `dispatched/foreground-service` or `reject/...`. No event payload, timestamp, UUID, descriptor or user ID is logged.
