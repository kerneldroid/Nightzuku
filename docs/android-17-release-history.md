# Android 17 release history

Snapshot as of 26 November 2026. This chronology covers the Android 17 base cycle and QPR1–QPR3. The local sample contains 16 complete Google AOSP ARM64 GSIs. Google’s release notes describe platform and Pixel changes. Establishing a change in a particular GSI or device requires evidence from that image or device.

## Release chronology

Beta dates and device build IDs come from Google’s release notes. Dates on stable rows identify the source in parentheses. GSI build IDs come from archive names in `out/android17-gsi/catalog.tsv`. **Local** denotes a complete ZIP under `out/android17-gsi/images/`, **Partial** denotes an incomplete download, and **Catalog** denotes a recorded URL without a complete local copy. The build manifest lists direct URLs, published SHA-256 checksums, and Google’s numeric build IDs for the 16 audited images. [Build manifest](android-17-gsi-builds.tsv)

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
