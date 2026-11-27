# Android 17 GSI and Shizuku compatibility audit

**Snapshot: 27 November 2026.** This report combines the release timeline, Android 17 GSI image comparison, Shizuku and Nightzuku compatibility findings, and the code review of Discussion #19. Google lists Android 17 QPR3 Beta 4 (`DP11.261030.003`, 12 November 2026) as the latest QPR beta available in this snapshot. [QPR3 release notes](https://developer.android.com/about/versions/17/qpr3/release-notes) · [QPR3 GSI release](https://developer.android.com/about/versions/17/qpr3/gsi-release-notes)

**Contents:** [Key findings](#key-findings-for-shizuku-and-nightzuku) · [Release history](#android-17-release-chronology-and-published-changes) · [GSI comparison](#gsi-filesystem-and-artifact-comparison) · [Hidden APIs](#hidden-api-compatibility) · [ADB and Discussion #19](#adb-settings-and-discussion-19) · [Boot revisions](#nightzuku-boot-behavior-separate-code-revisions) · [Runtime status](#gsi-boot-attempt-status) · [Limits](#analysis-limits)

## Scope and evidence

- **Static image audit:** 16 complete Google AOSP ARM64 GSI archives from Android 17 Beta 1 through QPR3 Beta 4. The build manifest contains exact ZIP names, URLs, checksums, and build IDs: [gsi-builds.tsv](gsi-builds.tsv).
- **Release history:** the chronology also records releases outside those 16 images. It distinguishes sampled images from cataloged, partial, or unavailable GSI archives; a release listed in the chronology is not automatically part of the static comparison.
- **Inspection:** archive hashes and `build.prop` IDs were checked; the EXT4 `system.img` was examined read-only; selected framework, services, SettingsProvider, resource manifest, and ADB APEX contents were inspected. Path-level TSV output was removed at the user's request; summary counts and selected structural changes remain below.
- **Runtime:** no GSI boot was confirmed. Android Emulator 37.2.12 is installed, but `/dev/kvm` and an ARM64 AVD image are absent. The actual QPR3 Beta 4 x86_64 GSI was tested separately; see [GSI boot attempt status](#gsi-boot-attempt-status).
- **GSI launch triage:** the 16 static-comparison archives are ARM64 and were skipped on the x86_64 host after the emulator's ARM64-host limitation was confirmed. Google GSI archives contain a generic system partition, not a complete device boot/vendor image. [GSI requirements](https://developer.android.com/topic/generic-system-image/) · [Emulator acceleration](https://developer.android.com/studio/run/emulator-acceleration) · [AOSP emulator launcher](https://android.googlesource.com/platform/external/qemu/+/emu-master-dev/android/emulator/main-emulator.cpp)

## Key findings for Shizuku and Nightzuku

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

## Android 17 release chronology and published changes

## Release chronology

Beta dates and device build IDs come from Google’s release notes. Dates on stable rows identify the source in parentheses. GSI build IDs come from archive names in `out/android17-gsi/catalog.tsv`. **Local** denotes a complete ZIP under `out/android17-gsi/images/`, **Partial** denotes an incomplete download, and **Catalog** denotes a recorded URL without a complete local copy. The build manifest lists direct URLs, published SHA-256 checksums, and Google’s numeric build IDs for the 16 audited images. [Build manifest](gsi-builds.tsv)

### Base release

[Android 17 beta notes](https://developer.android.com/about/versions/17/release-notes) · [Stable announcement](https://android-developers.googleblog.com/2026/06/Android-17.html) · [Stable GSI](https://developer.android.com/about/versions/17/gsi-release-notes) · [AOSP build tags](https://source.android.com/docs/setup/reference/build-numbers)

| Date (2026) | Release | Published build | ARM64 GSI | Coverage |
| --- | --- | --- | --- | --- |
| Feb 13 | Beta 1 | `CP21.260116.011.B1`, `CP21.260116.011.A1` | `CP21.260116.011.B1` | Local |
| Feb 26 | Beta 2 | `CP21.260206.011`, `CP21.260206.011.A1` | `CP21.260206.011` | Catalog |
| Mar 26 | Beta 3 | `CP21.260306.017` | `CP21.260306.017` | Local |
| Apr 16 | Beta 4 | `CP21.260330.008` | `CP21.260330.008` | Local |
| Jun 1 | Beta 4.1 | `CP21.260330.011` | `CP21.260330.011` | Catalog |
| Jun 18 (GSI) | Stable | `CP2A.260605.016` (AOSP `android-17.0.0_r1`) | `CP2A.260605.012` | Local |

### QPR1

[QPR1 beta notes](https://developer.android.com/about/versions/17/qpr1/release-notes) · [September Pixel update](https://support.google.com/pixelphone/thread/467705218/google-pixel-update-september-2026?hl=en) · [Stable GSI](https://developer.android.com/about/versions/17/qpr1/gsi-release-notes) · [Archived Beta 9 GSI listing](https://web.archive.org/web/20260906220712/https://developer.android.com/topic/generic-system-image/releases)

| Date (2026) | Release | Published device build | ARM64 GSI | Coverage |
| --- | --- | --- | --- | --- |
| Apr 22 | Beta 1 | `CP31.260403.005.A1` | — | Early listing led to base Beta 4 ([Google issue #508092631](https://issuetracker.google.com/issues/508092631)) |
| May 6 | Beta 2 | `CP31.260423.012.A1` | `CP31.260423.012.A1` | Local |
| May 19 | Beta 3 | `CP31.260508.005` | `CP31.260508.005` | Local |
| Jun 10 | Beta 4 | `CP31.260522.006` | `CP31.260522.006` | Local |
| Jun 23 | Beta 5 | `CP31.260608.007` | — | No confirmed GSI URL |
| Jul 1 | Beta 6 | `CP31.260618.005` | `CP31.260618.005` | Local |
| Jul 15 | Beta 7 | `CP31.260623.005` | `CP31.260623.005` | Local |
| Jul 31 | Beta 8 | `CP31.260623.010` | `CP31.260623.010` | Local |
| Aug 17 | Beta 9 | `CP31.260623.012` | `CP31.260623.013` | Partial |
| Sep 17 (GSI) | Stable | `CP3A.260905.009` (September Pixel update) | `CP3A.260905.010` | Local |

### QPR2

[QPR2 beta notes](https://developer.android.com/about/versions/17/qpr2/release-notes) · [QPR2 GSI](https://developer.android.com/about/versions/17/qpr2/gsi-release-notes) · [November Pixel update](https://support.google.com/pixelphone/thread/477214134/google-pixel-update-november-2026?hl=en-gb) · [November Pixel bulletin](https://source.android.com/docs/security/bulletin/pixel/2026/2026-11-01)

| Date (2026) | Release | Published device build | ARM64 GSI | Coverage |
| --- | --- | --- | --- | --- |
| Jul 20 | Beta 1 | `CP41.260701.005` | `CP41.260701.005` | Local |
| Aug 3 | Beta 2 | `CP41.260717.006` | `CP41.260717.006` | Partial |
| Aug 14 | Beta 3 | `CP41.260731.005.A2`, `CP41.260731.005.B1` | `CP41.260731.005.B1` | Local |
| Aug 28 | Beta 4 | `CP41.260814.003.A2`, `CP41.260814.003.B1`, `CP41.260814.003.C2` | `CP41.260814.003.B1` | Partial |
| Sep 15 | Beta 5 | `CP41.260828.004.A8`, `CP41.260828.005.A6` | `CP41.260828.004.A8` | Partial |
| Sep 24 | Beta 6 | `CP41.260831.007` | `CP41.260831.007` | Catalog |
| Sep 29 | Beta 6.1 | `CP41.260831.007.A3`, `CP41.260831.011` | `CP41.260831.007.A3` | Local |
| Oct 9 | Beta 7 | `CP41.260831.016` | `CP41.260831.016` | Partial |
| Oct 16 | Beta 7.1 | `CP41.260831.019` | `CP41.260831.019` | Catalog, archive URL returned HTTP 404 |
| Nov 10 (Pixel) | Stable | `CP4A.261105.040.A1`, `CP4A.261105.040.D1` (global builds) | — | No confirmed stable GSI URL |

The live QPR2 GSI page labels the download section `CP41.260831.019`, while the ARM64 ZIP it serves carries `CP41.260831.007.A3`. Use the ZIP ID when citing results from the local QPR2 Beta 6.1 image. [Live GSI listing](https://developer.android.com/about/versions/17/qpr2/gsi-release-notes) · [Archived Beta 7.1 listing](https://web.archive.org/web/20261018104138/https://developer.android.com/topic/generic-system-image/releases)

### QPR3

[QPR3 beta notes](https://developer.android.com/about/versions/17/qpr3/release-notes) · [QPR3 Beta 4 GSI](https://developer.android.com/about/versions/17/qpr3/gsi-release-notes)

| Date (2026) | Release | Published device build | ARM64 GSI | Coverage |
| --- | --- | --- | --- | --- |
| Oct 2 | Beta 1 | `DP11.260918.005`, `DP11.260918.006` | `DP11.260918.006` | Local |
| Oct 20 | Beta 2 | `DP11.261002.005` | `DP11.261002.005` | Partial |
| Oct 29 | Beta 3 | `DP11.261016.003`, `DP11.261016.004` | `DP11.261016.003` | Partial |
| Nov 12 | Beta 4 | `DP11.261030.003` | `DP11.261030.003` | Local |

## Published platform changes

### Base Android 17 (API 37)

- **Beta 1.** Android 17-targeted apps on large screens lost the general opt-out from resizability and orientation changes, with documented exemptions. Android introduced a lock-free `MessageQueue` for API 37 targets, generational ART garbage collection, more profiling triggers, tighter custom notification checks, and fewer activity restarts for selected configuration changes. Media additions included dynamic camera session updates, constant-quality video encoding, and VVC support. [Beta 1 announcement](https://android-developers.googleblog.com/2026/02/the-first-beta-of-android-17.html) · [Behavior changes for API 37 targets](https://developer.android.com/about/versions/17/behavior-changes-17)
- **Beta 2.** Google previewed wider app bubble support, introduced the contacts picker and system eyedropper, and added cross-device handoff and ranging APIs. Touchpads began reporting relative motion during pointer capture by default. The release notes also list a layout fix for the Wireless Debugging QR scanner. [Beta 2 announcement](https://android-developers.googleblog.com/2026/02/the-second-beta-of-android-17.html) · [Beta notes](https://developer.android.com/about/versions/17/release-notes)
- **Beta 3.** The public API reached Platform Stability. Additions included post-quantum APK signing support, per-session location access through a system-rendered button, an idle-alarm listener API, `ImageFormat.RAW14`, vendor camera extensions, and hearing-aid audio routing. [Beta 3 announcement](https://android-developers.googleblog.com/2026/03/the-third-beta-of-android-17.html) · [API diff from 36](https://developer.android.com/sdk/api_diff/37/changes/jdiff_statistics)
- **Beta 4 and 4.1.** Beta 4 documented per-app memory limits and default cross-profile loopback isolation. Same-profile loopback remains supported under the documented rule. Beta 4 also revised aspects of background audio enforcement. Beta 4.1 addressed status indicators, Bluetooth audio, hearing-aid pairing, and external display regressions. [Beta notes](https://developer.android.com/about/versions/17/release-notes) · [All-app behavior changes](https://developer.android.com/about/versions/17/behavior-changes-all)
- **Stable.** Google announced stable Android 17 on June 16 and dated the stable GSI June 18. API 37 target changes include the `ACCESS_LOCAL_NETWORK` permission for local-device access, protection of `static final` fields, and tighter native dynamic code loading checks. The platform also added new job diagnostics, expanded companion-device and satellite capabilities, and introduced privacy changes for contacts and messaging. [Stable announcement](https://android-developers.googleblog.com/2026/06/Android-17.html) · [Platform change index](https://developer.android.com/about/versions/17/summary)

### QPR1 (API 37.1)

- **Betas 1–3.** Google lists Terminal and audio HAL fixes in Beta 1, F2FS stability and Bluetooth tethering persistence fixes in Beta 2, and Wi-Fi quality detection and reboot-related widget fixes in Beta 3. [QPR1 beta notes](https://developer.android.com/about/versions/17/qpr1/release-notes)
- **Betas 4–6.** Beta 4 fixed a reported Wireless ADB and local-network connectivity regression. Beta 5 fixed DownloadManager timeouts when excluded from an active VPN and issues with locked Private Space apps appearing in search. Beta 6 reached Platform Stability and fixed `WindowManagerGlobal` crashes and hotspot naming. The public API diff covers input, parts of `java.util.jar`, the photo picker, HTTP, and other packages. [QPR1 beta notes](https://developer.android.com/about/versions/17/qpr1/release-notes) · [Wireless ADB issue #506418219](https://issuetracker.google.com/issues/506418219) · [API diff from 37](https://developer.android.com/sdk/api_diff/37.1/changes/jdiff_statistics)
- **Betas 7–9 and stable.** Later betas included Quick Settings fixes, audio buffer and NFC regressions, fingerprint unlock, reboot loops, and battery drain. The September Pixel release also listed system UI, connectivity, and stability fixes. [QPR1 beta notes](https://developer.android.com/about/versions/17/qpr1/release-notes) · [September Pixel update](https://support.google.com/pixelphone/thread/467705218/google-pixel-update-september-2026?hl=en)

### QPR2 (API 37.2)

- **Platform behavior.** Google documents restrictions on programmatic call-forwarding USSD and changes to broadcasts whose receivers share the sender’s process. These broadcasts no longer raise process importance, may wait while the sender is frozen, and may be lost if the process dies. The public API diff includes identity credentials, biometrics, app roles, companion devices, and framework APIs. [QPR2 beta notes](https://developer.android.com/about/versions/17/qpr2/release-notes) · [API diff from 37.1](https://developer.android.com/sdk/api_diff/37.2/changes/jdiff_statistics)
- **Betas 1–4.** Google lists fixes for Bluetooth re-pairing and notification delivery in Beta 1, notification-shade crashes in Beta 3, and Bluetooth call routing, audio resource locking, thermal stability, and UI rendering in Beta 4. The release notes give Beta 2 a build entry. [QPR2 beta notes](https://developer.android.com/about/versions/17/qpr2/release-notes)
- **Betas 5–7.1 and stable.** Beta 5 addressed Private Space unlocking, camera, and Bluetooth issues. Beta 6 fixed reboot cases under camera or microphone use. Beta 7 fixed DownloadManager HTTPS failures and an idle power regression. Beta 7.1 fixed severe UI stalls. Google’s November Pixel announcement records additional device fixes. [QPR2 beta notes](https://developer.android.com/about/versions/17/qpr2/release-notes) · [November Pixel update](https://support.google.com/pixelphone/thread/477214134/google-pixel-update-november-2026?hl=en-gb)

### QPR3

- **Betas 1–2.** Beta 1 opens the preview branch in Google’s chronology. Beta 2 lists fixes for reboots, lock-screen notification overlap, Linux Terminal VM startup, and Wi-Fi Aware reconnection. [QPR3 beta notes](https://developer.android.com/about/versions/17/qpr3/release-notes)
- **Betas 3–4.** Beta 3 addressed a lingering Settings process, startup and lock-screen crashes, WPA-Enterprise PEAP authentication, force-stop, and activity launch with Picture-in-Picture. Beta 4 fixed `system_server` memory leaks, excessive Bluetooth power use, and Conscrypt error reporting for rejected legacy TLS versions. [QPR3 beta notes](https://developer.android.com/about/versions/17/qpr3/release-notes)

## Wireless debugging evidence

- The base Beta 4 loopback announcement covers cross-profile traffic and explicitly preserves same-profile loopback. The local-network permission rules for API 37 targets cover access to LAN addresses. [Base beta notes](https://developer.android.com/about/versions/17/release-notes) · [Local network protections](https://developer.android.com/privacy-and-security/local-network-permission)
- QPR1 Beta 3 and Beta 4 are adjacent local images on either side of Google’s published Wireless ADB fix. The fix description gives a release-level comparison point for connection failures. [QPR1 beta notes](https://developer.android.com/about/versions/17/qpr1/release-notes)
- Google announced ADB Wi-Fi 2.0 on September 9. The announcement describes a revised workstation mDNS stack and `adbd` disabling itself on untrusted networks and resuming on user-allowed networks. First-unlock timing, the `Settings.Global` permission policy, and the first GSI implementation remain separate evidence questions. [ADB Wi-Fi 2.0 announcement](https://android-developers.googleblog.com/2026/09/wireless-debugging-adb-wifi-2.html?hl=ro)

## GSI filesystem and artifact comparison

## Sampled builds

Build IDs, SDK values, and patch levels come from the archives' `build.prop` files. Entry counts cover the archived `system.img` tree, including files, directories, and symlinks. Each complete ZIP was verified against its published SHA‑256 before inspection. The manifest records the exact filenames, full archive digests, download URLs, and source listings. [Build manifest](gsi-builds.tsv)

| Track | Release | Build ID | SDK (`sdk_full`) | Preview SDK | Patch | Entries | Files |
| --- | --- | --- | --- | ---: | --- | ---: | ---: |
| Base | Beta 1 | `CP21.260116.011.B1` | `36` (`36.1`) | 1 | 2026-01-05 | 4,375 | 3,604 |
| Base | Beta 3 | `CP21.260306.017` | `37` (`37.0`) | 0 | 2026-03-05 | 4,347 | 3,583 |
| Base | Beta 4 | `CP21.260330.008` | `37` (`37.0`) | 0 | 2026-04-05 | 4,363 | 3,599 |
| Base | Stable | `CP2A.260605.012` | `37` (`37.0`) | 0 | 2026-06-05 | 4,363 | 3,599 |
| QPR1 | Beta 2 | `CP31.260423.012.A1` | `37` (`37.0`) | 3712 | 2026-04-05 | 4,361 | 3,597 |
| QPR1 | Beta 3 | `CP31.260508.005` | `37` (`37.0`) | 3713 | 2026-05-05 | 4,361 | 3,599 |
| QPR1 | Beta 4 | `CP31.260522.006` | `37` (`37.0`) | 3714 | 2026-05-05 | 4,368 | 3,606 |
| QPR1 | Beta 6 | `CP31.260618.005` | `37` (`37.1`) | 0 | 2026-06-05 | 4,376 | 3,613 |
| QPR1 | Beta 7 | `CP31.260623.005` | `37` (`37.1`) | 0 | 2026-07-05 | 4,378 | 3,615 |
| QPR1 | Beta 8 | `CP31.260623.010` | `37` (`37.1`) | 0 | 2026-07-05 | 4,378 | 3,615 |
| QPR1 | Stable | `CP3A.260905.010` | `37` (`37.1`) | 0 | 2026-09-05 | 4,390 | 3,624 |
| QPR2 | Beta 1 | `CP41.260701.005` | `37` (`37.1`) | 3721 | 2026-07-05 | 4,376 | 3,614 |
| QPR2 | Beta 3 | `CP41.260731.005.B1` | `37` (`37.1`) | 3723 | 2026-08-05 | 4,445 | 3,681 |
| QPR2 | Beta 6.1 | `CP41.260831.007.A3` | `37` (`37.2`) | 0 | 2026-09-05 | 4,455 | 3,690 |
| QPR3 | Beta 1 | `DP11.260918.006` | `37` (`37.2`) | 0 | 2026-09-05 | 4,365 | 3,649 |
| QPR3 | Beta 4 | `DP11.261030.003` | `37` (`37.2`) | 0 | 2026-10-05 | 4,416 | 3,694 |

## Within-track changes

Each row compares adjacent **sampled** builds in a release track. A gap can contain one or more unsampled releases. `File bytes` counts same-path regular files with different SHA‑256 digests. `Mode/owner` counts changes to mode, UID, or GID and may overlap with another column. `Paths with changes` counts each path once.

| From → to | Added | Removed | File bytes | Link targets | Type | Mode/owner | Paths with changes |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `CP21.260116.011.B1` → `CP21.260306.017` | 66 | 94 | 2,344 | 0 | 1 | 0 | 2,505 |
| `CP21.260306.017` → `CP21.260330.008` | 25 | 9 | 2,235 | 0 | 0 | 0 | 2,269 |
| `CP21.260330.008` → `CP2A.260605.012` | 0 | 0 | 564 | 0 | 0 | 0 | 564 |
| `CP31.260423.012.A1` → `CP31.260508.005` | 6 | 6 | 1,744 | 0 | 0 | 0 | 1,756 |
| `CP31.260508.005` → `CP31.260522.006` | 7 | 0 | 1,647 | 0 | 0 | 0 | 1,654 |
| `CP31.260522.006` → `CP31.260618.005` | 51 | 43 | 1,866 | 0 | 0 | 0 | 1,960 |
| `CP31.260618.005` → `CP31.260623.005` | 2 | 0 | 1,072 | 0 | 0 | 0 | 1,074 |
| `CP31.260623.005` → `CP31.260623.010` | 0 | 0 | 471 | 0 | 0 | 0 | 471 |
| `CP31.260623.010` → `CP3A.260905.010` | 12 | 0 | 509 | 0 | 0 | 0 | 521 |
| `CP41.260701.005` → `CP41.260731.005.B1` | 100 | 31 | 2,339 | 0 | 1 | 0 | 2,471 |
| `CP41.260731.005.B1` → `CP41.260831.007.A3` | 30 | 20 | 2,094 | 0 | 0 | 0 | 2,144 |
| `DP11.260918.006` → `DP11.261030.003` | 92 | 41 | 2,423 | 0 | 0 | 0 | 2,556 |

### Changed paths by component

Classification follows the path under `system`, `system_ext`, and `product`. App groups include package files and their preoptimized code. The APEX group counts containers and other entries in `apex/`. Package configuration includes permission declarations, default grants, system configuration, and VINTF XML files. Counts include added and removed directories.

| From → to | Framework | APEX | Privileged apps | System apps | SELinux | Boot configuration | Package configuration | Native executables | Libraries | Other |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `CP21.260116.011.B1` → `CP21.260306.017` | 620 | 44 | 196 | 102 | 25 | 16 | 16 | 162 | 1,255 | 69 |
| `CP21.260306.017` → `CP21.260330.008` | 588 | 43 | 172 | 100 | 12 | 19 | 20 | 168 | 1,102 | 45 |
| `CP21.260330.008` → `CP2A.260605.012` | 301 | 31 | 92 | 36 | 2 | 3 | 3 | 14 | 60 | 22 |
| `CP31.260423.012.A1` → `CP31.260508.005` | 587 | 43 | 172 | 97 | 6 | 7 | 2 | 119 | 689 | 34 |
| `CP31.260508.005` → `CP31.260522.006` | 589 | 43 | 172 | 98 | 5 | 5 | 1 | 75 | 631 | 35 |
| `CP31.260522.006` → `CP31.260618.005` | 589 | 44 | 172 | 101 | 5 | 7 | 15 | 101 | 737 | 189 |
| `CP31.260618.005` → `CP31.260623.005` | 589 | 35 | 172 | 97 | 4 | 3 | 1 | 30 | 117 | 26 |
| `CP31.260623.005` → `CP31.260623.010` | 261 | 25 | 80 | 36 | 6 | 3 | 0 | 6 | 36 | 18 |
| `CP31.260623.010` → `CP3A.260905.010` | 302 | 20 | 85 | 45 | 7 | 3 | 1 | 5 | 29 | 24 |
| `CP41.260701.005` → `CP41.260731.005.B1` | 589 | 43 | 172 | 99 | 10 | 19 | 5 | 167 | 1,311 | 56 |
| `CP41.260731.005.B1` → `CP41.260831.007.A3` | 589 | 44 | 172 | 99 | 10 | 12 | 7 | 130 | 1,027 | 54 |
| `DP11.260918.006` → `DP11.261030.003` | 606 | 43 | 144 | 81 | 10 | 14 | 11 | 172 | 1,385 | 90 |

## Cross-track comparisons

These rows compare selected branch milestones. Parallel beta tracks overlap, so the direction identifies the comparison baseline and target.

| From → to | Added | Removed | File bytes | Link targets | Type | Mode/owner | Paths with changes |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `CP21.260330.008` → `CP31.260423.012.A1` | 14 | 16 | 2,337 | 0 | 0 | 0 | 2,367 |
| `CP2A.260605.012` → `CP3A.260905.010` | 90 | 63 | 2,455 | 0 | 0 | 0 | 2,608 |
| `CP3A.260905.010` → `CP41.260701.005` | 7 | 21 | 2,400 | 0 | 0 | 0 | 2,428 |
| `CP3A.260905.010` → `CP41.260831.007.A3` | 134 | 69 | 2,370 | 0 | 1 | 0 | 2,574 |
| `CP41.260831.007.A3` → `DP11.260918.006` | 32 | 122 | 2,255 | 0 | 0 | 0 | 2,409 |
| `CP41.260831.007.A3` → `DP11.261030.003` | 119 | 158 | 2,417 | 0 | 0 | 0 | 2,694 |
| `CP2A.260605.012` → `DP11.261030.003` | 312 | 259 | 2,382 | 0 | 1 | 0 | 2,954 |

## Selected structural changes

- **Base Beta 1 → Beta 3.** Added `com.android.npumanager.capex` and its service preoptimization files, `ContactsPicker.apk`, `TelecomShim.apk`, and the `aisealhostservice` executable and init file. Removed standalone `framework-network-security-config.jar`, `framework-telecom.jar`, `MediaProviderLegacy.apk`, `Telecom.apk`, and `TelecomUi.apk`. The image gained telecom service preoptimization files for `com.android.telephonycore`. Several SELinux compatibility mappings for 29.0 and 30.0 disappeared. Both library directories switched the packaged protobuf filename suffix from 6.33.2 to 6.33.5.
- **Base Beta 3 → Beta 4.** Added `aoad`, `hidservice`, `bpftool`, and `mediacodeclist_generator`. The Beta 3 image changed `/system/bin/wificond` from a regular file to a symlink and dropped `/system/etc/init/wificond.rc`. Beta 4 added the executable and init file under `/system/system_ext/`. The base stable image retained Beta 4's path set and changed 564 regular-file digests.
- **QPR1 Beta 2 → Beta 4.** Beta 3 replaced the packaged `android.frameworks.stats-V2-ndk.so` with `android.frameworks.stats-V3-ndk.so` in `lib` and `lib64`. Beta 4 added `tracing_descriptors.gz` and `android.server.display.flags-aconfig-cc.so`.
- **QPR1 Beta 4 → Beta 6.** The sample moved to full SDK 37.1 and removed the preview SDK feature XML. It added `omapi` with init and VINTF files, `zygote_cli`, `zygote_next64.rc`, and `preinstalled-packages-merged-system.xml`. It dropped `zygote_next.rc` and several individual preinstall XML files. The built-in CA certificate file set also changed.
- **QPR1 Beta 6 → Beta 8 → stable.** Beta 7 added native AOA libraries. Beta 8 retained Beta 7's path set. The stable image added `VirtualDeviceManager.apk`, `com.android.extensions.computercontrol.jar`, and its system extension permissions XML relative to Beta 8.
- **QPR2 Beta 1 → Beta 3.** Added `cpustatsd` and its init and BPF files, `profcollectd` and `profcollectctl`, an OpenXR runtime configuration, and BoringSSL self-tests. Both library directories moved to newer packaged NDK interface filenames for audio, graphics composer, and TV input.
- **QPR2 Beta 3 → Beta 6.1.** Added `memory-limiter-config.xml` and five `kernelstats` BPF files. Removed standalone `appwidget.jar` and the preview SDK feature XML. The 64-bit KeyMint and Keystore 2 NDK library filenames moved from V5 to V6 and from V6 to V7, respectively.
- **QPR2 Beta 6.1 → QPR3 Beta 1 cross-track comparison.** The QPR3 sample adds `framework-files-platform.jar`, `dexpreopt_config.json`, and the default-permission XML for the Virtualization Terminal.
- **QPR3 Beta 1 → Beta 4.** Added standalone `service-telephony.jar` with a matching permissions XML, `SystemUI-ScreenRecordingMarkup.apk`, `VirtualDeviceManager.apk`, audio routing rules, BPF system statistics files, and USB authentication configuration. Renamed the packaged CPU stats init path from `cpustats.rc` to `cpustatsd.rc` and removed the OpenXR runtime configuration and both BoringSSL self-test binaries. Both library directories moved from protobuf filename suffix 7.35.1 to 7.36.2.

## Framework and ADB artifact identities

Entries below use 12-character SHA‑256 prefixes. All ADB containers shown are `com.android.adbd.capex`, with `ApexManifest.name` equal to `com.android.adbd`. The integer in the last column is `ApexManifest.version` read from the outer container's `apex_manifest.pb`. A container digest can change while the manifest version stays fixed. [APEX file format](https://source.android.com/docs/core/ota/apex) · [AOSP manifest schema](https://android.googlesource.com/platform/system/apex/+/refs/heads/main/proto/apex_manifest.proto)

| Build ID | `framework.jar` | `services.jar` | `framework-res.apk` | `SettingsProvider.apk` | `com.android.adbd.capex` | ADB APEX version |
| --- | --- | --- | --- | --- | --- | --- |
| `CP21.260116.011.B1` | `c2dea2fd1fe0` | `610a8726fa21` | `91abd52297a4` | `09d3669e19d3` | `a5583bb149fa` | 370399999 |
| `CP21.260306.017` | `a3e6c68e4511` | `78ddb810c646` | `9b85c8bf04d3` | `71bd3d9f999e` | `37123e68f5e9` | 370399999 |
| `CP21.260330.008` | `093096ef9f9a` | `6892dec0acac` | `225dcc9454ba` | `4f41890767ce` | `b76fc2e20518` | 370399999 |
| `CP2A.260605.012` | `55f235f3b4e8` | `b7bb8885f3c9` | `297b1f5c3c4e` | `2d9dbdc746db` | `f02260c0d975` | 370399999 |
| `CP31.260423.012.A1` | `d41c0d68465f` | `eceadacb020d` | `9f1fddfdd80a` | `a28bc43a5308` | `cddd4a07a2cd` | 371899999 |
| `CP31.260508.005` | `6fc55417ab51` | `3d73c1564a97` | `3d30a09873e2` | `75c2ab0c996a` | `d10f509d917f` | 371899999 |
| `CP31.260522.006` | `1452cc27e031` | `3efc823906ca` | `6e8827749657` | `b3af8143f0cc` | `9688657cb779` | 371899999 |
| `CP31.260618.005` | `951cae28da14` | `28e655cdc304` | `ab90ef56294c` | `d68dc6512ee5` | `a7c34f7a73e7` | 371899999 |
| `CP31.260623.005` | `6474e6dacfdc` | `86485fab5e07` | `2389bc0c1b84` | `ed06e59724c8` | `e85b3b7d68c1` | 371899999 |
| `CP31.260623.010` | `6cb2acc07c44` | `373b7cd77fda` | `883028f652cd` | `ed06e59724c8` | `b659f9417066` | 371899999 |
| `CP3A.260905.010` | `bc8a175a95dc` | `bc5d11f4f09e` | `07e8eef9f777` | `bc35c693c4fb` | `b659f9417066` | 371899999 |
| `CP41.260701.005` | `c17f7773c411` | `49d031ba0799` | `6e710a4570f1` | `a188b6c7a0a3` | `0d604f903d05` | 372899999 |
| `CP41.260731.005.B1` | `4f3801f7d2fb` | `48f9b5a4faf6` | `eb86ec2779b5` | `6702316a3121` | `625c9f0be9ca` | 372899999 |
| `CP41.260831.007.A3` | `3e0f1bf7589e` | `8b9e2e6801f5` | `39eccae4db71` | `fe049c283d95` | `3d5b8c86b9ea` | 372899999 |
| `DP11.260918.006` | `9f7f0b8a4266` | `b2ecb4f4eedd` | `d35a40a8555a` | `35c6706fd9f3` | `2038bb950124` | 374399999 |
| `DP11.261030.003` | `5baa9d61fb89` | `060b7416c209` | `af712962b8a0` | `6b65a850fedf` | `ff8191e44d70` | 374399999 |

The ADB APEX version is constant within each sampled track. QPR1 Beta 8 and QPR1 stable have byte-identical `com.android.adbd.capex` files. QPR1 Beta 7 and Beta 8 have byte-identical `SettingsProvider.apk` files. `framework.jar` and `services.jar` each have a distinct digest in all 16 images.

## Hidden API compatibility

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

## ADB settings and Discussion #19

## Discussion claims

| Claim | Evidence and scope |
| --- | --- |
| Pre-unlock wireless startup improved in the two Shizuku forks. | The opening post attributes the work to BombPerry and Dimlos and reports results on a small number of devices. It gives no build IDs, timings, or baseline. [Opening post](https://github.com/kerneldroid/Nightzuku/discussions/19) |
| Nightzuku added a `connectedDevice` Direct Boot service in `d03716d3`. | The published commit adds `DirectBootStartService` and invokes it from `LOCKED_BOOT_COMPLETED`. It handles fixed TCP and wireless ADB, prepares the ADB key while waiting for network readiness, watches for setting resets on API 30–36, and coordinates with `BOOT_COMPLETED`. The boot tests exercise service dispatch and handoff. [Commit](https://github.com/kerneldroid/Nightzuku/commit/d03716d3f6f1b0666efa297e4a759261a7d04440) |
| Discovery and storage changed in the same commit. | The patch adds `AdbPortScanner` and device-encrypted `AdbPortHistory`. Boot and manual discovery use the new endpoint discovery alongside mDNS. `ShizukuApplication` and `NightDogClient` add unlock-state guards around initialization and binder handling. These are app changes. [Commit](https://github.com/kerneldroid/Nightzuku/commit/d03716d3f6f1b0666efa297e4a759261a7d04440) |
| Android 17 redacts both ADB settings to `0` for regular apps. | The per-image table below distinguishes the USB and wireless keys. A displayed `0` also needs transport and caller context because `adb_enabled` represents USB debugging and can legitimately be `0` while wireless `adbd` is active. [Maintainer comment](https://github.com/kerneldroid/Nightzuku/discussions/19#discussioncomment-19923613), [Settings.Global API reference](https://developer.android.com/reference/android/provider/Settings.Global#ADB_ENABLED) |
| Maintainer claim: `MANAGE_DEBUGGING` replaced `WRITE_SECURE_SETTINGS` for `Settings.Global` writes. | This claim is not supported by the 16 sampled provider implementations: each checks `WRITE_SECURE_SETTINGS`; the separate privileged `AdbService` Binder path checks `MANAGE_DEBUGGING`. Dimlos also reports a Pixel 10 Pro write succeeding with `WRITE_SECURE_SETTINGS` alone. These observations are limited to the sampled images and that device. [Maintainer comment](https://github.com/kerneldroid/Nightzuku/discussions/19#discussioncomment-19923613), [Pixel report](https://github.com/kerneldroid/Nightzuku/discussions/19#discussioncomment-19941515) |
| ADB Wi-Fi 2.0 re-enables wireless debugging on a trusted network. | Google documents automatic re-enable on a user-approved network. Image evidence for network monitoring and the trusted-network handler helps identify the shipped path. Reaching that path before first unlock depends on the device, network state, saved trust, and platform configuration. [ADB Wi-Fi 2.0 announcement](https://developer.android.com/blog/posts/introducing-fast-and-reliable-wireless-debugging-with-android-debug-bridge-adb-wi-fi-2-0) |
| Catching a rejected `startForegroundService()` call protects the boot receiver. | The published commit catches service-start failures and supplies a fallback path. Android documents boot broadcast exemptions, foreground service type rules, and `ForegroundServiceStartNotAllowedException`. The discussion supplies no affected OEM fingerprint or exception trace. [Commit](https://github.com/kerneldroid/Nightzuku/commit/d03716d3f6f1b0666efa297e4a759261a7d04440), [Foreground service restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start) |
| A Pixel 10 Pro running Android 17 allowed `adb_wifi_enabled` reads and writes with `WRITE_SECURE_SETTINGS` alone. | Dimlos identifies the device build as `google/blazer/blazer:17/CP3A.261005.005/16271449:user/release-keys` (security patch 2026-10-05, stable Android 17, not a beta or GSI). The app reportedly did not hold `MANAGE_DEBUGGING`. This is one-device runtime evidence. [Follow-up with fingerprint](https://github.com/kerneldroid/Nightzuku/discussions/19#discussioncomment-19941515) |
| The Pixel returned `0` for `adb_enabled`; a later scan-order follow-up reports first-attempt startup in 10 of 11 runs using the current order and one locked-boot run that missed all five attempts. | An earlier report recorded a port 0.3–0.8 seconds after the wireless write and 7 of 7 first-attempt locked boots. The follow-up describes 25 locked boots with the grant, but does not reconcile that total with the 11 runs on the current scan order. These are self-reported runtime results for the identified stable Pixel build, not GSI results; `adb_enabled=0` alone does not distinguish redaction from USB being off. [Initial report](https://github.com/kerneldroid/Nightzuku/discussions/19#discussioncomment-19927161), [follow-up](https://github.com/kerneldroid/Nightzuku/discussions/19#discussioncomment-19941515) |

## Nightzuku code snapshots

Revision `5668e79` was used to compare the app's boot path with the implementation in `d03716d3`. In `5668e79`, `BootCompleteReceiver` registers for both boot broadcasts. For `LaunchMethod.ADB`, the receiver waits for `BOOT_COMPLETED` and submits a `JobScheduler` job. It checks the boot count and the existing Shizuku binder to avoid repeating work. An ADB boot launch in this revision is therefore scheduled after first unlock. [Direct Boot guide](https://developer.android.com/privacy-and-security/direct-boot)

The job handles fixed TCP separately. Its wireless flow on SDK 37 defers to discovery through `AdbSettings.prepareWirelessAdbForBoot()` and checks local network permission through `AdbMdns`. The app targets SDK 37. A missing permission can stop the wireless flow before discovery, and an available listener can still fail at pairing, authentication, starter execution, or binder delivery. The test record should identify the first failed stage. [Boot job](https://github.com/kerneldroid/Nightzuku/blob/5668e79acc841c5446e3deaa1c57f2b65e67c5bf/manager/src/main/java/moe/shizuku/manager/receiver/AdbBootStartScheduler.kt), [AdbSettings](https://github.com/kerneldroid/Nightzuku/blob/5668e79acc841c5446e3deaa1c57f2b65e67c5bf/manager/src/main/java/moe/shizuku/manager/adb/AdbSettings.kt), [AdbMdns](https://github.com/kerneldroid/Nightzuku/blob/5668e79acc841c5446e3deaa1c57f2b65e67c5bf/manager/src/main/java/moe/shizuku/manager/adb/AdbMdns.kt), [Local network permission](https://developer.android.com/privacy-and-security/local-network-permission)

The app branches on `Build.VERSION.SDK_INT`. The Beta 1 GSI reports SDK 36.1 and selects the older branch. Beta 3 reports SDK 37.0 and selects the discovery-first branch even though its ADB keys have no redaction metadata. The same branch covers the other SDK 37 images regardless of their compiled redaction policy. [AdbSettings](https://github.com/kerneldroid/Nightzuku/blob/5668e79acc841c5446e3deaa1c57f2b65e67c5bf/manager/src/main/java/moe/shizuku/manager/adb/AdbSettings.kt)

## Image comparison

The comparison follows `Settings.Global` field metadata and the corresponding framework or provider read path, then checks the provider mutation and ADB service paths. The exact APK or JAR matters because framework metadata alone cannot establish a key's effective permission or runtime value.

| Build ID | Release | Full SDK | Fields declaring redaction to `0` | Compiled redaction for regular app reads |
| --- | --- | --- | --- | --- |
| `CP21.260116.011.B1` | Android 17 Beta 1 | 36.1 | None | No replacement for either ADB key |
| `CP21.260306.017` | Android 17 Beta 3 | 37.0 | None | No replacement for either ADB key |
| `CP21.260330.008` | Android 17 Beta 4 | 37.0 | USB | Framework and provider switches off |
| `CP2A.260605.012` | Android 17 stable | 37.0 | USB | Framework and provider switches off |
| `CP31.260423.012.A1` | QPR1 Beta 2 | 37.0 | USB | Provider switch off |
| `CP31.260508.005` | QPR1 Beta 3 | 37.0 | USB | Provider switch off |
| `CP31.260522.006` | QPR1 Beta 4 | 37.0 | USB | Provider switch off |
| `CP31.260618.005` | QPR1 Beta 6 | 37.1 | USB | Provider returns the stored value |
| `CP31.260623.005` | QPR1 Beta 7 | 37.1 | USB | Provider returns the stored value |
| `CP31.260623.010` | QPR1 Beta 8 | 37.1 | USB | Provider returns the stored value |
| `CP3A.260905.010` | QPR1 stable | 37.1 | USB | USB replacement active for a stored row |
| `CP41.260701.005` | QPR2 Beta 1 | 37.1 | USB | Provider returns the stored value |
| `CP41.260731.005.B1` | QPR2 Beta 3 | 37.1 | USB | Provider returns the stored value |
| `CP41.260831.007.A3` | QPR2 Beta 6.1 | 37.2 | USB and Wi-Fi | Provider returns the stored value |
| `DP11.260918.006` | QPR3 Beta 1 | 37.2 | USB and Wi-Fi | Both replacements active for stored rows |
| `DP11.261030.003` | QPR3 Beta 4 | 37.2 | USB and Wi-Fi | Both replacements active on named reads, including absent rows |

- **Read semantics.** USB is `adb_enabled` and Wi-Fi is `adb_wifi_enabled`. `adb_allowed_connection_time` has no redacted value in the 16 images. The active provider path checks the caller and returns the configured `0` for covered keys. QPR3 Beta 4 passes the requested name into `SettingsProvider.getEffectiveValue` and can therefore apply a replacement on a named read with no stored row. It also handles SDK sandbox callers in its caller check. The earlier active implementation returns null for an absent row. A numeric getter can independently return its supplied default when it receives null.
- **Write semantics.** All 16 `SettingsProvider.apk` builds check `WRITE_SECURE_SETTINGS` in the ordinary single-key global mutation path. That path does not test `MANAGE_DEBUGGING`. All 16 `services.jar` builds check `MANAGE_DEBUGGING` on privileged `AdbService` Binder calls such as `getAdbWirelessPort()` and pairing management. All 16 `framework-res.apk` manifests declare `MANAGE_DEBUGGING` as `signature|privileged`. Provider permission, stored value, read response, and daemon response are separate observations.
- **Trusted Wi-Fi.** All 16 include `com.android.adbd.capex`, `AdbWifiNetworkMonitor`, and trusted-network checks in `AdbDebuggingManager$AdbDebuggingHandler`. These artifacts support a platform path for managing trusted connections. A GSI system image comparison supplies no device-specific network state, effective vendor policy, or timestamp for first-unlock availability. [ADB Wi-Fi 2.0 announcement](https://developer.android.com/blog/posts/introducing-fast-and-reliable-wireless-debugging-with-android-debug-bridge-adb-wi-fi-2-0)
- **Foreground services.** All 16 declare `FOREGROUND_SERVICE_CONNECTED_DEVICE` and retain the boot allowlist check in `ActiveServices.shouldAllowBootCompletedStart`. Earlier samples throw on the corresponding rejection inline. QPR3 Beta 4 moves the rejection into `enforceBootCompletedStartAllowed`. Google documents prerequisites for the `connectedDevice` service type. Whether the published Nightzuku service starts, raises an exception, or hands off to post-unlock startup remains specific to the app, device, and boot state. [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device)

The QPR1 stable GSI has the static split closest to the Pixel report. It can mask a stored USB setting while returning the wireless setting through the ordinary provider read. The reported Pixel fingerprint identifies a stable Android 17 build, but does not make GSI and Pixel behavior interchangeable: Pixel policy, vendor partitions, app build, and logs were not supplied. On the two examined QPR3 images, a regular app that treats a `0` result for `adb_wifi_enabled` as proof of a disabled daemon can misclassify an active wireless listener. Verifying the reported discovery results requires device traces.

## Runtime evidence still needed

- Record the device, full build fingerprint, security patch, Shizuku or fork commit, caller identity, target SDK, granted permissions, and paired and trusted network state. Treat first unlock and a relock after first unlock as different states. [Direct Boot guide](https://developer.android.com/privacy-and-security/direct-boot)
- Observe the two settings independently and distinguish a returned value, a caller-supplied fallback, and an exception. Record USB debugging state separately from wireless listener state. Preserve the permission baseline for any comparison with the Pixel report. [Settings.Global API reference](https://developer.android.com/reference/android/provider/Settings.Global)
- Time the app boot callback, Wi-Fi readiness, listener and mDNS availability, authenticated connection, starter result, binder availability, and first unlock. Include repeated cold boots and the relevant service-start or storage-access exceptions. This would test the timing, first-attempt rate, and OEM behavior reported for the stable Pixel fingerprint above. [Pixel follow-up](https://github.com/kerneldroid/Nightzuku/discussions/19#discussioncomment-19941515), [Foreground service restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)

## Nightzuku boot behavior: separate code revisions

The reports describe two code snapshots, not contradictory behavior in one revision:

- **Revision `5668e79`:** `BootCompleteReceiver` waits for `BOOT_COMPLETED` and schedules the ADB `JobScheduler` path. That is post-unlock scheduling. On API 37, its wireless path defers to mDNS discovery and checks `ACCESS_LOCAL_NETWORK`.
- **Commit `d03716d3`:** the discussed change adds a `connectedDevice` Direct Boot service launched from `LOCKED_BOOT_COMPLETED`, with coordination and fallback behavior around the regular boot path. This is a code change, not proof that a particular GSI/device starts it successfully.

The per-build image analysis can establish framework/provider code and permissions. It cannot establish the app's runtime ordering, OEM policy, first-unlock timing, Wi-Fi state, or whether a connection reaches Nightzuku's Binder. The runtime checklist above should be used for device reports.

### Boot flow in revision `5668e79` (post-unlock)

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
  - See [loopback restriction memo](../adb-loopback-restriction-memo.md)
    for forward-looking tracking of the potential `adbd` localhost (`127.0.0.1`)
    restriction in future QPR releases.

## References

- [Android 17 release notes](https://developer.android.com/about/versions/17/release-notes)
- [Android 17 QPR1 release notes](https://developer.android.com/about/versions/17/qpr1/release-notes)
- [Android 17 QPR2 release notes](https://developer.android.com/about/versions/17/qpr2/release-notes)
- [Android 17 QPR3 release notes](https://developer.android.com/about/versions/17/qpr3/release-notes)
- [Google GSI releases](https://developer.android.com/topic/generic-system-image/releases)
- [Nightzuku Discussion #19](https://github.com/kerneldroid/Nightzuku/discussions/19)
- [Direct Boot guide](https://developer.android.com/privacy-and-security/direct-boot)

## Evidence to capture for a beta-specific failure

Record the full build fingerprint, security patch, device model, app version and target SDK, Android user/profile, local-network permission state, and first-unlock state. Record whether the failure is in service scheduling, discovery, pairing, connection, the server process, or manager Binder delivery. For a hidden API error, include the exception and full descriptor. For a wireless failure, record USB and Wi-Fi setting reads separately, whether `adbd` was independently observed, and whether a trusted network and prior authorization were present. Keep a release-matched successful result or a control run when possible.

## Evidence collection

Each complete archive was verified against its published SHA-256 and the Build ID in its packaged `build.prop`. The raw EXT4 `system.img` was examined read-only, one image at a time. The file inventory records file hashes, modes, ownership, and symlink targets. Targeted DEX disassembly covered `framework.jar`, `services.jar`, `SettingsProvider.apk`, and the Settings wireless debugging UI. Permission declarations came from the binary `framework-res.apk` manifest. The inner ADB APEX was read separately. The analysis used a single disassembly worker per process with a capped Java heap.

These AOSP GSIs carry the generic system partition. Pixel and OEM behavior also depends on other partitions, later module updates, policy configuration, and user data. There was no corresponding booted ARM64 environment in this audit. The Pixel report identifies stable build `CP3A.261005.005` and remains independent runtime evidence, not a result from any beta GSI in this audit. [Google GSI archive](https://developer.android.com/topic/generic-system-image/releases) · [DEX format](https://source.android.com/docs/core/runtime/dex-format)

## GSI boot attempt status

The 16 ARM64 images in the static audit were not launched individually. The Android Emulator launcher rejects ARM64 AVDs on x86_64 hosts for current Android versions; this host also has no `/dev/kvm` or attached Android device. Their shared architecture/runtime mismatch is the skip reason.

To try a real GSI on the available x86_64 emulator, the official QPR3 Beta 4 AOSP x86_64 archive was downloaded and checked. The ZIP passed `unzip -t`; its SHA-256 matched Google's published value. This additional image was not included in the 16-image static comparison.

| Attempt | Observation | Result |
| --- | --- | --- |
| Emulator 37.2.12 with `-system` set to the GSI `system.img` | ADB became available, but the guest reported `ro.build.id=CP41.260831.015`, the stock SDK image build, rather than GSI build `DP11.261030.003`. | The GSI was not loaded by this invocation. |
| Emulator 37.2.12 with a custom `sysdir` pointing at the verified GSI `system.img` and build properties, plus the SDK's x86_64 kernel, ramdisk, and vendor image | The emulator process ran for about four minutes; ADB stayed `offline` and no guest build ID could be read. | No successful GSI boot was verified; the process was stopped. |
| Fastboot fallback on the disposable AVD | After `adb reboot bootloader`, `fastboot devices` listed no device. | Flashing through emulator fastboot was unavailable. |

The tested x86_64 archive was [`aosp_x86_64-exp-DP11.261030.003-16847878-0d205148.zip`](https://dl.google.com/developers/android/cinnamonbun/images/gsi/aosp_x86_64-exp-DP11.261030.003-16847878-0d205148.zip), SHA-256 `0d2051481ddb4d63b26d9d00c55b0a3bf1ba20884017938f38593427695c8c04`. [Google QPR3 Beta 4 GSI release](https://developer.android.com/about/versions/17/qpr3/gsi-release-notes). The test AVD and its user data were disposable; no physical device was modified.

## Analysis limits

These images contain generic system partitions. Pixel and OEM behavior also depends on boot/vendor partitions, policy, Mainline updates, hardware, and user data. The Pixel 10 Pro fingerprint and security patch are reported in Discussion #19; app version and runtime logs were not supplied. All compatibility conclusions here are from static code/image inspection unless explicitly identified as a published release-note claim or a discussion report.
