# ADB Modules API Specification

ADB Modules are ZIP archives installed into Nightzuku's private app storage and executed via the active `shizuku_server` process (UID `2000` under ADB or UID `0` under root). This runtime is a controlled script/WebUI runner, not a systemless root overlay filesystem.

## Archive Structure

A valid module archive contains `module.prop` at the ZIP root. Leading slashes are stripped and blank/`.`/`..` segments are rejected during extraction; canonical containment is enforced by `ModuleRuntimeUtils.ensureInside`.

```text
module.zip
├── module.prop
├── banner.png               (optional: .png, .jpg, .jpeg, .webp)
├── nightzuku-guard.sh       (optional anti-fork shell guard)
├── action.sh                (optional manual action script)
├── service.sh               (optional background hook)
└── webui/                   (optional: webroot/, webui/, or web/)
    ├── nightzuku-guard.js   (optional anti-fork WebUI guard)
    └── index.html
```

## `module.prop` Schema

| Field | Required | Constraint / Default |
| :--- | :--- | :--- |
| `id` | Yes | Regex `[A-Za-z][A-Za-z0-9._-]{1,63}` |
| `name` | Yes | Human-readable display name |
| `version` | Yes | Display version string |
| `versionCode` | Yes | Integer monotonically increasing for updates |
| `author` | Yes | Author identifier |
| `description` | Yes | Concise summary |
| `banner` | No | Relative path to `.png`/`.jpg`/`.jpeg`/`.webp`; auto-detects `banner.*` |
| `webui` | No | Directory containing `index.html`; auto-detects `webroot`, `webui`, `web` |
| `action` | No | Default: `action.sh` |
| `usesShellBridge` | No | `true` to expose `window.Nightzuku` / `window.Shizuku` in WebUI |
| `requireNightzuku` | No | Enforces genuine Nightzuku host during install and runtime guard checks |
| `minNightzukuApi` | No | Minimum `NIGHTZUKU_MODULES_API` integer |
| `minNightzukuVersion` | No | Minimum Manager `versionCode` (`63` for r63) |
| `updateJson` | No | HTTPS URL for update metadata |

`service.sh` is detected automatically at the module root.

## Installation Pipeline & Safety Limits

1. Archive is streamed into cache under a bounded byte limit.
2. `module.prop` is parsed and `minNightzukuApi` / `minNightzukuVersion` are verified.
3. Entries are extracted to a staging directory with canonical path containment checks (`ModuleRuntimeUtils.ensureInside`).
4. `.sh` files are marked executable and `.nightzuku_env` host attestation metadata is generated.
5. On update, the existing directory is moved to `.<id>.backup`, preserving `.source.prop` and `disable` state; failures roll back atomically.

| Parameter | Limit |
| :--- | :--- |
| Maximum ZIP entries | `2048` |
| Maximum extracted size | `200 MB` |
| Script execution timeout | `120 s` (exit code `124`) |
| Retained stdout / stderr buffer | Last `64 KB` per stream |
| WebUI `download()` file cap | `20 MB` (HTTPS only; `index.html` protected) |
| WebUI `execWithOptions` stdin cap | `64 KB` |

## Runtime Environment

Scripts are fed via stdin to `sh -s <module-id>` with `cwd = $MODDIR`, avoiding the 128 KB `argv` limit while setting `$0` to `<module-id>`. Always reference `$MODDIR`; never assume `/data/adb/modules`.

