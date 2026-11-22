# Android 15+ Hidden API Changes, Wireless ADB Restrictions, and the Android17Compat Layer

This document records what actually changed in the platform's hidden package,
permission, and debugging APIs, how that was established, and what
`Android17Compat` and the boot auto-start pipeline do about it. Every signature
and annotation below was read out of the platform, not recalled.

## How this was established

- The compiled Android 17 framework,
  `org.robolectric:android-all:17-robolectric-15733970`, which is built from AOSP
  and contains the hidden `android.permission.IPermissionManager$Stub` and
  `android.content.pm.IPackageManager$Stub` that the SDK's public `android.jar`
  does not ship. `javap` on those two interfaces gives the exact descriptors.
- Decompiled Android 17 Canary `/system/framework/framework.jar`
  (`ZP11.261016.008`, SDK 37.2, extension 24) for `android.provider.Settings`,
  `android.Manifest`, and `android.debug.*`.
- The AIDL and framework sources on the `android15-release`, `android16-release`
  and `android17-release` branches of `platform/frameworks/base`.
- `dev.rikka.hidden:compat:4.4.0` itself, to see which signatures the helper
  library was compiled against.

## What changed

### Hidden Package and Permission APIs

`IPermissionManager`, identically on 15, 16 and 17:

```
int  checkPermission(String packageName, String permissionName, String persistentDeviceId, int userId)
void grantRuntimePermission(String packageName, String permissionName, String persistentDeviceId, int userId)
void revokeRuntimePermission(String packageName, String permissionName, String persistentDeviceId, int userId, String reason)
int  checkUidPermission(int uid, String permissionName, int deviceId)
```

Two details matter, and both are easy to get wrong:

1. **The package name comes first, the permission second.** Passing them the other
   way round does not fail loudly: both parameters are `String`, so the call
   succeeds and answers `PERMISSION_DENIED`. A permission that is held is then
   reported as denied, which reads as a permission problem rather than a
   programming one.
2. **The device id is a `String` here, and the value for the local device is
   `"default:0"`** — that is what `android.permission.PermissionManager`
   `getPersistentDeviceId(0)` returns. It is not `"default"`. At the same time
   other methods in the *same* interface — `shouldShowRequestPermissionRationale`,
   `isPermissionRevokedByPolicy`, `getPermissionRequestState` — still take an
   `int deviceId`, so no single hardcoded shape can be correct for the whole
   interface.

`IPackageManager`:

```
PackageInfo     getPackageInfo(String, long, int)         unchanged on 17; see the flag-width note
ApplicationInfo getApplicationInfo(String, long, int)     unchanged on 17; see the flag-width note
                getInstalledPackages(long, int)           ParceledListSlice -> PackageInfoList on 17
                getInstalledApplications(long, int)       unchanged
```

`getInstalledPackages` changed by **return type** (`ParceledListSlice` -> a
`PackageInfoList`-named class on 17). A JVM method descriptor includes the return
type, so an exact descriptor no longer resolves. `InstalledPackagesCompat`
resolves the method by name and parameter types and normalizes both shapes via
`getList()` instead of relying on a single hardcoded descriptor.
`getPackageInfo` and `getApplicationInfo` did *not* change in Android 17, but
they are not stable across older releases.

### The API 33 flag-width change

Android 13 (API 33) introduced `PackageInfoFlags` and `ApplicationInfoFlags` and
widened the hidden flags parameter of both methods from `int` to `long`:

```
API 33+    PackageInfo     getPackageInfo(String, long, int)
API <= 32  PackageInfo     getPackageInfo(String, int, int)
API 33+    ApplicationInfo getApplicationInfo(String, long, int)
API <= 32  ApplicationInfo getApplicationInfo(String, int, int)
```

`Android17Compat` resolves methods by exact `Class` equality for
`getPackageInfo`/`getApplicationInfo`; `InstalledPackagesCompat` resolves
`getInstalledPackages` by name and parameter types. A candidate list that only
contains `(String, long, int)` matches API 33 and later, but finds nothing on
Android 12L and older. That failure is not local: on such a
device `getApplicationInfo` returns `null`, `ShizukuService` cannot resolve the
manager package and exits with `MANAGER_APP_NOT_FOUND` (50); the starter treats
exit code 50 as "the manager was uninstalled", stops restarting the server, and
the UI eventually reports that the service did not initialize.

Both methods therefore carry two candidates:

- `(String, long, int)` first, so API 33+ keeps using the modern signature and
  no narrowing happens there;
- `(String, int, int)` second, with `flags.toInt()`, for Android 12L and older.

The `int` fallback is only selected after the `long` shape has been rejected by
reflection, so a device that supports both cannot accidentally take the legacy
path. The compat layer only passes flag values that fit in 32 bits (`0` plus
permission bits), so `toInt()` is lossless for those calls; do not reuse the
fallback for APIs that need the high 32 bits of a 64-bit flag value.

The change is not specific to Android 17: `android15-release` already carries the
four-argument permission signatures, and the flag-width split above is tied to
API 33 rather than to any 15/16/17 release.

## Wireless ADB & Boot Auto-Start Changes on Android 17 (API 37)

Three platform changes in Android 17 directly affect rootless wireless ADB
startup:

