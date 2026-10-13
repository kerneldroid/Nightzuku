mod android;
mod cgroup;
mod logging;
mod misc;
mod selinux;

use std::ffi::{CStr, CString, c_char, c_int};
use std::io::Write;
use std::os::unix::ffi::OsStringExt;
use std::ptr;

const EXIT_FATAL_SET_CLASSPATH: c_int = 3;
const EXIT_FATAL_FORK: c_int = 4;
const EXIT_FATAL_APP_PROCESS: c_int = 5;
const EXIT_FATAL_UID: c_int = 6;
const EXIT_FATAL_PM_PATH: c_int = 7;
const EXIT_FATAL_BINDER_BLOCKED_BY_SELINUX: c_int = 10;

const PACKAGE_NAME: &str = "kerneldroid.nightzuku";
const SERVER_NAME: &str = "shizuku_server";
const SERVER_CLASS_PATH: &str = "rikka.shizuku.server.ShizukuService";

#[cfg(target_arch = "arm")]
const ABI: &str = "arm";
#[cfg(target_arch = "x86")]
const ABI: &str = "x86";
#[cfg(target_arch = "x86_64")]
const ABI: &str = "x86_64";
#[cfg(target_arch = "aarch64")]
const ABI: &str = "arm64";

fn exit(code: c_int) -> ! {
    std::process::exit(code)
}

fn flush() {
    let _ = std::io::stdout().flush();
}

fn c_arg(value: &str) -> CString {
    CString::new(value).unwrap_or_default()
}

fn dirname(path: &str) -> String {
    if !path.is_empty() && path.bytes().all(|byte| byte == b'/') {
        return "/".to_string();
    }
    let trimmed = path.trim_end_matches('/');
    match trimmed.rfind('/') {
        None => ".".to_string(),
        Some(0) => "/".to_string(),
        Some(index) => {
            let head = trimmed[..index].trim_end_matches('/');
            if head.is_empty() { "/".to_string() } else { head.to_string() }
        }
    }
}

fn access(path: &str, mode: c_int) -> c_int {
    let Ok(path) = CString::new(path) else {
        return -1;
    };
    unsafe { libc::access(path.as_ptr(), mode) }
}

fn display(value: *const c_char) -> String {
    if value.is_null() {
        return "(null)".to_string();
    }
    unsafe { CStr::from_ptr(value) }.to_string_lossy().into_owned()
}

#[cfg(debug_assertions)]
fn push_debug_vm_params(argv: &mut Vec<CString>) {
    let api_level = android::api_level();
    argv.push(c_arg("-Xcompiler-option"));
    argv.push(c_arg("--debuggable"));
    if api_level >= 30 {
        argv.push(c_arg("-XjdwpProvider:adbconnection"));
        argv.push(c_arg("-XjdwpOptions:suspend=n,server=y"));
    } else if api_level >= 28 {
        argv.push(c_arg("-XjdwpProvider:internal"));
        argv.push(c_arg("-XjdwpOptions:transport=dt_android_adb,suspend=n,server=y"));
    } else {
        argv.push(c_arg("-agentlib:jdwp=transport=dt_android_adb,suspend=n,server=y"));
    }
}

fn run_server(dex_path: &str, main_class: &str, process_name: &str) {
    let Ok(classpath) = CString::new(dex_path) else {
        logging::loge("can't set CLASSPATH\n");
        exit(EXIT_FATAL_SET_CLASSPATH);
    };

    if unsafe { libc::setenv(c"CLASSPATH".as_ptr(), classpath.as_ptr(), 1) } != 0 {
        logging::loge("can't set CLASSPATH\n");
        exit(EXIT_FATAL_SET_CLASSPATH);
    }

    let lib_path = format!("{}/lib/{}", dirname(dex_path), ABI);

    let mut argv = Vec::with_capacity(8);
    argv.push(c_arg("/system/bin/app_process"));
    argv.push(c_arg(&format!("-Djava.class.path={dex_path}")));
    argv.push(c_arg(&format!("-Dshizuku.library.path={lib_path}")));
    #[cfg(debug_assertions)]
    push_debug_vm_params(&mut argv);
    argv.push(c_arg("/system/bin"));
    argv.push(c_arg(&format!("--nice-name={process_name}")));
    argv.push(c_arg(main_class));

    let mut argv_ptrs: Vec<*const c_char> = argv.iter().map(|entry| entry.as_ptr()).collect();
    argv_ptrs.push(ptr::null());

    logging::logd("exec app_process");

    if unsafe { libc::execvp(argv_ptrs[0], argv_ptrs.as_ptr()) } != 0 {
        exit(EXIT_FATAL_APP_PROCESS);
    }
}

