mod cipher;
mod spake;

use std::ffi::c_void;

use jni::objects::{JByteArray, JClass, JObject};
use jni::sys::{JNI_ERR, JNI_FALSE, JNI_TRUE, JNI_VERSION_1_6, jboolean, jbyteArray, jint, jlong};
use jni::{JNIEnv, JavaVM, NativeMethod};
use log::error;
use rand::RngCore;
use rand::rngs::OsRng;

use crate::cipher::PairingCipher;
use crate::spake::{MESSAGE_SIZE, Spake2State, SpakeRole};

const PAIRING_CONTEXT_CLASS: &str = "moe/shizuku/manager/adb/PairingContext";

#[derive(Debug)]
pub(crate) enum PairingError {
    Protocol(String),
    Authentication,
}

impl std::fmt::Display for PairingError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            PairingError::Protocol(reason) => formatter.write_str(reason),
            PairingError::Authentication => formatter.write_str("peer info authentication failed"),
        }
    }
}

impl std::error::Error for PairingError {}

struct PairingContext {
    spake: Option<Spake2State>,
    message: [u8; MESSAGE_SIZE],
    cipher: Option<PairingCipher>,
}

unsafe fn context<'local>(ptr: jlong) -> Option<&'local mut PairingContext> {
    if ptr == 0 {
        return None;
    }
    Some(unsafe { &mut *(ptr as *mut PairingContext) })
}

fn init_logging() {
    #[cfg(target_os = "android")]
    {
        const LOG_TAG: &str = "AdbPairClient";

        android_logger::init_once(
            android_logger::Config::default()
                .with_max_level(log::LevelFilter::Debug)
                .with_tag(LOG_TAG),
        );
    }
}

extern "system" fn native_constructor<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    is_client: jboolean,
    password: JByteArray<'local>,
) -> jlong {
    let password = match env.convert_byte_array(&password) {
        Ok(value) => value,
        Err(source) => {
            error!("Unable to read the pairing password: {source}");
            return 0;
        }
    };

    let role = if is_client != 0 {
        SpakeRole::Client
    } else {
        SpakeRole::Server
    };

    let mut random = [0_u8; 64];
    OsRng.fill_bytes(&mut random);

    match Spake2State::start(role, &password, random) {
        Ok((state, message)) => {
            let context = Box::new(PairingContext {
                spake: Some(state),
                message,
                cipher: None,
            });
            Box::into_raw(context) as jlong
        }
        Err(source) => {
            error!("Unable to generate the SPAKE2 public key: {source}");
            0
        }
    }
}

extern "system" fn native_msg<'local>(
    env: JNIEnv<'local>,
    _this: JObject<'local>,
    ptr: jlong,
) -> jbyteArray {
    let Some(context) = (unsafe { context(ptr) }) else {
        error!("Unable to read the pairing context.");
        return std::ptr::null_mut();
    };

    match env.byte_array_from_slice(&context.message) {
        Ok(array) => array.into_raw(),
        Err(source) => {
            error!("Unable to return the SPAKE2 message: {source}");
            std::ptr::null_mut()
        }
    }
}

extern "system" fn native_init_cipher<'local>(
    env: JNIEnv<'local>,
    _this: JObject<'local>,
    ptr: jlong,
    their_msg: JByteArray<'local>,
) -> jboolean {
    let Some(context) = (unsafe { context(ptr) }) else {
        error!("Unable to read the pairing context.");
        return JNI_FALSE;
    };

    let their_msg = match env.convert_byte_array(&their_msg) {
        Ok(value) => value,
        Err(source) => {
            error!("Unable to read their public key: {source}");
            return JNI_FALSE;
        }
    };

    if their_msg.len() > MESSAGE_SIZE {
        error!(
            "their_msg size [{}] greater then max size [{}].",
            their_msg.len(),
            MESSAGE_SIZE
        );
        return JNI_FALSE;
    }

    let Some(state) = context.spake.take() else {
        error!("The SPAKE2 state is not available.");
        return JNI_FALSE;
    };

    let key_material = match state.finish(&their_msg) {
        Ok(value) => value,
        Err(source) => {
            error!("Unable to process their public key: {source}");
            return JNI_FALSE;
        }
    };

    match PairingCipher::new(&*key_material) {
        Ok(cipher) => {
            context.cipher = Some(cipher);
            JNI_TRUE
        }
        Err(source) => {
            error!("Unable to derive the pairing cipher key: {source}");
            JNI_FALSE
        }
    }
}

