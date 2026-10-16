//! JNI bridge used by `com.dihcord.app.voice.NativeVoice`.
//!
//! * DAVE (Discord's audio/video end-to-end encryption) through the `davey` crate, the same implementation
//!   discord.js uses. Required by Discord for every non-stage voice connection since March 2026.
//! * Opus encode/decode through a vendored libopus.
//!
//! Handles are raw pointers (as `jlong`) owned by the Kotlin side, which must call the matching `*Destroy`.
//! Failures return null / negative values and leave a message for `lastError()`; Rust panics are caught so they
//! can never unwind into the JVM.

use std::cell::RefCell;
use std::num::NonZeroU16;
use std::panic::{AssertUnwindSafe, catch_unwind};

use davey::{DaveSession, MediaType, ProposalsOperationType};
use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JLongArray, JShortArray};
use jni::sys::{JNI_FALSE, JNI_TRUE, jboolean, jbyteArray, jint, jlong, jstring};
use opus_head_sys as opus;

thread_local! {
    static LAST_ERROR: RefCell<String> = const { RefCell::new(String::new()) };
}

fn set_error(message: impl Into<String>) {
    LAST_ERROR.with(|e| *e.borrow_mut() = message.into());
}

/// Runs [f], turning a panic into [fallback] plus a recorded error.
fn guarded<T>(fallback: T, f: impl FnOnce() -> T) -> T {
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(value) => value,
        Err(panic) => {
            let message = panic
                .downcast_ref::<&str>()
                .map(|s| s.to_string())
                .or_else(|| panic.downcast_ref::<String>().cloned())
                .unwrap_or_else(|| "native panic".to_string());
            set_error(format!("panic: {message}"));
            fallback
        }
    }
}

fn bytes(env: &JNIEnv, array: &JByteArray) -> Option<Vec<u8>> {
    match env.convert_byte_array(array) {
        Ok(v) => Some(v),
        Err(e) => {
            set_error(format!("byte array: {e}"));
            None
        }
    }
}

fn to_java(env: &JNIEnv, data: &[u8]) -> jbyteArray {
    match env.byte_array_from_slice(data) {
        Ok(a) => a.into_raw(),
        Err(e) => {
            set_error(format!("byte array: {e}"));
            std::ptr::null_mut()
        }
    }
}

unsafe fn session<'a>(handle: jlong) -> Option<&'a mut DaveSession> {
    if handle == 0 {
        set_error("no session");
        None
    } else {
        Some(unsafe { &mut *(handle as *mut DaveSession) })
    }
}

