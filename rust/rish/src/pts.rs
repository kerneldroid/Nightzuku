use core::ffi::c_int;
use core::fmt::{self, Arguments, Write};
use std::cell::UnsafeCell;
use std::ffi::CStr;
use std::sync::OnceLock;

#[cfg(target_os = "android")]
use core::ffi::c_char;

use log::{debug, error};

pub const ATTY_IN: i8 = 1;
pub const ATTY_OUT: i8 = 2;
pub const ATTY_ERR: i8 = 4;
pub const ATTY_ALL: i8 = ATTY_IN | ATTY_OUT | ATTY_ERR;

const LOG_DEBUG: c_int = 3;
const LOG_ERROR: c_int = 6;

#[cfg(target_os = "android")]
const LOG_TAG: &CStr = c"RISH";

#[cfg(target_os = "android")]
unsafe extern "C" {
    fn __android_log_write(prio: c_int, tag: *const c_char, text: *const c_char) -> c_int;
}

pub struct CStrBytes<'a>(pub &'a CStr);

impl fmt::Display for CStrBytes<'_> {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        for &byte in self.0.to_bytes() {
            if (0x20..=0x7e).contains(&byte) {
                formatter.write_char(byte as char)?;
            } else {
                write!(formatter, "\\x{byte:02x}")?;
            }
        }

        Ok(())
    }
}

struct FixedBuffer {
    buffer: [u8; 512],
    length: usize,
}

impl Default for FixedBuffer {
    fn default() -> Self {
        Self {
            buffer: [0; 512],
            length: 0,
        }
    }
}

impl FixedBuffer {
    fn new() -> Self {
        Self::default()
    }
}

impl Write for FixedBuffer {
    fn write_str(&mut self, value: &str) -> fmt::Result {
        let available = self.buffer.len() - 1 - self.length;
        let bytes = value.as_bytes();
        let count = bytes.len().min(available);
        self.buffer[self.length..self.length + count].copy_from_slice(&bytes[..count]);
        self.length += count;

        Ok(())
    }
}

fn emit(priority: c_int, buffer: &FixedBuffer) {
    #[cfg(target_os = "android")]
    unsafe {
        __android_log_write(priority, LOG_TAG.as_ptr(), buffer.buffer.as_ptr().cast());
    }

    #[cfg(not(target_os = "android"))]
    unsafe {
        libc::write(
            libc::STDERR_FILENO,
            buffer.buffer.as_ptr().cast(),
            buffer.length,
        );
        let _ = priority;
    }
}

#[cfg(target_os = "android")]
fn current_errno() -> c_int {
    unsafe { *libc::__errno() }
}

#[cfg(not(target_os = "android"))]
fn current_errno() -> c_int {
    std::io::Error::last_os_error()
        .raw_os_error()
        .unwrap_or_default()
}

pub fn debug_child(args: Arguments<'_>) {
    let mut buffer = FixedBuffer::new();
    let _ = buffer.write_fmt(args);
    emit(LOG_DEBUG, &buffer);
}

pub fn ploge_child(args: Arguments<'_>) {
    let errno = current_errno();

    let mut buffer = FixedBuffer::new();
    let _ = buffer.write_fmt(args);
    let _ = write!(buffer, " failed with {errno}: ");

    let message = unsafe { libc::strerror(errno) };

    if message.is_null() {
        let _ = buffer.write_str("unknown error");
    } else {
        let display = CStrBytes(unsafe { CStr::from_ptr(message) });
        let _ = write!(buffer, "{display}");
    }

    emit(LOG_ERROR, &buffer);
}

pub struct RawMutex {
    mutex: UnsafeCell<libc::pthread_mutex_t>,
}

unsafe impl Send for RawMutex {}
unsafe impl Sync for RawMutex {}

impl RawMutex {
    pub fn lock(&self) {
        let result = unsafe { libc::pthread_mutex_lock(self.mutex.get()) };

        if result != 0 {
            error!("pthread_mutex_lock failed with {result}");
        }
    }

    pub fn unlock(&self) {
        let result = unsafe { libc::pthread_mutex_unlock(self.mutex.get()) };

        if result != 0 {
            error!("pthread_mutex_unlock failed with {result}");
        }
    }
}

fn create_mutex() -> RawMutex {
    let mut mutex = std::mem::MaybeUninit::<libc::pthread_mutex_t>::zeroed();
    let result = unsafe { libc::pthread_mutex_init(mutex.as_mut_ptr(), std::ptr::null()) };

    if result != 0 {
        error!("pthread_mutex_init failed with {result}");
    }

    RawMutex {
        mutex: UnsafeCell::new(unsafe { mutex.assume_init() }),
    }
}

