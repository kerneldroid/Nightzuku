// Portions of the PTY poll/drain lifecycle are adapted from
// https://github.com/d4rken-org/porter-api (eu.darken.porter / porsh, Apache-2.0 / MIT).

use core::ffi::c_int;
use core::fmt::{self, Arguments, Write};
use std::ffi::CStr;
use std::os::fd::{FromRawFd, OwnedFd, RawFd};
use std::sync::atomic::{AtomicI32, Ordering};
use std::sync::{Condvar, Mutex, OnceLock};

#[cfg(target_os = "android")]
use core::ffi::c_char;

use log::{debug, error};

pub const ATTY_IN: i8 = 1;
pub const ATTY_OUT: i8 = 1 << 1;
pub const ATTY_ERR: i8 = 1 << 2;
pub const ATTY_ALL: i8 = ATTY_IN | ATTY_OUT | ATTY_ERR;

/// Type-safe wrapper around the `ATTY_*` bitmask describing which stdio streams are attached to a TTY.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct AttyFlags(i8);

impl AttyFlags {
    #[inline]
    pub const fn from_bits(bits: i8) -> Self {
        Self(bits & ATTY_ALL)
    }

    #[inline]
    pub const fn bits(self) -> i8 {
        self.0
    }

    #[inline]
    pub const fn has_in(self) -> bool {
        (self.0 & ATTY_IN) != 0
    }

    #[inline]
    pub const fn has_out(self) -> bool {
        (self.0 & ATTY_OUT) != 0
    }

    #[inline]
    pub const fn has_err(self) -> bool {
        (self.0 & ATTY_ERR) != 0
    }

    #[inline]
    pub const fn is_any(self) -> bool {
        self.0 != 0
    }

    #[inline]
    pub const fn is_all(self) -> bool {
        self.0 == ATTY_ALL
    }
}

/// RAII pair of pipe file descriptors created with `O_CLOEXEC`.
pub struct PipePair {
    pub read: OwnedFd,
    pub write: OwnedFd,
}

impl PipePair {
    pub fn new_cloexec() -> Option<Self> {
        let mut fds = [-1 as RawFd; 2];
        if unsafe { libc::pipe2(fds.as_mut_ptr(), libc::O_CLOEXEC) } != 0 {
            return None;
        }
        Some(unsafe {
            Self {
                read: OwnedFd::from_raw_fd(fds[0]),
                write: OwnedFd::from_raw_fd(fds[1]),
            }
        })
    }
}

/// Duplicates `fd` with `F_DUPFD_CLOEXEC` and returns an [`OwnedFd`].
pub fn dup_cloexec(fd: RawFd) -> Option<OwnedFd> {
    if fd < 0 {
        return None;
    }
    let dup_fd = unsafe { libc::fcntl(fd, libc::F_DUPFD_CLOEXEC, 0) };
    if dup_fd < 0 {
        None
    } else {
        Some(unsafe { OwnedFd::from_raw_fd(dup_fd) })
    }
}

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
pub fn current_errno() -> c_int {
    unsafe { *libc::__errno() }
}

#[cfg(not(target_os = "android"))]
pub fn current_errno() -> c_int {
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

/// Safe cross-thread completion gate replacing raw cross-thread `pthread_mutex_unlock` UB.
pub struct ProcessExitGate {
    state: Mutex<bool>,
    condvar: Condvar,
}

impl Default for ProcessExitGate {
    fn default() -> Self {
        Self::new()
    }
}

impl ProcessExitGate {
    pub const fn new() -> Self {
        Self {
            state: Mutex::new(false),
            condvar: Condvar::new(),
        }
    }

    pub fn reset(&self) {
        let mut exited = self.state.lock().unwrap_or_else(|e| e.into_inner());
        *exited = false;
    }

    pub fn notify_exit(&self) {
        let mut exited = self.state.lock().unwrap_or_else(|e| e.into_inner());
        *exited = true;
        self.condvar.notify_all();
    }

    pub fn wait(&self) {
        let guard = self.state.lock().unwrap_or_else(|e| e.into_inner());
        let _guard = self
            .condvar
            .wait_while(guard, |exited| !*exited)
            .unwrap_or_else(|e| e.into_inner());
    }
}

pub fn process_exit_gate() -> &'static ProcessExitGate {
    static GATE: OnceLock<ProcessExitGate> = OnceLock::new();
    GATE.get_or_init(ProcessExitGate::new)
}