// ---------------------------------------------------------------------------------------------------------------
// General

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_dihcord_app_voice_NativeVoice_lastError(env: JNIEnv, _: JClass) -> jstring {
    let message = LAST_ERROR.with(|e| e.borrow().clone());
    env.new_string(message).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_dihcord_app_voice_NativeVoice_daveMaxProtocolVersion(_: JNIEnv, _: JClass) -> jint {
    davey::DAVE_PROTOCOL_VERSION as jint
}

// ---------------------------------------------------------------------------------------------------------------
// DAVE

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_dihcord_app_voice_NativeVoice_daveCreate(
    _: JNIEnv,
    _: JClass,
    protocol_version: jint,
    user_id: jlong,
    channel_id: jlong,
) -> jlong {
    guarded(0, || {
        let Some(version) = NonZeroU16::new(protocol_version as u16) else {
            set_error("protocol version must be > 0");
            return 0;
        };
        match DaveSession::new(version, user_id as u64, channel_id as u64, None) {
            Ok(s) => Box::into_raw(Box::new(s)) as jlong,
            Err(e) => {
                set_error(format!("create session: {e:?}"));
                0
            }
        }
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_dihcord_app_voice_NativeVoice_daveDestroy(_: JNIEnv, _: JClass, handle: jlong) {
    if handle != 0 {
        guarded((), || drop(unsafe { Box::from_raw(handle as *mut DaveSession) }));
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_dihcord_app_voice_NativeVoice_daveReinit(
    _: JNIEnv,
    _: JClass,
    handle: jlong,
    protocol_version: jint,
    user_id: jlong,
    channel_id: jlong,
) -> jboolean {
    guarded(JNI_FALSE, || {
        let Some(s) = (unsafe { session(handle) }) else { return JNI_FALSE };
        let Some(version) = NonZeroU16::new(protocol_version as u16) else {
            set_error("protocol version must be > 0");
            return JNI_FALSE;
        };
        match s.reinit(version, user_id as u64, channel_id as u64, None) {
            Ok(()) => JNI_TRUE,
            Err(e) => {
                set_error(format!("reinit: {e:?}"));
                JNI_FALSE
            }
        }
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_dihcord_app_voice_NativeVoice_daveReset(_: JNIEnv, _: JClass, handle: jlong) -> jboolean {
    guarded(JNI_FALSE, || {
        let Some(s) = (unsafe { session(handle) }) else { return JNI_FALSE };
        match s.reset() {
            Ok(()) => JNI_TRUE,
            Err(e) => {
                set_error(format!("reset: {e:?}"));
                JNI_FALSE
            }
        }
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_dihcord_app_voice_NativeVoice_daveIsReady(_: JNIEnv, _: JClass, handle: jlong) -> jboolean {
    guarded(JNI_FALSE, || match unsafe { session(handle) } {
        Some(s) if s.is_ready() => JNI_TRUE,
        _ => JNI_FALSE,
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_dihcord_app_voice_NativeVoice_daveSetExternalSender<'l>(
    env: JNIEnv<'l>,
    _: JClass<'l>,
    handle: jlong,
    data: JByteArray<'l>,
) -> jboolean {
    guarded(JNI_FALSE, || {
        let Some(s) = (unsafe { session(handle) }) else { return JNI_FALSE };
        let Some(data) = bytes(&env, &data) else { return JNI_FALSE };
        match s.set_external_sender(&data) {
            Ok(()) => JNI_TRUE,
            Err(e) => {
                set_error(format!("external sender: {e:?}"));
                JNI_FALSE
            }
        }
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_dihcord_app_voice_NativeVoice_daveKeyPackage(
    env: JNIEnv,
    _: JClass,
    handle: jlong,
) -> jbyteArray {
    guarded(std::ptr::null_mut(), || {
        let Some(s) = (unsafe { session(handle) }) else { return std::ptr::null_mut() };
        match s.create_key_package() {
            Ok(kp) => to_java(&env, &kp),
            Err(e) => {
                set_error(format!("key package: {e:?}"));
                std::ptr::null_mut()
            }
        }
    })
}

/// Returns commit (+ welcome, concatenated) to send as opcode 28, an empty array when there's nothing to send,
/// or null on error.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_dihcord_app_voice_NativeVoice_daveProcessProposals<'l>(
    env: JNIEnv<'l>,
    _: JClass<'l>,
    handle: jlong,
    operation: jint,
    proposals: JByteArray<'l>,
    expected_user_ids: JLongArray<'l>,
) -> jbyteArray {
    guarded(std::ptr::null_mut(), || {
        let Some(s) = (unsafe { session(handle) }) else { return std::ptr::null_mut() };
        let Some(proposals) = bytes(&env, &proposals) else { return std::ptr::null_mut() };
        let op = if operation == 1 { ProposalsOperationType::REVOKE } else { ProposalsOperationType::APPEND };
        let expected: Option<Vec<u64>> = if expected_user_ids.is_null() {
            None
        } else {
            let len = env.get_array_length(&expected_user_ids).unwrap_or(0) as usize;
            let mut ids = vec![0i64; len];
            if env.get_long_array_region(&expected_user_ids, 0, &mut ids).is_err() {
                set_error("expected user ids");
                return std::ptr::null_mut();
            }
            Some(ids.into_iter().map(|id| id as u64).collect())
        };
        match s.process_proposals(op, &proposals, expected.as_deref()) {
            Ok(Some(cw)) => {
                let mut out = cw.commit;
                if let Some(welcome) = cw.welcome {
                    out.extend_from_slice(&welcome);
                }
                to_java(&env, &out)
            }
            Ok(None) => to_java(&env, &[]),
            Err(e) => {
                set_error(format!("proposals: {e:?}"));
                std::ptr::null_mut()
            }
        }
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_dihcord_app_voice_NativeVoice_daveProcessCommit<'l>(
    env: JNIEnv<'l>,
    _: JClass<'l>,
    handle: jlong,
    commit: JByteArray<'l>,
) -> jboolean {
    guarded(JNI_FALSE, || {
        let Some(s) = (unsafe { session(handle) }) else { return JNI_FALSE };
        let Some(commit) = bytes(&env, &commit) else { return JNI_FALSE };
        match s.process_commit(&commit) {
            Ok(()) => JNI_TRUE,
            Err(e) => {
                set_error(format!("commit: {e:?}"));
                JNI_FALSE
            }
        }
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_dihcord_app_voice_NativeVoice_daveProcessWelcome<'l>(
    env: JNIEnv<'l>,
    _: JClass<'l>,
    handle: jlong,
    welcome: JByteArray<'l>,
) -> jboolean {
    guarded(JNI_FALSE, || {
        let Some(s) = (unsafe { session(handle) }) else { return JNI_FALSE };
        let Some(welcome) = bytes(&env, &welcome) else { return JNI_FALSE };
        match s.process_welcome(&welcome) {
            Ok(()) => JNI_TRUE,
            Err(e) => {
                set_error(format!("welcome: {e:?}"));
                JNI_FALSE
            }
        }
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_dihcord_app_voice_NativeVoice_daveEncryptOpus<'l>(
    env: JNIEnv<'l>,
    _: JClass<'l>,
    handle: jlong,
    frame: JByteArray<'l>,
) -> jbyteArray {
    guarded(std::ptr::null_mut(), || {
        let Some(s) = (unsafe { session(handle) }) else { return std::ptr::null_mut() };
        let Some(frame) = bytes(&env, &frame) else { return std::ptr::null_mut() };
        match s.encrypt_opus(&frame) {
            Ok(out) => to_java(&env, &out),
            Err(e) => {
                set_error(format!("encrypt: {e:?}"));
                std::ptr::null_mut()
            }
        }
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_dihcord_app_voice_NativeVoice_daveDecrypt<'l>(
    env: JNIEnv<'l>,
    _: JClass<'l>,
    handle: jlong,
    user_id: jlong,
    frame: JByteArray<'l>,
) -> jbyteArray {
    guarded(std::ptr::null_mut(), || {
        let Some(s) = (unsafe { session(handle) }) else { return std::ptr::null_mut() };
        let Some(frame) = bytes(&env, &frame) else { return std::ptr::null_mut() };
        match s.decrypt(user_id as u64, MediaType::AUDIO, &frame) {
            Ok(out) => to_java(&env, &out),
            Err(e) => {
                set_error(format!("decrypt: {e:?}"));
                std::ptr::null_mut()
            }
        }
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_dihcord_app_voice_NativeVoice_daveCanPassthrough(
    _: JNIEnv,
    _: JClass,
    handle: jlong,
    user_id: jlong,
) -> jboolean {
    guarded(JNI_FALSE, || match unsafe { session(handle) } {
        Some(s) if s.can_passthrough(user_id as u64) => JNI_TRUE,
        _ => JNI_FALSE,
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_dihcord_app_voice_NativeVoice_daveSetPassthroughMode(
    _: JNIEnv,
    _: JClass,
    handle: jlong,
    passthrough: jboolean,
    expiry_seconds: jint,
) {
    guarded((), || {
        if let Some(s) = unsafe { session(handle) } {
            s.set_passthrough_mode(passthrough == JNI_TRUE, Some(expiry_seconds.max(0) as u32));
        }
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_dihcord_app_voice_NativeVoice_davePrivacyCode(
    env: JNIEnv,
    _: JClass,
    handle: jlong,
) -> jstring {
    let code = guarded(String::new(), || {
        unsafe { session(handle) }
            .and_then(|s| s.voice_privacy_code().map(str::to_string))
            .unwrap_or_default()
    });
    env.new_string(code).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

// ---------------------------------------------------------------------------------------------------------------
// Opus

const OPUS_OK: i32 = 0;

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_dihcord_app_voice_NativeVoice_opusEncoderCreate(
    _: JNIEnv,
    _: JClass,
    sample_rate: jint,
    channels: jint,
    bitrate: jint,
) -> jlong {
    guarded(0, || unsafe {
        let mut error = 0;
        let enc = opus::opus_encoder_create(sample_rate, channels, opus::OPUS_APPLICATION_VOIP as i32, &mut error);
        if enc.is_null() || error != OPUS_OK {
            set_error(format!("opus encoder: {error}"));
            return 0;
        }
        opus::opus_encoder_ctl(enc, opus::OPUS_SET_BITRATE_REQUEST as i32, bitrate);
        // In-band FEC helps a lossy watch connection; the receiver can recover a lost frame from the next one.
        opus::opus_encoder_ctl(enc, opus::OPUS_SET_INBAND_FEC_REQUEST as i32, 1i32);
        opus::opus_encoder_ctl(enc, opus::OPUS_SET_PACKET_LOSS_PERC_REQUEST as i32, 10i32);
        enc as jlong
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_dihcord_app_voice_NativeVoice_opusEncoderDestroy(_: JNIEnv, _: JClass, handle: jlong) {
    if handle != 0 {
        guarded((), || unsafe { opus::opus_encoder_destroy(handle as *mut opus::OpusEncoder) });
    }
}

/// Encodes one frame of interleaved 16-bit PCM; returns the Opus packet, or null on error.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_dihcord_app_voice_NativeVoice_opusEncode<'l>(
    env: JNIEnv<'l>,
    _: JClass<'l>,
    handle: jlong,
    pcm: JShortArray<'l>,
    frame_size: jint,
) -> jbyteArray {
    guarded(std::ptr::null_mut(), || {
        if handle == 0 {
            set_error("no encoder");
            return std::ptr::null_mut();
        }
        let len = env.get_array_length(&pcm).unwrap_or(0) as usize;
        let mut samples = vec![0i16; len];
        if env.get_short_array_region(&pcm, 0, &mut samples).is_err() {
            set_error("pcm");
            return std::ptr::null_mut();
        }
        let mut out = [0u8; 1500];
        let n = unsafe {
            opus::opus_encode(
                handle as *mut opus::OpusEncoder,
                samples.as_ptr(),
                frame_size,
                out.as_mut_ptr(),
                out.len() as i32,
            )
        };
        if n < 0 {
            set_error(format!("opus encode: {n}"));
            return std::ptr::null_mut();
        }
        to_java(&env, &out[..n as usize])
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_dihcord_app_voice_NativeVoice_opusDecoderCreate(
    _: JNIEnv,
    _: JClass,
    sample_rate: jint,
    channels: jint,
) -> jlong {
    guarded(0, || unsafe {
        let mut error = 0;
        let dec = opus::opus_decoder_create(sample_rate, channels, &mut error);
        if dec.is_null() || error != OPUS_OK {
            set_error(format!("opus decoder: {error}"));
            return 0;
        }
        dec as jlong
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_dihcord_app_voice_NativeVoice_opusDecoderDestroy(_: JNIEnv, _: JClass, handle: jlong) {
    if handle != 0 {
        guarded((), || unsafe { opus::opus_decoder_destroy(handle as *mut opus::OpusDecoder) });
    }
}

/// Decodes [packet] (null = conceal a lost packet) into [pcm]; returns samples per channel, or < 0 on error.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_dihcord_app_voice_NativeVoice_opusDecode<'l>(
    env: JNIEnv<'l>,
    _: JClass<'l>,
    handle: jlong,
    packet: JByteArray<'l>,
    pcm: JShortArray<'l>,
    frame_size: jint,
    channels: jint,
) -> jint {
    guarded(-1, || {
        if handle == 0 {
            set_error("no decoder");
            return -1;
        }
        let data = if packet.is_null() { None } else { bytes(&env, &packet) };
        let mut out = vec![0i16; (frame_size * channels).max(0) as usize];
        let n = unsafe {
            match &data {
                Some(d) => opus::opus_decode(
                    handle as *mut opus::OpusDecoder,
                    d.as_ptr(),
                    d.len() as i32,
                    out.as_mut_ptr(),
                    frame_size,
                    0,
                ),
                None => opus::opus_decode(
                    handle as *mut opus::OpusDecoder,
                    std::ptr::null(),
                    0,
                    out.as_mut_ptr(),
                    frame_size,
                    0,
                ),
            }
        };
        if n < 0 {
            set_error(format!("opus decode: {n}"));
            return n;
        }
        let used = (n * channels) as usize;
        if env.set_short_array_region(&pcm, 0, &out[..used]).is_err() {
            set_error("pcm out");
            return -1;
        }
        n
    })
}
