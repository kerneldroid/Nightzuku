# NightDog & Background Module Update Architecture

Nightzuku splits background supervision into two specialized components so privileged process monitoring never wastes cycles on unprivileged network polling:

1. **NightDog (`NightDog` + `NightDogClient`)** — Privileged server watchdog, local module integrity inspector, `service.sh` supervisor, and privileged update installer.
2. **`ModuleUpdateScheduler` (`JobScheduler` / `ModuleUpdateJobService`)** — OS-scheduled, network-constrained background update checker for ADB modules.

## Why `JobScheduler` + `NightDog` Instead of Polling Inside `NightDogClient`

- **Separation of privileges**: Checking GitHub Releases or raw `module.prop` over HTTPS requires zero ADB/root privileges and depends on network state (unmetered Wi-Fi vs cellular, Doze window batching). `ModuleUpdateScheduler` delegates periodic polling to Android's system `JobScheduler` (`ModuleUpdateJobService`, job ID `4200`), which enforces `NETWORK_TYPE_UNMETERED` / `NETWORK_TYPE_ANY`, survives app process death without keeping a 15-minute coroutine loop alive, and adds zero third-party library bloat (`androidx.work` / `Room` are not required).
- **Privileged execution handoff**: When `ModuleUpdateScheduler` detects available updates and `Auto-update modules` is enabled, it hands the verified update targets to `NightDogClient.applyUpdates()`. If `shizuku_server` is active (`Shizuku.pingBinder()`), `NightDogClient` atomically backs up and installs each module, then re-executes `service.sh` with root/ADB privileges. If the server is offline during a background job, `NightDogClient` queues the pending updates and applies them automatically as soon as `OnBinderReceivedListener` fires.

## How It Works

### 1. Server Watchdog (`NightDog` inside `shizuku_server`)

- **Main-thread heartbeat monitoring**:
  - When enabled, `ShizukuService` posts `NightDog.beat()` on its main looper thread every 5 seconds.
  - `NightDog.start()` records an initial baseline timestamp immediately so a freeze before the first scheduled beat is still caught.
  - A dedicated daemon `HandlerThread` (`nightdog`) checks heartbeat freshness dynamically against the configured timeout (`60s`), scheduling follow-up checks at `min(pollInterval, remainingTimeout)` so stalls are detected at the exact timeout boundary rather than up to `2x` timeout.
  - Before terminating a stalled server process (`Process.killProcess(Process.myPid())`), NightDog logs the main looper thread's stack trace.
- **System service death & recovery monitoring**:
  - Registers `IBinder.linkToDeath` recipients on four critical system services (`package`, `activity`, `user`, `appops`) and optional `permissionmgr`.
  - Performs fallback `pingBinder()` checks and unlinks stale death recipients before rebinding.
  - Retries dead services with exponential backoff (`5s`, `10s`, `20s`, `40s`, `60s`, up to 10 attempts), verifying replacement binders via `pingBinder()`.
  - When a service recovers (`NightDog.Listener.onServiceRecovered`), `ShizukuService` invalidates cached `Android17Compat` proxy handles, re-registers `BinderSender` UID/process observers, and re-dispatches binders to the manager and client apps.
  - If a critical service cannot be recovered after 10 attempts, NightDog terminates `shizuku_server` so the native `starter` supervisor respawns it cleanly.

### 2. Privileged Module Supervisor (`NightDogClient`)

- Registers global `OnBinderReceivedListener` and `OnBinderDeadListener` callbacks in `ShizukuApplication`:
  - **On binder death**: Resets `AdbModuleManager`'s service run guard so `service.sh` scripts re-run automatically when the server restarts.
  - **On binder received**:
    1. Queries server watchdog telemetry via `BINDER_TRANSACTION_getNightDogStatus` (`10005`): bound system services, total rebinds, heartbeat count, and uptime.
    2. Cleans up stale `.installing` staging directories in `adb_modules`.
    3. Executes enabled module `service.sh` scripts (`AdbModuleManager.runEnabledServicesIfAllowed`) when background actions (`allowBackgroundActions`) are permitted.
    4. Inspects installed module integrity (`AdbModuleManager.inspectModuleHealth`): `module.prop`, executable `.sh` permissions, `action.sh`/`service.sh` size, non-zero exit codes in `logs/service-last.log`, and WebUI `index.html`.
    5. Applies any queued auto-updates (`applyPendingAutoUpdatesIfReady`) or triggers `ModuleUpdateScheduler.onServerStarted()` when update frequency is set to `ON_START`.