| Variable | Value |
| :--- | :--- |
| `MODDIR` | `/data/local/tmp/kerneldroid.nightzuku/adb_modules/<id>` (shell staging dir; scripts run as UID 2000) |
| `NIGHTZUKU_APP_MODDIR` | App-private directory (`files/adb_modules/<id>`) for modules that need host-only storage |
| `ASH_STANDALONE` | `1` |
| `SHIZUKU_MODULE_ID` | Module `id` |
| `SHIZUKU_MODULE_MODE` | `safe` \| `custom` \| `full` |
| `SHIZUKU_MODULE_TRUSTED` | `0` \| `1` |
| `SHIZUKU_MODULE_BACKGROUND` | `0` \| `1` |
| `NIGHTZUKU_HOST` | `1` |
| `NIGHTZUKU_HOST_PKG` | `kerneldroid.nightzuku` |
| `NIGHTZUKU_HOST_VERSION` | Manager `versionName` |
| `NIGHTZUKU_HOST_VERSION_CODE` | Manager `versionCode` |
| `NIGHTZUKU_MODULES_API` | `2` |
| `NIGHTZUKU_BINDER_DESCRIPTOR` | `kerneldroid.nightzuku.INightService` |
| `NIGHTZUKU_PROVIDER_AUTHORITY` | `kerneldroid.nightzuku.nightzuku` (canonical; `.shizuku` remains a legacy alias) |
| `NIGHTZUKU_SIGNER_SHA256` | Manager signing certificate SHA-256 hex digest |
| `NIGHTZUKU_SESSION_TOKEN` | 64-char module-bound HMAC-SHA256 token |

## Execution Policy Matrix

| Hook | Trigger | Safe Mode | Custom / Full Mode | Log File |
| :--- | :--- | :--- | :--- | :--- |
| `action.sh` | Manual user trigger | Blocked unless the module has Full Trust | Allowed | `logs/action-last.log` |
| `service.sh` | Once per Binder session & after updates | Blocked | Allowed when Service permission + Background Actions enabled (or Full Trust) | `logs/service-last.log` |

## Anti-Fork Host Attestation (`nightzuku-guard`)

Modules can bundle `nightzuku-guard.sh` and `webui/nightzuku-guard.js` to reject execution on incompatible forks lacking `INightService` or the full WebUI bridge:

1. **Identity check**: Validates `MODDIR`, `NIGHTZUKU_HOST_PKG`, and `NIGHTZUKU_BINDER_DESCRIPTOR`.
2. **Signed env file**: Compares `NIGHTZUKU_SESSION_TOKEN` against `$MODDIR/.nightzuku_env`.
3. **Live IPC challenge (`verifyNightzukuHost`)**:
   - Shell: `content call --uri content://kerneldroid.nightzuku.nightzuku --method verifyNightzukuHost --arg "guard-<pid>-<time>" --extra "moduleId:s:<id>"` (falls back to the `.nightzuku_env` token check if SELinux blocks `content call`).
   - WebUI: `window.Nightzuku.verifyHost(nonce)` validates `genuine=true` and the 64-hex HMAC token.

## Virtual HTTPS WebUI & JS Bridge

Each module's WebUI is served by `ModuleWebAssetServer` on an isolated per-module virtual HTTPS origin:

```text
https://mod-<sha256_prefix>.appassets.androidplatform.net/
```

- **Isolation**: Distinct subdomains isolate `localStorage`, `sessionStorage`, `IndexedDB`, and `CacheStorage` per module. Raw `file://` and `content://` WebView access are disabled.
- **Native ES6 support**: `<script type="module">`, `import`/`export` (`.js`, `.mjs`, `.cjs`), `fetch()`, Web Workers, and `.wasm` work without Full Trust.
- **Built-in ES6 bridge**: `import { exec, getModuleInfo, verifyHost, download } from "/__nightzuku__/bridge.js";` (also aliased at `/nightzuku.mjs`).

| Bridge Method | Description |
| :--- | :--- |
| `getModuleInfo()` | Returns module metadata plus a nested `host` object (`packageName`, `binderDescriptor`, `modulesApiVersion`, `signerSha256`, etc.); the attestation token comes from `verifyHost(nonce)` |
| `verifyHost(nonce)` | Returns signed host attestation payload for `nightzuku-guard.js` |
| `exec(command)` | Executes shell command with default `120 s` timeout |
| `execWithOptions(command, optionsJson)` | Executes with custom `timeoutSeconds`, `cwd`, `stdin` (`<= 64 KB`), and `env` |
| `download(url, relativeWebPath)` | Downloads an HTTPS asset (`<= 20 MB`) into the module's WebUI directory (cannot overwrite `index.html`) |

See [NightDog Architecture](nightdog.md) for `JobScheduler` background update checks and privileged auto-updates.
