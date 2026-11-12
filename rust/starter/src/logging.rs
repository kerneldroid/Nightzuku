#[cfg(target_os = "android")]
use std::ffi::{CString, c_char};
#[cfg(not(target_os = "android"))]
use std::ffi::CString;
use std::ffi::c_int;
use std::fs::{File, OpenOptions};
use std::io::{Read, Seek, SeekFrom, Write};
#[cfg(unix)]
use std::os::unix::fs::OpenOptionsExt;
#[cfg(unix)]
use std::os::unix::io::AsRawFd;
use std::ptr;
use std::sync::OnceLock;
use std::time::{SystemTime, UNIX_EPOCH};

#[cfg(target_os = "android")]
const TAG: &std::ffi::CStr = c"Shizuku";

const ANDROID_LOG_VERBOSE: c_int = 2;
const ANDROID_LOG_DEBUG: c_int = 3;
const ANDROID_LOG_INFO: c_int = 4;
const ANDROID_LOG_WARN: c_int = 5;
const ANDROID_LOG_ERROR: c_int = 6;

const SMART_LOG_TMP_PATH: &str = "/data/local/tmp/nightzuku_smart.log";
const SMART_LOG_SHELL_PATH: &str = "/data/user_de/0/com.android.shell/nightzuku_smart.log";
const DEFAULT_MANAGER_PACKAGE: &str = "kerneldroid.nightzuku";

/// Stealth builds use a randomized application id; the package is resolved from the APK path
/// before any snapshot is written.
static MANAGER_PACKAGE: OnceLock<String> = OnceLock::new();

pub fn set_manager_package(package: &str) {
    let _ = MANAGER_PACKAGE.set(package.to_string());
}

pub fn manager_package() -> &'static str {
    MANAGER_PACKAGE
        .get()
        .map(String::as_str)
        .unwrap_or(DEFAULT_MANAGER_PACKAGE)
}

fn ext_emulated_path() -> String {
    format!(
        "/storage/emulated/0/Android/data/{}/cache/nightzuku_smart.log",
        manager_package()
    )
}

fn app_de_cache_dir() -> String {
    format!("/data/user_de/0/{}/cache", manager_package())
}

fn app_de_cache_path() -> String {
    format!("{}/nightzuku_smart.log", app_de_cache_dir())
}

fn app_ce_cache_dir() -> String {
    format!("/data/data/{}/cache", manager_package())
}

fn app_ce_cache_path() -> String {
    format!("{}/nightzuku_smart.log", app_ce_cache_dir())
}

const MAX_SMART_LOG_BYTES: u64 = 128 * 1024;
const RETAIN_SMART_LOG_BYTES: usize = 64 * 1024;

#[cfg(target_os = "android")]
#[link(name = "log")]
unsafe extern "C" {
    fn __android_log_print(prio: c_int, tag: *const c_char, fmt: *const c_char, ...) -> c_int;
}

#[cfg(target_os = "android")]
fn log(prio: c_int, message: &str) {
    if let Ok(message) = CString::new(message) {
        unsafe {
            __android_log_print(prio, TAG.as_ptr(), c"%s".as_ptr(), message.as_ptr());
        }
    }
}

#[cfg(not(target_os = "android"))]
fn log(_prio: c_int, _message: &str) {}

#[allow(dead_code)]
pub fn logv(message: &str) {
    log(ANDROID_LOG_VERBOSE, message);
}

pub fn logd(message: &str) {
    log(ANDROID_LOG_DEBUG, message);
}

#[allow(dead_code)]
pub fn logi(message: &str) {
    log(ANDROID_LOG_INFO, message);
}

#[allow(dead_code)]
pub fn logw(message: &str) {
    log(ANDROID_LOG_WARN, message);
}

pub fn loge(message: &str) {
    log(ANDROID_LOG_ERROR, message);
}

pub fn current_epoch_ms() -> u128 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis())
        .unwrap_or(0)
}

/// Encodes arbitrary bytes into standard Base64 (`A-Za-z0-9+/=`) without line wraps so snapshots
/// can be passed safely through `content call --arg base64:...` without shell escaping issues.
pub fn base64_encode(input: &[u8]) -> String {
    const TABLE: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = String::with_capacity(input.len().div_ceil(3) * 4);
    for chunk in input.chunks(3) {
        let b0 = chunk[0] as u32;
        let b1 = if chunk.len() > 1 { chunk[1] as u32 } else { 0 };
        let b2 = if chunk.len() > 2 { chunk[2] as u32 } else { 0 };
        let triple = (b0 << 16) | (b1 << 8) | b2;

        out.push(TABLE[((triple >> 18) & 0x3f) as usize] as char);
        out.push(TABLE[((triple >> 12) & 0x3f) as usize] as char);
        if chunk.len() > 1 {
            out.push(TABLE[((triple >> 6) & 0x3f) as usize] as char);
        } else {
            out.push('=');
        }
        if chunk.len() > 2 {
            out.push(TABLE[(triple & 0x3f) as usize] as char);
        } else {
            out.push('=');
        }
    }
    out
}

