# Android 17 (API 37) Compatibility Comparison

This document provides a technical comparison between the original Shizuku release (v13.6.0) and our modernized fork (v13.6.0.r39+) when running on Android 17 (Cinnamon Bun / API 37) Canary.

## The Core Issue

In the May 2026 update for Android 17, Google introduced significant changes to hidden APIs to support Virtual Devices and improve security. Specifically, method signatures within `IPackageManager` and `IPermissionManager` were modified to include a `deviceId` parameter for multi-device awareness.

### Affected APIs
- `IPermissionManager.grantRuntimePermission`
- `IPermissionManager.revokeRuntimePermission`
- `IPermissionManager.checkPermission`
- `IPackageManager.getInstalledPackages`
- `IPackageManager.getPackageInfo`
- `IPackageManager.getApplicationInfo`

Because Nightzuku relies on these hidden APIs to operate, the legacy implementation fails with `NoSuchMethodError` when running on API 37+.

## Original Shizuku (v13.6.0)

When running the original Shizuku on Android 17, the server process initializes but encounters critical failures during permission evaluation or package listing.

### Behavior
- Permission grants/revocations fail silently or crash the server.
- Package listing returns empty results or triggers crashes in client apps.
- `NoSuchMethodError` is frequently logged in Logcat when interacting with system services.

## Modernized Fork (v13.6.0.r39+)

Our fork implements a high-performance, dynamic reflection fallback mechanism via `Android17Compat.java`.

### Technical Implementation
- **Dynamic Method Resolution:** Instead of hardcoding signatures, `Android17Compat` scans available methods at runtime. It identifies if the target method expects the new `deviceId` parameter and injects `Context.DEVICE_ID_DEFAULT` (0) accordingly.
- **Robustness:** The fallback handles variations in parameter count (e.g., `revokeRuntimePermission` with 4 or 5 arguments) and ensures that `userId` is always mapped correctly regardless of the new signature structure.
- **Caching Layer:** To eliminate reflection overhead, resolved `Method` objects and system service proxies (`sPackageManager`, `sPermissionManager`) are memoized after the first successful resolution. This ensures that subsequent calls perform at near-native speeds.
- **Service-Wide Integration:** `ShizukuService` has been refactored to use `Android17Compat` for all critical system API calls, ensuring stability across both legacy and modern Android versions.

### Logcat Evidence
```text
# Fallback successfully intercepts API mismatch and resolves signatures:
D ShizukuAndroid17Compat: Method grantRuntimePermission(String, String, int, int) resolved and cached.
D ShizukuAndroid17Compat: Method getPackageInfo(String, long, int, int) resolved and cached.
```

## Conclusion

The legacy Shizuku implementation is fundamentally broken on Android 17 due to the Virtual Device API shift. Our modernized `Android17Compat` layer provides a production-grade solution that restores full functionality with minimal performance impact, guaranteeing that Nightzuku remains the standard for elevated privilege access on the latest Android versions.
