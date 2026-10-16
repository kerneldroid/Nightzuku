#!/bin/bash
echo "Testing Nightzuku Connector..."
OUTPUT=$(adb shell content query --uri content://kerneldroid.nightzuku.connector)
echo "Content Provider Output: $OUTPUT"

if [[ $OUTPUT == *"command="* ]]; then
    CMD=$(echo "$OUTPUT" | grep -o 'command=.*' | cut -d= -f2-)
    echo "Executing command from provider: $CMD"
    adb shell "$CMD"
else
    echo "No command found. Please enable 'Nightzuku Connectors' in Lab Features."
    adb shell am start -n kerneldroid.nightzuku/moe.shizuku.manager.settings.LabFeaturesActivity
fi