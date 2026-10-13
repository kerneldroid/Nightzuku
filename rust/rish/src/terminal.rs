use jni::JNIEnv;
use jni::objects::JClass;
use jni::sys::{jbyte, jint, jlong};
use log::{debug, info};

use crate::pts;

extern "C" fn on_window_change(_signal: libc::c_int) {
    pts::winch_mutex().unlock();
}

pub(crate) extern "system" fn native_prepare(_env: JNIEnv, _class: JClass) -> jbyte {
    let mut atty: jbyte = 0;

    if unsafe { libc::isatty(libc::STDIN_FILENO) } != 0 {
        atty |= pts::ATTY_IN;
    }

    if unsafe { libc::isatty(libc::STDOUT_FILENO) } != 0 {
        atty |= pts::ATTY_OUT;
    }

    if unsafe { libc::isatty(libc::STDERR_FILENO) } != 0 {
        atty |= pts::ATTY_ERR;
    }

    debug!(
        "istty stdin {} stdout {} stderr {}",
        jint::from(atty & pts::ATTY_IN != 0),
        jint::from(atty & pts::ATTY_OUT != 0),
        jint::from(atty & pts::ATTY_ERR != 0)
    );

    if atty & pts::ATTY_OUT != 0 {
        let mut winch = std::mem::MaybeUninit::<libc::sigset_t>::zeroed();

        unsafe {
            libc::sigemptyset(winch.as_mut_ptr());
            libc::sigaddset(winch.as_mut_ptr(), libc::SIGWINCH);
            libc::pthread_sigmask(libc::SIG_BLOCK, winch.as_ptr(), std::ptr::null_mut());
        }
    }

    atty
}

pub(crate) extern "system" fn native_start(
    _env: JNIEnv,
    _class: JClass,
    tty: jbyte,
    stdin_pipe: jint,
    stdout_pipe: jint,
    stderr_pipe: jint,
) -> jint {
    let in_tty = tty & pts::ATTY_IN != 0;
    let out_tty = tty & pts::ATTY_OUT != 0;
    let err_tty = tty & pts::ATTY_ERR != 0;

    let tty_fd = if in_tty {
        libc::STDIN_FILENO
    } else if out_tty {
        libc::STDOUT_FILENO
    } else if err_tty {
        libc::STDERR_FILENO
    } else {
        -1
    };

    let previous = if tty == pts::ATTY_ALL {
        pts::make_tty_raw(tty_fd)
    } else {
        None
    };

    pts::process_exit_mutex().lock();

    pts::transfer_async(libc::STDIN_FILENO, stdin_pipe, None, true, true);
    pts::transfer_async(
        stdout_pipe,
        libc::STDOUT_FILENO,
        Some(Box::new(move || {
            info!("remote exit");

            if let Some(previous) = previous {
                pts::restore_fd(tty_fd, &previous);
            }

            pts::process_exit_mutex().unlock();
        })),
        true,
        true,
    );

    if !err_tty {
        pts::transfer_async(stderr_pipe, libc::STDERR_FILENO, None, true, true);
    }

    unsafe {
        libc::signal(
            libc::SIGWINCH,
            on_window_change as extern "C" fn(libc::c_int) as libc::sighandler_t,
        )
    };

    tty_fd
}

pub(crate) extern "system" fn native_wait_for_window_size_change(
    _env: JNIEnv,
    _class: JClass,
    fd: jint,
) -> jlong {
    pts::winch_mutex().lock();

    pts::get_window_size(fd)
}

pub(crate) extern "system" fn native_wait_for_process_exit(_env: JNIEnv, _class: JClass) {
    let mutex = pts::process_exit_mutex();

    mutex.lock();
    mutex.unlock();
}
