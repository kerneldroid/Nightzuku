use std::collections::{HashMap, HashSet};
use std::env;
use std::io::{BufRead, BufReader};
use std::process::{Command, Stdio};
use std::thread;
use std::time::{Duration, Instant};

const DEFAULT_PACKAGE: &str = "kerneldroid.nightzuku";
const DEFAULT_POLL_INTERVAL_SECS: u64 = 10;
const DEFAULT_COOLDOWN_SECS: u64 = 120;
const TLS_CONNECT_SERVICE: &str = "_adb-tls-connect._tcp";

#[derive(Debug, Clone)]
struct Config {
    adb_bin: String,
    package_name: String,
    poll_interval: Duration,
    cooldown: Duration,
    allow_hosts: HashSet<String>,
    once: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct MdnsEndpoint {
    instance: String,
    service_type: String,
    target: String,
    host: String,
}

fn main() {
    let config = match parse_args(env::args().skip(1)) {
        Ok(Some(cfg)) => cfg,
        Ok(None) => return,
        Err(msg) => {
            eprintln!("error: {msg}");
            std::process::exit(2);
        }
    };

    eprintln!(
        "[nightzuku-lan-starter] starting (adb={}, package={}, cooldown={}s, allow_hosts={})",
        config.adb_bin,
        config.package_name,
        config.cooldown.as_secs(),
        if config.allow_hosts.is_empty() {
            "any".to_string()
        } else {
            config.allow_hosts.len().to_string()
        }
    );

    let mut last_attempt: HashMap<String, Instant> = HashMap::new();

    if config.once {
        poll_once(&config, &mut last_attempt);
        return;
    }

    loop {
        if !run_track_services(&config, &mut last_attempt) {
            eprintln!(
                "[nightzuku-lan-starter] adb mdns track-services unavailable or exited; polling every {}s",
                config.poll_interval.as_secs()
            );
            poll_once(&config, &mut last_attempt);
            thread::sleep(config.poll_interval);
        } else {
            thread::sleep(Duration::from_secs(2));
        }
    }
}

fn parse_args<I: IntoIterator<Item = String>>(args: I) -> Result<Option<Config>, String> {
    let mut adb_bin = env::var("NIGHTZUKU_ADB_BIN").unwrap_or_else(|_| "adb".to_string());
    let mut package_name =
        env::var("NIGHTZUKU_PACKAGE").unwrap_or_else(|_| DEFAULT_PACKAGE.to_string());
    let mut poll_secs = DEFAULT_POLL_INTERVAL_SECS;
    let mut cooldown_secs = DEFAULT_COOLDOWN_SECS;
    let mut allow_hosts = HashSet::new();
    let mut once = false;

    if let Ok(env_hosts) = env::var("NIGHTZUKU_ALLOW_HOSTS") {
        for item in env_hosts.split(',').map(str::trim).filter(|s| !s.is_empty()) {
            allow_hosts.insert(item.to_string());
        }
    }

    let mut iter = args.into_iter();
    while let Some(arg) = iter.next() {
        match arg.as_str() {
            "-h" | "--help" => {
                print_usage();
                return Ok(None);
            }
            "--adb" => {
                adb_bin = iter
                    .next()
                    .ok_or_else(|| "--adb requires a path argument".to_string())?;
            }
            "--package" => {
                package_name = iter
                    .next()
                    .ok_or_else(|| "--package requires an Android package name".to_string())?;
            }
            "--interval" => {
                let raw = iter
                    .next()
                    .ok_or_else(|| "--interval requires seconds".to_string())?;
                poll_secs = raw
                    .parse::<u64>()
                    .map_err(|_| format!("invalid --interval value: {raw}"))?;
            }
            "--cooldown" => {
                let raw = iter
                    .next()
                    .ok_or_else(|| "--cooldown requires seconds".to_string())?;
                cooldown_secs = raw
                    .parse::<u64>()
                    .map_err(|_| format!("invalid --cooldown value: {raw}"))?;
            }
            "--allow" => {
                let raw = iter
                    .next()
                    .ok_or_else(|| "--allow requires an IP or host".to_string())?;
                allow_hosts.insert(raw);
            }
            "--once" => {
                once = true;
            }
            other => {
                return Err(format!("unknown argument: {other}"));
            }
        }
    }

    Ok(Some(Config {
        adb_bin,
        package_name,
        poll_interval: Duration::from_secs(poll_secs.max(1)),
        cooldown: Duration::from_secs(cooldown_secs),
        allow_hosts,
        once,
    }))
}

fn print_usage() {
    println!(
        "nightzuku-lan-starter - LAN/OpenWrt/PC mDNS auto-starter for Nightzuku\n\n\
         USAGE:\n\
         \tnightzuku-lan-starter [OPTIONS]\n\n\
         OPTIONS:\n\
         \t--adb <PATH>         Path to adb binary (env: NIGHTZUKU_ADB_BIN, default: adb)\n\
         \t--package <PKG>      Nightzuku manager package (env: NIGHTZUKU_PACKAGE, default: {DEFAULT_PACKAGE})\n\
         \t--interval <SECS>    Fallback polling interval when track-services is unavailable (default: {DEFAULT_POLL_INTERVAL_SECS})\n\
         \t--cooldown <SECS>    Minimum seconds between launch attempts per host (default: {DEFAULT_COOLDOWN_SECS})\n\
         \t--allow <HOST>       Allow only this device IP/host (repeatable; env: NIGHTZUKU_ALLOW_HOSTS)\n\
         \t--once               Run a single discovery pass and exit\n\
         \t-h, --help           Show this help message"
    );
}

/// Streams `adb mdns track-services` (ADB 37.0.0+) and launches Nightzuku whenever a paired
/// Android device advertises `_adb-tls-connect._tcp` on the LAN.
fn run_track_services(config: &Config, last_attempt: &mut HashMap<String, Instant>) -> bool {
    let mut child = match Command::new(&config.adb_bin)
        .args(["mdns", "track-services"])
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
    {
        Ok(c) => c,
        Err(e) => {
            eprintln!("[nightzuku-lan-starter] failed to spawn adb: {e}");
            return false;
        }
    };

    let stdout = match child.stdout.take() {
        Some(s) => s,
        None => {
            let _ = child.kill();
            let _ = child.wait();
            return false;
        }
    };

    let reader = BufReader::new(stdout);
    let mut saw_any_line = false;
    for line in reader.lines().map_while(Result::ok) {
        saw_any_line = true;
        if let Some(ep) = parse_mdns_line(&line) {
            handle_endpoint(config, &ep, last_attempt);
        }
    }

    let status = child.wait().ok();
    saw_any_line && status.map(|s| s.success()).unwrap_or(false)
}

fn poll_once(config: &Config, last_attempt: &mut HashMap<String, Instant>) {
    let output = match Command::new(&config.adb_bin)
        .args(["mdns", "services"])
        .output()
    {
        Ok(o) if o.status.success() => o.stdout,
        Ok(o) => {
            eprintln!(
                "[nightzuku-lan-starter] adb mdns services failed: {}",
                String::from_utf8_lossy(&o.stderr).trim()
            );
            return;
        }
        Err(e) => {
            eprintln!("[nightzuku-lan-starter] failed to execute adb mdns services: {e}");
            return;
        }
    };

    for line in String::from_utf8_lossy(&output).lines() {
        if let Some(ep) = parse_mdns_line(line) {
            handle_endpoint(config, &ep, last_attempt);
        }
    }
}

fn handle_endpoint(
    config: &Config,
    ep: &MdnsEndpoint,
    last_attempt: &mut HashMap<String, Instant>,
) {
    if ep.service_type != TLS_CONNECT_SERVICE {
        return;
    }
    if !config.allow_hosts.is_empty() && !config.allow_hosts.contains(&ep.host) {
        return;
    }

    let now = Instant::now();
    if let Some(prev) = last_attempt.get(&ep.host) {
        if now.duration_since(*prev) < config.cooldown {
            return;
        }
    }
    last_attempt.insert(ep.host.clone(), now);

    eprintln!(
        "[nightzuku-lan-starter] discovered {} at {}",
        ep.instance, ep.target
    );

    if !adb_connect(&config.adb_bin, &ep.target) {
        return;
    }

    if is_server_running(&config.adb_bin, &ep.target) {
        eprintln!(
            "[nightzuku-lan-starter] shizuku_server already running on {}",
            ep.target
        );
        return;
    }

    match launch_nightzuku(&config.adb_bin, &ep.target, &config.package_name) {
        Ok(()) => eprintln!(
            "[nightzuku-lan-starter] started Nightzuku server on {}",
            ep.target
        ),
        Err(err) => eprintln!(
            "[nightzuku-lan-starter] failed to start Nightzuku on {}: {err}",
            ep.target
        ),
    }
}

fn adb_connect(adb_bin: &str, target: &str) -> bool {
    match Command::new(adb_bin).args(["connect", target]).output() {
        Ok(out) => {
            let stdout = String::from_utf8_lossy(&out.stdout);
            let ok = out.status.success()
                && (stdout.contains("connected to") || stdout.contains("already connected"));
            if !ok {
                eprintln!(
                    "[nightzuku-lan-starter] adb connect {target} did not succeed: {}",
                    stdout.trim()
                );
            }
            ok
        }
        Err(e) => {
            eprintln!("[nightzuku-lan-starter] adb connect {target} error: {e}");
            false
        }
    }
}

fn is_server_running(adb_bin: &str, target: &str) -> bool {
    Command::new(adb_bin)
        .args(["-s", target, "shell", "pidof", "shizuku_server"])
        .output()
        .map(|out| out.status.success() && !String::from_utf8_lossy(&out.stdout).trim().is_empty())
        .unwrap_or(false)
}

fn launch_nightzuku(adb_bin: &str, target: &str, package_name: &str) -> Result<(), String> {
    let pm_out = Command::new(adb_bin)
        .args(["-s", target, "shell", "pm", "path", package_name])
        .output()
        .map_err(|e| format!("pm path failed: {e}"))?;

    if !pm_out.status.success() {
        return Err(format!(
            "package {package_name} not found: {}",
            String::from_utf8_lossy(&pm_out.stderr).trim()
        ));
    }

    let apk_path = String::from_utf8_lossy(&pm_out.stdout)
        .lines()
        .find_map(|line| line.trim().strip_prefix("package:"))
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .ok_or_else(|| format!("no APK path returned for {package_name}"))?
        .to_string();

    // Resolve libshizuku.so relative to the installed APK directory so ABI differences
    // (arm64 / arm / x86_64) are handled automatically on the device.
    let shell_cmd = format!(
        "APK='{apk}'; DIR=$(dirname \"$APK\"); \
         STARTER=$(ls \"$DIR\"/lib/*/libshizuku.so 2>/dev/null | head -n 1); \
         if [ -z \"$STARTER\" ]; then echo 'libshizuku.so not found' >&2; exit 1; fi; \
         \"$STARTER\" --apk=\"$APK\"",
        apk = apk_path.replace('\'', "'\\''")
    );

    let run = Command::new(adb_bin)
        .args(["-s", target, "shell", &shell_cmd])
        .output()
        .map_err(|e| format!("starter execution failed: {e}"))?;

    if run.status.success() {
        Ok(())
    } else {
        Err(format!(
            "exit={:?} stderr={}",
            run.status.code(),
            String::from_utf8_lossy(&run.stderr).trim()
        ))
    }
}

/// Parses a single line from `adb mdns services` or `adb mdns track-services`:
/// `adb-XXXX-YYYY\t_adb-tls-connect._tcp\t192.168.1.42:37891`
fn parse_mdns_line(line: &str) -> Option<MdnsEndpoint> {
    let trimmed = line.trim();
    if trimmed.is_empty() || trimmed.starts_with("List of") {
        return None;
    }
    let parts: Vec<&str> = trimmed.split_whitespace().collect();
    if parts.len() < 3 {
        return None;
    }

    let instance = parts[0].to_string();
    let service_type = parts[1].trim_end_matches('.').to_string();
    let target = parts[2].to_string();
    let host = extract_host(&target)?;

    Some(MdnsEndpoint {
        instance,
        service_type,
        target,
        host,
    })
}

fn extract_host(target: &str) -> Option<String> {
    if let Some(rest) = target.strip_prefix('[') {
        let end = rest.find("]:")?;
        let host = &rest[..end];
        let port = &rest[end + 2..];
        if !host.is_empty() && port.parse::<u16>().is_ok() {
            return Some(host.to_string());
        }
        return None;
    }
    let (host, port) = target.rsplit_once(':')?;
    if !host.is_empty() && port.parse::<u16>().is_ok() {
        Some(host.to_string())
    } else {
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_ipv4_tls_connect_line() {
        let ep = parse_mdns_line("adb-R5CT123-abc123\t_adb-tls-connect._tcp\t192.168.1.50:41235")
            .expect("should parse");
        assert_eq!(ep.instance, "adb-R5CT123-abc123");
        assert_eq!(ep.service_type, "_adb-tls-connect._tcp");
        assert_eq!(ep.target, "192.168.1.50:41235");
        assert_eq!(ep.host, "192.168.1.50");
    }

    #[test]
    fn parses_ipv6_and_trailing_dot() {
        let ep = parse_mdns_line("adb-dev\t_adb-tls-connect._tcp.\t[fe80::1]:42001")
            .expect("should parse");
        assert_eq!(ep.service_type, "_adb-tls-connect._tcp");
        assert_eq!(ep.host, "fe80::1");
    }

    #[test]
    fn ignores_header_and_invalid_ports() {
        assert!(parse_mdns_line("List of discovered mdns services").is_none());
        assert!(parse_mdns_line("adb-dev\t_adb-tls-connect._tcp\t192.168.1.50:notaport").is_none());
    }
}
