# ADB Modules Guide

This guide is for module authors and testers. For the exact API contract, see
[ADB Modules API reference](adb-modules-api.md).

## What This System Is

ADB Modules are ZIP packages installed into Nightzuku private storage and executed through
the active Nightzuku server.

- ADB-started Nightzuku: scripts run with ADB shell privileges.
- Root-started Nightzuku: scripts run with root privileges.
- Safe mode: manual actions only.
- Full access: allows stronger module behavior.
- Background actions: required before `service.sh` can run.

This is a real module runner, not a visual stub. It installs ZIPs, parses metadata,
stores module files, runs shell scripts through Nightzuku, opens local WebUI, tracks enabled
state, checks for updates via `JobScheduler`, and writes last-run logs.

It is not a Magisk/KSU systemless overlay implementation. There are no mount hooks,
no `/data/adb/modules` compatibility promise, and no long-running daemon supervisor.

## Minimal Module

Create this structure:

```text
my-module/
├── module.prop
├── nightzuku-guard.sh
├── action.sh
└── webui/
    ├── nightzuku-guard.js
    └── index.html
```

`module.prop`:

```properties
id=my-module
name=My Module
version=1.0
versionCode=1
author=Author
description=Short module description
usesShellBridge=true
requireNightzuku=true
minNightzukuApi=1
minNightzukuVersion=13
```

`action.sh`:

```sh
#!/system/bin/sh
MODDIR="${MODDIR:-${0%/*}}"
[ -f "$MODDIR/nightzuku-guard.sh" ] && . "$MODDIR/nightzuku-guard.sh"

echo "module=$SHIZUKU_MODULE_ID"
echo "mode=$SHIZUKU_MODULE_MODE"
id
```

Package it:

```sh
cd my-module
zip -r ../my-module.zip .
```

Install `my-module.zip` from the ADB Modules screen.

## Protecting Your Module Against Broken Forks (`nightzuku-guard`)

If an unofficial fork copies Nightzuku's module manager UI without implementing the native `kerneldroid.nightzuku.INightService` runtime or full WebUI bridge, your module may fail silently or behave unpredictably, leading users of that fork to report false bugs to you.

To block execution on incompatible forks:

1. Copy `test-modules/adb-test-module/nightzuku-guard.sh` into your module root and source it at the top of `action.sh` and `service.sh`:
   ```sh
   MODDIR="${MODDIR:-${0%/*}}"
   [ -f "$MODDIR/nightzuku-guard.sh" ] && . "$MODDIR/nightzuku-guard.sh"
   ```
2. Copy `test-modules/adb-test-module/webui/nightzuku-guard.js` into your `webui/` folder and load it first in `webui/index.html`:
   ```html
   <script src="nightzuku-guard.js"></script>
   ```
3. Set `requireNightzuku=true` and `minNightzukuApi=1` in `module.prop`.

When run inside genuine Nightzuku, `nightzuku-guard` verifies the `kerneldroid.nightzuku` package path, `.nightzuku_env` HMAC token, and live `verifyNightzukuHost` ContentProvider / JS bridge challenge in a few milliseconds. On any unofficial fork, it aborts immediately with a clear message telling the user that their manager app is an unsupported fork and not to report the error to the module author.

## Optional Files

Banner:

```text
banner.png
```

WebUI:

```text
webui/index.html
```

Background/service hook:

```text
service.sh
```

Custom paths can be declared in `module.prop`:

```properties
banner=assets/banner.webp
webui=webui
usesShellBridge=true
action=scripts/action.sh
```

`usesShellBridge=true` is mandatory for WebUI pages that need `window.Nightzuku` / `window.Shizuku`.

### ES6 Modules in WebUI

Every module WebUI is served over an isolated virtual HTTPS origin (`https://mod-<hash>.appassets.androidplatform.net/`). You can use `<script type="module" src="js/main.js">`, `import`/`export` across local `.js`/`.mjs` files, `fetch(./config.json)`, and the built-in `/__nightzuku__/bridge.js` ES6 module in any enabled module without needing Full Trust.

## Script Environment

Scripts run from the module directory. Use these variables:

```sh
MODDIR=/data/user/0/kerneldroid.nightzuku/files/adb_modules/<id>
ASH_STANDALONE=1
SHIZUKU_MODULE_ID=<id>
SHIZUKU_MODULE_MODE=safe|custom|full
SHIZUKU_MODULE_TRUSTED=0|1
SHIZUKU_MODULE_BACKGROUND=0|1
NIGHTZUKU_HOST=1
NIGHTZUKU_HOST_PKG=kerneldroid.nightzuku
NIGHTZUKU_MODULES_API=2
NIGHTZUKU_BINDER_DESCRIPTOR=kerneldroid.nightzuku.INightService
NIGHTZUKU_SESSION_TOKEN=<64-char HMAC token>
```

Do not hardcode Magisk/KSU paths. Use `$MODDIR`.

## Action vs Service

Use `action.sh` for user-triggered commands.

Use `service.sh` for background setup. It runs when:
- The module is enabled.
- Access mode is Full, or Custom with Service enabled (or Full Trust is enabled for the module).
- Background actions are enabled in Settings.
- Nightzuku binder is available.

`NightDogClient` auto-runs enabled services once per binder session and after auto-updates.

## Full Trust

Full Trust is a per-module override. Long-press a module card to toggle. Trusted modules bypass global policy gates while respecting core safety limits (timeouts, output caps).

## Safety Limits

- ZIP path traversal is rejected.
- Max entries: `2048`.
- Max extracted size: `200 MB`.
- Script timeout: `120 seconds`.
- Output retained: last `64 KB` per stdout/stderr stream.

Timeout exit code is `124`.

## Logs

Logs are written inside the installed module directory:

```text
logs/action-last.log
logs/service-last.log
```

Each log includes module id, script name, exit code, access mode, stdout, and stderr.

## Test Module

The repository includes:

```text
test-modules/adb-test-module.zip
```

Use it to verify install, enable/disable, Action, Service, WebUI, anti-fork host attestation (`nightzuku-guard.sh` / `nightzuku-guard.js`), and banner rendering.
