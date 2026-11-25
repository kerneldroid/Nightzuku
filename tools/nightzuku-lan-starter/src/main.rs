use std::collections::{HashMap, HashSet};
use std::env;
use std::io::{BufRead, BufReader};
use std::process::{Command, Stdio};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

const DEFAULT_PACKAGE: &str = "kerneldroid.nightzuku";
const DEFAULT_POLL_INTERVAL_SECS: u64 = 10;
const DEFAULT_COOLDOWN_SECS: u64 = 120;
const FAILURE_RETRY_BACKOFF: Duration = Duration::from_secs(15);
const MAX_IN_FLIGHT_WORKERS: usize = 8;
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

#[derive(Debug, Default)]
struct HostState {
    next_allowed: HashMap<String, Instant>,
    in_flight: HashSet<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum EndpointOutcome {
    Started,
    Failed,
}

struct InFlightGuard {
    tracker: Arc<Mutex<HostState>>,
    host: String,
    cooldown: Duration,
    outcome: EndpointOutcome,
}

impl Drop for InFlightGuard {
    fn drop(&mut self) {
        let mut state = self.tracker.lock().unwrap_or_else(|e| e.into_inner());
        state.in_flight.remove(&self.host);
        let delay = match self.outcome {
            EndpointOutcome::Started => self.cooldown,
            EndpointOutcome::Failed => self.cooldown.min(FAILURE_RETRY_BACKOFF),
        };
        state
            .next_allowed
            .insert(self.host.clone(), Instant::now() + delay);
    }
}

fn main() {
    let config = match parse_args(env::args().skip(1)) {
        Ok(Some(cfg)) => Arc::new(cfg),
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

    let tracker = Arc::new(Mutex::new(HostState::default()));

    if config.once {
        poll_once(&config, &tracker);
        return;
    }

    loop {
        if !run_track_services(&config, &tracker) {
            eprintln!(
                "[nightzuku-lan-starter] adb mdns track-services unavailable or exited; polling every {}s",
                config.poll_interval.as_secs()
            );
            poll_once(&config, &tracker);
            thread::sleep(config.poll_interval);
        } else {
            thread::sleep(Duration::from_secs(2));
        }
    }
}

fn is_valid_android_package(pkg: &str) -> bool {
    !pkg.is_empty()
        && pkg.contains('.')
        && !pkg.starts_with('.')
        && !pkg.ends_with('.')
        && !pkg.contains("..")
        && pkg
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'.' || b == b'_')
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

    if !is_valid_android_package(&package_name) {
        return Err(format!(
            "invalid Android package name '{package_name}': must use only [a-zA-Z0-9._], contain '.', and have no leading, trailing, or consecutive dots"
        ));
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
         \t--cooldown <SECS>    Minimum seconds between successful starts per host (default: {DEFAULT_COOLDOWN_SECS})\n\
         \t--allow <HOST>       Allow only this device IP/host (repeatable; env: NIGHTZUKU_ALLOW_HOSTS)\n\
         \t--once               Run a single discovery pass and exit\n\
         \t-h, --help           Show this help message"
    );
}

/// Streams `adb mdns track-services` (ADB 37.0.0+) and launches Nightzuku whenever a paired
/// Android device advertises `_adb-tls-connect._tcp` on the LAN.
fn run_track_services(config: &Arc<Config>, tracker: &Arc<Mutex<HostState>>) -> bool {
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
            let _ = dispatch_endpoint(config, ep, tracker);
        }
    }

    let status = child.wait().ok();
    saw_any_line && status.map(|s| s.success()).unwrap_or(false)
}

fn poll_once(config: &Arc<Config>, tracker: &Arc<Mutex<HostState>>) {
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

    let mut handles = Vec::new();
    for line in String::from_utf8_lossy(&output).lines() {
        if let Some(ep) = parse_mdns_line(line) {
            if let Some(handle) = dispatch_endpoint(config, ep, tracker) {
                handles.push(handle);
            }
        }
    }

    for handle in handles {
        let _ = handle.join();
    }
}

fn try_reserve_host(
    tracker: &Arc<Mutex<HostState>>,
    host: &str,
    now: Instant,
    max_workers: usize,
) -> bool {
    let mut state = tracker.lock().unwrap_or_else(|e| e.into_inner());
    if state.in_flight.contains(host) || state.in_flight.len() >= max_workers {
        return false;
    }
    if let Some(&until) = state.next_allowed.get(host) {
        if now < until {
            return false;
        }
    }
    state.in_flight.insert(host.to_string());
    true
}

