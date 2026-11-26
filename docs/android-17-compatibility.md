# Android 15+ Hidden API Changes, Wireless ADB Restrictions, and the Android17Compat Layer

This document records what actually changed in the platform's hidden package,
permission, and debugging APIs, how that was established, and what
`Android17Compat` and the boot auto-start pipeline do about it. Every signature
and annotation below was read out of the platform, not recalled.

The [Android 17 GSI audit](android-17-shizuku-findings.md) adds evidence from 16
public ARM64 builds, including per-build differences from the Canary snapshot
listed below. The [discussion #19 review](android-17-discussion-19.md) records
the SettingsProvider and Direct Boot findings.

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
                getInstalledPackages(long, int)           ParceledListSlice -> PackageInfoList by 17 Beta 3
                getInstalledApplications(long, int)       unchanged
```

`getInstalledPackages` changed by **return type** between the sampled Android 17
Beta 1 (`ParceledListSlice`) and Beta 3 (`PackageInfoList`). A JVM method descriptor includes the return
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

1. **Settings writes and ADB Binder calls.** In the 16 audited GSIs, the ordinary
   Global Settings write path enforces `WRITE_SECURE_SETTINGS`. The separate
   `AdbService` Binder interface enforces `MANAGE_DEBUGGING` on privileged
   operations. The caller, user restrictions, and timing can also affect the
   result. [SettingsProvider evidence](android-17-discussion-19.md#image-comparison)
2. **Version-specific read redaction.** QPR1 stable actively redacts `adb_enabled`
   for ordinary app reads. The two audited QPR3 betas actively redact both
   `adb_enabled` and `adb_wifi_enabled`. Earlier samples include disabled
   redaction gates and, in QPR2 Beta 6.1, annotations for both keys with inactive
   enforcement. Using a setting read as the sole indication of daemon state can
   produce a false negative on an affected build. The complete table records
   the flags and caller behavior. [Per-build evidence](android-17-discussion-19.md#image-comparison)
3. **Local network runtime permission (`ACCESS_LOCAL_NETWORK`).** Android 17
   enforces local-network protection for apps targeting API 37 or higher.
   `NsdManager` discovery and LAN connections can be affected. Android also
   exposes system-mediated discovery for user-selected services.
   [Android local network permission](https://developer.android.com/privacy-and-security/local-network-permission)

### How Nightzuku handles boot auto-start across Android 11–17+

- **`BootCompleteReceiver` + `DirectBootStartService` + `AdbBootStartScheduler` (`JobScheduler`):**
  For `LaunchMethod.ADB`, `LOCKED_BOOT_COMPLETED` starts the `directBootAware`
  `DirectBootStartService` (`connectedDevice`), which immediately connects on
  fixed TCP ports or waits for Wi-Fi IP readiness, pre-warms `AdbKey`, triggers
  `adb_wifi_enabled` (`0 -> 1` on API 30–36 with `ContentObserver` refusal
  detection), and starts the server before first unlock when the Wi-Fi network
  is already trusted ("Always allow"). On `BOOT_COMPLETED`, if the server is
  already running it avoids restarting it; otherwise `AdbBootStartScheduler`
  schedules a non-persisted `JobInfo` (`JOB_ID_ADB_BOOT_START = 4210`,
  `NETWORK_TYPE_UNMETERED` or `NETWORK_TYPE_NONE` for TCP) with exponential
  backoff.
- **Parallel `AdbPortScanner` + hardened `AdbMdns`:**
  `AdbPortScanner` probes `127.0.0.1` ephemeral ports (prioritizing kernel
  `inet_csk_find_open_port` lower-half odd ports and `AdbPortHistory`) with
  `A_CNXN` -> `A_STLS` verification concurrently with `AdbMdns` (which uses
  `NsdManager.registerServiceInfoCallback()` on API 34+, serialized legacy
  `resolveService()` on API 30–33, `WifiManager.MulticastLock`, and
  `ACCESS_LOCAL_NETWORK` checks on API 37+), avoiding early-boot `mdnsd` races.
- **Native ADB Wi-Fi 2.0 Zero-Touch (Android 17+):**
  Android 17 ADB Wi-Fi 2.0 automatically re-enables wireless debugging when the
  device connects to a trusted Wi-Fi network
  ([Android Developers Blog](https://developer.android.com/blog/posts/introducing-fast-and-reliable-wireless-debugging-with-android-debug-bridge-adb-wi-fi-2-0)).
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
