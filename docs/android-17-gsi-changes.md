# Android 17 GSI filesystem changes

This audit covers 16 Google AOSP ARM64 images across Android 17 and QPR1–QPR3. It compares shipped files, package placement, and selected artifact identities. [Shizuku findings](android-17-shizuku-findings.md) · [Release history](android-17-release-history.md) · [Discussion #19 review](android-17-discussion-19.md) · [Google GSI releases](https://developer.android.com/topic/generic-system-image/releases)

## Sampled builds

Build IDs, SDK values, and patch levels come from the archives' `build.prop` files. Entry counts cover the archived `system.img` tree, including files, directories, and symlinks. Each complete ZIP was verified against its published SHA‑256 before inspection. The manifest records the exact filenames, full archive digests, download URLs, and source listings. [Build manifest](android-17-gsi-builds.tsv)

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

## Path-level data

[Download the path-level TSV](android-17-gsi-path-deltas.tsv). Its 37,979 rows cover 19 comparisons. Each row records the build IDs, comparison type, component, path, and applicable changes, followed by the before and after entry type, size, full SHA‑256, octal mode, UID, GID, and symlink target. Empty fields indicate that the corresponding entry or property is absent.

The inventory covers the archived system filesystem. Device kernels, vendor partitions, over-the-air Mainline updates, and runtime state require separate evidence. File hashes identify byte changes, which can arise from code, resources, compilation output, signatures, or package metadata. Filesystem extended attributes and hardlink topology are outside this comparison. [Android GSI scope](https://developer.android.com/topic/generic-system-image/)
