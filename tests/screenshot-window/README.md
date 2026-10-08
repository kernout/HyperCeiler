# HyperOS 4 screenshot window exclusion test

Run `python3 tests/screenshot-window/run_tests.py` with a JDK available.

The host harness compiles the actual rule against simulated WMS, framework and
hook APIs. It exercises the inherited builder field, screenshot caller scope,
merged cast exclusions, overlay types 2006/2038, freeform mode 5, task deduplication,
independent switches, status-bar-only and combined exclusions, copied
SurfaceControl lifetime and failure cleanup. The `HC-Screenshot` logcat tag
records installation, screenshot caller and exclusions independently of the
module's log-level preference.
It is not a SurfaceFlinger integration test or an LSPosed on-device test.

The inspected HyperOS 4 / Android 17 sources are:

- `IWindowManager.captureDisplay(int, CaptureArgs, ScreenCaptureListener)` is used
  by both `ImageCaptureImpl` and MIUI `CaptureStrategyX`.
- `WindowManagerService.captureDisplay` calls `getCaptureArgs` and then
  `ScreenCaptureInternal.captureLayers` on the same thread.
- `getCaptureArgs` builds `LayerCaptureArgs` with cast exclusions, retaining MIUI
  layer-name exclusions. The hook appends window surface copies at builder time.
- WMS may release capture exclusions after submitting the native request;
  WMS-owned window surfaces must not be placed directly in that list.

Test APK setup: enable Android in LSPosed, enable the desired screenshot switches
under System UI > Status bar, and reboot. The two switches are independent.
Third-party apps do not need to be added to the module scope for this rule.

Device verification remains required for normal/mini freeform windows, separate
shell decorations, overlay implementations using other window types, long
screenshots and rotation. Accessibility overlay type 2032, PiP and split-screen
are not included by this policy. On SDK < 37, the old SystemUI overlay rule is
retained and the new freeform setting is hidden.
