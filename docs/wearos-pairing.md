# Wear OS Wireless ADB Pairing Guide

Procedure for pairing and starting `shizuku_server` on Wear OS 5+ devices.

## Pairing Procedure

1. Enable Developer Options: **Settings -> System -> About -> Software Info**, tap **Software Version** 7 times.
2. Enable **ADB debugging** and **Wireless debugging** under **Settings -> Developer Options**.
3. Open **Wireless debugging -> Pair new device**. Keep the screen awake and the pairing code visible.
4. Open Nightzuku, select **Pairing**, and enter the displayed port and 6-digit pairing code.
5. Return to the main **Wireless debugging** screen, note the active connection port, and select **Start** in Nightzuku.

## Troubleshooting

| Symptom | Likely Cause | Resolution |
| :--- | :--- | :--- |
| TLS / SSL handshake error | Stale ADB RSA key authorization | **Developer options -> Revoke USB debugging authorizations**, reboot watch, and pair again |
| Service fails to initialize in background | OS battery optimization / app standby freeze | Set Nightzuku battery usage to **Unrestricted** |
| Connection timed out | Watch disconnected from Wi-Fi or wrong port | Verify active Wi-Fi connection and confirm the current Wireless debugging port (not the pairing port) |
