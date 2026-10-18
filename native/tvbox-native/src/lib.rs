mod artifact;
mod catalog;
mod config;
mod display_name;
mod epg;
mod hls;
mod live;
mod maccms;
mod search;
mod text_normalizer;
mod ts_probe;

use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jstring, JNI_TRUE};
use jni::JNIEnv;

/// Linkage canary: [com.wuming.tv.core.native.NativeLoader] calls this after
/// `System.loadLibrary`, so a library that loads but exports the wrong symbol
/// names reports itself unavailable instead of crashing every caller.
#[no_mangle]
pub extern "system" fn Java_com_wuming_tv_core_native_NativeLoader_nativeProbe(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    JNI_TRUE
}

pub(crate) fn jstring_to_string(env: &mut JNIEnv, s: &JString) -> String {
    env.get_string(s)
        .map(|js| js.to_str().unwrap_or("").to_owned())
        .unwrap_or_default()
}

pub(crate) fn return_jstring(env: &mut JNIEnv, s: &str) -> jstring {
    env.new_string(s)
        .map(|js| js.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

pub use artifact::*;
pub use catalog::*;
pub use config::*;
pub use display_name::*;
pub use epg::*;
pub use hls::*;
pub use live::*;
pub use maccms::*;
pub use search::*;
pub use text_normalizer::*;
pub use ts_probe::*;
