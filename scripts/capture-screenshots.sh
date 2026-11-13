#!/usr/bin/env bash
#
# Capture Nightzuku screenshots on a running emulator.
#
# Usage: ./scripts/capture-screenshots.sh <phone|wearos|tv>
#
# Expects:
#   - a booted emulator visible to adb
#   - artifacts/apk/nightzuku-debug.apk
#   - artifacts/apk/shizutest-debug.apk
#
# The emulator gets the test module installed into the manager's module directory,
# full module access enabled, and shizutest pre-granted in the server config so the
# UI shows "Authorized 1 application" instead of an empty app list.

set -euo pipefail

SCREEN="${1:?usage: capture-screenshots.sh <phone|wearos|tv>}"
ADB="${ADB:-adb}"
OUT_DIR="artifacts/screenshots/${SCREEN}"
MANAGER_PKG="kerneldroid.nightzuku"
TEST_PKG="kerneldroid.shizutest"

MANAGER_APK="artifacts/apk/nightzuku-debug.apk"
TEST_APK="artifacts/apk/shizutest-debug.apk"

mkdir -p "$OUT_DIR"

log() { echo "[screenshots:${SCREEN}] $*"; }

adb_shell() { "$ADB" shell "$@"; }

start_activity() {
    local activity="$1"
    shift
    "$ADB" shell am force-stop "$MANAGER_PKG" || true
    if ! timeout 60 "$ADB" shell am start -W -n "${MANAGER_PKG}/${activity}" "$@" >/dev/null; then
        log "warning: could not start ${activity}"
    fi
    sleep 1
}

shot() {
    local name="$1"
    "$ADB" exec-out screencap -p > "${OUT_DIR}/${name}"
    if [ ! -s "${OUT_DIR}/${name}" ]; then
        echo "[screenshots:${SCREEN}] empty screenshot: ${name}" >&2
        exit 1
    fi
    log "captured ${name}"
}

wait_text() {
    local needle="$1"
    local seconds="${2:-45}"
    local deadline=$((SECONDS + seconds))
    while [ "$SECONDS" -lt "$deadline" ]; do
        if adb_shell uiautomator dump /sdcard/window.xml >/dev/null 2>&1; then
            if adb_shell cat /sdcard/window.xml 2>/dev/null | grep -Fq "$needle"; then
                return 0
            fi
        fi
        sleep 2
    done
    return 1
}

scroll_once() {
    local size width height
    size="$(adb_shell wm size | tr -d '\r')"
    width="$(printf '%s\n' "$size" | sed -n 's/.*: \([0-9]*\)x\([0-9]*\).*/\1/p')"
    height="$(printf '%s\n' "$size" | sed -n 's/.*: \([0-9]*\)x\([0-9]*\).*/\2/p')"
    [ -n "$width" ] && [ -n "$height" ] || return 0
    adb_shell input swipe "$((width / 2))" "$((height * 3 / 4))" "$((width / 2))" "$((height / 4))" 500
}

tap_text() {
    local needle="$1"
    adb_shell uiautomator dump /sdcard/window.xml >/dev/null 2>&1 || return 1
    local line
    line="$(adb_shell cat /sdcard/window.xml 2>/dev/null | tr '>' '\n' | grep -F "$needle" | head -n 1)"
    [ -n "$line" ] || return 1
    local bounds x1 y1 x2 y2
    bounds="$(printf '%s\n' "$line" | sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/p')"
    [ -n "$bounds" ] || return 1
    read -r x1 y1 x2 y2 <<< "$bounds"
    adb_shell input tap "$(( (x1 + x2) / 2 ))" "$(( (y1 + y2) / 2 ))"
}

# ---------------------------------------------------------------- preparation

"$ADB" wait-for-device
adb_shell settings put global window_animation_scale 0 || true
adb_shell settings put global transition_animation_scale 0 || true
adb_shell settings put global animator_duration_scale 0 || true
adb_shell settings put system screen_off_timeout 2147483647 || true
adb_shell svc power stayon true || true
adb_shell input keyevent KEYCODE_WAKEUP || true
adb_shell wm dismiss-keyguard || true

log "installing APKs"
# Fresh installs keep the app data directory at 0700, which run-as requires.
"$ADB" uninstall "$MANAGER_PKG" >/dev/null 2>&1 || true
"$ADB" uninstall "$TEST_PKG" >/dev/null 2>&1 || true
"$ADB" install -g "$MANAGER_APK" >/dev/null
"$ADB" install -g "$TEST_APK" >/dev/null

if [ "$SCREEN" = "phone" ]; then
    log "installing the ADB test module"
    tar -C test-modules -cf - adb-test-module \
        | adb_shell "run-as ${MANAGER_PKG} sh -c 'mkdir -p files/adb_modules && cd files/adb_modules && rm -rf adb-test-module && tar -xf -'"

    log "trusting the test module and enabling full module access"
    adb_shell "run-as ${MANAGER_PKG} sh -c 'mkdir -p /data/user_de/0/${MANAGER_PKG}/shared_prefs && cat > /data/user_de/0/${MANAGER_PKG}/shared_prefs/settings.xml'" <<'XML'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="adb_modules_access_mode">full</string>
    <set name="adb_modules_trusted_modules">
        <string>adb-test-module</string>
    </set>