/// Async-signal-safe `SIGWINCH` self-pipe replacing signal-handler `pthread_mutex_unlock` UB.
static WINCH_WRITE_FD: AtomicI32 = AtomicI32::new(-1);
static WINCH_READ_FD: AtomicI32 = AtomicI32::new(-1);

pub fn initialize_sync_primitives() {
    process_exit_gate();
    ensure_winch_pipe();
}

fn ensure_winch_pipe() -> (RawFd, RawFd) {
    let existing_read = WINCH_READ_FD.load(Ordering::Acquire);
    let existing_write = WINCH_WRITE_FD.load(Ordering::Acquire);
    if existing_read >= 0 && existing_write >= 0 {
        return (existing_read, existing_write);
    }

    static INIT: OnceLock<()> = OnceLock::new();
    INIT.get_or_init(|| {
        let mut fds = [-1 as RawFd; 2];
        if unsafe { libc::pipe2(fds.as_mut_ptr(), libc::O_CLOEXEC | libc::O_NONBLOCK) } == 0 {
            WINCH_READ_FD.store(fds[0], Ordering::Release);
            WINCH_WRITE_FD.store(fds[1], Ordering::Release);
        } else {
            ploge("pipe2(winch)");
        }
    });

    (
        WINCH_READ_FD.load(Ordering::Acquire),
        WINCH_WRITE_FD.load(Ordering::Acquire),
    )
}

/// Async-signal-safe handler invoked by `SIGWINCH`. Only calls `libc::write` on a non-blocking pipe.
pub extern "C" fn on_sigwinch(_sig: c_int) {
    let write_fd = WINCH_WRITE_FD.load(Ordering::Relaxed);
    if write_fd >= 0 {
        let byte: u8 = 1;
        unsafe {
            let _ = libc::write(write_fd, (&raw const byte).cast(), 1);
        }
    }
}

