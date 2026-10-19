# NightDog & Background Module Check

NightDog is the built-in watchdog for the Nightzuku server process (`shizuku_server`) and the unified coordinator for background module health, `service.sh` execution, and update checks in the Nightzuku Manager.

## How it works

1. **Main-thread heartbeat monitoring**:
   - When enabled, `ShizukuService` posts a heartbeat (`NightDog.beat()`) on its main looper thread every 5 seconds.
   - `NightDog.start()` records an initial baseline timestamp immediately so a freeze before the first scheduled beat is still caught.
   - A dedicated daemon `HandlerThread` (`nightdog`) checks heartbeat freshness dynamically against the configured timeout (`60s`), scheduling follow-up checks at `min(pollInterval, remainingTimeout)` so stalls are detected promptly at the timeout boundary rather than up to `2x` timeout.
   - Before killing a stalled server process (`Process.killProcess(Process.myPid())`), NightDog captures and logs the main looper thread's stack trace to aid debugging.

2. **System service death & recovery monitoring**:
   - NightDog registers `IBinder.linkToDeath` recipients on four critical system services (`package`, `activity`, `user`, `appops`) and optional `permissionmgr`.
   - Each health-check cycle also performs a fallback `pingBinder()` check and properly unlinks stale death recipients before rebinding.
   - When a service dies or becomes unresponsive, NightDog deduplicates pending rebinds per service and retries with exponential backoff (`5s`, `10s`, `20s`, `40s`, `60s`, up to 10 attempts), verifying that the replacement binder is alive (`pingBinder()`) before accepting it.
   - When a service recovers (`NightDog.Listener.onServiceRecovered`), `ShizukuService` invalidates cached `Android17Compat` proxy handles (`package` / `permissionmgr`), re-registers `BinderSender` process/UID observers (if `activity` restarted), and re-dispatches binders to the manager and client apps.
   - If a critical service cannot be recovered after all 10 attempts, NightDog terminates the server process so the starter daemon can respawn it and wait for system services cleanly.

3. **Unified Background Module Check (`NightDogClient`)**:
   - `NightDogClient` registers both `OnBinderReceivedListener` and `OnBinderDeadListener` globally in `ShizukuApplication`:
     - When `shizuku_server` binder dies, `NightDogClient` resets `AdbModuleManager`'s service run guard so `service.sh` scripts will be re-executed automatically once the server recovers.
     - When the server binder is received (on boot, manual start, or NightDog recovery), and periodically every 15 minutes while background checking is enabled, `NightDogClient` runs `runBackgroundCheck()`:
       1. Queries real-time server watchdog telemetry via `BINDER_TRANSACTION_getNightDogStatus` (`10005`): bound system services, total rebinds, heartbeat count, and uptime.
       2. Cleans up stale `.installing` staging directories in `adb_modules`.
       3. Executes enabled module `service.sh` scripts (`AdbModuleManager.runEnabledServicesIfAllowed`) when background actions (`allowBackgroundActions`) are permitted.
       4. Inspects every installed module's integrity (`AdbModuleManager.inspectModuleHealth`): `module.prop`, executable script permissions, `action.sh`/`service.sh` size and non-zero exit codes in `logs/service-last.log`, and WebUI `index.html`.
       5. Checks installed modules for updates via `UpdateChecker` according to the configured schedule (`DAILY` / `WEEKLY`) or on manual trigger.

## UI Controls

NightDog and Background Module Check are exposed across Phone, Android TV, and Wear OS:

- **Settings -> Lab Features** (`LabFeaturesActivity`, `TvLabMenuScreen`):
  - Toggle **NightDog Watchdog** (`BINDER_TRANSACTION_setNightDogEnabled` / `BINDER_TRANSACTION_getNightDogEnabled`).
  - Toggle **Background module check (NightDog)** (`ModuleSettings.isBackgroundCheckEnabled()`).
  - Toggle **Allow background actions (`service.sh`)** (`ModuleSettings.allowBackgroundActions()`).
  - Live telemetry & manual check button (**Run module & server check now**).
