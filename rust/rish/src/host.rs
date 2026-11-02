// Portions of the PTY fd ownership, /dev/null drain, and process-group teardown
// are adapted from https://github.com/d4rken-org/porter-api (eu.darken.porter / porsh, Apache-2.0 / MIT).

use std::ffi::{CStr, CString, c_char};
use std::os::fd::{AsRawFd, FromRawFd, IntoRawFd, OwnedFd, RawFd};
use std::ptr;

use jni::JNIEnv;
use jni::objects::{JByteArray, JClass};
use jni::sys::{jbyte, jint, jintArray, jlong};
use log::{debug, error, warn};

use crate::pts::{self, AttyFlags, CStrBytes, PipePair};

const SHELL: &CStr = c"/system/bin/sh";

fn decode_vector(env: &JNIEnv, block: &JByteArray, count: jint) -> Option<Vec<CString>> {
    let bytes = env.convert_byte_array(block).ok()?;
    let mut offset = 0;
    let mut result = Vec::with_capacity(count.max(0) as usize);

    for _ in 0..count {
        let tail = bytes.get(offset..)?;
        let length = tail.iter().position(|&byte| byte == 0)?;

        result.push(CString::new(tail.get(..length)?).ok()?);
        offset += length + 1;
    }

    Some(result)
}

fn fail(env: &mut JNIEnv, message: &str) {
    if let Err(source) = env.throw_new("java/lang/IllegalStateException", message) {
        error!("Unable to throw IllegalStateException: {source}");
    }
}

fn int_array(env: &mut JNIEnv, pid: jint, ptmx: jint) -> jintArray {
    let Ok(array) = env.new_int_array(2) else {
        error!("Unable to allocate the host result array.");
        return ptr::null_mut();
    };

    if let Err(source) = env.set_int_array_region(&array, 0, &[pid, ptmx]) {
        error!("Unable to write the host result: {source}");
        return ptr::null_mut();
    }

    array.into_raw()
}

fn open_dev_null_write() -> Option<OwnedFd> {
    let fd = unsafe { libc::open(c"/dev/null".as_ptr(), libc::O_WRONLY | libc::O_CLOEXEC) };
    if fd < 0 {
        None
    } else {
        Some(unsafe { OwnedFd::from_raw_fd(fd) })
    }
}

fn on_remote_stdout_closed(pid: jint) {
    if pid <= 0 {
        return;
    }

    let mut info = std::mem::MaybeUninit::<libc::siginfo_t>::zeroed();
    let wait_res = pts::retry(|| unsafe {
        libc::waitid(
            libc::P_PID,
            pid as libc::id_t,
            info.as_mut_ptr(),
            libc::WEXITED | libc::WNOHANG | libc::WNOWAIT,
        )
    });
    if wait_res == 0 {
        let info = unsafe { info.assume_init() };
        if unsafe { info.si_pid() } == pid {
            return;
        }
    } else if std::io::Error::last_os_error().raw_os_error() == Some(libc::ECHILD) {
        return;
    }

    warn!("client dead, kill forked process group {pid}");
    if unsafe { libc::kill(-pid, libc::SIGKILL) } == -1
        && std::io::Error::last_os_error().raw_os_error() == Some(libc::ESRCH)
    {
        unsafe { libc::kill(pid, libc::SIGKILL) };
    }
}

/// RAII guard for the caller-supplied remote client pipe descriptors (`stdin_read`, `stdout_write`, `stderr_write`)
/// so they are never leaked if `native_start` fails before spawning transfer threads.
struct CallerStreams {
    stdin_read: OwnedFd,
    stdout_write: OwnedFd,
    stderr_write: Option<OwnedFd>,
}

impl CallerStreams {
    unsafe fn from_raw(stdin_read: RawFd, stdout_write: RawFd, stderr_write: RawFd) -> Self {
        Self {
            stdin_read: unsafe { OwnedFd::from_raw_fd(stdin_read) },
            stdout_write: unsafe { OwnedFd::from_raw_fd(stdout_write) },
            stderr_write: if stderr_write >= 0 {
                Some(unsafe { OwnedFd::from_raw_fd(stderr_write) })
            } else {
                None
            },
        }
    }
}