fn start_server(path: &str, main_class: &str, process_name: &str) -> ! {
    let mut restart_count: c_int = 0;
    let max_restarts: c_int = 5;
    let restart_delay_secs: libc::c_uint = 5;

    loop {
        let pid = unsafe { libc::fork() };
        if pid == -1 {
            eprintln!("fatal: can't fork");
            exit(EXIT_FATAL_FORK);
        } else if pid == 0 {
            logging::logd("child");
            unsafe {
                libc::setsid();
            }
            unsafe {
                libc::chdir(c"/".as_ptr());
            }
            let fd = unsafe { libc::open(c"/dev/null".as_ptr(), libc::O_RDWR) };
            if fd != -1 {
                unsafe {
                    libc::dup2(fd, 0);
                    libc::dup2(fd, 1);
                    libc::dup2(fd, 2);
                }
                if fd > 2 {
                    unsafe {
                        libc::close(fd);
                    }
                }
            }
            run_server(path, main_class, process_name);
            unsafe {
                libc::_exit(1);
            }
        } else {
            if restart_count == 0 {
                println!("info: shizuku_server pid is {pid}");
            }

            let mut status: c_int = 0;
            unsafe {
                libc::waitpid(pid, &mut status, 0);
            }

            let mut exit_code: c_int = -1;
            let mut killed_by_signal = false;
            if libc::WIFEXITED(status) {
                exit_code = libc::WEXITSTATUS(status);
            } else if libc::WIFSIGNALED(status) {
                exit_code = 128 + libc::WTERMSIG(status);
                killed_by_signal = true;
            }

            println!("info: shizuku_server exited with code {exit_code}");

            if !killed_by_signal && exit_code == 0 {
                println!("info: shizuku_server exited cleanly, no restart");
                exit(0);
            }

            restart_count += 1;
            if restart_count > max_restarts {
                println!("fatal: shizuku_server crashed {restart_count} times, giving up");
                exit(EXIT_FATAL_APP_PROCESS);
            }

            println!(
                "info: restarting shizuku_server in {restart_delay_secs} seconds (attempt {restart_count}/{max_restarts})..."
            );
            flush();
            unsafe {
                libc::sleep(restart_delay_secs);
            }
        }
    }
}

fn check_selinux(s: &CStr, t: *const c_char, class: &CStr, perm: &CStr) -> c_int {
    let res = selinux::selinux_check_access(s.as_ptr(), t, class.as_ptr(), perm.as_ptr(), ptr::null_mut());
    if res != 0 {
        println!(
            "info: selinux_check_access {} {} {} {}: {}",
            s.to_string_lossy(),
            display(t),
            class.to_string_lossy(),
            perm.to_string_lossy(),
            res
        );
        flush();
    }
    res
}

fn switch_cgroup() -> c_int {
    let pid = unsafe { libc::getpid() };
    if cgroup::switch_cgroup("/acct", pid) {
        println!("info: switch cgroup succeeded, cgroup in /acct");
        return 0;
    }
    if cgroup::switch_cgroup("/dev/cg2_bpf", pid) {
        println!("info: switch cgroup succeeded, cgroup in /dev/cg2_bpf");
        return 0;
    }
    if cgroup::switch_cgroup("/sys/fs/cgroup", pid) {
        println!("info: switch cgroup succeeded, cgroup in /sys/fs/cgroup");
        return 0;
    }
    if let Some(value) = android::prop("ro.config.per_app_memcg") {
        if !value.as_bytes().starts_with(b"false") {
            if cgroup::switch_cgroup("/dev/memcg/apps", pid) {
                println!("info: switch cgroup succeeded, cgroup in /dev/memcg/apps");
                return 0;
            }
        }
    }
    println!("warn: can't switch cgroup");
    flush();
    -1
}