/// Formats a structured Smart Log snapshot entry that can be parsed and merged by
/// `shizuku_server` and the Nightzuku Manager application.
pub fn format_smart_snapshot(
    timestamp_ms: u128,
    category: &str,
    severity: &str,
    summary: &str,
    details: &str,
) -> String {
    let uid = unsafe { libc::getuid() };
    let pid = unsafe { libc::getpid() };
    let sdk = crate::android::api_level();
    let preview_sdk = crate::android::preview_api_level();
    let release = crate::android::prop("ro.build.version.release").unwrap_or_else(|| "unknown".to_string());
    let codename = crate::android::prop("ro.build.version.codename").unwrap_or_else(|| "REL".to_string());
    let fingerprint = crate::android::prop("ro.build.fingerprint").unwrap_or_else(|| "unknown".to_string());
    let sanitized_summary = summary.replace('\n', " ").trim().to_string();

    format!(
        "===SMART_SNAPSHOT_BEGIN===\n\
         TIMESTAMP_MS: {timestamp_ms}\n\
         SOURCE: RUST_STARTER\n\
         CATEGORY: {category}\n\
         SEVERITY: {severity}\n\
         SUMMARY: {sanitized_summary}\n\
         DETAILS:\n\
         starter_uid={uid} starter_pid={pid} android={release} sdk={sdk} preview_sdk={preview_sdk} codename={codename}\n\
         fingerprint={fingerprint}\n\
         {}\n\
         ===SMART_SNAPSHOT_END===\n",
        details.trim()
    )
}

fn append_to_spool_file(path: &str, payload: &[u8], mode: u32) -> Option<File> {
    let mut opts = OpenOptions::new();
    opts.create(true).read(true).write(true).append(true);
    #[cfg(unix)]
    opts.mode(mode);

    let mut file = opts.open(path).ok()?;

    #[cfg(unix)]
    unsafe {
        let _ = libc::fchmod(file.as_raw_fd(), mode as libc::mode_t);
    }

    if let Ok(meta) = file.metadata()
        && meta.len() > MAX_SMART_LOG_BYTES
    {
        rotate_spool_file(&mut file);
    }

    let _ = file.write_all(payload);
    let _ = file.flush();
    Some(file)
}

/// Writes the snapshot into the Manager's internal DE/CE cache directory when running as root (`UID 0`)
/// and copies both DAC ownership (`uid:gid`) and the `security.selinux` extended attribute (`app_data_file`)
/// from the parent cache directory so `untrusted_app` can read it without SELinux denials even when
/// `shizuku_server` is dead.
#[cfg(unix)]
fn append_to_app_internal_cache_as_root(dir_path: &str, file_path: &str, payload: &[u8]) {
    let Ok(dir_c) = CString::new(dir_path) else {
        return;
    };
    let Ok(file_c) = CString::new(file_path) else {
        return;
    };

    let mut st: libc::stat = unsafe { std::mem::zeroed() };
    if unsafe { libc::stat(dir_c.as_ptr(), &mut st) } != 0 {
        return;
    }

    let Some(file) = append_to_spool_file(file_path, payload, 0o600) else {
        return;
    };

    unsafe {
        let _ = libc::fchown(file.as_raw_fd(), st.st_uid, st.st_gid);
        let _ = libc::fchmod(file.as_raw_fd(), 0o600);
    }

    // Copy SELinux context (`u:object_r:app_data_file:s0:c...`) from parent directory to the spool file
    let mut xattr_buf = [0u8; 256];
    let xattr_len = unsafe {
        libc::syscall(
            libc::SYS_getxattr,
            dir_c.as_ptr(),
            c"security.selinux".as_ptr(),
            xattr_buf.as_mut_ptr().cast::< libc::c_void>(),
            xattr_buf.len() - 1,
        )
    };
    if xattr_len > 0 {
        xattr_buf[xattr_len as usize] = 0;
        let _ = crate::selinux::setfilecon(file_c.as_ptr(), xattr_buf.as_ptr().cast());
    }
}

