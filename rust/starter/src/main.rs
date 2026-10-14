mod android;
mod cgroup;
mod logging;
mod misc;
mod selinux;

use std::ffi::{CStr, CString, c_char, c_int};
use std::io::Write;
use std::mem;
use std::os::unix::ffi::OsStringExt;
use std::ptr;
use std::time::Instant;

const EXIT_FATAL_SET_CLASSPATH: c_int = 3;
const EXIT_FATAL_FORK: c_int = 4;
const EXIT_FATAL_APP_PROCESS: c_int = 5;
const EXIT_FATAL_UID: c_int = 6;
const EXIT_FATAL_PM_PATH: c_int = 7;
const EXIT_FATAL_BINDER_BLOCKED_BY_SELINUX: c_int = 10;
const EXIT_MANAGER_APP_NOT_FOUND: c_int = 50;
const STABLE_UPTIME_RESET_SECS: u64 = 60;

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
    let _ = std::io::stderr().flush();
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

fn detach_stdio_to_dev_null() {
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
    unsafe {
        libc::signal(libc::SIGPIPE, libc::SIG_DFL);
        libc::signal(libc::SIGHUP, libc::SIG_DFL);
    }

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
    #[cfg(debug_assertions)]
    argv.push(c_arg("--debug"));

    let mut argv_ptrs: Vec<*const c_char> = argv.iter().map(|entry| entry.as_ptr()).collect();
    argv_ptrs.push(ptr::null());

    logging::logd("exec app_process");

    if unsafe { libc::execvp(argv_ptrs[0], argv_ptrs.as_ptr()) } != 0 {
        exit(EXIT_FATAL_APP_PROCESS);
    }
}

fn supervise_server(path: &str, main_class: &str, process_name: &str, mut pipe_write_fd: c_int) -> ! {
    let mut restart_count: c_int = 0;
    let max_restarts: c_int = 5;
    let restart_delay_secs: libc::c_uint = 5;

    loop {
        let pid = unsafe { libc::fork() };
        if pid == -1 {
            logging::loge("fatal: can't fork shizuku_server");
            if pipe_write_fd != -1 {
                unsafe {
                    libc::close(pipe_write_fd);
                }
            }
            exit(EXIT_FATAL_FORK);
        } else if pid == 0 {
            if pipe_write_fd != -1 {
                unsafe {
                    libc::close(pipe_write_fd);
                }
            }
            logging::logd("child");
            unsafe {
                libc::setsid();
                libc::chdir(c"/".as_ptr());
            }
            detach_stdio_to_dev_null();
            run_server(path, main_class, process_name);
            unsafe {
                libc::_exit(EXIT_FATAL_APP_PROCESS);
            }
        }

        let started_at = Instant::now();
        if pipe_write_fd != -1 {
            let pid_bytes = pid.to_ne_bytes();
            let _ = unsafe {
                libc::write(pipe_write_fd, pid_bytes.as_ptr().cast(), pid_bytes.len())
            };
            unsafe {
                libc::close(pipe_write_fd);
            }
            pipe_write_fd = -1;
            detach_stdio_to_dev_null();
        }
        logging::logi(&format!("shizuku_server pid is {pid}"));

        let mut status: c_int = 0;
        loop {
            let waited = unsafe { libc::waitpid(pid, &mut status, 0) };
            if waited == -1 && misc::errno() == libc::EINTR {
                continue;
            }
            break;
        }

        let uptime_secs = started_at.elapsed().as_secs();
        let mut exit_code: c_int = -1;
        let mut killed_by_signal = false;
        if libc::WIFEXITED(status) {
            exit_code = libc::WEXITSTATUS(status);
        } else if libc::WIFSIGNALED(status) {
            exit_code = 128 + libc::WTERMSIG(status);
            killed_by_signal = true;
        }

        logging::logi(&format!(
            "shizuku_server exited with code {exit_code} after {uptime_secs}s"
        ));

        if !killed_by_signal && exit_code == 0 {
            logging::logi("shizuku_server exited cleanly, no restart");
            exit(0);
        }

        if !killed_by_signal && exit_code == EXIT_MANAGER_APP_NOT_FOUND {
            logging::logw("manager application not found, stopping supervisor without restart");
            exit(0);
        }

        if uptime_secs >= STABLE_UPTIME_RESET_SECS {
            restart_count = 0;
        }

        restart_count += 1;
        if restart_count > max_restarts {
            logging::loge(&format!(
                "fatal: shizuku_server crashed {restart_count} times consecutively, giving up"
            ));
            exit(EXIT_FATAL_APP_PROCESS);
        }

        logging::logw(&format!(
            "restarting shizuku_server in {restart_delay_secs} seconds (attempt {restart_count}/{max_restarts})..."
        ));
        unsafe {
            libc::sleep(restart_delay_secs);
        }
    }
}