1. **Write restriction (`MANAGE_DEBUGGING`):**
   `SettingsProvider` requires `android.permission.MANAGE_DEBUGGING`
   (`signature|privileged`, not grantable via `pm grant`) to write `adb_enabled`,
   `adb_wifi_enabled`, and `adb_allowed_connection_time`. Holding
   `WRITE_SECURE_SETTINGS` alone is no longer sufficient after a reboot when the
   Nightzuku server is not running.
2. **Read redaction (`@Readable(redactedValue = "0")`):**
   In `framework.jar` (`ZP11.261016.008`, `android/provider/Settings.java`,
   lines 4497–4501), both `Settings.Global.ADB_ENABLED` and
   `Settings.Global.ADB_WIFI_ENABLED` are annotated with
   `@Readable(redactedValue = "0")`. Third-party apps always read `0` even when
   wireless debugging is active. Gating boot startup on
   `AdbSettings.isWirelessAdbEnabled()` therefore aborts even when `adbd` is
   already listening.
3. **Local network runtime permission (`ACCESS_LOCAL_NETWORK`):**
   Apps targeting API 37 must hold the runtime permission
   `android.permission.ACCESS_LOCAL_NETWORK` for `NsdManager` mDNS discovery and
   local LAN TCP connections; otherwise discovery fails with `EPERM`
   ([Android Local Network Permission](https://developer.android.com/privacy-and-security/local-network-permission)).

### How Nightzuku handles boot auto-start across Android 11–17+

- **`BootCompleteReceiver` + `AdbBootStartScheduler` (`JobScheduler`):**
  For `LaunchMethod.ADB`, `BootCompleteReceiver` ignores `LOCKED_BOOT_COMPLETED`
  and immediately schedules a persisted `JobInfo` (`JOB_ID_ADB_BOOT_START = 4210`)
  with exponential backoff. The Wi-Fi transport constraint is applied when no
  ADB TCP port is configured; with a TCP port set the job uses no network
  constraint. This eliminates the Wi-Fi association race and the 10-second
  `goAsync()` ANR ceiling.
- **Hardened `AdbMdns`:**
  Uses `NsdManager.registerServiceInfoCallback()` on API 34+ (avoiding deprecated
  `resolveService()`), serializes legacy `resolveService()` calls on API 30–33 to
  prevent `FAILURE_ALREADY_ACTIVE`, acquires `WifiManager.MulticastLock` during
  discovery, and checks `ACCESS_LOCAL_NETWORK` on API 37+.
- **Native ADB Wi-Fi 2.0 Zero-Touch (Android 17+):**
  Android 17 ADB Wi-Fi 2.0 automatically re-enables wireless debugging when the
  device connects to a trusted Wi-Fi network
  ([Android Developers Blog](https://android-developers.googleblog.com/2026/09/wireless-debugging-adb-wifi-2.html)).
  `AdbSettings.prepareWirelessAdbForBoot()` returns `REDACTION_DEFER_TO_MDNS` on
  API 37+ so `AdbBootStartJobService` proceeds directly to mDNS discovery without
  reading or writing `Settings.Global`. While the server is active (`UID 2000`),
  `NightDogClient` calls `AdbSettings.preProvisionForAutostart()` to persist
  `adb_allowed_connection_time = 0` so paired keys do not expire after 7 days.
- **Non-loopback & offline companion architectures:**
  - **External LAN / OpenWrt / PC mDNS starter** (`tools/nightzuku-lan-starter`):
    connects over `wlan0` when the phone joins home Wi-Fi; unaffected by Android
    Doze, `ACCESS_LOCAL_NETWORK`, or a future loopback restriction.
  - **Hybrid Device Owner bridge** (`DeviceOwnerBridge` +
    `NightzukuDeviceAdminReceiver`): optional opt-in offline layer surviving cold
    reboots without Wi-Fi or ADB for package management, runtime permissions, and
    `Settings.Global.ADB_ENABLED` persistence.
  - See [`docs/adb-loopback-restriction-memo.md`](adb-loopback-restriction-memo.md)
    for forward-looking tracking of the potential `adbd` localhost (`127.0.0.1`)
    restriction in future QPR releases.

## What Android17Compat does

- Resolves the hidden interfaces through `ServiceManager` and `Class.forName`, and
  tries candidate argument shapes against the methods the platform actually
  exposes.
- Requires an **exact** parameter-type match, and never tries a candidate whose
  parameter shape duplicates one already tried. Two argument lists that bind to
  the same parameter types are not alternatives — reflection cannot tell them
  apart, so treating them as alternatives is how a wrong order gets silently
  accepted.
- Reads `IllegalArgumentException` as "this shape is not for this method" and
  tries the next, and `InvocationTargetException` as the platform answering, which
  is not retried as a wrong guess.
- Caches the resolved method together with the shape that won, so the guessing
  happens once, and reports through the server log when no candidate matched
  instead of returning a denial that looks like an answer.
- `grantRuntimePermission` and `revokeRuntimePermission` return whether the
  platform was reached, so a grant cannot be lost without a word.

## Known limits

- The 15/16/17 shapes are covered. Pre-15 shapes are kept as legacy candidates
  where they are distinguishable by arity, but they are not the target.
- This layer is compile-verified against the Android 17 SDK and reviewed against
  the platform sources.
