# Android 15–17 Hidden API Compatibility (`Android17Compat` & `UsersCompat`)

This specification documents verified AOSP hidden API signature changes across Android 13–17 and the reflection/dispatch strategy implemented in `Android17Compat`, `UsersCompat`, and `InstalledPackagesCompat`.

## Verification Sources

- `org.robolectric:android-all:17-robolectric-15733970` (`javap` descriptors on `IPermissionManager$Stub` and `IPackageManager$Stub`).
- AOSP `platform/frameworks/base` AIDL sources (`android15-release`, `android16-release`, `android17-release`).
- `dev.rikka.hidden:compat:4.4.0` compiled stubs.

## Verified Platform Signature Matrix

### `android.permission.IPermissionManager` (Android 15, 16, 17)

| Method | Signature | Notes |
| :--- | :--- | :--- |
| `checkPermission` | `int checkPermission(String packageName, String permissionName, String persistentDeviceId, int userId)` | `packageName` precedes `permissionName`; swapping silently returns `PERMISSION_DENIED` |
| `grantRuntimePermission` | `void grantRuntimePermission(String packageName, String permissionName, String persistentDeviceId, int userId)` | Local device ID is `"default:0"` (from `PermissionManager.getPersistentDeviceId(0)`), not `"default"` |
| `revokeRuntimePermission` | `void revokeRuntimePermission(String packageName, String permissionName, String persistentDeviceId, int userId, String reason)` | Same `"default:0"` persistent device ID requirement |
| `checkUidPermission` | `int checkUidPermission(int uid, String permissionName, int deviceId)` | Takes `int deviceId` (`0`) |

Other methods on the same interface (`shouldShowRequestPermissionRationale`, `isPermissionRevokedByPolicy`, `getPermissionRequestState`) continue to take `int deviceId`.

### `android.content.pm.IPackageManager`

| Method | API <= 32 | API 33–36 | API 37 (Android 17) |
| :--- | :--- | :--- | :--- |
| `getPackageInfo` | `(String, int, int)` | `(String, long, int)` | `(String, long, int)` |
| `getApplicationInfo` | `(String, int, int)` | `(String, long, int)` | `(String, long, int)` |
| `getInstalledPackages` | `(int, int) -> ParceledListSlice` | `(long, int) -> ParceledListSlice` | `(long, int) -> PackageInfoList` (return-type descriptor break -> `NoSuchMethodError`) |
| `getInstalledApplications` | `(int, int)` | `(long, int)` | `(long, int)` |

Because `Android17Compat` matches parameter types by exact `Class` equality, `getPackageInfo` and `getApplicationInfo` probe `(String, long, int)` first and fall back to `(String, int, int)` with `flags.toInt()` on API <= 32. Without the `int` fallback, API <= 32 devices fail to resolve the manager package and exit with `MANAGER_APP_NOT_FOUND` (`50`). Only low-32-bit flag values are passed on the fallback path.

### `android.os.IUserManager` (Android 17 QPR1+)

| Method | Pre-Android 17 QPR1 | Android 17 QPR1+ |
| :--- | :--- | :--- |
| `getUsers` | `getUsers(boolean excludePartial, boolean excludeDying, boolean excludePreCreated)` | `getUsers(boolean excludeDying)` (`NoSuchMethodError` on 3-arg call) |

`UsersCompat.getUsers()` invokes the 3-boolean `UserManagerApis.getUsers` first and falls back to `IUserManager.getUsers(excludeDying)` on `NoSuchMethodError`.

## `Android17Compat` Resolution Algorithm

1. Resolves target binders via `ServiceManager` and `Class.forName` after `HiddenApiIntrospector.exemptHiddenApis()` lifts ART hidden API blacklists.
2. Matches candidate argument shapes by exact parameter `Class` equality, disallowing duplicate parameter-type tuples (preventing silent argument-order swaps).
3. Treats `IllegalArgumentException` as a candidate mismatch and advances; treats `InvocationTargetException` as an authoritative platform error and does not retry.
4. Caches the winning `(Method, shape)` pair per operation; invalidated by `NightDog` on system service recovery.
5. `grantRuntimePermission` and `revokeRuntimePermission` return a boolean indicating whether the platform call succeeded.
6. Unresolved signatures are recorded via `ServerSmartLogCollector.recordQprCompatFailure()` with live AIDL method dumps from `HiddenApiIntrospector`.
