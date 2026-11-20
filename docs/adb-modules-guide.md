# ADB Modules Authoring Guide

Practical workflow for building and testing Nightzuku ADB Modules. For the normative contract, see [ADB Modules API Specification](adb-modules-api.md).

## Scope & Constraints

- Execution privilege inherits the active `shizuku_server` UID (`2000` for ADB, `0` for root).
- No Magisk/KernelSU mount overlays, no `/data/adb/modules` compatibility, and no persistent daemon supervisor are provided.
- Always store state relative to `$MODDIR`.

## Minimal Module Template

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
description=Short technical description
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

echo "module=$SHIZUKU_MODULE_ID mode=$SHIZUKU_MODULE_MODE"
id
```

Package and install via the ADB Modules screen (phone UI):

```sh
cd my-module && zip -r ../my-module.zip .
```

## Anti-Fork Guard Setup

1. Copy `test-modules/adb-test-module/nightzuku-guard.sh` to the module root and source it at the top of `action.sh` and `service.sh`.
2. Copy `test-modules/adb-test-module/webui/nightzuku-guard.js` to `webui/` and include `<script src="nightzuku-guard.js"></script>` first in `webui/index.html`.
3. Declare `requireNightzuku=true` and `minNightzukuApi=1` in `module.prop`.

## Hook Selection & Policy

| Requirement | Hook | Policy Gate |
| :--- | :--- | :--- |
| On-demand user command | `action.sh` | Allowed in all modes |
| Per-session initialization | `service.sh` | Blocked in Safe mode; requires Full/Custom+Service + Background Actions (or Full Trust) |
| Interactive configuration UI | `webui/index.html` | Served on isolated `https://mod-<hash>.appassets.androidplatform.net/`; set `usesShellBridge=true` for shell bridge |

**Full Trust** is a per-module override (long-press a module card) that bypasses global policy mode gates while retaining core safety limits (`2048` entries, `200 MB` extracted cap, `120 s` timeout / exit `124`, `64 KB` per-stream log retention).

## Debugging & Reference Fixture

- Execution logs are stored at `$MODDIR/logs/action-last.log` and `$MODDIR/logs/service-last.log`.
- `test-modules/adb-test-module.zip` provides a complete verification fixture covering installation, Action, Service, WebUI ES6 bridge, banner rendering, and `nightzuku-guard` attestation.