- **Settings -> Modules -> Update & Check Settings** (`SettingsActivity`, `UpdateSettingsScreen`, `TvSettingsScreen`, `WearSettingsScreen`):
  - Full control over background module actions, background checks, update check cadence (`Manual`, `Daily`, `Weekly`), default install mode (`Sources` vs `Release ZIP`), and GitHub PAT.
- **ADB Modules screen** (`ModulesActivity`, `TvModulesScreen`, `WearModulesScreen`):
  - Top NightDog module check banner with quick check button and background actions switch.
  - Per-module health badges (`Healthy` / script exit code issues), background policy badges (`Background allowed` / `Background blocked`), update badges (`Update: vX.Y`), and one-tap **Update** button via `ModuleInstaller.updateModule()`.

## Starter recovery behavior

When `shizuku_server` is launched or exits abnormally / is killed by NightDog:

- The native `starter` (`rust/starter/src/main.rs`) ignores `SIGPIPE` and `SIGHUP` (`SIG_IGN`) so closing the launching ADB/root shell cannot kill the background supervisor or trigger broken-pipe terminations, while restoring `SIG_DFL` inside the spawned `shizuku_server` child before `execvp`.
- Before spawning a new supervisor, `starter` terminates both stale `shizuku_server` instances and any lingering previous `starter` supervisor processes (matched via `/proc/<pid>/exe`) so duplicate supervisors never race each other.
- `starter` forks a detached background supervisor (`setsid`, `/dev/null` stdio) and communicates the initial `shizuku_server` PID over a pipe so the foreground starter process immediately prints `info: shizuku_server pid is <pid>` and `info: shizuku_starter exit with 0` and exits cleanly without blocking `StarterActivity` or wireless ADB sessions.
- The detached supervisor waits on `shizuku_server` via `waitpid` (retrying on `EINTR`), logs lifecycle events to Android logcat (`Shizuku` tag), and waits 5 seconds before respawning `shizuku_server` on abnormal exit.
- Rapid crash loops are capped at 5 consecutive restarts (`max_restarts = 5`).
- If `shizuku_server` runs stably for at least 60 seconds (`STABLE_UPTIME_RESET_SECS`), the consecutive crash counter resets to `0`, allowing NightDog to recover the server across multi-day uptimes without exhausting the retry budget.
- Clean exits (`0`) and manager-uninstalled exits (`50` / `MANAGER_APP_NOT_FOUND`) stop the starter without restarting.

## Configuration

| Parameter | Default | Description |
|-----------|---------|-------------|
| Heartbeat interval | 5 s | Main thread heartbeat cadence |
| Heartbeat timeout | 60 s | Maximum allowed time without a heartbeat before process termination |
| Poll interval | 60 s | Maximum interval between fallback `pingBinder()` checks |
| Initial rebind delay | 2 s | Delay before first rebind attempt after service death |
| Backoff range | 5 s – 60 s | Exponential backoff delay between failed rebind attempts |
| Max rebind attempts | 10 | Maximum attempts before terminating server for starter recovery |
| Stable uptime reset | 60 s | Server uptime threshold that resets starter's consecutive crash counter |
| Background module check interval | 15 min | Periodic module health & due-update check cadence in `NightDogClient` |

## Files

- `nightdog/src/main/java/rikka/shizuku/nightdog/NightDog.kt`
- `server/src/main/java/rikka/shizuku/server/ShizukuService.kt`
- `server/src/main/java/rikka/shizuku/server/ServerConstants.kt`
- `server/src/main/java/rikka/shizuku/server/ShizukuConfigManager.kt`
- `server/src/main/java/rikka/shizuku/server/BinderSender.kt`
- `rust/starter/src/main.rs`
- `manager/src/main/java/moe/shizuku/manager/nightdog/NightDogClient.kt`
- `manager/src/main/java/moe/shizuku/manager/module/AdbModuleManager.kt`
- `manager/src/main/java/moe/shizuku/manager/module/ModuleSettings.kt`
- `manager/src/main/java/moe/shizuku/manager/module/ModulesActivity.kt`
- `manager/src/main/java/moe/shizuku/manager/module/update/UpdateSettingsScreen.kt`
- `manager/src/main/java/moe/shizuku/manager/settings/LabFeaturesActivity.kt`
- `manager/src/main/java/moe/shizuku/manager/settings/TvLabMenuScreen.kt`
