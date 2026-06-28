# NightDog

NightDog is a lightweight watchdog for the Nightzuku server process.

## What it does

- Monitors server health via `Binder.linkToDeath()` (zero-polling, kernel-driven)
- Server pushes heartbeat every 5 seconds via `NightDog.beat()`
- If heartbeat is overdue by 60 seconds, NightDog kills the server process
- Starter automatically restarts the server after crash

## Architecture

Based on AOSP `Watchdog.java` pattern (system_server watchdog):

```
Server (main thread)              NightDog (watchdog thread)
      |                                  |
      |-- beat() every 5s ------------->|  (resets timestamp)
      |-- beat() --------------------->|
      |        [main thread stuck]        |
      |                                  |
      |        [no beat for 60s]         |-- Process.killProcess()
      |                                  |   (starter restarts server)
```

## Components

| Component | Purpose |
|-----------|---------|
| `NightDog.kt` | Singleton orchestrator, heartbeat check, linkToDeath |
| `NightDog.beat()` | Called by server main thread every 5s |
| `NightDog.start()` | Initializes linkToDeath on 4 system services |
| `NightDog.stop()` | Cleanup on server exit |

## Monitored services

- `package` (PackageManagerService)
- `activity` (ActivityManagerService)
- `user` (UserManagerService)
- `appops` (AppOpsService)

## Configuration

| Parameter | Default | Description |
|-----------|---------|-------------|
| Timeout | 60s | Time without heartbeat before kill |
| Poll interval | 60s | Fallback pingBinder check interval |
| Heartbeat interval | 5s | Server pushes beat every 5s |
| Max rebind attempts | 10 | Retry limit for dead service recovery |

## Compatibility

- Android 7.1+ (API 25+)
- Uses hidden APIs via `app_process` (no bypass needed)
- Works on Phone, Wear OS, and Android TV

## Enabling

Lab Features > NightDog Beta > Enable

## Files

- `nightdog/src/main/java/rikka/shizuku/nightdog/NightDog.kt`
- `server/src/main/java/rikka/shizuku/server/ShizukuService.java` (integration)
- `manager/src/main/java/moe/shizuku/manager/settings/LabFeaturesActivity.kt` (toggle)
