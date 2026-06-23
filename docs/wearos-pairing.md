# Wear OS Setup and Pairing Guide

This guide describes how to pair and start Nightzuku on Wear OS 5+ devices (such as Galaxy Watch 7).

## Instructions

1. Enable Developer Options: Go to Settings -> System -> About -> Software Info. Tap Software Version 7 times.
2. Enable Debugging: In Settings -> Developer Options, enable ADB debugging and Wireless debugging.
3. Device Pairing: Tap Wireless debugging -> Pair new device. Keep the screen active and the app in the foreground. Open Nightzuku, tap Pairing, and input the port and code.
4. Start Service: Go back to the main Wireless debugging screen, note the new port, and tap Start in Nightzuku.

## Troubleshooting

- SSL Handshake Errors: Go to Developer options -> Revoke USB debugging authorizations, reboot the watch, and pair again.
- Background Freezing: Ensure Nightzuku's battery optimization is set to Unrestricted to prevent the OS from freezing background service initialization.
- Connection Timed Out: Verify that the watch is connected to the same Wi-Fi network.