### 3. Background Module Update Checker (`ModuleUpdateScheduler` & `ModuleUpdateJobService`)

- Synchronizes an OS `JobInfo` schedule whenever update settings change (`ModuleUpdateScheduler.syncSchedule()`):
  - **Frequencies**: `MANUAL` (default, no periodic job), `ON_START` (runs when `shizuku_server` starts), `EVERY_6_HOURS`, `DAILY`, `WEEKLY`.
  - **Network constraints**: Enforces `JobInfo.NETWORK_TYPE_UNMETERED` when `Wi-Fi / unmetered network only` is enabled, or `JobInfo.NETWORK_TYPE_ANY` otherwise, plus a direct `ConnectivityManager` check for immediate/manual runs.
- **Per-module version pinning (`Freeze version`)**: Modules pinned by the user are skipped during update checks and batch updates.
- **Atomic backup & rollback (`AdbModuleManager`)**: Before replacing an existing module directory during an update, Nightzuku moves the previous version to `<id>.backup`, preserves `.source.prop` and the `disable` state, and rolls back automatically if extraction or validation fails.

## UI Controls

- **Settings -> Lab Features** (`LabFeaturesActivity`, `TvLabMenuScreen`):
  - Dedicated solely to **NightDog Server Watchdog** toggle and live server telemetry (`boundServices`, `rebindCount`, `heartbeatCount`, `uptimeSeconds`). Duplicated module switches have been removed from Lab Features.
- **Settings -> Modules -> Update & Check Settings** (`SettingsActivity`, `UpdateSettingsScreen`, `TvSettingsScreen`, `WearSettingsScreen`):
  - Central configuration for background module checks, update frequency (`Never`, `On server start`, `Every 6 hours`, `Daily`, `Weekly`), auto-update toggle, Wi-Fi-only constraint, update notifications, default install mode (`Sources` vs `Release ZIP`), Module Catalog toggle, and GitHub PAT.
- **ADB Modules Screen** (`ModulesActivity`, `TvModulesScreen`, `WearModulesScreen`):
  - Top status banner with manual check trigger, **Update all (N)** batch progress bar, and background actions switch.
  - Per-module health badges, update badges (`Update: vX.Y`), changelog & install-mode dialog, and version freeze (`Updates frozen`).

## Configuration Summary

| Parameter | Default | Description |
|-----------|---------|-------------|
| Heartbeat interval | 5 s | `shizuku_server` main thread heartbeat cadence |
| Heartbeat timeout | 60 s | Max time without heartbeat before server termination |
| Poll interval | 60 s | Max interval between fallback `pingBinder()` checks |
| Rebind backoff | 5 s – 60 s | Exponential backoff delay between failed service rebinds |
| Max rebind attempts | 10 | Max attempts before terminating server for starter recovery |
| Stable uptime reset | 60 s | Server uptime threshold that resets starter's crash counter |
| Update check scheduler | `JobScheduler` | OS-managed periodic job (`JOB_ID = 4200`) with network constraints |
| Default update cadence | `MANUAL` | Configurable to `MANUAL`, `ON_START`, `6h`, `24h`, or `168h` |

## Key Files

- `nightdog/src/main/java/rikka/shizuku/nightdog/NightDog.kt`
- `server/src/main/java/rikka/shizuku/server/ShizukuService.kt`
- `rust/starter/src/main.rs`
- `manager/src/main/java/moe/shizuku/manager/nightdog/NightDogClient.kt`
- `manager/src/main/java/moe/shizuku/manager/module/update/ModuleUpdateScheduler.kt`
- `manager/src/main/java/moe/shizuku/manager/module/update/UpdateChecker.kt`
- `manager/src/main/java/moe/shizuku/manager/module/update/ModuleInstaller.kt`
- `manager/src/main/java/moe/shizuku/manager/module/update/UpdateSettingsScreen.kt`
- `manager/src/main/java/moe/shizuku/manager/module/ModuleRuntimeUtils.kt`
