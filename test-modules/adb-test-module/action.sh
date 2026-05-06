#!/system/bin/sh
echo "ADB Test Module action"
echo "MODDIR=$MODDIR"
echo "SHIZUKU_MODULE_ID=$SHIZUKU_MODULE_ID"
echo "SHIZUKU_MODULE_MODE=$SHIZUKU_MODULE_MODE"
echo "uid=$(id)"
echo "sdk=$(getprop ro.build.version.sdk)"
cmd package list packages moe.shizuku.privileged.api 2>/dev/null || true
