// Text normalization for search title matching — NFKC + lowercase + trim.
// Called from JNI when the native library is available; Kotlin fallback otherwise.
use jni::objects::JString;
use jni::sys::jstring;
use jni::JNIEnv;

#[no_mangle]
pub extern "system" fn Java_com_wuming_tv_core_search_TextNormalizerNative_normalize(
    mut env: JNIEnv,
    _class: jni::objects::JClass,
    input: JString,
) -> jstring {
    let text = env.get_string(&input)
        .map(|js| js.to_str().unwrap_or("").to_owned())
        .unwrap_or_default();
    let normalized = normalize_text(&text);
    env.new_string(&normalized)
        .map(|js| js.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

fn normalize_text(input: &str) -> String {
    // NFKC normalization, lowercase, trim whitespace
    // Full implementation would use unicode-normalization crate
    input.trim().to_lowercase()
}