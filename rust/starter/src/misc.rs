use std::ffi::{CStr, CString, c_int, c_void};
use std::mem::MaybeUninit;
use std::ptr;

#[cfg(target_os = "android")]
pub fn errno() -> c_int {
    unsafe { *libc::__errno() }
}

#[cfg(not(target_os = "android"))]
pub fn errno() -> c_int {
    unsafe { *libc::__errno_location() }
}

#[cfg(target_os = "android")]
pub fn errno_set(value: c_int) {
    unsafe {
        *libc::__errno() = value;
    }
}

#[cfg(not(target_os = "android"))]
pub fn errno_set(value: c_int) {
    unsafe {
        *libc::__errno_location() = value;
    }
}

pub fn is_space(byte: u8) -> bool {
    matches!(byte, b' ' | b'\t' | b'\n' | 0x0b | 0x0c | b'\r')
}

pub fn atoi(bytes: &[u8]) -> c_int {
    let mut index = 0;
    while index < bytes.len() && is_space(bytes[index]) {
        index += 1;
    }
    let negative = index < bytes.len() && bytes[index] == b'-';
    if index < bytes.len() && (bytes[index] == b'-' || bytes[index] == b'+') {
        index += 1;
    }
    let mut value: c_int = 0;
    while index < bytes.len() && bytes[index].is_ascii_digit() {
        value = value
            .saturating_mul(10)
            .saturating_add(c_int::from(bytes[index] - b'0'));
        index += 1;
    }
    if negative { -value } else { value }
}

pub fn is_num(bytes: &[u8]) -> bool {
    !bytes.is_empty() && bytes.iter().all(u8::is_ascii_digit)
}

pub fn fdgets(buf: &mut [u8], fd: c_int) -> isize {
    if buf.is_empty() {
        return -1;
    }
    buf[0] = 0;
    let mut ret = unsafe { libc::read(fd, buf.as_mut_ptr().cast(), buf.len() - 1) };
    while ret < 0 && errno() == libc::EINTR {
        ret = unsafe { libc::read(fd, buf.as_mut_ptr().cast(), buf.len() - 1) };
    }
    if ret < 0 {
        return -1;
    }
    buf[ret as usize] = 0;
    ret
}

pub fn get_proc_name(pid: c_int, name: &mut [u8]) -> c_int {
    let Ok(path) = CString::new(format!("/proc/{pid}/cmdline")) else {
        return 1;
    };
    let fd = unsafe { libc::open(path.as_ptr(), libc::O_RDONLY | libc::O_CLOEXEC) };
    if fd == -1 {
        return 1;
    }
    let read_res = fdgets(name, fd);
    unsafe {
        libc::close(fd);
    }
    if read_res <= 0 { 1 } else { 0 }
}

/// Parses a `/proc/<pid>/status` payload to extract the real UID (`Uid:\t<real>\t...`).
pub fn parse_proc_status_uid(status_bytes: &[u8]) -> Option<libc::uid_t> {
    for line in status_bytes.split(|&b| b == b'\n') {
        if let Some(rest) = line.strip_prefix(b"Uid:") {
            let trimmed = rest
                .iter()
                .copied()
                .skip_while(|b| is_space(*b))
                .take_while(|b| b.is_ascii_digit())
                .collect::<Vec<u8>>();
            if !trimmed.is_empty() {
                return Some(atoi(&trimmed) as libc::uid_t);
            }
        }
    }
    None
}

/// Reads the real UID of `pid` from `/proc/<pid>/status` (`Uid:\t<real>\t...`).
pub fn get_proc_uid(pid: c_int) -> Option<libc::uid_t> {
    let path = CString::new(format!("/proc/{pid}/status")).ok()?;
    let fd = unsafe { libc::open(path.as_ptr(), libc::O_RDONLY | libc::O_CLOEXEC) };
    if fd == -1 {
        return None;
    }
    let mut buf = [0u8; 4096];
    let n = fdgets(&mut buf, fd);
    unsafe {
        libc::close(fd);
    }
    if n <= 0 {
        return None;
    }
    parse_proc_status_uid(&buf[..n as usize])
}

pub fn switch_mnt_ns(pid: c_int) -> c_int {
    let Ok(path) = CString::new(format!("/proc/{pid}/ns/mnt")) else {
        return -1;
    };
    if unsafe { libc::access(path.as_ptr(), libc::R_OK) } == -1 {
        return -1;
    }
    let fd = unsafe { libc::open(path.as_ptr(), libc::O_RDONLY | libc::O_CLOEXEC) };
    if fd < 0 {
        return -1;
    }
    let res = unsafe { libc::setns(fd, 0) };
    unsafe {
        libc::close(fd);
    }
    res
}

