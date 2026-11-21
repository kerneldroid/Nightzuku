# Nightzuku LAN mDNS Auto-Starter

A zero-dependency Rust daemon for a home router (OpenWrt), Raspberry Pi, or workstation that automatically starts the Nightzuku server whenever a paired Android device rejoins your home Wi-Fi after a reboot.

## Why This Exists

1. **Android 17+ ADB Wi-Fi 2.0 compatible**: When your phone boots and connects to a trusted Wi-Fi network, Android 17 automatically re-enables `adbd` and advertises `_adb-tls-connect._tcp` via mDNS.
2. **Immune to on-device restrictions**: Bypasses Android Doze, background service limits, `ACCESS_LOCAL_NETWORK` prompts, and even a potential future AOSP restriction binding `adbd` exclusively to `wlan0` (blocking `127.0.0.1` loopback connections).
3. **Zero external crate dependencies**: Uses only the Rust standard library (`std`), making cross-compilation for OpenWrt/ARM/MIPS straightforward.

## Prerequisites

- Android SDK Platform-Tools (`adb`, version 37.0.0+ recommended for streaming `adb mdns track-services`; older versions automatically fall back to polling `adb mdns services`).
- Pair the host once with your phone via Wireless Debugging (`adb pair <ip>:<pairing_port>`), and mark your home Wi-Fi as trusted ("Always allow on this network").

## Build & Run

```bash
cargo build --release
./target/release/nightzuku-lan-starter --adb "$(which adb)"
```

Restrict to specific device IPs if desired:

```bash
./target/release/nightzuku-lan-starter --allow 192.168.1.42
```

## Service Installation

- **Linux (`systemd`)**: Copy `target/release/nightzuku-lan-starter` to `/usr/local/bin/` and install `systemd/nightzuku-lan-starter.service`.
- **OpenWrt (`procd`)**: Cross-compile for your router target, install `nightzuku-lan-starter` to `/usr/bin/`, and copy `openwrt/nightzuku-lan-starter.init` to `/etc/init.d/nightzuku-lan-starter`.
