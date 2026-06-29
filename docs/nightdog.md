# NightDog

NightDog monitors the Shizuku server process for hangs and dead system services, and kills the process when a fault is detected so the starter can restart it.

## How it works

1. The server main thread calls `NightDog.beat()` every 5 seconds.
2. A watchdog thread checks heartbeat freshness on a 60-second poll interval.
3. If the heartbeat is overdue by 60 seconds, the watchdog calls `Process.killProcess()`.
4. The watchdog also holds `linkToDeath` bindings on four system services (`package`, `activity`, `user`, `appops`). If any service dies, NightDog attempts to rebind with exponential backoff (up to 10 attempts).
5. A fallback `pingBinder()` check runs each poll cycle to catch services that are unresponsive but haven't triggered the death recipient.

## Enabling and disabling

NightDog is controlled by a Lab Feature toggle in the manager app. The toggle sends a binder transaction (`setNightDogEnabled` / `getNightDogEnabled`) to the server, which starts or stops the watchdog and heartbeat loop accordingly.

## Restart behavior

When the server process is killed, the starter (a separate native process) detects the exit and restarts the server, up to a maximum of 5 attempts.

## Configuration

| Parameter | Default |
|-----------|---------|
| Heartbeat interval | 5 s |
| Timeout (heartbeat overdue) | 60 s |
| Poll interval | 60 s |
| Max rebind attempts | 10 |

## Files

- `nightdog/src/main/java/rikka/shizuku/nightdog/NightDog.kt`
- `server/src/main/java/rikka/shizuku/server/ShizukuService.java`
- `manager/src/main/java/moe/shizuku/manager/settings/LabFeaturesActivity.kt`