/// Pushes a Base64-encoded Smart Log snapshot directly into `ShizukuManagerProvider`
/// (`content://kerneldroid.nightzuku.shizuku`, method `pushSmartLog`) via a non-blocking
/// child process with a 3-second alarm timeout. Works for both `root` (`UID 0`) and `shell`
/// (`UID 2000`) even when `shizuku_server` crashes fatally and never brings up Binder.
#[cfg(target_os = "android")]
fn push_snapshot_to_manager_provider(payload: &str) {
    let arg_value = format!("base64:{}", base64_encode(payload.as_bytes()));
    let Ok(arg_c) = CString::new(arg_value) else {
        return;
    };

    // Double-fork so the caller does not have to wait for `content call` and no zombie is left behind
    let pid = unsafe { libc::fork() };
    if pid < 0 {
        return;
    }
    if pid == 0 {
        let grandchild = unsafe { libc::fork() };
        if grandchild == 0 {
            unsafe {
                libc::alarm(3);
                let dev_null = libc::open(c"/dev/null".as_ptr(), libc::O_RDWR | libc::O_CLOEXEC);
                if dev_null >= 0 {
                    libc::dup2(dev_null, 0);
                    libc::dup2(dev_null, 1);
                    libc::dup2(dev_null, 2);
                    if dev_null > 2 {
                        libc::close(dev_null);
                    }
                }
                let argv: [*const libc::c_char; 9] = [
                    c"/system/bin/content".as_ptr(),
                    c"call".as_ptr(),
                    c"--uri".as_ptr(),
                    c"content://kerneldroid.nightzuku.shizuku".as_ptr(),
                    c"--method".as_ptr(),
                    c"pushSmartLog".as_ptr(),
                    c"--arg".as_ptr(),
                    arg_c.as_ptr(),
                    ptr::null(),
                ];
                libc::execv(argv[0], argv.as_ptr());
                libc::_exit(1);
            }
        }
        unsafe {
            libc::_exit(0);
        }
    }
    let mut status: c_int = 0;
    unsafe {
        let _ = libc::waitpid(pid, &mut status, 0);
    }
}

#[cfg(not(target_os = "android"))]
fn push_snapshot_to_manager_provider(_payload: &str) {}

fn rotate_spool_file(file: &mut File) {
    let Ok(len) = file.metadata().map(|m| m.len() as usize) else {
        return;
    };
    if len <= RETAIN_SMART_LOG_BYTES {
        return;
    }
    let start_offset = (len - RETAIN_SMART_LOG_BYTES) as u64;
    if file.seek(SeekFrom::Start(start_offset)).is_err() {
        return;
    }
    let mut tail = Vec::with_capacity(RETAIN_SMART_LOG_BYTES);
    if file.read_to_end(&mut tail).is_err() {
        return;
    }
    // Align to the start of a complete snapshot block if possible
    let marker = b"===SMART_SNAPSHOT_BEGIN===";
    let slice = if let Some(idx) = tail.windows(marker.len()).position(|w| w == marker) {
        &tail[idx..]
    } else {
        tail.as_slice()
    };
    if file.set_len(0).is_ok() && file.seek(SeekFrom::Start(0)).is_ok() {
        let _ = file.write_all(slice);
        let _ = file.flush();
    }
}

/// Records a structured Smart Log snapshot to logcat, SELinux-compliant spool files, and
/// pushes it to `ShizukuManagerProvider` so the Manager receives it even if `shizuku_server` dies.
pub fn record_smart_snapshot(category: &str, severity: &str, summary: &str, details: &str) {
    let payload = format_smart_snapshot(current_epoch_ms(), category, severity, summary, details);
    let bytes = payload.as_bytes();
    loge(&format!("[SmartLog][{category}] {summary}: {details}"));

    // 1. Server-readable spool files (UID 0 / 2000)
    let _ = append_to_spool_file(SMART_LOG_TMP_PATH, bytes, 0o644);
    let _ = append_to_spool_file(SMART_LOG_SHELL_PATH, bytes, 0o644);

    // 2. App-owned external cache spool (accessible to both shell/root and untrusted_app
    // Manager). /sdcard is the same file as /storage/emulated/0; writing both duplicated entries.
    let _ = append_to_spool_file(&ext_emulated_path(), bytes, 0o666);

    // 3. When running as root (UID 0), also write directly into the Manager's internal DE/CE cache
    //    with inherited UID/GID and `app_data_file` SELinux context
    #[cfg(unix)]
    if unsafe { libc::getuid() } == 0 {
        append_to_app_internal_cache_as_root(
            &app_de_cache_dir(),
            &app_de_cache_path(),
            bytes,
        );
        append_to_app_internal_cache_as_root(
            &app_ce_cache_dir(),
            &app_ce_cache_path(),
            bytes,
        );
    }

    // 4. Push directly to `content://kerneldroid.nightzuku.shizuku` (`pushSmartLog`)
    push_snapshot_to_manager_provider(&payload);
}

/// Installs a panic hook that persists a structured Smart Log snapshot if the starter panics.
pub fn install_panic_hook() {
    std::panic::set_hook(Box::new(|info| {
        let location = info
            .location()
            .map(|loc| format!("{}:{}:{}", loc.file(), loc.line(), loc.column()))
            .unwrap_or_else(|| "unknown_location".to_string());
        let message = if let Some(s) = info.payload().downcast_ref::<&str>() {
            (*s).to_string()
        } else if let Some(s) = info.payload().downcast_ref::<String>() {
            s.clone()
        } else {
            "non-string panic payload".to_string()
        };
        record_smart_snapshot(
            "RUST_STARTER_PANIC",
            "FATAL",
            &format!("Rust starter panicked at {location}"),
            &format!("panic_location={location}\npanic_message={message}"),
        );
    }));
}
