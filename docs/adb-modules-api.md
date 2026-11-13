# Nightzuku ADB Modules API

ADB Modules are ZIP packages installed into Nightzuku private app storage and executed through the currently active Nightzuku server. If Nightzuku is running from ADB, module scripts run with ADB shell privileges. If Nightzuku is running from root, scripts run with root privileges.

This is not a root overlay system. It is a Nightzuku-backed module runner for actions, WebUI, service hooks, and controlled ADB/root shell access.

## Package Format

A module is a `.zip` file with `module.prop` at the ZIP root.

```text
module.zip
├── module.prop
├── banner.png
├── nightzuku-guard.sh       (optional anti-fork shell guard)
├── action.sh
├── service.sh
└── webui/
    ├── nightzuku-guard.js   (optional anti-fork WebUI guard)
    └── index.html
```

All paths must be relative. Absolute paths and `..` traversal are rejected during install.

## module.prop

Required fields:

```properties
id=my-module
name=My Module
version=1.0
versionCode=1
author=Author
description=Short description
```

Optional fields:

```properties
banner=banner.png
webui=webui
action=action.sh
usesShellBridge=true
requireNightzuku=true
minNightzukuApi=1
minNightzukuVersion=13
updateJson=https://example.com/update.json
```

Rules:

- `id` must match `[A-Za-z][A-Za-z0-9._-]{1,63}`.
- `banner` can point to `.png`, `.jpg`, `.jpeg`, or `.webp`.
- If `banner` is omitted, Nightzuku checks `banner.png`, `banner.jpg`, `banner.jpeg`, then `banner.webp`.
- If `webui` is omitted, Nightzuku checks `webroot`, `webui`, then `web`.
- WebUI is available only when `<webui>/index.html` exists.
- `action` defaults to `action.sh`.
- `service.sh` is detected automatically.
- `requireNightzuku`, `minNightzukuApi`, and `minNightzukuVersion` enforce genuine Nightzuku host requirements during installation and runtime guard verification.

## Install & Update Behavior

Install flow:

1. User selects a module ZIP with Android file picker or installs from the GitHub Module Catalog.
2. Nightzuku copies the archive into cache with a bounded stream limit (`200 MB`).
3. Nightzuku validates `module.prop` and checks `minNightzukuApi` / `minNightzukuVersion`.
4. Nightzuku extracts into a staging directory and rejects unsafe relative paths or canonical escapes.
5. Nightzuku marks `.sh` files executable and generates `.nightzuku_env` host attestation metadata.
6. When updating an existing module, Nightzuku creates an atomic `<id>.backup` directory, preserves `.source.prop` and `disable` state, and rolls back automatically if installation fails.

Safety limits:

- Max ZIP entries: `2048`.
- Max extracted size: `200 MB`.
- Script output retained in memory/log: last `64 KB` per stream.
- Script timeout: `120 seconds`.

## Runtime Environment

Scripts run through Nightzuku server process creation with the script fed to `sh -s <module-id>` over stdin and the module directory as the working directory. This avoids the 128 KB `argv` limit and keeps `$0` set to the module id.

Environment variables injected into every script execution:

```sh
MODDIR=/data/user/0/kerneldroid.nightzuku/files/adb_modules/<id>
ASH_STANDALONE=1
SHIZUKU_MODULE_ID=<id>
SHIZUKU_MODULE_MODE=safe|custom|full
SHIZUKU_MODULE_TRUSTED=0|1
SHIZUKU_MODULE_BACKGROUND=0|1
NIGHTZUKU_HOST=1
NIGHTZUKU_HOST_PKG=kerneldroid.nightzuku
NIGHTZUKU_HOST_VERSION=<manager_version_name>
NIGHTZUKU_HOST_VERSION_CODE=<manager_version_code>
NIGHTZUKU_MODULES_API=2
NIGHTZUKU_BINDER_DESCRIPTOR=kerneldroid.nightzuku.INightService
NIGHTZUKU_PROVIDER_AUTHORITY=kerneldroid.nightzuku.shizuku
NIGHTZUKU_SIGNER_SHA256=<manager signing certificate SHA-256>
NIGHTZUKU_SESSION_TOKEN=<64-char HMAC-SHA256 token bound to module id>
```

Use `MODDIR` for all module-local files. Do not assume root paths such as `/data/adb/modules`.

## Anti-Fork Host Attestation (`nightzuku-guard.sh` & `nightzuku-guard.js`)

Third-party forks (such as "Shizuku Next") sometimes copy Nightzuku's module UI without implementing the native `kerneldroid.nightzuku.INightService` dual-wire protocol, proper `MODDIR` isolation, or the full WebUI shell bridge. Module authors can drop `nightzuku-guard.sh` and `webui/nightzuku-guard.js` into their module so it refuses to run on broken forks with a clear error message directing users to official Nightzuku (`https://github.com/kerneldroid/Nightzuku`).

### How Multi-Layer Host Verification Works

