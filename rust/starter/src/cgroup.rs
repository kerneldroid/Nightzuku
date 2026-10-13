use std::ffi::CString;

pub fn switch_cgroup(cgroup: &str, pid: i32) -> bool {
    let Ok(path) = CString::new(format!("{cgroup}/cgroup.procs")) else {
        return false;
    };
    if unsafe { libc::access(path.as_ptr(), libc::F_OK) } != 0 {
        return false;
    }
    let fd = unsafe { libc::open(path.as_ptr(), libc::O_WRONLY | libc::O_APPEND | libc::O_CLOEXEC) };
    if fd == -1 {
        return false;
    }
    let data = format!("{pid}\n");
    let written = unsafe { libc::write(fd, data.as_ptr().cast(), data.len()) };
    unsafe {
        libc::close(fd);
    }
    written as usize == data.len()
}
