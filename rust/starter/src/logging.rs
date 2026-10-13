use std::ffi::{CString, c_char, c_int};

const TAG: &std::ffi::CStr = c"Shizuku";

const ANDROID_LOG_VERBOSE: c_int = 2;
const ANDROID_LOG_DEBUG: c_int = 3;
const ANDROID_LOG_INFO: c_int = 4;
const ANDROID_LOG_WARN: c_int = 5;
const ANDROID_LOG_ERROR: c_int = 6;

#[link(name = "log")]
unsafe extern "C" {
    fn __android_log_print(prio: c_int, tag: *const c_char, fmt: *const c_char, ...) -> c_int;
}

fn log(prio: c_int, message: &str) {
    if let Ok(message) = CString::new(message) {
        unsafe {
            __android_log_print(prio, TAG.as_ptr(), c"%s".as_ptr(), message.as_ptr());
        }
    }
}

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
