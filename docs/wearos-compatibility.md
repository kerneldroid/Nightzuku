# Wear OS Compatibility (Android 15-17 / Wear OS 5-7)

This document details the Wear OS compatibility enhancements introduced in the Nightzuku modern fork.

## Supported Versions
* Wear OS 5.1 (Android 15)
* Wear OS 6 (Android 16)
* Wear OS 6.1 (Android 16 / API 36.1)
* Wear OS 7 (Android 17 / API 37) - **Full Support**

## Backward Compatibility
Nightzuku maintains full backward compatibility with older Wear OS versions (down to API 25).
* **Dynamic Checks:** We use `Build.VERSION.SDK_INT` and `Android17Compat` to ensure modern system APIs (like multi-device awareness) are used only when available.
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
* **Optimized Layouts:** Removed the previous 0.8x scaling hack. The UI now natively adapts to small, high-density circular screens with proper paddings and touch targets.

### 3. Native Wear Compose Material 3 Dialogs
Legacy `MaterialAlertDialogBuilder` and XML dialogs have been replaced with native `androidx.wear.compose.material3.AlertDialog`.
* **Visual Consistency:** Dialogs now feature a solid `MaterialTheme.colorScheme.background` (true black) instead of the previous white background issues.
* **Scroll Support:** Dialog content is now scrollable, ensuring long messages or lists are fully accessible on round displays.
* **Platform UI:** Uses native Wear OS Material 3 buttons and layouts.

### 4. WebView Fallback for WearOS
On WearOS devices (especially emulators or specialized builds) that lack a functional WebView provider, Nightzuku now implements a robust fallback.
* **Crash Prevention:** Activity initialization is wrapped in a `try-catch` block to intercept `UnsupportedOperationException` from the system `WebViewFactory`.
* **User Feedback:** Instead of crashing or showing a white screen, a native WearOS dialog informs the user that WebUI is unsupported on their hardware.


## Verification
* The Nightzuku server successfully binds and operates on Wear OS 7 (API 37) and Wear OS 6.1 (API 36.1) emulators and real devices.
* Application UI provides a first-class native experience on 1.4-inch and 1.5-inch round displays across all supported versions.
* All core functionalities, including ADB bindings and root execution, are functional.