extern "system" fn native_encrypt<'local>(
    env: JNIEnv<'local>,
    _this: JObject<'local>,
    ptr: jlong,
    input: JByteArray<'local>,
) -> jbyteArray {
    let Some(context) = (unsafe { context(ptr) }) else {
        error!("Unable to read the pairing context.");
        return std::ptr::null_mut();
    };

    let input = match env.convert_byte_array(&input) {
        Ok(value) => value,
        Err(source) => {
            error!("Unable to read the plaintext: {source}");
            return std::ptr::null_mut();
        }
    };

    let Some(cipher) = context.cipher.as_mut() else {
        error!("The AES-GCM context is not initialized.");
        return std::ptr::null_mut();
    };

    match cipher.encrypt(&input) {
        Ok(output) => match env.byte_array_from_slice(&output) {
            Ok(array) => array.into_raw(),
            Err(source) => {
                error!("Unable to return the ciphertext: {source}");
                std::ptr::null_mut()
            }
        },
        Err(_) => {
            error!("Failed to encrypt (in_len={}).", input.len());
            std::ptr::null_mut()
        }
    }
}

extern "system" fn native_decrypt<'local>(
    env: JNIEnv<'local>,
    _this: JObject<'local>,
    ptr: jlong,
    input: JByteArray<'local>,
) -> jbyteArray {
    let Some(context) = (unsafe { context(ptr) }) else {
        error!("Unable to read the pairing context.");
        return std::ptr::null_mut();
    };

    let input = match env.convert_byte_array(&input) {
        Ok(value) => value,
        Err(source) => {
            error!("Unable to read the ciphertext: {source}");
            return std::ptr::null_mut();
        }
    };

    let Some(cipher) = context.cipher.as_mut() else {
        error!("The AES-GCM context is not initialized.");
        return std::ptr::null_mut();
    };

    match cipher.decrypt(&input) {
        Ok(output) => match env.byte_array_from_slice(&output) {
            Ok(array) => array.into_raw(),
            Err(source) => {
                error!("Unable to return the plaintext: {source}");
                std::ptr::null_mut()
            }
        },
        Err(_) => {
            error!("Failed to decrypt (in_len={}).", input.len());
            std::ptr::null_mut()
        }
    }
}

extern "system" fn native_destroy<'local>(_env: JNIEnv<'local>, _this: JObject<'local>, ptr: jlong) {
    if ptr == 0 {
        return;
    }
    drop(unsafe { Box::from_raw(ptr as *mut PairingContext) });
}

#[unsafe(no_mangle)]
pub extern "system" fn JNI_OnLoad(vm: JavaVM, _reserved: *mut c_void) -> jint {
    init_logging();

    let Ok(mut env) = vm.get_env() else {
        return JNI_ERR;
    };

    let Ok(class) = env.find_class(PAIRING_CONTEXT_CLASS) else {
        error!("Unable to find {PAIRING_CONTEXT_CLASS}.");
        return JNI_ERR;
    };

    let methods = [
        NativeMethod {
            name: "nativeConstructor".into(),
            sig: "(Z[B)J".into(),
            fn_ptr: native_constructor as *mut c_void,
        },
        NativeMethod {
            name: "nativeMsg".into(),
            sig: "(J)[B".into(),
            fn_ptr: native_msg as *mut c_void,
        },
        NativeMethod {
            name: "nativeInitCipher".into(),
            sig: "(J[B)Z".into(),
            fn_ptr: native_init_cipher as *mut c_void,
        },
        NativeMethod {
            name: "nativeEncrypt".into(),
            sig: "(J[B)[B".into(),
            fn_ptr: native_encrypt as *mut c_void,
        },
        NativeMethod {
            name: "nativeDecrypt".into(),
            sig: "(J[B)[B".into(),
            fn_ptr: native_decrypt as *mut c_void,
        },
        NativeMethod {
            name: "nativeDestroy".into(),
            sig: "(J)V".into(),
            fn_ptr: native_destroy as *mut c_void,
        },
    ];

    if let Err(source) = env.register_native_methods(class, &methods) {
        error!("Unable to register the PairingContext native methods: {source}");
        return JNI_ERR;
    }

    JNI_VERSION_1_6
}

#[cfg(test)]
mod tests {
    use super::PairingError;
    use super::cipher::PairingCipher;
    use super::spake::{Spake2State, SpakeRole};

    fn vector_random(offset: u8) -> [u8; 64] {
        let mut random = [0_u8; 64];
        for (index, byte) in random.iter_mut().enumerate() {
            *byte = u8::try_from(index).expect("vector index fits").wrapping_add(offset);
        }
        random
    }

