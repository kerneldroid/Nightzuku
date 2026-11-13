# Wear OS Compatibility (Android 15-17 / Wear OS 5-7)

This document details the Wear OS compatibility enhancements introduced in the Nightzuku modern fork.

## Supported Versions
* Wear OS 5.1 (Android 15)
* Wear OS 6 (Android 16)
* Wear OS 6.1 (Android 16 / API 36.1)
* Wear OS 7 (Android 17 / API 37) - **Full Support**

## Backward Compatibility
Nightzuku maintains full backward compatibility with older Wear OS versions (down to API 25).
* **Dynamic Checks:** the server resolves the hidden package and permission APIs through `Android17Compat`, which tries the signatures the running platform actually exposes and caches the one that worked, so the platform's multi-device aware permission methods are used only where they exist.
* **Stable UI:** The Compose Material 3 UI degrades gracefully on older devices, ensuring a functional experience even without latest platform features like advanced Monet transitions.


## Changes Implemented

### 1. Watch Form Factor Installation
We have added the `android.hardware.type.watch` hardware feature flag (marked as `required="false"`) to `AndroidManifest.xml`.
This ensures that the Android package manager and Google Play correctly identify the APK as installable on Wear OS smartwatch devices.

### 2. Native Wear Compose Material 3 UI
The entire user interface for Wear OS has been rewritten using the official `androidx.wear.compose:compose-material3` library.
* **TransformingLazyColumn:** All lists (Authorized Apps, ADB Modules, Settings) use the watch-specific components that automatically adapt to circular displays, scaling and curving elements as they scroll off the edges.
* **Edge-to-Edge Components:** We use native Wear OS Material 3 buttons, cards, and switches that respect the circular screen geometry.
* **Dynamic Color (Monet):** Full support for dynamic color schemes (Monet) on Wear OS 4+ devices, automatically matching the user's watch face or system theme.
* **Optimized Layouts:** The UI natively adapts to small, high-density circular screens with proper paddings and touch targets.

### 3. Native Wear Compose Material 3 Dialogs
Legacy dialogs have been replaced with native `androidx.wear.compose.material3.AlertDialog`.
* **Visual Consistency:** Dialogs feature a true black background for visual consistency.
* **Scroll Support:** Dialog content is scrollable, ensuring accessibility on round displays.
* **Platform UI:** Uses native Wear OS Material 3 buttons and layouts.

### 4. WebView on WearOS
ADB Modules (including their WebUI) are not offered on WearOS. The mobile module UI, WebView host and engine remain available on phones; no Wear-specific WebView fallback is shipped.