fn dispatch_endpoint(
    config: &Arc<Config>,
    ep: MdnsEndpoint,
    tracker: &Arc<Mutex<HostState>>,
) -> Option<thread::JoinHandle<()>> {
    if ep.service_type != TLS_CONNECT_SERVICE {
        return None;
    }
    if !config.allow_hosts.is_empty() && !config.allow_hosts.contains(&ep.host) {
        return None;
    }

    if !try_reserve_host(tracker, &ep.host, Instant::now(), MAX_IN_FLIGHT_WORKERS) {
        return None;
    }

    let mut guard = InFlightGuard {
        tracker: Arc::clone(tracker),
        host: ep.host.clone(),
        cooldown: config.cooldown,
        outcome: EndpointOutcome::Failed,
    };
    let worker_config = Arc::clone(config);
    let target_for_log = ep.target.clone();

    match thread::Builder::new()
        .name("lan-starter-worker".to_string())
        .spawn(move || {
            guard.outcome = handle_endpoint(&worker_config, &ep);
        }) {
        Ok(handle) => Some(handle),
        Err(err) => {
            eprintln!(
                "[nightzuku-lan-starter] failed to spawn worker for {target_for_log}: {err}"
            );
            None
        }
    }
}

fn handle_endpoint(config: &Config, ep: &MdnsEndpoint) -> EndpointOutcome {
    eprintln!(
        "[nightzuku-lan-starter] discovered {} at {}",
        ep.instance, ep.target
    );

    if !adb_connect(&config.adb_bin, &ep.target) {
        return EndpointOutcome::Failed;
    }

    if is_server_running(&config.adb_bin, &ep.target) {
        eprintln!(
            "[nightzuku-lan-starter] shizuku_server already running on {}",
            ep.target
        );
        return EndpointOutcome::Started;
    }

    match launch_nightzuku(&config.adb_bin, &ep.target, &config.package_name) {
        Ok(()) => {
            eprintln!(
                "[nightzuku-lan-starter] started Nightzuku server on {}",
                ep.target
            );
            EndpointOutcome::Started
        }
        Err(err) => {
            eprintln!(
                "[nightzuku-lan-starter] failed to start Nightzuku on {}: {err}",
                ep.target
            );
            EndpointOutcome::Failed
        }
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

fn select_base_apk_path(pm_stdout: &str) -> Option<String> {
    let mut first_apk: Option<&str> = None;
    for line in pm_stdout.lines() {
        let Some(path) = line.trim().strip_prefix("package:").map(str::trim) else {
            continue;
        };
        if path.is_empty() {
            continue;
        }
        if path.ends_with("/base.apk") {
            return Some(path.to_string());
        }
        if first_apk.is_none() {
            first_apk = Some(path);
        }
    }
    first_apk.map(str::to_string)
}

fn map_android_abi_to_lib_dir(abi: &str) -> Option<&'static str> {
    match abi.trim() {
        "arm64-v8a" => Some("arm64"),
        "armeabi-v7a" => Some("arm"),
        "x86_64" => Some("x86_64"),
        "x86" => Some("x86"),
        _ => None,
    }
}

fn detect_primary_lib_dir(adb_bin: &str, target: &str) -> Option<&'static str> {
    let out = Command::new(adb_bin)
        .args(["-s", target, "shell", "getprop", "ro.product.cpu.abi"])
        .output()
        .ok()?;
    if !out.status.success() {
        return None;
    }
    let abi = String::from_utf8_lossy(&out.stdout);
    map_android_abi_to_lib_dir(&abi)
}

