// Portions of the dual-stream stdout/stderr drain synchronization are adapted from
// https://github.com/d4rken-org/porter-api (eu.darken.porter / porsh, Apache-2.0 / MIT).

use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};

use jni::JNIEnv;
use jni::objects::JClass;
use jni::sys::{jbyte, jint, jlong};
use log::{debug, info};

use crate::pts::{self, AttyFlags};

fn install_sigwinch_handler() {
    unsafe {
        let mut winch_mask = std::mem::MaybeUninit::<libc::sigset_t>::zeroed();
        libc::sigemptyset(winch_mask.as_mut_ptr());
        libc::sigaddset(winch_mask.as_mut_ptr(), libc::SIGWINCH);
        libc::pthread_sigmask(libc::SIG_UNBLOCK, winch_mask.as_ptr(), std::ptr::null_mut());

        let mut sa = std::mem::MaybeUninit::<libc::sigaction>::zeroed();
        let sa_ptr = sa.as_mut_ptr();
        (*sa_ptr).sa_sigaction = pts::on_sigwinch as extern "C" fn(libc::c_int) as usize;
        libc::sigemptyset(&raw mut (*sa_ptr).sa_mask);
        (*sa_ptr).sa_flags = libc::SA_RESTART;
        libc::sigaction(libc::SIGWINCH, sa.as_ptr(), std::ptr::null_mut());
    }
}

pub(crate) extern "system" fn native_prepare(_env: JNIEnv, _class: JClass) -> jbyte {
    pts::initialize_sync_primitives();

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

    let flags = AttyFlags::from_bits(atty);
    debug!(
        "istty stdin {} stdout {} stderr {}",
        jint::from(flags.has_in()),
        jint::from(flags.has_out()),
        jint::from(flags.has_err())
    );

    if flags.has_out() {
        install_sigwinch_handler();
    }

    flags.bits()
}

pub(crate) extern "system" fn native_start(
    _env: JNIEnv,
    _class: JClass,
    tty: jbyte,
    stdin_pipe: jint,
    stdout_pipe: jint,
    stderr_pipe: jint,
) -> jint {
    let flags = AttyFlags::from_bits(tty);

    let tty_fd = if flags.has_in() {
        libc::STDIN_FILENO
    } else if flags.has_out() {
        libc::STDOUT_FILENO
    } else if flags.has_err() {
        libc::STDERR_FILENO
    } else {
        -1
    };

    let previous = if flags.is_all() {
        pts::make_tty_raw(tty_fd)
    } else {
        None
    };

    pts::process_exit_gate().reset();

    let pending = Arc::new(AtomicUsize::new(if flags.has_err() { 1 } else { 2 }));
    let pending_out = Arc::clone(&pending);

    let finish_stream = move |pending_ref: &AtomicUsize, prev_tty: Option<libc::termios>| {
        if pending_ref.fetch_sub(1, Ordering::AcqRel) == 1 {
            info!("remote exit");

            if let Some(previous) = prev_tty {
                pts::restore_fd(tty_fd, &previous);
            }

            pts::process_exit_gate().notify_exit();
        }
    };

    pts::transfer_async(libc::STDIN_FILENO, stdin_pipe, None, false, true, false);
    pts::transfer_async(
        stdout_pipe,
        libc::STDOUT_FILENO,
        Some(Box::new(move |_failed| {
            finish_stream(&pending_out, previous);
        })),
        true,
        false,
        false,
    );

    if !flags.has_err() {
        let pending_err = Arc::clone(&pending);
        pts::transfer_async(
            stderr_pipe,
            libc::STDERR_FILENO,
            Some(Box::new(move |_failed| {
                finish_stream(&pending_err, previous);
            })),
            true,
            false,
            false,
        );
    } else if stderr_pipe >= 0 {
        unsafe { libc::close(stderr_pipe) };
    }

    if flags.has_out() {
        install_sigwinch_handler();
    }

    tty_fd
}

pub(crate) extern "system" fn native_wait_for_window_size_change(
    _env: JNIEnv,
    _class: JClass,
    fd: jint,
) -> jlong {
    pts::wait_for_sigwinch();
    pts::get_window_size(fd)
}

pub(crate) extern "system" fn native_wait_for_process_exit(_env: JNIEnv, _class: JClass) {
    pts::process_exit_gate().wait();
}
