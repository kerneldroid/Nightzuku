# Wear OS Compatibility Specification

Technical summary of Wear OS support in `:manager` and `:server`.

## Supported Platform Matrix

| Wear OS Version | Android Base | API Level | Status |
| :--- | :--- | :--- | :--- |
| Legacy Wear OS | Android 7.1–14 | API 25–34 | Backward-compatible via `Android17Compat` dynamic dispatch |
| Wear OS 5.1 | Android 15 | API 35 | Supported |
| Wear OS 6 / 6.1 | Android 16 | API 36 / 36.1 | Supported |
| Wear OS 7 | Android 17 | API 37 | Supported (emulator-verified) |

## Implementation Details

| Area | Implementation |
| :--- | :--- |
| **Manifest declaration** | `<uses-feature android:name="android.hardware.type.watch" android:required="false" />` enables standalone APK installation on watch form factors |
| **UI framework** | Native `androidx.wear.compose:compose-material3` (`WearShizukuTheme`, `TransformingLazyColumn`, `SurfaceTransformation`, `WearAlertDialog`) routed via `EnvironmentUtils.isWatch(context)` |
| **Screens** | `WearHomeScreen.kt`, `WearApplicationManagementScreen.kt`, `WearSettingsScreen.kt`, `WearStarterScreen.kt`, `LabFeaturesActivity.kt` (Wear branch) |
| **Theming** | Dynamic color (`WearDynamicColorScheme`) on API 31+ with optional true-black (`#000000`) OLED background |
| **ADB Modules & WebView** | Intentionally excluded on Wear OS; module management and WebView hosting remain phone-only |