/// Blocks until at least one `SIGWINCH` event is written to the self-pipe and drains coalesced events.
pub fn wait_for_sigwinch() {
    let (read_fd, _) = ensure_winch_pipe();
    if read_fd < 0 {
        return;
    }

    let mut pfd = libc::pollfd {
        fd: read_fd,
        events: libc::POLLIN,
        revents: 0,
    };
    let _ = retry(|| unsafe { libc::poll(&raw mut pfd, 1, -1) });

    let mut drain = [0u8; 64];
    while unsafe { libc::read(read_fd, drain.as_mut_ptr().cast(), drain.len()) } > 0 {}
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

pub fn make_tty_raw(fd: RawFd) -> Option<libc::termios> {
    let mut termios = std::mem::MaybeUninit::<libc::termios>::zeroed();

    if unsafe { libc::tcgetattr(fd, termios.as_mut_ptr()) } < 0 {
        ploge("tcgetattr");
        return None;
    }

    let previous = unsafe { termios.assume_init() };
    let mut raw = previous;
    unsafe { libc::cfmakeraw(&raw mut raw) };

    if unsafe { libc::tcsetattr(fd, libc::TCSANOW, &raw const raw) } < 0 {
        ploge("tcsetattr");
        return None;
    }

    Some(previous)
}

pub fn restore_fd(fd: RawFd, termios: &libc::termios) {
    if unsafe { libc::tcsetattr(fd, libc::TCSANOW, termios) } < 0 {
        ploge("tcsetattr");
    }
}

fn write_full(fd: RawFd, mut data: &[u8]) -> i32 {
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

pub fn transfer(
    input: RawFd,
    output: RawFd,
    close_in: bool,
    close_out: bool,
    watch_out: bool,
    function: Option<Box<dyn FnOnce(bool) + Send>>,
) {
    let mut buffer = [0_u8; 8192];
    let mut failed = false;

    loop {
        if watch_out && output >= 0 {
            let mut fds = [
                libc::pollfd {
                    fd: input,
                    events: libc::POLLIN,
                    revents: 0,
                },
                libc::pollfd {
                    fd: output,
                    events: libc::POLLERR | libc::POLLHUP | libc::POLLNVAL,
                    revents: 0,
                },
            ];
            let ready = retry(|| unsafe { libc::poll(fds.as_mut_ptr(), 2, -1) });
            if ready < 0 {
                failed = true;
                break;
            }
            if fds[0].revents & libc::POLLIN != 0 {
                let size =
                    retry(|| unsafe { libc::read(input, buffer.as_mut_ptr().cast(), buffer.len()) });
                if size <= 0 {
                    break;
                }
                if write_full(output, &buffer[..size as usize]) == -1 {
                    failed = true;
                    break;
                }
                continue;
            }
            if fds[1].revents & (libc::POLLERR | libc::POLLHUP | libc::POLLNVAL) != 0 {
                failed = true;
                break;
            }
            if fds[0].revents & (libc::POLLERR | libc::POLLHUP | libc::POLLNVAL) != 0 {
                break;
            }
        } else {
            let size =
                retry(|| unsafe { libc::read(input, buffer.as_mut_ptr().cast(), buffer.len()) });

            if size <= 0 {
                break;
            }

            if output >= 0 && write_full(output, &buffer[..size as usize]) == -1 {
                failed = true;
                break;
            }
        }
    }

    if close_in && input >= 0 {
        unsafe { libc::close(input) };
    }

    if close_out && output >= 0 {
        unsafe { libc::close(output) };
    }

    if let Some(function) = function {
        function(failed);
    }
}

/// RAII guard that cleans up `input`/`output` descriptors and invokes `callback(true)`
/// if `std::thread::Builder::spawn` fails and drops the closure before thread execution begins.
struct SpawnCleanupGuard {
    input: RawFd,
    output: RawFd,
    close_in: bool,
    close_out: bool,
    callback: Option<Box<dyn FnOnce(bool) + Send>>,
}

impl SpawnCleanupGuard {
    fn disarm(mut self) -> Option<Box<dyn FnOnce(bool) + Send>> {
        self.close_in = false;
        self.close_out = false;
        self.callback.take()
    }
}

impl Drop for SpawnCleanupGuard {
    fn drop(&mut self) {
        if self.close_in && self.input >= 0 {
            unsafe { libc::close(self.input) };
        }
        if self.close_out && self.output >= 0 {
            unsafe { libc::close(self.output) };
        }
        if let Some(cb) = self.callback.take() {
            cb(true);
        }
    }
}

pub fn transfer_async(
    input: RawFd,
    output: RawFd,
    function: Option<Box<dyn FnOnce(bool) + Send>>,
    close_in: bool,
    close_out: bool,
    watch_out: bool,
) {
    let guard = SpawnCleanupGuard {
        input,
        output,
        close_in,
        close_out,
        callback: function,
    };

    let spawned = std::thread::Builder::new()
        .name("rish-relay".into())
        .spawn(move || {
            let cb = guard.disarm();
            transfer(input, output, close_in, close_out, watch_out, cb);
        });

    if let Err(source) = spawned {
        error!("pthread_create failed with {source}");
    }
}

pub fn open_ptmx() -> Option<OwnedFd> {
    let fd = unsafe { libc::open(c"/dev/ptmx".as_ptr(), libc::O_RDWR | libc::O_CLOEXEC) };
    if fd < 0 {
        return None;
    }
    let owned = unsafe { OwnedFd::from_raw_fd(fd) };

    if unsafe { libc::grantpt(fd) } == -1 {
        ploge("grantpt");
        return None;
    }

    if unsafe { libc::unlockpt(fd) } == -1 {
        ploge("unlockpt");
        return None;
    }

    Some(owned)
}

/// Packs a `libc::winsize` into a 64-bit integer using native-endian layout without `transmute`.
#[inline]
pub fn pack_winsize(ws: libc::winsize) -> i64 {
    let mut bytes = [0u8; 8];
    bytes[0..2].copy_from_slice(&ws.ws_row.to_ne_bytes());
    bytes[2..4].copy_from_slice(&ws.ws_col.to_ne_bytes());
    bytes[4..6].copy_from_slice(&ws.ws_xpixel.to_ne_bytes());
    bytes[6..8].copy_from_slice(&ws.ws_ypixel.to_ne_bytes());
    i64::from_ne_bytes(bytes)
}

/// Unpacks a 64-bit integer into a `libc::winsize` using native-endian layout without `transmute`.
#[inline]
pub fn unpack_winsize(packed: i64) -> libc::winsize {
    let bytes = packed.to_ne_bytes();
    libc::winsize {
        ws_row: u16::from_ne_bytes([bytes[0], bytes[1]]),
        ws_col: u16::from_ne_bytes([bytes[2], bytes[3]]),
        ws_xpixel: u16::from_ne_bytes([bytes[4], bytes[5]]),
        ws_ypixel: u16::from_ne_bytes([bytes[6], bytes[7]]),
    }
}

pub fn get_window_size(fd: RawFd) -> i64 {
    let mut ws = libc::winsize {
        ws_row: 0,
        ws_col: 0,
        ws_xpixel: 0,
        ws_ypixel: 0,
    };

    if unsafe { libc::ioctl(fd, libc::TIOCGWINSZ, &raw mut ws) } == -1 {
        ploge("ioctl TIOCGWINSZ");
        return 0;
    }

    pack_winsize(ws)
}

pub fn set_window_size(fd: RawFd, size: i64) {
    let window = unpack_winsize(size);

    debug!(
        "setWindowSize {} {} {} {}",
        window.ws_row, window.ws_col, window.ws_xpixel, window.ws_ypixel
    );

    if unsafe { libc::ioctl(fd, libc::TIOCSWINSZ, &raw const window) } == -1 {
        ploge("ioctl TIOCSWINSZ");
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::fd::IntoRawFd;
    use std::sync::Arc;
    use std::sync::atomic::AtomicBool;

    #[test]
    fn atty_flags_bitmask_invariants() {
        let none = AttyFlags::from_bits(0);
        assert!(!none.is_any());
        assert!(!none.is_all());

        let all = AttyFlags::from_bits(ATTY_ALL);
        assert!(all.has_in());
        assert!(all.has_out());
        assert!(all.has_err());
        assert!(all.is_any());
        assert!(all.is_all());

        let out_only = AttyFlags::from_bits(ATTY_OUT);
        assert!(!out_only.has_in());
        assert!(out_only.has_out());
        assert!(!out_only.has_err());
    }

    #[test]
    fn winsize_pack_and_unpack_roundtrip() {
        let original = libc::winsize {
            ws_row: 48,
            ws_col: 160,
            ws_xpixel: 1920,
            ws_ypixel: 1080,
        };
        let packed = pack_winsize(original);
        let restored = unpack_winsize(packed);
        assert_eq!(restored.ws_row, 48);
        assert_eq!(restored.ws_col, 160);
        assert_eq!(restored.ws_xpixel, 1920);
        assert_eq!(restored.ws_ypixel, 1080);
    }

    #[test]
    fn process_exit_gate_coordinates_threads_without_ub() {
        let gate = Arc::new(ProcessExitGate::new());
        gate.reset();
        let gate_bg = Arc::clone(&gate);
        let handle = std::thread::spawn(move || {
            gate_bg.notify_exit();
        });
        gate.wait();
        handle.join().unwrap();
    }

    #[test]
    fn transfer_watch_out_detects_remote_pipe_closure_immediately() {
        let input_pipe = PipePair::new_cloexec().expect("input pipe");
        let output_pipe = PipePair::new_cloexec().expect("output pipe");

        // Close the read side of output_pipe immediately to simulate client disconnect (POLLERR/POLLHUP)
        drop(output_pipe.read);

        // Keep input_pipe.write open so input never produces EOF on its own
        let _keep_in_write = input_pipe.write;
        let in_read_fd = input_pipe.read.into_raw_fd();
        let out_write_fd = output_pipe.write.into_raw_fd();

        let failed_flag = Arc::new(AtomicBool::new(false));
        let failed_clone = Arc::clone(&failed_flag);

        transfer(
            in_read_fd,
            out_write_fd,
            true,
            true,
            true,
            Some(Box::new(move |failed| {
                failed_clone.store(failed, Ordering::Release);
            })),
        );

        assert!(
            failed_flag.load(Ordering::Acquire),
            "watch_out must report failure when remote reader closes output pipe"
        );
    }
}
