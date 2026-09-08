# Dextop HONOR Test 1

Version: 1.6.7-honor-test1 (34). Android 11+, ARM64. Initial target: HONOR Magic Desktop.

This build adds external-display DPI / logical working-area controls and read-only HONOR diagnostics.
Native Magic Desktop on the phone without a monitor is **not implemented or claimed working** in Test 1.
Physical HDMI/DisplayPort output mode changes remain in the existing display-mode picker.
No device profile or Samsung desktop launch strategy is changed.

## Install and use

The `honor` flavor installs as **Dextop HONOR Test** (`io.github.inoyuuyuu.dextop.honortest`).
It can coexist with the original Dextop. Stop the original Dextop session before testing;
both apps manipulate the same Android display subsystem. Complete initial access setup
in the test app. Existing permissions/settings are not copied from the original app.

Open **Settings → Display → External display & HONOR diagnostics (Test 1)**.
The controls open on the phone even when invoked from an external desktop.

1. Start Magic Desktop on the external monitor and refresh monitors.
2. First try only DPI, e.g. 160 or 200. Smaller DPI generally means smaller UI.
3. Check text, icons and mouse alignment; choose **Keep changes** before the countdown ends.
4. Then optionally enable the width/height checkbox to try a logical working area.
5. **Restore changes made by this app** restores the prior overrides, including existing custom values.

Changed values are read back through `wm` and `DisplayManager`. A success exit code alone
does not count as an applied change. Some HONOR firmware may reject these operations.
Physical output resolution and logical working area are shown separately.

## Recovery behavior

Trials journal the old size and density before making changes. An independent shell watchdog
attempts rollback after 30 seconds unless an atomic confirmation wins first. The UI countdown
ends slightly earlier. The OEM display identity/dump format is checked before allowing a trial.
The phone's display and Dextop virtual/overlay displays cannot be selected.

After process death, unconfirmed changes are also recovered on reopening the tools page.
If the monitor is disconnected, recovery remains pending: reconnect the same monitor and
refresh. A recycled numerical display ID is not enough to authorize restoration.
Recovery still requires a working privileged service/OS; revoking debugging access or an
OEM killing shell jobs can prevent the watchdog. Reopening the tools page retries the journal.
Confirmed values are not automatically reapplied after reconnect in Test 1.

## Diagnostics for phone-only Magic Desktop work

Save one snapshot in each state, then use **Copy combined diagnostics**:

- 1: external monitor disconnected; Dextop stopped.
- 2: native Magic Desktop working on the external monitor.
- 3: external monitor disconnected; immediately after the phone Dextop attempt fails.

Reports include firmware, display flags/types/modes, current and overridden metrics,
desktop-related OEM components/services/settings, and Dextop logs. Probes do not start
OEM services or alter settings. Intent contents, URIs and window titles are not collected;
MAC addresses are redacted. Snapshots stay on the device and are copied only on request.

## Build and validation

The `HONOR Test APK` workflow analyzes Flutter, runs Flutter and Android unit tests,
then builds the ARM64 `honorDebug` APK. The original debug workflow remains available.
CI prepares an ignored `android/honor-test-debug.keystore` from the public AOSP development
`testkey` at platform_build commit `045a3d6a3e359633a14853a5a5e1e4f2a11cbdae`.
This is solely for update-compatible experimental APKs, never production or trusted distribution.
No private signing material is committed. Release/distribution keys remain separate.

Required device checks: first launch/setup, DPI apply/confirm, expiry rollback, existing
override preservation, logical resize, pointer alignment, forced app termination during
a trial, unplug/replug, diagnostic snapshots, and Samsung regression checks. Automated
tests cannot establish HONOR firmware compatibility; actual device results are pending.