pub fn process_exit_mutex() -> &'static RawMutex {
    static MUTEX: OnceLock<RawMutex> = OnceLock::new();

    MUTEX.get_or_init(create_mutex)
}

pub fn winch_mutex() -> &'static RawMutex {
    static MUTEX: OnceLock<RawMutex> = OnceLock::new();

    MUTEX.get_or_init(create_mutex)
}

pub fn initialize_mutexes() {
    process_exit_mutex();
    winch_mutex();
}

pub fn ploge(context: &str) {
    let source = std::io::Error::last_os_error();

    error!(
        "{context} failed with {}: {source}",
        source.raw_os_error().unwrap_or_default()
    );
}

pub fn retry<T, F>(mut call: F) -> T
where
    T: Copy + PartialEq + From<i8>,
    F: FnMut() -> T,
{
    loop {
        let result = call();

        if result != T::from(-1)
            || std::io::Error::last_os_error().raw_os_error() != Some(libc::EINTR)
        {
            return result;
        }
    }
}

pub fn make_tty_raw(fd: i32) -> Option<libc::termios> {
    let mut termios = std::mem::MaybeUninit::<libc::termios>::zeroed();

    if unsafe { libc::tcgetattr(fd, termios.as_mut_ptr()) } < 0 {
        ploge("tcgetattr");
        return None;
    }

    let previous = unsafe { termios.assume_init() };
    let mut raw = previous;
    unsafe { libc::cfmakeraw(&mut raw) };

    if unsafe { libc::tcsetattr(fd, libc::TCSANOW, &raw) } < 0 {
        ploge("tcsetattr");
        return None;
    }

    Some(previous)
}

pub fn restore_fd(fd: i32, termios: &libc::termios) {
    if unsafe { libc::tcsetattr(fd, libc::TCSANOW, termios) } < 0 {
        ploge("tcsetattr");
    }
}

fn write_full(fd: i32, mut data: &[u8]) -> i32 {
    while !data.is_empty() {
        let count = data.len().min(isize::MAX as usize);
        let size = retry(|| unsafe { libc::write(fd, data.as_ptr().cast(), count) });

        if size <= 0 {
            return -1;
        }

        data = &data[size as usize..];
    }

    0
}

fn transfer(
    input: i32,
    output: i32,
    close_in: bool,
    close_out: bool,
    function: Option<Box<dyn FnOnce() + Send>>,
) {
    let mut buffer = [0_u8; 8192];

    loop {
        let size = retry(|| unsafe { libc::read(input, buffer.as_mut_ptr().cast(), buffer.len()) });

        if size <= 0 {
            break;
        }

        if write_full(output, &buffer[..size as usize]) == -1 {
            break;
        }
    }

    if close_in {
        unsafe { libc::close(input) };
    }

    if close_out {
        unsafe { libc::close(output) };
    }

    if let Some(function) = function {
        function();
    }
}

pub fn transfer_async(
    input: i32,
    output: i32,
    function: Option<Box<dyn FnOnce() + Send>>,
    close_in: bool,
    close_out: bool,
) {
    let spawned = std::thread::Builder::new().spawn(move || {
        transfer(input, output, close_in, close_out, function);
    });

    if let Err(source) = spawned {
        error!("pthread_create failed with {source}");
    }
}

pub fn open_ptmx() -> i32 {
    let fd = unsafe { libc::open(c"/dev/ptmx".as_ptr(), libc::O_RDWR) };

    if fd == -1 {
        return -1;
    }

    if unsafe { libc::grantpt(fd) } == -1 {
        ploge("grantpt");
        unsafe { libc::close(fd) };
        return -1;
    }

    if unsafe { libc::unlockpt(fd) } == -1 {
        ploge("unlockpt");
        unsafe { libc::close(fd) };
        return -1;
    }

    fd
}

pub fn get_window_size(fd: i32) -> i64 {
    let mut size: i64 = 0;

    if unsafe { libc::ioctl(fd, libc::TIOCGWINSZ, &mut size) } == -1 {
        ploge("ioctl TIOCGWINSZ");
        return 0;
    }

    size
}

pub fn set_window_size(fd: i32, size: i64) {
    let window: libc::winsize = unsafe { std::mem::transmute(size) };

    debug!(
        "setWindowSize {} {} {} {}",
        window.ws_row, window.ws_col, window.ws_xpixel, window.ws_ypixel
    );

    if unsafe { libc::ioctl(fd, libc::TIOCSWINSZ, &window) } == -1 {
        ploge("ioctl TIOCSWINSZ");
    }
}
