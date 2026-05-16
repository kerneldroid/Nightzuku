# Wear OS Compatibility (Android 15-17 / Wear OS 5-7)

This document details the Wear OS compatibility enhancements introduced in the Shizuku modern fork.

## Supported Versions
* Wear OS 5.1 (Android 15)
* Wear OS 6 (Android 16)
* Wear OS 7 (Android 17 / API 36/37)

## Changes Implemented

### 1. Watch Form Factor Installation
We have added the `android.hardware.type.watch` hardware feature flag (marked as `required="false"`) to `AndroidManifest.xml`.
This ensures that the Android package manager and Google Play correctly identify the APK as installable on Wear OS smartwatch devices.

### 2. Native Wear Compose Material 3 UI
The entire user interface for Wear OS has been rewritten using the official `androidx.wear.compose:compose-material3` library.
* **TransformingLazyColumn:** All lists (Authorized Apps, ADB Modules, Settings) use the часовые-специфичные компоненты that automatically adapt to circular displays, scaling and curving elements as they scroll off the edges.
* **Edge-to-Edge Components:** We use native Wear OS Material 3 buttons, cards, and switches that respect the circular screen geometry.
* **Dynamic Color (Monet):** Full support for dynamic color schemes (Monet) on Wear OS 4+ devices, automatically matching the user's watch face or system theme.
* **Optimized Layouts:** Removed the previous 0.8x scaling hack. The UI now natively adapts to small, high-density circular screens with proper paddings and touch targets.

## Verification
* The Shizuku server successfully binds and operates on Wear OS 7 (API 36+) emulators and real devices.
* Application UI provides a first-class native experience on 1.4-inch and 1.5-inch round displays.
* All core functionalities, including ADB bindings and root execution, are functional.