</map>
XML
fi

log "pre-granting shizutest in the server config"
TEST_UID="$(adb_shell pm list packages -U "$TEST_PKG" | tr -d '\r' \
    | sed -n 's/.*uid:\([0-9]\{1,\}\).*/\1/p' | head -n 1)"
if [ -z "$TEST_UID" ]; then
    echo "[screenshots:${SCREEN}] could not resolve ${TEST_PKG} uid" >&2
    exit 1
fi
adb_shell "mkdir -p /data/user_de/0/com.android.shell"
adb_shell "cat > /data/user_de/0/com.android.shell/shizuku.json" <<JSON
{"version":3,"packages":[{"uid":${TEST_UID},"flags":2,"packages":["${TEST_PKG}"],"lastConnectedAt":0}],"nightDogEnabled":false,"accessPaused":false}
JSON
adb_shell chmod 600 /data/user_de/0/com.android.shell/shizuku.json

adb_shell "rm -f /data/local/tmp/nightzuku_smart.log /data/user_de/0/com.android.shell/nightzuku_smart.log" || true

log "starting the Nightzuku server as shell"
MANAGER_APK_PATH="$(adb_shell pm path "$MANAGER_PKG" | head -n 1 | sed 's/^package://' | tr -d '\r')"
DEVICE_ABI="$(adb_shell getprop ro.product.cpu.abi | tr -d '\r')"
STARTER="$(dirname "$MANAGER_APK_PATH")/lib/${DEVICE_ABI}/libshizuku.so"
if ! adb_shell "test -f '$STARTER'"; then
    echo "[screenshots:${SCREEN}] starter not found at ${STARTER}" >&2
    exit 1
fi
adb_shell "'$STARTER' --apk='$MANAGER_APK_PATH'" || true

for _ in $(seq 1 60); do
    if adb_shell pidof shizuku_server >/dev/null 2>&1; then
        log "shizuku_server is up"
        break
    fi
    sleep 1
done
if ! adb_shell pidof shizuku_server >/dev/null 2>&1; then
    echo "[screenshots:${SCREEN}] shizuku_server did not start" >&2
    exit 1
fi

# ---------------------------------------------------------------- capture

case "$SCREEN" in
    phone)
        MAIN_SHOT="main.png"
        APPS_SHOT="apps.png"
        MODULES_SHOT="modules.png"
        SETTINGS_SHOT="settings.png"
        ;;
    wearos)
        MAIN_SHOT="main.png"
        APPS_SHOT="apps.png"
        MODULES_SHOT=""
        SETTINGS_SHOT="settings.png"
        ;;
    tv)
        MAIN_SHOT="main.png"
        APPS_SHOT=""
        MODULES_SHOT=""
        SETTINGS_SHOT="settings.png"
        ;;
    *)
        echo "unknown screen profile: $SCREEN" >&2
        exit 1
        ;;
esac

dismiss_dialogs() {
    for _ in 1 2 3 4; do
        if tap_text "Got it"; then sleep 1; continue; fi
        if tap_text "Close"; then sleep 1; continue; fi
        break
    done
}

log "capturing home"
start_activity moe.shizuku.manager.MainActivity
sleep 2
dismiss_dialogs
wait_text "is running" 60 || log "warning: service status text not found"
if [ "$SCREEN" != "wearos" ]; then
    wait_text "Authorized 1 application" 30 || log "warning: authorized app count not confirmed"
fi
sleep 2
shot "$MAIN_SHOT"

if [ -n "$APPS_SHOT" ]; then
    log "capturing application management"
    start_activity moe.shizuku.manager.management.ApplicationManagementActivity
    wait_text "ShizuTest" 45 || log "warning: ShizuTest row not found"
    sleep 2
    shot "$APPS_SHOT"
fi

if [ -n "$MODULES_SHOT" ]; then
    log "capturing ADB Modules"
    start_activity moe.shizuku.manager.module.ModulesActivity
    if ! wait_text "ADB Test Module" 20; then
        for _ in 1 2 3; do
            scroll_once
            sleep 2
            wait_text "ADB Test Module" 5 && break
        done
    fi
    sleep 2
    shot "$MODULES_SHOT"
fi

if [ "$SCREEN" = "phone" ]; then
    log "capturing Module WebUI"
    start_activity moe.shizuku.manager.module.ModuleWebViewActivity --es module_id adb-test-module
    # The trusted test module pulls BeerCSS from jsDelivr; give it time to style the page.
    wait_text "WebUI loaded" 20 || true
    sleep 6
    shot module-webui.png
fi

log "capturing Settings"
start_activity moe.shizuku.manager.settings.SettingsActivity
sleep 3
shot "$SETTINGS_SHOT"

if [ "$SCREEN" = "wearos" ]; then
    log "capturing the Wear OS theme dialog"
    if tap_text "Dark theme"; then
        sleep 2
        shot dialog.png
    else
        log "warning: could not open the theme dialog"
    fi
fi

log "done"
