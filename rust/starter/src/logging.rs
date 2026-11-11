#[cfg(target_os = "android")]
use std::ffi::{CString, c_char};
use std::ffi::c_int;
use std::fs::{File, OpenOptions};
use std::io::{Read, Seek, SeekFrom, Write};
#[cfg(unix)]
use std::os::unix::fs::OpenOptionsExt;
#[cfg(unix)]
use std::os::unix::io::AsRawFd;
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

fn append_to_spool_file(path: &str, payload: &[u8]) {
    let mut opts = OpenOptions::new();
    opts.create(true).read(true).write(true).append(true);
    #[cfg(unix)]
    opts.mode(0o644);

    let Ok(mut file) = opts.open(path) else {
        return;
    };

    #[cfg(unix)]
    unsafe {
        let _ = libc::fchmod(file.as_raw_fd(), 0o644);
    }

    if let Ok(meta) = file.metadata()
        && meta.len() > MAX_SMART_LOG_BYTES
    {
        rotate_spool_file(&mut file);
    }

    let _ = file.write_all(payload);
    let _ = file.flush();
}

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

/// Records a structured Smart Log snapshot to logcat and persistent starter spool files.
pub fn record_smart_snapshot(category: &str, severity: &str, summary: &str, details: &str) {
    let payload = format_smart_snapshot(current_epoch_ms(), category, severity, summary, details);
    loge(&format!("[SmartLog][{category}] {summary}: {details}"));
    append_to_spool_file(SMART_LOG_TMP_PATH, payload.as_bytes());
    append_to_spool_file(SMART_LOG_SHELL_PATH, payload.as_bytes());
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
