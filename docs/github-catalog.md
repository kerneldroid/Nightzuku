# GitHub Catalog

Online module discovery system for Nightzuku ADB modules.

## How It Works

Modules are discovered via GitHub Topics search (`topic:adb-modules`). Module authors tag their repos with this topic. No central registry required.

**Flow:**
1. User enters GitHub PAT (Personal Access Token) on first launch
2. App searches GitHub: `GET /search/repositories?q=topic:adb-modules`
3. Each repo is validated — checks for `module.prop` in root or subdirectories
4. Modules are cached locally (6h TTL)
5. User browses catalog, selects install mode (sources or release ZIP)

## Module Limits

- Max 4 modules per user (across all their repos)
- Official owner (`kerneldroid`) is exempt from limits
- Repos from users exceeding the limit are skipped during discovery

## Install Modes

**From Sources:**
- Fetches files from GitHub Contents API
- Resolves default branch dynamically (not hardcoded)
- Builds clean ZIP: module.prop, *.sh, banner.*, webui/** (all files)
- Installs via existing `AdbModuleManager.install()`

**From Release ZIP:**
- Downloads asset from GitHub Releases
- Matches asset by module ID pattern
- Installs directly

## Security

- Token stored encrypted with AndroidKeyStore (`AES/GCM/NoPadding`); legacy plaintext tokens are migrated on startup
- Token validated for `ghp_`/`github_pat_` prefix (30+ chars)
- Token NOT logged to logcat
- Token only sent to `api.github.com` / `github.com` domains (not leaked to updateJson URLs)

## Platform Support

| Platform | Catalog UI | Token Settings | Lab Menu |
|----------|-----------|----------------|----------|
| Phone | CatalogScreen.kt | UpdateSettingsScreen.kt | Via SettingsActivity |
| Android TV | Not available (modules UI removed) | Not available | TvLabMenuScreen.kt |
| WearOS | Not available (modules UI removed) | Not available | Via WearSettingsScreen |

## API Usage

- Unauthenticated: 60 req/hour (limited)
- With PAT: 5,000 req/hour
- Rate limit tracked across all API calls
- ETag conditional requests used where possible (304 = free)

## Files

```
module/discovery/
├── ModuleDiscoveryManager.kt   # Orchestrator: search → validate → cache
├── ModuleValidator.kt          # module.prop validation + nested dir scan
├── DiscoveredModule.kt         # Data class for discovered modules
├── GitHubModels.kt             # GitHub API response models
├── DiscoveryCache.kt           # SharedPreferences cache (6h TTL)
└── RateLimitTracker.kt         # GitHub rate limit tracking

module/update/
├── ModuleInstaller.kt          # Install flow (sources + release)
├── SourceZipBuilder.kt         # Clean ZIP from source files
├── UpdateChecker.kt            # Check for module updates
├── UpdateResult.kt             # Update check result model
├── UpdateSettingsScreen.kt     # Phone settings for updates
└── GitHubReleaseModels.kt      # GitHub Release API models

module/catalog/
├── CatalogScreen.kt            # Phone catalog UI
├── TvCatalogScreen.kt          # TV catalog UI
├── TokenStore.kt               # Token storage

settings/
├── TvLabMenuScreen.kt          # TV Lab Features menu
```