pub fn foreach_proc(mut func: impl FnMut(c_int)) {
    let dir = unsafe { libc::opendir(c"/proc".as_ptr()) };
    if dir.is_null() {
        return;
    }
    loop {
        let entry = unsafe { libc::readdir(dir) };
        if entry.is_null() {
            break;
        }
        let entry = unsafe { &*entry };
        if entry.d_type != libc::DT_DIR {
            continue;
        }
        let name = unsafe { CStr::from_ptr(entry.d_name.as_ptr()) }.to_bytes();
        if !is_num(name) {
            continue;
        }
        func(atoi(name));
    }
    unsafe {
        libc::closedir(dir);
    }
}

#[allow(dead_code)]
pub fn copyfileat(src_dirfd: c_int, src_path: &CStr, dst_dirfd: c_int, dst_path: &CStr) -> c_int {
    let src_fd = unsafe { libc::openat(src_dirfd, src_path.as_ptr(), libc::O_RDONLY | libc::O_CLOEXEC) };
    if src_fd == -1 {
        return -1;
    }

    let mut stat_buf = MaybeUninit::<libc::stat>::zeroed();
    if unsafe { libc::fstat(src_fd, stat_buf.as_mut_ptr()) } == -1 {
        unsafe {
            libc::close(src_fd);
        }
        return -1;
    }
    let stat_buf = unsafe { stat_buf.assume_init() };

    let dst_fd = unsafe {
        libc::openat(
            dst_dirfd,
            dst_path.as_ptr(),
            libc::O_WRONLY | libc::O_CREAT | libc::O_TRUNC | libc::O_CLOEXEC,
            stat_buf.st_mode as libc::c_uint,
        )
    };
    if dst_fd == -1 {
        unsafe {
            libc::close(src_fd);
        }
        return -1;
    }

    let mut size_remaining = stat_buf.st_size;
    loop {
        let count = if size_remaining > 0x7ffff000 {
            0x7ffff000usize
        } else {
            size_remaining as usize
        };

        let result = unsafe { libc::sendfile(dst_fd, src_fd, ptr::null_mut(), count) };
        if result == -1 {
            unsafe {
                libc::close(src_fd);
                libc::close(dst_fd);
                libc::unlink(dst_path.as_ptr());
            }
            return -1;
        }

        size_remaining -= result as i64;
        if size_remaining <= 0 {
            unsafe {
                libc::close(src_fd);
                libc::close(dst_fd);
            }
            return 0;
        }
    }
}

#[allow(dead_code)]
pub fn copyfile(src_path: &CStr, dst_path: &CStr) -> c_int {
    copyfileat(0, src_path, 0, dst_path)
}

#[allow(dead_code)]
pub fn memsearch(start: usize, end: usize, value: &[u8]) -> usize {
    let mut cursor = start;
    loop {
        if cursor.wrapping_add(value.len()) >= end {
            return 0;
        }

        if unsafe { libc::memcmp(cursor as *const c_void, value.as_ptr().cast(), value.len()) } == 0
        {
            return cursor;
        }

        cursor = cursor.wrapping_add(1);
    }
}

pub fn trim(s: &str) -> String {
    s.trim_matches(|c: char| c.is_ascii_whitespace()).to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_atoi_and_is_num() {
        assert!(is_num(b"12345"));
        assert!(!is_num(b""));
        assert!(!is_num(b"123a"));
        assert_eq!(atoi(b"  +2000\n"), 2000);
        assert_eq!(atoi(b" -42 "), -42);
    }

    #[test]
    fn test_parse_proc_status_uid() {
        let sample = b"Name:\tshizuku_server\nState:\tS (sleeping)\nPid:\t4321\nUid:\t2000\t2000\t2000\t2000\nGid:\t2000\t2000\n";
        assert_eq!(parse_proc_status_uid(sample), Some(2000));

        let root_sample = b"Name:\tshizuku_server\nUid:\t0\t0\t0\t0\n";
        assert_eq!(parse_proc_status_uid(root_sample), Some(0));

        let missing = b"Name:\tshizuku_server\nPid:\t100\n";
        assert_eq!(parse_proc_status_uid(missing), None);
    }

    #[test]
    fn test_get_proc_uid_self() {
        let pid = unsafe { libc::getpid() };
        let expected_uid = unsafe { libc::getuid() };
        assert_eq!(get_proc_uid(pid), Some(expected_uid));
    }

    #[test]
    fn test_trim() {
        assert_eq!(trim("  package:/data/app/base.apk \r\n"), "package:/data/app/base.apk");
    }
}
