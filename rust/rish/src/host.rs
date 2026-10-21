use std::ffi::{CStr, CString, c_char};
use std::ptr;

use jni::JNIEnv;
use jni::objects::{JByteArray, JClass};
use jni::sys::{jbyte, jint, jintArray, jlong};
use log::{debug, error, warn};

use crate::pts::{self, CStrBytes};

const SHELL: &CStr = c"/system/bin/sh";

fn decode_vector(env: &JNIEnv, block: &JByteArray, count: jint) -> Option<Vec<CString>> {
    let bytes = env.convert_byte_array(block).ok()?;
    let mut offset = 0;
    let mut result = Vec::with_capacity(count.max(0) as usize);

    for _ in 0..count {
        let tail = bytes.get(offset..)?;
        let length = tail.iter().position(|byte| *byte == 0)?;

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

fn close_fd(fd: &mut jint) {
    if *fd >= 0 {
        unsafe {
            libc::close(*fd);
        }
        *fd = -1;
    }
}

fn close_pipe(pipe: &mut [jint; 2]) {
    close_fd(&mut pipe[0]);
    close_fd(&mut pipe[1]);
}

fn cleanup_fds(
    ptmx: &mut jint,
    stdin_pipe: &mut [jint; 2],
    stdout_pipe: &mut [jint; 2],
    stderr_pipe: &mut [jint; 2],
) {
    close_fd(ptmx);
    close_pipe(stdin_pipe);
    close_pipe(stdout_pipe);
    close_pipe(stderr_pipe);
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
    let in_tty = tty & pts::ATTY_IN != 0;
    let out_tty = tty & pts::ATTY_OUT != 0;
    let err_tty = tty & pts::ATTY_ERR != 0;

    let Some(arguments) = decode_vector(&env, &arg_block, argc) else {
        error!("Unable to decode the argument block.");
        fail(&mut env, "Unable to decode the argument block");
        return ptr::null_mut();
    };

    let mut argv = Vec::with_capacity(arguments.len() + 1);
    argv.push(SHELL.to_owned());
    argv.extend(arguments);

    let mut argv_ptrs: Vec<*const c_char> =
        argv.iter().map(|argument| argument.as_ptr()).collect();
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
                    .position(|byte| *byte == 0)
                    .unwrap_or(bytes.len());

                CString::new(&bytes[..length]).ok()
            }
            Err(source) => {
                error!("Unable to decode the working directory: {source}");
                None
            }
        }
    };

    let mut ptmx = -1;

    if tty != 0 {
        ptmx = pts::open_ptmx();

        if ptmx == -1 {
            fail(&mut env, "Unable to open ptmx");
            return ptr::null_mut();
        }

        debug!("ptmx {ptmx}");
    }

    let mut stdin_pipe = [-1, -1];
    let mut stdout_pipe = [-1, -1];
    let mut stderr_pipe = [-1, -1];

    debug!(
        "istty stdin {} stdout {} stderr {}",
        jint::from(in_tty),
        jint::from(out_tty),
        jint::from(err_tty)
    );

    if !in_tty && unsafe { libc::pipe2(stdin_pipe.as_mut_ptr(), libc::O_CLOEXEC) } != 0 {
        cleanup_fds(&mut ptmx, &mut stdin_pipe, &mut stdout_pipe, &mut stderr_pipe);
        fail(&mut env, "Unable to create stdin pipe");
        return ptr::null_mut();
    }

    if !out_tty && unsafe { libc::pipe2(stdout_pipe.as_mut_ptr(), libc::O_CLOEXEC) } != 0 {
        cleanup_fds(&mut ptmx, &mut stdin_pipe, &mut stdout_pipe, &mut stderr_pipe);
        fail(&mut env, "Unable to create stdout pipe");
        return ptr::null_mut();
    }

    if !err_tty && unsafe { libc::pipe2(stderr_pipe.as_mut_ptr(), libc::O_CLOEXEC) } != 0 {
        cleanup_fds(&mut ptmx, &mut stdin_pipe, &mut stdout_pipe, &mut stderr_pipe);
        fail(&mut env, "Unable to create stderr pipe");
        return ptr::null_mut();
    }

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
        cleanup_fds(&mut ptmx, &mut stdin_pipe, &mut stdout_pipe, &mut stderr_pipe);
        fail(&mut env, "Unable to fork");
        return ptr::null_mut();
    }

    if pid > 0 {
        if in_tty {
            pts::transfer_async(stdin_read, ptmx, None, true, true);
        } else {
            pts::transfer_async(stdin_read, stdin_pipe[1], None, true, true);
            unsafe { libc::close(stdin_pipe[0]) };
        }

        if out_tty {
            pts::transfer_async(
                ptmx,
                stdout_write,
                Some(Box::new(move || {
                    warn!("client dead, kill forked process");
                    unsafe { libc::kill(pid, libc::SIGKILL) };
                })),
                true,
                true,
            );
        } else {
            pts::transfer_async(
                stdout_pipe[0],
                stdout_write,
                Some(Box::new(move || {
                    warn!("client dead, kill forked process");
                    unsafe { libc::kill(pid, libc::SIGKILL) };
                })),
                true,
                true,
            );
            unsafe { libc::close(stdout_pipe[1]) };
        }

        if !err_tty {
            pts::transfer_async(stderr_pipe[0], stderr_write, None, true, true);
            unsafe { libc::close(stderr_pipe[1]) };
        }

        return int_array(&mut env, pid, ptmx);
    }

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

    let mut pts = -1;

    if tty != 0 {
        let mut slave = [0; libc::PATH_MAX as usize];

        if unsafe { libc::ptsname_r(ptmx, slave.as_mut_ptr(), libc::PATH_MAX as usize - 1) } == -1 {
            pts::ploge_child(format_args!("ptsname_r"));
            unsafe { libc::_exit(1) };
        }

        pts = unsafe { libc::open(slave.as_ptr(), libc::O_RDWR | libc::O_CLOEXEC) };

        if pts == -1 {
            pts::ploge_child(format_args!("open"));
        }

        pts::debug_child(format_args!("pts {pts}"));
    } else {
        pts::debug_child(format_args!("no need pts"));
    }

    if in_tty {
        unsafe { libc::dup2(pts, libc::STDIN_FILENO) };
        pts::debug_child(format_args!("pts -> in"));
    } else {
        unsafe { libc::dup2(stdin_pipe[0], libc::STDIN_FILENO) };
        unsafe { libc::close(stdin_pipe[0]) };
        unsafe { libc::close(stdin_pipe[1]) };
        pts::debug_child(format_args!("pipe -> in"));
    }

    if out_tty {
        unsafe { libc::dup2(pts, libc::STDOUT_FILENO) };
        pts::debug_child(format_args!("pts -> out"));
    } else {
        unsafe { libc::dup2(stdout_pipe[1], libc::STDOUT_FILENO) };
        unsafe { libc::close(stdout_pipe[0]) };
        unsafe { libc::close(stdout_pipe[1]) };
        pts::debug_child(format_args!("pipe -> out"));
    }

    if err_tty {
        unsafe { libc::dup2(pts, libc::STDERR_FILENO) };
        pts::debug_child(format_args!("pts -> err"));
    } else {
        unsafe { libc::dup2(stderr_pipe[1], libc::STDERR_FILENO) };
        unsafe { libc::close(stderr_pipe[0]) };
        unsafe { libc::close(stderr_pipe[1]) };
        pts::debug_child(format_args!("pipe -> err"));
    }

    pts::debug_child(format_args!(
        "istty stdin {} stdout {} stderr {}",
        unsafe { libc::isatty(libc::STDIN_FILENO) },
        unsafe { libc::isatty(libc::STDOUT_FILENO) },
        unsafe { libc::isatty(libc::STDERR_FILENO) }
    ));

    if pts != -1 {
        unsafe { libc::close(pts) };
    }
    if ptmx != -1 {
        unsafe { libc::close(ptmx) };
    }

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
        let result = pts::retry(|| unsafe { libc::waitpid(pid, &mut status, 0) });

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
            debug!("killed by signal {}", libc::WTERMSIG(status));
            return 0;
        }
    }
}