1. **Path & Package Identity Check**: Verifies `MODDIR` belongs to `kerneldroid.nightzuku`, `NIGHTZUKU_HOST_PKG=kerneldroid.nightzuku`, and `NIGHTZUKU_BINDER_DESCRIPTOR=kerneldroid.nightzuku.INightService`.
2. **Signed Environment File (`.nightzuku_env`)**: Before each script run, `AdbModuleManager` writes `$MODDIR/.nightzuku_env` containing the module-bound `NIGHTZUKU_SESSION_TOKEN` (`HMAC-SHA256`).
3. **Live Binder IPC Challenge (`verifyNightzukuHost`)**:
   - **Shell (`nightzuku-guard.sh`)**: Calls:
     ```sh
     content call --uri content://kerneldroid.nightzuku.shizuku \
       --method verifyNightzukuHost \
       --arg "guard-probe"
     ```
     `ShizukuManagerProvider` computes the HMAC-SHA256 challenge token and returns `genuine=true`, `hostPackage=kerneldroid.nightzuku`, `binderDescriptor=kerneldroid.nightzuku.INightService`, `modulesApiVersion`, and `attestationToken`. If `content call` is blocked by SELinux, the guard falls back to comparing `NIGHTZUKU_SESSION_TOKEN` with the token stored in `.nightzuku_env`.
   - **WebUI (`nightzuku-guard.js`)**: Calls `window.Nightzuku.verifyHost(nonce)` (also aliased on `window.Shizuku.verifyHost(nonce)`), verifies the returned host metadata and 64-hex attestation token, and renders a full-screen blocking banner if loaded inside an incompatible fork.

### Usage in `action.sh` / `service.sh`

```sh
#!/system/bin/sh
MODDIR="${MODDIR:-${0%/*}}"
[ -f "$MODDIR/nightzuku-guard.sh" ] && . "$MODDIR/nightzuku-guard.sh"
```

### Usage in `webui/index.html`

```html
<script src="nightzuku-guard.js"></script>
```

## Actions

`action.sh` is a manual user action launched from the module card.

Action result:
- stdout/stderr are shown in a dialog.
- Last output is written to `logs/action-last.log` inside the module directory.
- Timeout (120s) returns exit code `124`.

## Services

`service.sh` is the background hook.

Execution policy:
- **Safe mode**: Blocked.
- **Custom / Full access mode**: Allowed if Service permission and "Allow background actions" are enabled (or the module has Full Trust).
- Service scripts run once per Nightzuku binder session (and automatically re-run after a module update or server restart).
- Last output is written to `logs/service-last.log`.
- Timeout (120s) returns exit code `124`.

## WebUI, Virtual HTTPS Asset Server & ES6 Modules

WebUI is served from `webui/index.html` over a dedicated, per-module isolated virtual HTTPS origin (`https://mod-<sha256_prefix>.appassets.androidplatform.net/`) via `ModuleWebAssetServer`:

- **Native ES6 Modules Without Full Trust**: `<script type="module" src="...">`, static/dynamic `import`/`export` statements (`.js`, `.mjs`, `.cjs`, including extensionless imports), `fetch()`, Web Workers, WASM (`.wasm`), and CSS `@import` work out of the box under standard browser same-origin rules for all enabled modules — **Full Trust is not required**.
- **Per-Module Origin Isolation**: Each module ID resolves to its own isolated subdomain so `localStorage`, `sessionStorage`, `IndexedDB`, and `CacheStorage` cannot leak across different modules.
- **Zero Raw `file://` Exposure**: Raw `file://` and `content://` WebView access (`allowFileAccess`, `allowFileAccessFromFileURLs`, `allowUniversalAccessFromFileURLs`) are disabled for all modules, and all asset paths are canonically verified inside `webRoot` (`ModuleRuntimeUtils.ensureInside`).
- **Built-in ES6 Bridge Module (`/__nightzuku__/bridge.js` / `/nightzuku.mjs`)**: Modern ES6 modules can import async helpers directly:
  ```js
  import { exec, getModuleInfo, verifyHost, download } from "/__nightzuku__/bridge.js";
  ```
- Both `window.Nightzuku` and `window.Shizuku` globals are also exposed when `usesShellBridge=true` in `module.prop` and policy allows.

### Bridge Methods

- `window.Nightzuku.getModuleInfo()` — Returns JSON with `id`, `name`, `version`, `versionCode`, `author`, `description`, `enabled`, `trusted`, `accessMode`, `hostPackage`, `hostVersionCode`, `wireDescriptor`, `moduleApiVersion`, and `hostToken`.
- `window.Nightzuku.verifyHost(nonce)` — Cryptographic host attestation for `nightzuku-guard.js`.
- `window.Nightzuku.exec(command)` — Executes a shell command with default timeout (`120s`).
- `window.Nightzuku.execWithOptions(command, optionsJson)` — Executes with custom `timeoutSeconds`, `cwd`, `stdin` (max 64 KB), and `env`.
- `window.Nightzuku.download(url, relativeWebPath)` — Downloads an HTTPS asset (up to 20 MB) into the module WebUI directory (cannot overwrite `index.html`).

## Background Updates

Periodic update checks are scheduled via Android `JobScheduler` (`ModuleUpdateScheduler` / `ModuleUpdateJobService`), while privileged auto-updates and `service.sh` restarts are coordinated by `NightDogClient`. See [NightDog & Background Module Update Architecture](nightdog.md).