#[allow(clippy::too_many_arguments)]
pub(crate) extern "system" fn native_start<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    arg_block: JByteArray<'local>,
    argc: jint,
    env_block: JByteArray<'local>,
    envc: jint,
    dir_block: JByteArray<'local>,
    tty: jbyte,
    stdin_read: jint,
    stdout_write: jint,
    stderr_write: jint,
) -> jintArray {
    // Take RAII ownership of caller descriptors immediately so any early return closes them cleanly.
    let caller = unsafe { CallerStreams::from_raw(stdin_read, stdout_write, stderr_write) };
    let flags = AttyFlags::from_bits(tty);

    let Some(arguments) = decode_vector(&env, &arg_block, argc) else {
        error!("Unable to decode the argument block.");
        fail(&mut env, "Unable to decode the argument block");
        return ptr::null_mut();
    };

    let mut argv = Vec::with_capacity(arguments.len() + 1);
    argv.push(SHELL.to_owned());
    argv.extend(arguments);

    let mut argv_ptrs: Vec<*const c_char> = argv.iter().map(|arg| arg.as_ptr()).collect();
    argv_ptrs.push(ptr::null());

    let environment = if envc > 0 {
        let Some(variables) = decode_vector(&env, &env_block, envc) else {
            error!("Unable to decode the environment block.");
            fail(&mut env, "Unable to decode the environment block");
            return ptr::null_mut();
        };
        Some(variables)
    } else {
        None
    };

    let env_ptrs: Vec<*const c_char> = match &environment {
        Some(variables) => variables
            .iter()
            .map(|variable| variable.as_ptr())
            .chain(std::iter::once(ptr::null()))
            .collect(),
        None => Vec::new(),
    };

    let directory = if dir_block.as_raw().is_null() {
        None
    } else {
        match env.convert_byte_array(&dir_block) {
            Ok(bytes) => {
                let length = bytes
                    .iter()
                    .position(|&byte| byte == 0)
                    .unwrap_or(bytes.len());
                CString::new(&bytes[..length]).ok()
            }
            Err(source) => {
                error!("Unable to decode the working directory: {source}");
                None
            }
        }
    };

    let mut ptmx: Option<OwnedFd> = None;
    let mut ptmx_in: Option<OwnedFd> = None;
    let mut ptmx_out: Option<OwnedFd> = None;
    let mut dev_null: Option<OwnedFd> = None;

    if flags.is_any() {
        let Some(master) = pts::open_ptmx() else {
            fail(&mut env, "Unable to open ptmx");
            return ptr::null_mut();
        };
        let master_fd = master.as_raw_fd();
        debug!("ptmx {master_fd}");

        if flags.has_in() {
            let Some(dup) = pts::dup_cloexec(master_fd) else {
                fail(&mut env, "Unable to duplicate ptmx for stdin");
                return ptr::null_mut();
            };
            ptmx_in = Some(dup);
        }

        if flags.has_out() || flags.has_in() || flags.has_err() {
            let Some(dup) = pts::dup_cloexec(master_fd) else {
                fail(&mut env, "Unable to duplicate ptmx for stdout");
                return ptr::null_mut();
            };
            ptmx_out = Some(dup);
        }

        if !flags.has_out() && (flags.has_in() || flags.has_err()) {
            let Some(null_fd) = open_dev_null_write() else {
                fail(&mut env, "Unable to open /dev/null");
                return ptr::null_mut();
            };
            dev_null = Some(null_fd);
        }

        ptmx = Some(master);
    }

    debug!(
        "istty stdin {} stdout {} stderr {}",
        jint::from(flags.has_in()),
        jint::from(flags.has_out()),
        jint::from(flags.has_err())
    );

    let stdin_pipe = if !flags.has_in() {
        let Some(pipe) = PipePair::new_cloexec() else {
            fail(&mut env, "Unable to create stdin pipe");
            return ptr::null_mut();
        };
        Some(pipe)
    } else {
        None
    };

    let stdout_pipe = if !flags.has_out() {
        let Some(pipe) = PipePair::new_cloexec() else {
            fail(&mut env, "Unable to create stdout pipe");
            return ptr::null_mut();
        };
        Some(pipe)
    } else {
        None
    };

    let stderr_pipe = if !flags.has_err() {
        let Some(pipe) = PipePair::new_cloexec() else {
            fail(&mut env, "Unable to create stderr pipe");
            return ptr::null_mut();
        };
        Some(pipe)
    } else {
        None
    };

    for (index, argument) in argv_ptrs.iter().enumerate() {
        if argument.is_null() {
            debug!("arg{index}=null");
        } else {
            debug!(
                "arg{index}={}",
                unsafe { CStr::from_ptr(*argument) }.to_string_lossy()
            );
        }
    }

    let pid: jint = unsafe { libc::fork() };

    if pid == -1 {
        fail(&mut env, "Unable to fork");
        return ptr::null_mut();
    }

    if pid > 0 {
        let stdin_read_fd = caller.stdin_read.into_raw_fd();
        let stdout_write_fd = caller.stdout_write.into_raw_fd();
        let stderr_write_opt = caller.stderr_write;

        if let Some(dup_in) = ptmx_in {
            pts::transfer_async(stdin_read_fd, dup_in.into_raw_fd(), None, true, true, false);
        } else if let Some(pipe) = stdin_pipe {
            drop(pipe.read);
            pts::transfer_async(stdin_read_fd, pipe.write.into_raw_fd(), None, true, true, false);
        }

        if flags.has_out() {
            let dup_out_fd = ptmx_out.map_or(-1, OwnedFd::into_raw_fd);
            pts::transfer_async(
                dup_out_fd,
                stdout_write_fd,
                Some(Box::new(move |failed| {
                    if failed {
                        on_remote_stdout_closed(pid);
                    }
                })),
                true,
                true,
                true,
            );
        } else if let Some(pipe) = stdout_pipe {
            drop(pipe.write);
            pts::transfer_async(
                pipe.read.into_raw_fd(),
                stdout_write_fd,
                Some(Box::new(move |failed| {
                    if failed {
                        on_remote_stdout_closed(pid);
                    }
                })),
                true,
                true,
                true,
            );

            if let (Some(dup_out), Some(null_fd)) = (ptmx_out, dev_null) {
                // Drain PTY master output (line-discipline echo and/or stderr) to /dev/null
                // so the slave PTY buffer never fills up and deadlocks the child process.
                pts::transfer_async(
                    dup_out.into_raw_fd(),
                    null_fd.into_raw_fd(),
                    None,
                    true,
                    true,
                    false,
                );
            }
        }

        if let Some(pipe) = stderr_pipe {
            drop(pipe.write);
            let err_out_fd = stderr_write_opt.map_or(-1, OwnedFd::into_raw_fd);
            pts::transfer_async(pipe.read.into_raw_fd(), err_out_fd, None, true, true, false);
        } else {
            // When stderr is routed to the PTY (`err_tty`), dropping `stderr_write_opt` closes it cleanly.
            drop(stderr_write_opt);
        }

        let master_ptmx_fd = ptmx.map_or(-1, OwnedFd::into_raw_fd);
        return int_array(&mut env, pid, master_ptmx_fd);
    }

    // Child process branch: only async-signal-safe operations below.
    if unsafe { libc::setsid() } < 0 {
        pts::ploge_child(format_args!("setsid"));
        unsafe { libc::_exit(1) };
    }

    if let Some(directory) = &directory {
        pts::debug_child(format_args!("attempt to chdir {}", CStrBytes(directory.as_c_str())));

        if unsafe { libc::access(directory.as_ptr(), libc::X_OK) } == 0 {
            if unsafe { libc::chdir(directory.as_ptr()) } == -1 {
                pts::ploge_child(format_args!("chdir {}", CStrBytes(directory.as_c_str())));
            } else {
                pts::debug_child(format_args!("chdir {}", CStrBytes(directory.as_c_str())));
            }
        } else {
            pts::ploge_child(format_args!("access {}", CStrBytes(directory.as_c_str())));
        }
    }

    let mut pts_fd: RawFd = -1;

    if let Some(ref master) = ptmx {
        let mut slave = [0 as c_char; libc::PATH_MAX as usize];

        if unsafe {
            libc::ptsname_r(
                master.as_raw_fd(),
                slave.as_mut_ptr(),
                libc::PATH_MAX as usize - 1,
            )
        } == -1
        {
            pts::ploge_child(format_args!("ptsname_r"));
            unsafe { libc::_exit(1) };
        }

        pts_fd = unsafe { libc::open(slave.as_ptr(), libc::O_RDWR | libc::O_CLOEXEC) };

        if pts_fd == -1 {
            pts::ploge_child(format_args!("open"));
            unsafe { libc::_exit(1) };
        }

        unsafe {
            libc::ioctl(pts_fd, libc::TIOCSCTTY as _, 0);
        }

        pts::debug_child(format_args!("pts {pts_fd}"));
    } else {
        pts::debug_child(format_args!("no need pts"));
    }

    if flags.has_in() {
        unsafe { libc::dup2(pts_fd, libc::STDIN_FILENO) };
        pts::debug_child(format_args!("pts -> in"));
    } else if let Some(ref pipe) = stdin_pipe {
        unsafe { libc::dup2(pipe.read.as_raw_fd(), libc::STDIN_FILENO) };
        pts::debug_child(format_args!("pipe -> in"));
    }

    if flags.has_out() {
        unsafe { libc::dup2(pts_fd, libc::STDOUT_FILENO) };
        pts::debug_child(format_args!("pts -> out"));
    } else if let Some(ref pipe) = stdout_pipe {
        unsafe { libc::dup2(pipe.write.as_raw_fd(), libc::STDOUT_FILENO) };
        pts::debug_child(format_args!("pipe -> out"));
    }

    if flags.has_err() {
        unsafe { libc::dup2(pts_fd, libc::STDERR_FILENO) };
        pts::debug_child(format_args!("pts -> err"));
    } else if let Some(ref pipe) = stderr_pipe {
        unsafe { libc::dup2(pipe.write.as_raw_fd(), libc::STDERR_FILENO) };
        pts::debug_child(format_args!("pipe -> err"));
    }

    pts::debug_child(format_args!(
        "istty stdin {} stdout {} stderr {}",
        unsafe { libc::isatty(libc::STDIN_FILENO) },
        unsafe { libc::isatty(libc::STDOUT_FILENO) },
        unsafe { libc::isatty(libc::STDERR_FILENO) }
    ));

    if pts_fd != -1 {
        unsafe { libc::close(pts_fd) };
    }
    drop(stdin_pipe);
    drop(stdout_pipe);
    drop(stderr_pipe);
    drop(ptmx);
    drop(ptmx_in);
    drop(ptmx_out);
    drop(dev_null);
    drop(caller);

    if environment.is_some() {
        unsafe { libc::execvpe(SHELL.as_ptr(), argv_ptrs.as_ptr(), env_ptrs.as_ptr()) };
    } else {
        unsafe { libc::execvp(SHELL.as_ptr(), argv_ptrs.as_ptr()) };
    }

    pts::ploge_child(format_args!("execv"));
    unsafe { libc::_exit(1) };
}

pub(crate) extern "system" fn native_set_window_size(
    _env: JNIEnv,
    _class: JClass,
    ptmx: jint,
    size: jlong,
) {
    pts::set_window_size(ptmx, size);
}

pub(crate) extern "system" fn native_wait_for(_env: JNIEnv, _class: JClass, pid: jint) -> jint {
    if pid < 0 {
        return -1;
    }

    let mut status: libc::c_int = 0;

    loop {
        let result = pts::retry(|| unsafe { libc::waitpid(pid, &raw mut status, 0) });

        if result == -1 {
            if std::io::Error::last_os_error().raw_os_error() == Some(libc::ECHILD) {
                return 0;
            }

            pts::ploge("waitpid");
            return -1;
        }

        if libc::WIFEXITED(status) {
            debug!("exited with {}", libc::WEXITSTATUS(status));
            return libc::WEXITSTATUS(status);
        }

        if libc::WIFSIGNALED(status) {
            let sig = libc::WTERMSIG(status);
            debug!("killed by signal {sig}");
            return 128 + sig;
        }
    }
}
