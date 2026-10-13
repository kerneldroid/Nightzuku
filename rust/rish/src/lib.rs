mod host;
mod pts;
mod terminal;

use std::ffi::c_void;

use jni::sys::{JNI_ERR, JNI_VERSION_1_6, jint};
use jni::{JNIEnv, JavaVM, NativeMethod};
use log::error;

const HOST_CLASS: &str = "rikka/rish/RishHost";
const TERMINAL_CLASS: &str = "rikka/rish/RishTerminal";

#[cfg(target_os = "android")]
fn init_logging() {
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Debug)
            .with_tag("RISH"),
    );
}

#[cfg(not(target_os = "android"))]
fn init_logging() {}

fn register_natives(env: &mut JNIEnv, class_name: &str, methods: &[NativeMethod]) -> bool {
    let Ok(class) = env.find_class(class_name) else {
        error!("Unable to find {class_name}.");
        return false;
    };

    if let Err(source) = env.register_native_methods(class, methods) {
        error!("Unable to register the {class_name} native methods: {source}");
        return false;
    }

    true
}

#[unsafe(no_mangle)]
pub extern "system" fn JNI_OnLoad(vm: JavaVM, _reserved: *mut c_void) -> jint {
    init_logging();
    pts::initialize_mutexes();

    let Ok(mut env) = vm.get_env() else {
        error!("Unable to obtain the JNI environment.");
        return JNI_ERR;
    };

    let host_methods = [
        NativeMethod {
            name: "start".into(),
            sig: "([BI[BI[BBIII)[I".into(),
            fn_ptr: host::native_start as *mut c_void,
        },
        NativeMethod {
            name: "setWindowSize".into(),
            sig: "(IJ)V".into(),
            fn_ptr: host::native_set_window_size as *mut c_void,
        },
        NativeMethod {
            name: "waitFor".into(),
            sig: "(I)I".into(),
            fn_ptr: host::native_wait_for as *mut c_void,
        },
    ];

    if !register_natives(&mut env, HOST_CLASS, &host_methods) {
        return JNI_ERR;
    }

    let terminal_methods = [
        NativeMethod {
            name: "prepare".into(),
            sig: "()B".into(),
            fn_ptr: terminal::native_prepare as *mut c_void,
        },
        NativeMethod {
            name: "start".into(),
            sig: "(BIII)I".into(),
            fn_ptr: terminal::native_start as *mut c_void,
        },
        NativeMethod {
            name: "waitForWindowSizeChange".into(),
            sig: "(I)J".into(),
            fn_ptr: terminal::native_wait_for_window_size_change as *mut c_void,
        },
        NativeMethod {
            name: "waitForProcessExit".into(),
            sig: "()V".into(),
            fn_ptr: terminal::native_wait_for_process_exit as *mut c_void,
        },
    ];

    if !register_natives(&mut env, TERMINAL_CLASS, &terminal_methods) {
        return JNI_ERR;
    }

    JNI_VERSION_1_6
}