fn main() {
    unsafe {
        libc::signal(libc::SIGPIPE, libc::SIG_DFL);
    }

    let mut apk_path = String::new();
    for arg in std::env::args_os() {
        let bytes = arg.into_vec();
        if bytes.starts_with(b"--apk=") {
            apk_path = String::from_utf8_lossy(&bytes[6..]).into_owned();
        }
    }

    let uid = unsafe { libc::getuid() };
    if uid != 0 && uid != 2000 {
        eprintln!("fatal: run Shizuku from non root nor adb user (uid={uid}).");
        exit(EXIT_FATAL_UID);
    }

    selinux::init();

    if uid == 0 {
        switch_cgroup();

        if android::api_level() >= 29 {
            println!("info: switching mount namespace to init...");
            misc::switch_mnt_ns(1);
        }
    }

    if uid == 0 {
        let mut context: *mut c_char = ptr::null_mut();
        if selinux::getcon(&mut context) == 0 {
            let mut res = 0;
            res |= check_selinux(c"u:r:untrusted_app:s0", context, c"binder", c"call");
            res |= check_selinux(c"u:r:untrusted_app:s0", context, c"binder", c"transfer");

            if res != 0 {
                eprintln!(
                    "fatal: the su you are using does not allow app (u:r:untrusted_app:s0) to connect to su ({}) with binder.",
                    display(context)
                );
                exit(EXIT_FATAL_BINDER_BLOCKED_BY_SELINUX);
            }
            selinux::freecon(context);
        }
    }

    println!("info: starter begin");
    flush();

    println!("info: killing old process...");
    flush();

    misc::foreach_proc(|pid| {
        if pid == unsafe { libc::getpid() } {
            return;
        }

        let mut buf = [0u8; 1024];
        if misc::get_proc_name(pid, &mut buf) != 0 {
            return;
        }

        let name = unsafe { CStr::from_ptr(buf.as_ptr().cast()) };
        if name.to_bytes() != SERVER_NAME.as_bytes() {
            return;
        }

        if unsafe { libc::kill(pid, libc::SIGKILL) } == 0 {
            println!("info: killed {pid} ({})", name.to_string_lossy());
        } else if std::io::Error::last_os_error().raw_os_error() == Some(libc::EPERM) {
            println!("warn: can't kill {pid} (EPERM), ignoring potential fake process.");
        } else {
            println!("warn: failed to kill {pid} ({})", name.to_string_lossy());
        }
    });

    if access(&apk_path, libc::R_OK) == 0 {
        println!("info: use apk path from argv");
        flush();
    }

    if apk_path.is_empty() {
        let command = c_arg(&format!("pm path {PACKAGE_NAME}"));
        let pipe = unsafe { libc::popen(command.as_ptr(), c"r".as_ptr()) };
        if !pipe.is_null() {
            let mut line = [0u8; libc::PATH_MAX as usize];
            unsafe {
                libc::fgets(line.as_mut_ptr().cast(), libc::PATH_MAX, pipe);
            }
            let end = line.iter().position(|byte| *byte == 0).unwrap_or(line.len());
            let line = misc::trim(&String::from_utf8_lossy(&line[..end]));
            if let Some(path) = line.strip_prefix("package:") {
                apk_path = path.to_string();
            }
            unsafe {
                libc::pclose(pipe);
            }
        }
    }

    if apk_path.is_empty() {
        eprintln!("fatal: can't get path of manager");
        exit(EXIT_FATAL_PM_PATH);
    }

    println!("info: apk path is {apk_path}");
    if access(&apk_path, libc::R_OK) != 0 {
        eprintln!("fatal: can't access manager {apk_path}");
        exit(EXIT_FATAL_PM_PATH);
    }

    println!("info: starting server...");
    flush();
    logging::logd("start_server");
    start_server(&apk_path, SERVER_CLASS_PATH, SERVER_NAME);
}
