use std::ffi::{CString, c_char, c_int};
use std::sync::atomic::{AtomicI32, Ordering};

use crate::misc::atoi;

const PROP_VALUE_MAX: usize = 92;

unsafe extern "C" {
    fn __system_property_get(name: *const c_char, value: *mut c_char) -> c_int;
}

pub fn prop(name: &str) -> Option<String> {
    let name = CString::new(name).ok()?;
    let mut buf = [0u8; PROP_VALUE_MAX + 1];
    let len = unsafe { __system_property_get(name.as_ptr(), buf.as_mut_ptr().cast()) };
    if len <= 0 {
        return None;
    }
    let end = buf.iter().position(|byte| *byte == 0).unwrap_or(buf.len());
    Some(String::from_utf8_lossy(&buf[..end]).into_owned())
}

static API_LEVEL: AtomicI32 = AtomicI32::new(0);

pub fn api_level() -> c_int {
    let cached = API_LEVEL.load(Ordering::Relaxed);
    if cached > 0 {
        return cached;
    }
    let level = prop("ro.build.version.sdk").map_or(0, |value| atoi(value.as_bytes()));
    API_LEVEL.store(level, Ordering::Relaxed);
    level
}

static PREVIEW_API_LEVEL: AtomicI32 = AtomicI32::new(0);

#[allow(dead_code)]
pub fn preview_api_level() -> c_int {
    let cached = PREVIEW_API_LEVEL.load(Ordering::Relaxed);
    if cached > 0 {
        return cached;
    }
    let level = prop("ro.build.version.preview_sdk").map_or(0, |value| atoi(value.as_bytes()));
    PREVIEW_API_LEVEL.store(level, Ordering::Relaxed);
    level
}
