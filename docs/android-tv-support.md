# Android TV Architecture

Technical specification of the Android TV UI in `:manager`.

## UI Stack & Form-Factor Routing

`EnvironmentUtils.isTV(context)` routes Activities to dedicated TV composables built on `androidx.tv.material3` (`TvShizukuTheme`) rather than scaling phone layouts.

| Component | Phone Implementation | Android TV Implementation |
| :--- | :--- | :--- |
| Home screen | `PhoneHomeScreen` (`HomeCards.kt`) | `TVHomeScreen` (`TVHomeScreen.kt`) |
| Authorized apps | `ApplicationManagementActivity` | `TvApplicationManagementScreen.kt` |
| Settings | `SettingsActivity` (segmented categories) | `TvSettingsScreen.kt` (two-pane D-pad layout) |
| Lab Features | `LabFeaturesActivity` | `TvLabMenuScreen.kt` |
| ADB Pairing & Starter | `PairingGuideScreen`, `StarterActivity` | `TvAdbPairingTutorialScreen.kt`, `TvStarterScreen.kt` |
| Shell Tutorial | `ShellGuideScreen.kt` | `TvShellTutorialScreen.kt` |
| ADB Modules & Catalog | `ModulesActivity`, `CatalogScreen` | Excluded (phone-only) |

## Theming & Interaction

| Feature | Specification |
| :--- | :--- |
| **Dynamic Color (Monet)** | Enabled on Android 12+ (API 31+) via `dynamicDarkColorScheme` / `dynamicLightColorScheme`, toggleable in Settings |
| **Black Night Theme** | Pure black (`#000000`) background surface override for OLED / dark-room viewing |
| **D-pad Navigation** | Explicit focus traversal across `TvSurface` controls with container highlight states and standard Back-key routing |
| **Dialogs** | Center-aligned `AlertDialog` themed with `TvMaterialTheme` and D-pad scrollable content |

## Package Identity & Verification

- Package ID: `kerneldroid.nightzuku`
- Emulator verification target: Android TV 16 (`screenshots/tv/`).
