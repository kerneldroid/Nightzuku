use std::ffi::{CStr, c_char, c_int, c_void};
use std::ptr;
use std::sync::OnceLock;

use crate::misc::{errno, errno_set};

pub type GetconFn = unsafe extern "C" fn(*mut *mut c_char) -> c_int;
pub type SetconFn = unsafe extern "C" fn(*const c_char) -> c_int;
pub type SetfileconFn = unsafe extern "C" fn(*const c_char, *const c_char) -> c_int;
pub type CheckAccessFn = unsafe extern "C" fn(
    *const c_char,
    *const c_char,
    *const c_char,
    *const c_char,
    *mut c_void,
) -> c_int;
pub type FreeconFn = unsafe extern "C" fn(*mut c_char);

unsafe extern "C" fn default_getcon(context: *mut *mut c_char) -> c_int {
    if context.is_null() {
        errno_set(libc::EINVAL);
        return -1;
    }
    unsafe {
        *context = ptr::null_mut();
    }

    let fd = unsafe { libc::open(c"/proc/self/attr/current".as_ptr(), libc::O_RDONLY | libc::O_CLOEXEC) };
    if fd < 0 {
        return -1;
    }

    let page_size = unsafe { libc::sysconf(libc::_SC_PAGE_SIZE) };
    let size = if page_size > 0 { page_size as usize } else { 4096 };
    let buf = unsafe { libc::malloc(size) };
    let mut rc = -1;

    if !buf.is_null() {
        unsafe {
            libc::memset(buf, 0, size);
        }

        let mut ret = unsafe { libc::read(fd, buf, size - 1) };
        while ret < 0 && errno() == libc::EINTR {
            ret = unsafe { libc::read(fd, buf, size - 1) };
        }

        if ret > 0 {
            let dup = unsafe { libc::strdup(buf.cast()) };
            if !dup.is_null() {
                unsafe {
                    *context = dup;
                }
                rc = 0;
            }
        }

        unsafe {
            libc::free(buf);
        }
    }

    let held = errno();
    unsafe {
        libc::close(fd);
    }
    errno_set(held);
    rc
}

unsafe extern "C" fn default_setcon(context: *const c_char) -> c_int {
    let fd = unsafe { libc::open(c"/proc/self/attr/current".as_ptr(), libc::O_WRONLY | libc::O_CLOEXEC) };
    if fd < 0 {
        return fd;
    }
    let len = unsafe { CStr::from_ptr(context) }.to_bytes_with_nul().len();
    let written = unsafe { libc::write(fd, context.cast(), len) };
    unsafe {
        libc::close(fd);
    }
    (written != len as isize) as c_int
}

unsafe extern "C" fn default_setfilecon(path: *const c_char, context: *const c_char) -> c_int {
    let rc = unsafe {
        libc::syscall(
            libc::SYS_setxattr,
            path,
            c"security.selinux".as_ptr(),
            context,
            CStr::from_ptr(context).to_bytes_with_nul().len(),
            0,
        )
    };
    if rc != 0 {
        errno_set(-(rc as c_int));
        return -1;
    }
    0
}

unsafe extern "C" fn default_selinux_check_access(
    _scon: *const c_char,
    _tcon: *const c_char,
    _tclass: *const c_char,
    _perm: *const c_char,
    _auditdata: *mut c_void,
) -> c_int {
    0
}

unsafe extern "C" fn default_freecon(context: *mut c_char) {
    unsafe {
        libc::free(context.cast());
    }
}

#[derive(Clone, Copy)]
struct Table {
    getcon: GetconFn,
    setcon: SetconFn,
    setfilecon: SetfileconFn,
    selinux_check_access: CheckAccessFn,
    freecon: FreeconFn,
}

const DEFAULTS: Table = Table {
    getcon: default_getcon,
    setcon: default_setcon,
    setfilecon: default_setfilecon,
    selinux_check_access: default_selinux_check_access,
    freecon: default_freecon,
};

static TABLE: OnceLock<Table> = OnceLock::new();

fn table() -> Table {
    TABLE.get().copied().unwrap_or(DEFAULTS)
}

fn resolve(handle: *mut c_void, name: &CStr) -> *mut c_void {
    unsafe { libc::dlsym(handle, name.as_ptr()) }
}

pub fn init() {
    let present = unsafe {
        libc::access(c"/system/lib/libselinux.so".as_ptr(), libc::F_OK) == 0
            || libc::access(c"/system/lib64/libselinux.so".as_ptr(), libc::F_OK) == 0
    };
    if !present {
        return;
    }

    let handle = unsafe { libc::dlopen(c"libselinux.so".as_ptr(), libc::RTLD_LAZY | libc::RTLD_LOCAL) };
    if handle.is_null() {
        return;
    }

    let mut table = DEFAULTS;

    let symbol = resolve(handle, c"getcon");
    if !symbol.is_null() {
        table.getcon = unsafe { std::mem::transmute(symbol) };
    }
    let symbol = resolve(handle, c"setcon");
    if !symbol.is_null() {
        table.setcon = unsafe { std::mem::transmute(symbol) };
    }
    let symbol = resolve(handle, c"setfilecon");
    if !symbol.is_null() {
        table.setfilecon = unsafe { std::mem::transmute(symbol) };
    }
    let symbol = resolve(handle, c"selinux_check_access");
    if !symbol.is_null() {
        table.selinux_check_access = unsafe { std::mem::transmute(symbol) };
    }
    let symbol = resolve(handle, c"freecon");
    if !symbol.is_null() {
        table.freecon = unsafe { std::mem::transmute(symbol) };
    }

    let _ = TABLE.set(table);
}

pub fn getcon(context: *mut *mut c_char) -> c_int {
    unsafe { (table().getcon)(context) }
}

#[allow(dead_code)]
pub fn setcon(context: *const c_char) -> c_int {
    unsafe { (table().setcon)(context) }
}

#[allow(dead_code)]
pub fn setfilecon(path: *const c_char, context: *const c_char) -> c_int {
    unsafe { (table().setfilecon)(path, context) }
}

pub fn selinux_check_access(
    scon: *const c_char,
    tcon: *const c_char,
    tclass: *const c_char,
    perm: *const c_char,
    auditdata: *mut c_void,
) -> c_int {
    unsafe { (table().selinux_check_access)(scon, tcon, tclass, perm, auditdata) }
}

pub fn freecon(context: *mut c_char) {
    unsafe { (table().freecon)(context) };
}
