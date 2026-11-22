# NightDog Watchdog & Background Module Supervision

Nightzuku separates privileged process supervision from unprivileged network polling into two subsystems:

1. **NightDog (`NightDog` + `NightDogClient`)** — Privileged server watchdog, system-service recovery, module integrity checks, `service.sh` execution, and atomic privileged updates.
2. **`ModuleUpdateScheduler` (`JobScheduler` / `ModuleUpdateJobService`)** — OS-scheduled, network-constrained update checker (`JOB_ID = 4200`), requiring zero ADB/root privileges and no `androidx.work` / `Room` dependencies.

## Subsystem Architecture

### 1. Server Watchdog (`NightDog` in `shizuku_server`)

- **Heartbeat monitor**: `ShizukuService` invokes `NightDog.beat()` on the main `Looper` every `5 s`. A dedicated daemon `HandlerThread` (`nightdog`) checks freshness at `min(pollInterval, remainingTimeout)` against the `60 s` timeout. On stall, it logs the main thread stack trace and calls `Process.killProcess(Process.myPid())` so `rust/starter` respawns the server.
- **System service recovery**: Registers `IBinder.linkToDeath` on `package`, `activity`, `user`, `appops`, and optional `permissionmgr`, supplemented by fallback `pingBinder()` polls. Failed services are rebound with exponential backoff (`2 s` -> `5 s` -> `60 s`, max `10` attempts).
- **Recovery callback (`onServiceRecovered`)**: `ShizukuService` invalidates `Android17Compat` caches, re-registers `BinderSender` observers, reconciles grants, and re-dispatches Binders within the active `UserAccessPolicy`. Fatal faults are recorded to `ServerSmartLogCollector`.

### 2. Manager Supervisor (`NightDogClient`)

Subscribes to global `OnBinderReceivedListener` and `OnBinderDeadListener` callbacks in `ShizukuApplication`:

- **On binder death**: Resets `AdbModuleManager`'s per-session service execution guard so `service.sh` re-runs after server restart.
- **On binder received**:
  1. Fetches telemetry via `BINDER_TRANSACTION_getNightDogStatus` (`10005`).
  2. Cleans stale `.installing` staging directories in `adb_modules`.
  3. Runs enabled `service.sh` scripts (`runEnabledServicesIfAllowed`) if policy permits.
  4. Runs module health inspection (`inspectModuleHealth`: `module.prop`, script sizes, `logs/service-last.log` exit codes, WebUI `index.html`); `.sh` executable modes are set via `markScriptsExecutable()`.
  5. Flushes queued privilege/auto-updates (`flushPendingPrivilegeUpdates()`) or calls `ModuleUpdateScheduler.checkUpdates(context, force, onServerStart = true)` when cadence is `ON_START`.

### 3. Update Scheduler (`ModuleUpdateScheduler`)

- Synchronizes `JobInfo` (`JOB_ID = 4200`) on settings change across cadences: `MANUAL` (default), `ON_START`, `EVERY_6_HOURS`, `DAILY`, `WEEKLY`.
- Periodic cadences enforce `NETWORK_TYPE_UNMETERED` when Wi-Fi-only is enabled (`NETWORK_TYPE_ANY` otherwise). `MANUAL` and `ON_START` use `NETWORK_TYPE_NONE`; forced checks bypass the direct `ConnectivityManager` check.
- Respects per-module version pinning (`Freeze version`) and delegates atomic backup/install/rollback to `AdbModuleManager` via `NightDogClient.applyUpdates()`.

## Parameters & UI Locations

| Parameter | Default | Description |
| :--- | :--- | :--- |
| Heartbeat interval | `5 s` | `ShizukuService` main-thread heartbeat cadence |
| Heartbeat timeout | `60 s` | Stall threshold before process termination |
| Fallback poll interval | `60 s` | Maximum interval between `pingBinder()` checks |
| Rebind backoff | `2 s – 60 s` | Exponential delay (`2 s` -> `5 s` -> `60 s`) between failed service rebinds |
| Max rebind attempts | `10` | Attempts before terminating server for starter respawn |
| Stable uptime reset | `60 s` | Uptime threshold resetting starter's crash counter |
| Update `JobScheduler` ID | `4200` | System job ID for `ModuleUpdateJobService` |
| Default update cadence | `MANUAL` | `MANUAL`, `ON_START`, `6 h`, `24 h`, or `168 h` |

| UI Path | Scope |
| :--- | :--- |
| **Settings -> Lab Features** (`LabFeaturesActivity`, `TvLabMenuScreen`) | NightDog enable toggle and live telemetry (`boundServices`, `totalRebinds`, `beatCount`, `uptimeMs`) |
| **Settings -> Modules -> Update Settings** (`UpdateSettingsScreen`, phone only) | Update cadence, auto-update, Wi-Fi-only constraint, notifications, default install mode, Catalog toggle, GitHub PAT |
| **ADB Modules** (`ModulesActivity`, phone only) | Manual check, batch **Update all**, background actions toggle, per-module health/update badges, version freeze |

## Key Source Files

- `nightdog/src/main/java/rikka/shizuku/nightdog/NightDog.kt`
- `server/src/main/java/rikka/shizuku/server/ShizukuService.kt`
- `rust/starter/src/main.rs`
- `manager/src/main/java/moe/shizuku/manager/nightdog/NightDogClient.kt`
- `manager/src/main/java/moe/shizuku/manager/module/update/ModuleUpdateScheduler.kt`
- `manager/src/main/java/moe/shizuku/manager/module/update/UpdateChecker.kt`
- `manager/src/main/java/moe/shizuku/manager/module/update/ModuleInstaller.kt`
- `manager/src/main/java/moe/shizuku/manager/module/update/UpdateSettingsScreen.kt`
