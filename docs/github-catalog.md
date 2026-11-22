# GitHub Module Catalog

Decentralized online discovery and installation subsystem for Nightzuku ADB Modules using GitHub Topics (`topic:adb-modules`).

## Discovery & Installation Flow

1. Client queries `GET /search/repositories?q=topic:adb-modules` (optionally authenticated via user PAT).
2. `ModuleValidator` inspects repository roots and subdirectories for valid `module.prop` descriptors.
3. Discovery results are cached locally in `DiscoveryCache` with a `6 h` TTL.
4. `ModuleInstaller` installs the selected module via `AdbModuleManager.install()`.

## Quotas & Rate Limits

| Constraint | Value |
| :--- | :--- |
| Max modules per GitHub account | `4` across all repositories (official owner `kerneldroid` exempt) |
| Unauthenticated GitHub API quota | `60 req/hour` |
| Authenticated (PAT) GitHub API quota | `5,000 req/hour` |
| Conditional requests | Not implemented; `RateLimitTracker` reads only `x-ratelimit-remaining` / `x-ratelimit-reset` |
| Local cache TTL | `6 hours` |

## Install Modes

| Mode | Mechanism |
| :--- | :--- |
| **From Sources** | Queries GitHub Contents API on the resolved default branch; `SourceZipBuilder` packages all source files except `.git/`, `.github/` and root license/ignore files, honoring the resolved `subPath` |
| **From Release ZIP** | Queries GitHub Releases API, matches asset by module ID pattern, and installs directly |

## Token Security

- Stored encrypted via AndroidKeyStore (`AES/GCM/NoPadding`) in `TokenStore`; legacy plaintext tokens are migrated on startup.
- Format-validated for `ghp_` / `github_pat_` prefix (`>= 30` characters).
- Never written to logcat.
- Sent only to GitHub-owned hosts: `api.github.com`, `raw.githubusercontent.com`, `github.com` and their subdomains; never forwarded to third-party `updateJson` URLs.

## Platform Availability

| Platform | Catalog UI | Update & Token Settings | Lab Features |
| :--- | :--- | :--- | :--- |
| Phone / Tablet | `CatalogScreen.kt` | `UpdateSettingsScreen.kt` | `LabFeaturesActivity.kt` |
| Android TV | Excluded | Excluded | `TvLabMenuScreen.kt` |
| Wear OS | Excluded | Excluded | `LabFeaturesActivity.kt` (Wear layout) |

## Source Layout

| Directory | Key Files |
| :--- | :--- |
| `manager/.../module/discovery/` | `ModuleDiscoveryManager.kt`, `ModuleValidator.kt`, `DiscoveredModule.kt`, `GitHubModels.kt`, `DiscoveryCache.kt`, `RateLimitTracker.kt` |
| `manager/.../module/update/` | `ModuleInstaller.kt`, `ModuleUpdateScheduler.kt`, `SourceZipBuilder.kt`, `UpdateChecker.kt`, `UpdateResult.kt`, `UpdateSettingsScreen.kt`, `GitHubReleaseModels.kt` |
| `manager/.../module/catalog/` | `CatalogScreen.kt`, `TokenStore.kt` |
| `manager/.../settings/` | `LabFeaturesActivity.kt`, `TvLabMenuScreen.kt` |