fn start_server(path: &str, main_class: &str, process_name: &str) -> ! {
    let mut pipe_fds = [-1 as c_int; 2];
    if unsafe { libc::pipe(pipe_fds.as_mut_ptr()) } != 0 {
        pipe_fds = [-1, -1];
    }

    let supervisor_pid = unsafe { libc::fork() };
    if supervisor_pid == -1 {
        if pipe_fds[0] != -1 {
            unsafe {
                libc::close(pipe_fds[0]);
                libc::close(pipe_fds[1]);
            }
        }
        eprintln!("fatal: can't fork");
        exit(EXIT_FATAL_FORK);
    } else if supervisor_pid == 0 {
        if pipe_fds[0] != -1 {
            unsafe {
                libc::close(pipe_fds[0]);
            }
        }
        unsafe {
            libc::setsid();
            libc::chdir(c"/".as_ptr());
        }
        supervise_server(path, main_class, process_name, pipe_fds[1]);
    }

    if pipe_fds[1] != -1 {
        unsafe {
            libc::close(pipe_fds[1]);
        }
    }

    let mut server_pid: libc::pid_t = supervisor_pid;
    if pipe_fds[0] != -1 {
        let mut buf = [0u8; mem::size_of::<libc::pid_t>()];
        let read_bytes = unsafe {
            libc::read(pipe_fds[0], buf.as_mut_ptr().cast(), buf.len())
        };
        unsafe {
            libc::close(pipe_fds[0]);
        }
        if read_bytes == buf.len() as isize {
            server_pid = libc::pid_t::from_ne_bytes(buf);
        } else {
            eprintln!("fatal: failed to spawn shizuku_server");
            exit(EXIT_FATAL_FORK);
        }
    }

    println!("info: shizuku_server pid is {server_pid}");
    println!("info: shizuku_starter exit with 0");
    flush();
    exit(0);
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

fn kill_old_processes() {
    let self_pid = unsafe { libc::getpid() };
    let self_exe = unsafe {
        let mut buf = [0u8; libc::PATH_MAX as usize];
        let len = libc::readlink(
            c"/proc/self/exe".as_ptr(),
            buf.as_mut_ptr().cast(),
            buf.len() - 1,
        );
        if len > 0 {
            Some(buf[..len as usize].to_vec())
        } else {
            None
        }
    };

    misc::foreach_proc(|pid| {
        if pid == self_pid {
            return;
        }

        let mut buf = [0u8; 1024];
        if misc::get_proc_name(pid, &mut buf) != 0 {
            return;
        }

        let name = unsafe { CStr::from_ptr(buf.as_ptr().cast()) };
        let name_bytes = name.to_bytes();

        let is_server = name_bytes == SERVER_NAME.as_bytes();
        let is_old_supervisor = if !is_server {
            if let Some(ref my_exe) = self_exe {
                if let Ok(exe_path) = CString::new(format!("/proc/{pid}/exe")) {
                    let mut exe_buf = [0u8; libc::PATH_MAX as usize];
                    let len = unsafe {
                        libc::readlink(
                            exe_path.as_ptr(),
                            exe_buf.as_mut_ptr().cast(),
                            exe_buf.len() - 1,
                        )
                    };
                    len > 0 && &exe_buf[..len as usize] == my_exe.as_slice()
                } else {
                    false
                }
            } else {
                false
            }
        } else {
            false
        };

        if !is_server && !is_old_supervisor {
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
}

fn main() {
    unsafe {
        libc::signal(libc::SIGPIPE, libc::SIG_IGN);
        libc::signal(libc::SIGHUP, libc::SIG_IGN);
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

    kill_old_processes();

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