    #[test]
    fn boringssl_pairing_matches_the_published_vector() {
        let mut password = Vec::from(b"123456".as_slice());
        password.extend_from_slice(&[0xa5; 64]);

        let (client, client_message) =
            Spake2State::start(SpakeRole::Client, &password, vector_random(0))
                .expect("client vector should start");
        let (server, server_message) =
            Spake2State::start(SpakeRole::Server, &password, vector_random(64))
                .expect("server vector should start");
        let key = client
            .finish(&server_message)
            .expect("client vector should finish");
        let server_key = server
            .finish(&client_message)
            .expect("server vector should finish");
        let mut cipher = PairingCipher::new(&*key).expect("vector cipher should derive");
        let ciphertext = cipher.encrypt(b"DroidDeck").expect("vector should encrypt");

        assert_eq!(
            client_message,
            [
                0x06, 0x5f, 0x7c, 0xb4, 0x26, 0x93, 0xcd, 0x91, 0xee, 0x88, 0xcc, 0x20, 0x71, 0x0b,
                0xd8, 0xdd, 0xa6, 0x80, 0xec, 0xf7, 0x27, 0xc1, 0xc6, 0x3a, 0x0d, 0x0b, 0x39, 0x25,
                0x33, 0x97, 0xf2, 0x7b,
            ]
        );
        assert_eq!(
            server_message,
            [
                0xa2, 0xba, 0xde, 0xe9, 0xf9, 0x20, 0x77, 0x3b, 0x29, 0x4b, 0x81, 0x22, 0xdc, 0x9d,
                0x71, 0x3f, 0x6c, 0x9e, 0xfe, 0x58, 0x34, 0x8e, 0x5b, 0xf1, 0x7c, 0xad, 0x75, 0x22,
                0x95, 0x5e, 0xaf, 0x19,
            ]
        );
        assert_eq!(
            &*key,
            &[
                0x02, 0xc2, 0xdd, 0x53, 0x2c, 0x0b, 0x3b, 0x36, 0x8c, 0xb7, 0x94, 0x94, 0x10, 0xef,
                0xc7, 0x1c, 0xe0, 0xeb, 0xa9, 0x8e, 0xa4, 0x4f, 0xc5, 0x31, 0x33, 0x71, 0x41, 0x81,
                0xea, 0xcc, 0x30, 0xe0, 0x8d, 0xa5, 0x06, 0x3a, 0xdf, 0x84, 0xa0, 0x67, 0x26, 0x56,
                0x8f, 0xa2, 0x7b, 0xc9, 0xc3, 0xc8, 0xfc, 0xa5, 0x0c, 0x4a, 0x27, 0x0a, 0x46, 0x84,
                0x0b, 0x74, 0xf4, 0x20, 0x59, 0x10, 0x89, 0x15,
            ]
        );
        assert_eq!(&*server_key, &*key);
        assert_eq!(
            ciphertext,
            [
                0x22, 0x99, 0x5d, 0xaa, 0xc0, 0xe8, 0x69, 0xe3, 0xc1, 0xed, 0xed, 0x3d, 0x16, 0x25,
                0x2b, 0xf6, 0xad, 0x7d, 0x29, 0xf7, 0x22, 0xee, 0x33, 0x89, 0xe2,
            ]
        );
    }

    #[test]
    fn both_roles_derive_the_same_key() {
        let password = b"123456 plus tls exporter";
        let (client, client_message) =
            Spake2State::start(SpakeRole::Client, password, [0x11; 64]).expect("client should start");
        let (server, server_message) =
            Spake2State::start(SpakeRole::Server, password, [0x22; 64]).expect("server should start");

        let client_key = client
            .finish(&server_message)
            .expect("client should finish");
        let server_key = server
            .finish(&client_message)
            .expect("server should finish");

        assert_eq!(&*client_key, &*server_key);
    }

    #[test]
    fn the_wrong_password_fails_authentication() {
        let (client, client_message) =
            Spake2State::start(SpakeRole::Client, b"123456 exporter", [0x33; 64])
                .expect("client should start");
        let (server, server_message) =
            Spake2State::start(SpakeRole::Server, b"654321 exporter", [0x44; 64])
                .expect("server should start");

        let client_key = client
            .finish(&server_message)
            .expect("client exchange should finish");
        let server_key = server
            .finish(&client_message)
            .expect("server exchange should finish");
        let mut client_cipher = PairingCipher::new(&*client_key).expect("cipher should derive");
        let mut server_cipher = PairingCipher::new(&*server_key).expect("cipher should derive");
        let encrypted = client_cipher
            .encrypt(b"peer info")
            .expect("encryption should succeed");

        assert!(matches!(
            server_cipher.decrypt(&encrypted),
            Err(PairingError::Authentication)
        ));
    }

    #[test]
    fn cipher_sequences_are_independent_per_direction() {
        let key_material = [0x42_u8; 64];
        let mut client = PairingCipher::new(&key_material).expect("cipher should derive");
        let mut server = PairingCipher::new(&key_material).expect("cipher should derive");

        let encrypted = client.encrypt(b"hello").expect("should encrypt");
        assert_eq!(
            server.decrypt(&encrypted).expect("should decrypt"),
            b"hello"
        );
    }
}
