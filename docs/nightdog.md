# NightDog

NightDog is the built-in watchdog for the Nightzuku server process (`shizuku_server`). It monitors main-thread responsiveness and critical Android system service bindings, terminating the server when a fault occurs so the native starter daemon can restart it cleanly.

## How it works

1. **Main-thread heartbeat monitoring**:
   - When enabled, `ShizukuService` posts a heartbeat (`NightDog.beat()`) on its main looper thread every 5 seconds.
   - `NightDog.start()` records an initial baseline timestamp immediately so a freeze before the first scheduled beat is still caught.
   - A dedicated daemon `HandlerThread` (`nightdog`) checks heartbeat freshness dynamically against the configured timeout (`60s`), scheduling follow-up checks at `min(pollInterval, remainingTimeout)` so stalls are detected promptly at the timeout boundary rather than up to `2x` timeout.
   - Before killing a stalled server process (`Process.killProcess(Process.myPid())`), NightDog captures and logs the main looper thread's stack trace to aid debugging.

2. **System service death & recovery monitoring**:
   - NightDog registers `IBinder.linkToDeath` recipients on four critical system services: `package`, `activity`, `user`, and `appops`.
   - Each health-check cycle also performs a fallback `pingBinder()` check and properly unlinks stale death recipients before rebinding.
   - When a service dies or becomes unresponsive, NightDog deduplicates pending rebinds per service and retries with exponential backoff (`5s`, `10s`, `20s`, `40s`, `60s`, up to 10 attempts), verifying that the replacement binder is alive (`pingBinder()`) before accepting it.
   - When a service recovers (`NightDog.Listener.onServiceRecovered`), `ShizukuService` re-registers `BinderSender` process/UID observers (if `activity` restarted) and re-dispatches binders to the manager and client apps.
   - If a critical service cannot be recovered after all 10 attempts, NightDog terminates the server process so the starter daemon can respawn it and wait for system services cleanly.

## Enabling and disabling

NightDog is controlled via **Settings -> Lab Features -> NightDog Watchdog** across Phone, Wear OS, and Android TV:

- `NightDogClient` communicates with `ShizukuService` over Binder IPC (`BINDER_TRANSACTION_setNightDogEnabled` / `BINDER_TRANSACTION_getNightDogEnabled`).
- On the server, `ShizukuConfigManager` flushes `nightdog_enabled` Changes immediately to `/data/user_de/0/com.android.shell/shizuku.json`.
- If toggled in the manager while the server is offline, `NightDogClient` caches the preference locally and synchronizes it automatically as soon as the server binder is received.

## Starter recovery behavior

When `shizuku_server` exits abnormally or is killed by NightDog:

- The native `starter` supervisor (`rust/starter/src/main.rs`) waits 5 seconds and respawns `shizuku_server`.
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

## Files

- `nightdog/src/main/java/rikka/shizuku/nightdog/NightDog.kt`
- `server/src/main/java/rikka/shizuku/server/ShizukuService.kt`
- `server/src/main/java/rikka/shizuku/server/ShizukuConfigManager.kt`
- `server/src/main/java/rikka/shizuku/server/BinderSender.kt`
- `rust/starter/src/main.rs`
- `manager/src/main/java/moe/shizuku/manager/nightdog/NightDogClient.kt`
- `manager/src/main/java/moe/shizuku/manager/settings/LabFeaturesActivity.kt`
- `manager/src/main/java/moe/shizuku/manager/settings/TvLabMenuScreen.kt`
