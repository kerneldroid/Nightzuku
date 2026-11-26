# Android 17 and Shizuku compatibility

This report compares 16 Google ARM64 AOSP GSIs from Android 17 Beta 1 through QPR3 Beta 4. It records platform changes that can explain specific Shizuku or Nightzuku symptoms. The audit used static analysis of the shipped builds and public release notes. None of the GSIs was booted on a device for this audit.

| Reference | Contents |
| --- | --- |
| [Release history](android-17-release-history.md) | Public changes, release dates, Build IDs, and sample coverage |
| [GSI changes](android-17-gsi-changes.md) | Partition, app, APEX, library, policy, and framework deltas |
| [Discussion #19](android-17-discussion-19.md) | Per-build ADB settings evidence and assessment of the locked-boot claims |

## Which change explains which symptom?

| Symptom | Earliest sampled evidence | What the evidence supports |
| --- | --- | --- |
| A legacy server throws `NoSuchMethodError` on `getInstalledPackages` or loses installed-package enumeration | Base Beta 3 `CP21.260306.017` | `IPackageManager.getInstalledPackages(long, int)` now returns `PackageInfoList`. A caller compiled against the `ParceledListSlice` result has a different method descriptor. This is a server-side compatibility failure. The sampled Beta 1 still has the older return type. |
| A legacy server throws `NoSuchMethodError` on three-argument `getUsers`, or loses multi-user enumeration | Base Beta 1 `CP21.260116.011.B1` | The shipped `IUserManager` already exposes only `getUsers(boolean)` among `getUsers` overloads. The first sample sets an upper bound on when the change landed. |
| An app reports USB debugging off while `adbd` is running | QPR1 stable `CP3A.260905.010` | The compiled SettingsProvider can return `0` for `adb_enabled` to ordinary callers. A consumer that treats this read as daemon state can abort startup incorrectly. Base Beta 4 has the annotation with redaction disabled in its compiled flag. |
| An app reports Wireless debugging off even though it is available | QPR3 Beta 1 `DP11.260918.006` and Beta 4 `DP11.261030.003` | Both have active provider-side `0` redaction for `adb_wifi_enabled` and `adb_enabled`. QPR2 Beta 6.1 carries both annotations with its redaction gate disabled. A settings read is an unreliable stand-in for daemon state on the active builds. |
| A Global Settings write fails with a permission error | All 16 have the same primary Global mutation permission check | `SettingsProvider.mutateGlobalSetting` enforces `WRITE_SECURE_SETTINGS`, with additional caller, user, and restriction checks. `MANAGE_DEBUGGING` gates privileged `AdbService` Binder operations. The failing API and exception identify which path was taken. |
| Local wireless discovery or LAN connectivity fails on an app targeting API 37 | Android 17's documented local-network policy. Google also reports a Wireless ADB and local-network regression fix in QPR1 Beta 4. | Determine whether the app had `ACCESS_LOCAL_NETWORK` and whether the failure was discovery, connection, or authorization. The regression's Shizuku-specific impact remains unverified. |
| A client calling the hidden `IAdbManager.denyWirelessDebugging()` fails to link | QPR3 Beta 4 `DP11.261030.003` | The zero-argument descriptor is replaced by `denyWirelessDebugging(boolean, String, String)`. This can break callers of the old hidden Binder method. |
| Auto-start succeeds only after first unlock | Open on the 16 images | The failure could precede discovery, service start, credential access, authorization, or Binder delivery. Revision `5668e79` schedules its ADB boot job at `BOOT_COMPLETED`. The Direct Boot implementation in `d03716d3`, discussed in #19, needs its own APK and device-side confirmation. |

[Android local network permission](https://developer.android.com/privacy-and-security/local-network-permission) · [QPR1 release notes](https://developer.android.com/about/versions/17/qpr1/release-notes) · [Nightzuku #19](https://github.com/kerneldroid/Nightzuku/discussions/19)

## Framework and service checkpoints

`framework.jar` and `services.jar` each have a distinct SHA-256 for every sampled image. Class counts cover every DEX entry in the corresponding JAR. Generated and synthetic classes are included, so a count is a measure of packaged content. Use the exact descriptors below for ABI conclusions.

| Track | GSI Build ID | Framework DEX classes | Services DEX classes |
| --- | --- | ---: | ---: |
| Base Beta 1 | `CP21.260116.011.B1` | 38,284 | 16,383 |
| Base Beta 3 | `CP21.260306.017` | 38,962 | 17,828 |
| Base Beta 4 | `CP21.260330.008` | 36,590 | 18,264 |
| Base stable | `CP2A.260605.012` | 36,590 | 18,302 |
| QPR1 Beta 2 | `CP31.260423.012.A1` | 36,751 | 18,411 |
| QPR1 Beta 3 | `CP31.260508.005` | 36,882 | 18,749 |
| QPR1 Beta 4 | `CP31.260522.006` | 36,987 | 18,820 |
| QPR1 Beta 6 | `CP31.260618.005` | 37,417 | 19,013 |
| QPR1 Beta 7 | `CP31.260623.005` | 37,441 | 18,989 |
| QPR1 Beta 8 | `CP31.260623.010` | 37,442 | 18,990 |
| QPR1 stable | `CP3A.260905.010` | 37,450 | 19,024 |
| QPR2 Beta 1 | `CP41.260701.005` | 37,624 | 19,071 |
| QPR2 Beta 3 | `CP41.260731.005.B1` | 38,195 | 19,424 |
| QPR2 Beta 6.1 | `CP41.260831.007.A3` | 38,773 | 19,882 |
| QPR3 Beta 1 | `DP11.260918.006` | 38,980 | 20,147 |
| QPR3 Beta 4 | `DP11.261030.003` | 40,545 | 20,165 |

### Hidden Binder declarations

The package, user, and permission checks come from `android/content/pm/IPackageManager`, `android/os/IUserManager`, and `android/permission/IPermissionManager` in `framework.jar`.

- `getPackageInfo(Ljava/lang/String;JI)Landroid/content/pm/PackageInfo;`, `getApplicationInfo(Ljava/lang/String;JI)Landroid/content/pm/ApplicationInfo;`, and `getInstalledApplications(JI)Landroid/content/pm/ParceledListSlice;` match in all 16 samples. `getInstalledPackages(JI)` changes by result type in the sampled Beta 1 to Beta 3 interval. `InstalledPackagesCompat` already resolves by parameter types and normalizes the result. [Nightzuku compatibility code](https://github.com/kerneldroid/Nightzuku/blob/5668e79acc841c5446e3deaa1c57f2b65e67c5bf/common/src/main/java/rikka/shizuku/common/util/InstalledPackagesCompat.kt)
- The checked `IPermissionManager` methods have identical descriptors in all 16 builds. `checkPermission`, `grantRuntimePermission`, and `revokeRuntimePermission` include a `String` persistent device ID. `checkUidPermission` takes an integer device ID.
- `IUserManager.getUsers(Z)Ljava/util/List;` is present in all 16 and the three-boolean descriptor is absent. `UsersCompat` catches an old-signature `NoSuchMethodError` and falls back to the surviving overload. [Nightzuku compatibility code](https://github.com/kerneldroid/Nightzuku/blob/5668e79acc841c5446e3deaa1c57f2b65e67c5bf/server/src/main/java/rikka/shizuku/server/util/UsersCompat.kt)
- `IAdbManager` adds `getAdbWirelessHostName()Ljava/lang/String;` between base Beta 3 and Beta 4. QPR3 Beta 4 replaces `denyWirelessDebugging()V` with `denyWirelessDebugging(ZLjava/lang/String;Ljava/lang/String;)V`. For the checked ADB operations, the separate privilege boundary is in `services.jar`, class `com/android/server/adb/AdbService`.

### Daemon, service startup, and discovery

- `services.jar` contains `AdbWifiNetworkMonitor`, network-transition handling, and a trusted-network decision in `AdbDebuggingManager` across all 16 builds. This supports the presence of the platform's trusted-network path. Its execution before first unlock depends on actual boot and network state. [Google's ADB Wi-Fi 2.0 announcement](https://developer.android.com/blog/posts/introducing-fast-and-reliable-wireless-debugging-with-android-debug-bridge-adb-wi-fi-2-0)
- `com.android.adbd.capex` contains the daemon. Hashing the inner `/bin/adbd` produces 12 distinct binaries. Base Beta 4 and base stable share one. QPR1 Beta 6, 7, 8, and stable share another. All 16 inner `/etc/init.rc` files match byte for byte. Same-device connection policy remains a separate runtime question.
- `services.jar` contains `ActiveServices` foreground-service start checks, including the `BOOT_COMPLETED` eligibility path and `ForegroundServiceStartNotAllowedException`, in all 16 samples. Whether a particular `connectedDevice` service receives that exception depends on the app, launch context, build, and device configuration. [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types)
- `framework-res.apk` declares `ACCESS_LOCAL_NETWORK` as a dangerous permission in every sample. Android's documented enforcement for API 37-targeted apps provides a separate explanation for missing mDNS results and LAN socket failures. Nightzuku revision `5668e79` targets API 37, declares the permission, and checks it on the mDNS path. [Permission guidance](https://developer.android.com/privacy-and-security/local-network-permission) · [Nightzuku discovery code](https://github.com/kerneldroid/Nightzuku/blob/5668e79acc841c5446e3deaa1c57f2b65e67c5bf/manager/src/main/java/moe/shizuku/manager/adb/AdbMdns.kt)

## Evidence to capture for a beta-specific failure

Record the full build fingerprint, security patch, device model, app version and target SDK, Android user/profile, local-network permission state, and first-unlock state. Record whether the failure is in service scheduling, discovery, pairing, connection, the server process, or manager Binder delivery. For a hidden API error, include the exception and full descriptor. For a wireless failure, record USB and Wi-Fi setting reads separately, whether `adbd` was independently observed, and whether a trusted network and prior authorization were present. Keep a release-matched successful result or a control run when possible.

## Evidence collection

Each complete archive was verified against its published SHA-256 and the Build ID in its packaged `build.prop`. The raw EXT4 `system.img` was examined read-only, one image at a time. The file inventory records file hashes, modes, ownership, and symlink targets. Targeted DEX disassembly covered `framework.jar`, `services.jar`, `SettingsProvider.apk`, and the Settings wireless debugging UI. Permission declarations came from the binary `framework-res.apk` manifest. The inner ADB APEX was read separately. The analysis used a single disassembly worker per process with a capped Java heap.

These AOSP GSIs carry the generic system partition. Pixel and OEM behavior also depends on other partitions, later module updates, policy configuration, and user data. There was no corresponding booted ARM64 environment in this audit. A report from a Pixel build with an unspecified fingerprint remains independent runtime evidence. [Google GSI archive](https://developer.android.com/topic/generic-system-image/releases) · [DEX format](https://source.android.com/docs/core/runtime/dex-format)