fn launch_nightzuku(adb_bin: &str, target: &str, package_name: &str) -> Result<(), String> {
    if !is_valid_android_package(package_name) {
        return Err(format!("invalid Android package name: {package_name}"));
    }

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

    let pm_stdout = String::from_utf8_lossy(&pm_out.stdout);
    let apk_path = select_base_apk_path(&pm_stdout)
        .ok_or_else(|| format!("no APK path returned for {package_name}"))?;

    let primary_lib_dir = detect_primary_lib_dir(adb_bin, target).unwrap_or("");

    // Resolve libshizuku.so using the device's primary ABI first, then fall back to globbing.
    let shell_cmd = format!(
        "APK='{apk}'; DIR=$(dirname \"$APK\"); STARTER=''; \
         if [ -n '{abi_dir}' ] && [ -x \"$DIR/lib/{abi_dir}/libshizuku.so\" ]; then \
           STARTER=\"$DIR/lib/{abi_dir}/libshizuku.so\"; \
         else \
           STARTER=$(ls \"$DIR\"/lib/*/libshizuku.so 2>/dev/null | head -n 1); \
         fi; \
         if [ -z \"$STARTER\" ]; then echo 'libshizuku.so not found' >&2; exit 1; fi; \
         \"$STARTER\" --apk=\"$APK\"",
        apk = apk_path.replace('\'', "'\\''"),
        abi_dir = primary_lib_dir,
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

fn split_last_whitespace(s: &str) -> Option<(&str, &str)> {
    let trimmed = s.trim_end();
    let idx = trimmed.rfind(char::is_whitespace)?;
    let ws_len = trimmed[idx..].chars().next()?.len_utf8();
    let head = trimmed[..idx].trim_end();
    let tail = &trimmed[idx + ws_len..];
    if head.is_empty() || tail.is_empty() {
        return None;
    }
    Some((head, tail))
}

/// Parses a single line from `adb mdns services` or `adb mdns track-services`:
/// `adb-XXXX-YYYY\t_adb-tls-connect._tcp\t192.168.1.42:37891`
/// Parses from the right so instance names containing spaces are preserved intact.
fn parse_mdns_line(line: &str) -> Option<MdnsEndpoint> {
    let trimmed = line.trim();
    if trimmed.is_empty() || trimmed.starts_with("List of") {
        return None;
    }

    let (before_target, target) = split_last_whitespace(trimmed)?;
    let (instance, raw_service_type) = split_last_whitespace(before_target)?;

    let service_type = raw_service_type.trim_end_matches('.');
    if !service_type.starts_with('_') {
        return None;
    }

    let host = extract_host(target)?;

    Some(MdnsEndpoint {
        instance: instance.to_string(),
        service_type: service_type.to_string(),
        target: target.to_string(),
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
    fn parses_instance_name_with_spaces() {
        let ep = parse_mdns_line("My Pixel 9 Pro\t_adb-tls-connect._tcp\t192.168.1.50:41235")
            .expect("should parse instance with spaces");
        assert_eq!(ep.instance, "My Pixel 9 Pro");
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

    #[test]
    fn validates_android_package_names() {
        assert!(is_valid_android_package("kerneldroid.nightzuku"));
        assert!(is_valid_android_package("com.example.app_1"));
        assert!(!is_valid_android_package(""));
        assert!(!is_valid_android_package("nodot"));
        assert!(!is_valid_android_package(".leading.dot"));
        assert!(!is_valid_android_package("trailing.dot."));
        assert!(!is_valid_android_package("double..dot"));
        assert!(!is_valid_android_package("com.example;id"));
        assert!(!is_valid_android_package("com.example $(id)"));
    }

    #[test]
    fn selects_base_apk_over_split_apks() {
        let split_output = "\
package:/data/app/~~abc==/kerneldroid.nightzuku-xyz==/split_config.arm64_v8a.apk\n\
package:/data/app/~~abc==/kerneldroid.nightzuku-xyz==/base.apk\n";
        assert_eq!(
            select_base_apk_path(split_output).as_deref(),
            Some("/data/app/~~abc==/kerneldroid.nightzuku-xyz==/base.apk")
        );

        let single_output = "package:/data/app/kerneldroid.nightzuku/app.apk\n";
        assert_eq!(
            select_base_apk_path(single_output).as_deref(),
            Some("/data/app/kerneldroid.nightzuku/app.apk")
        );
    }

    #[test]
    fn maps_android_abi_names() {
        assert_eq!(map_android_abi_to_lib_dir("arm64-v8a\r\n"), Some("arm64"));
        assert_eq!(map_android_abi_to_lib_dir("armeabi-v7a"), Some("arm"));
        assert_eq!(map_android_abi_to_lib_dir("x86_64"), Some("x86_64"));
        assert_eq!(map_android_abi_to_lib_dir("x86"), Some("x86"));
        assert_eq!(map_android_abi_to_lib_dir("riscv64"), None);
    }
}
