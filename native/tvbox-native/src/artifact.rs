use jni::objects::{JClass, JByteArray, JString};
use jni::sys::{jbyteArray, jstring};
use jni::JNIEnv;
use sha2::{Sha256, Digest};

fn compute_sha256(data: &[u8]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(data);
    let result = hasher.finalize();
    result.iter().map(|b| format!("{:02x}", b)).collect()
}

fn contains_substring(haystack: &[u8], needle: &[u8]) -> bool {
    if needle.is_empty() || haystack.len() < needle.len() { return false; }
    haystack.windows(needle.len()).any(|window| window == needle)
}

const FORBIDDEN_CLASS_MARKERS: &[&[u8]] = &[
    b"android/",
    b"androidx/",
    b"dalvik/",
    b"com/wuming/android/",
];

const FORBIDDEN_SUFFIXES: &[&str] = &[
    ".dex", ".so", ".guard", ".aar", ".apk", ".dylib", ".dll", ".jar",
];

const MAX_ENTRIES: usize = 4096;
const MAX_ENTRY_BYTES: usize = 16 * 1024 * 1024;
const MAX_TOTAL_BYTES: usize = 64 * 1024 * 1024;

#[derive(Debug)]
struct InspectionResult {
    sha256: String,
    classes: i32,
    total: i64,
    error: String,
}

fn inspect_jar(data: &[u8], expected_sha256: Option<&str>) -> InspectionResult {
    let sha256 = compute_sha256(data);

    if let Some(expected) = expected_sha256 {
        let expected = expected.trim();
        if !expected.is_empty() {
            if !sha256.eq_ignore_ascii_case(expected) {
                return InspectionResult { sha256, classes: 0, total: 0, error: "SHA-256 mismatch".to_string() };
            }
        }
    }

    let cursor = std::io::Cursor::new(data);
    let mut zip = match zip::ZipArchive::new(cursor) {
        Ok(z) => z,
        Err(e) => return InspectionResult { sha256, classes: 0, total: 0, error: format!("Not a readable JAR: {}", e) },
    };

    let mut entries = 0;
    let mut classes = 0;
    let mut total: i64 = 0;

    for i in 0..zip.len() {
        if entries > MAX_ENTRIES {
            return InspectionResult { sha256, classes: 0, total: 0, error: "Too many entries".to_string() };
        }

        let file = match zip.by_index(i) { Ok(f) => f, Err(_) => continue };
        let name = file.name().to_lowercase();
        let name_original = file.name().to_string();
        let declared = file.size() as usize;

        if name.ends_with('/') { continue; }
        entries += 1;

        if FORBIDDEN_SUFFIXES.iter().any(|suffix| name.ends_with(suffix)) {
            return InspectionResult { sha256, classes: 0, total: 0, error: format!("Non-portable entry: {}", name_original) };
        }

        if declared > MAX_ENTRY_BYTES {
            return InspectionResult { sha256, classes: 0, total: 0, error: format!("Entry too large: {}", name_original) };
        }

        if name.ends_with(".class") {
            let bytes: Vec<u8> = match std::io::Read::bytes(file).collect() {
                Ok(b) => b,
                Err(e) => return InspectionResult { sha256, classes: 0, total: 0, error: format!("Read error: {}", e) },
            };

            if bytes.len() > MAX_ENTRY_BYTES {
                return InspectionResult { sha256, classes: 0, total: 0, error: format!("Class too large: {}", name_original) };
            }

            for marker in FORBIDDEN_CLASS_MARKERS {
                if contains_substring(&bytes, marker) {
                    return InspectionResult { sha256, classes: 0, total: 0, error: format!("Android runtime reference: {}", name_original) };
                }
            }

            classes += 1;
            total += bytes.len() as i64;
        } else {
            total += declared as i64;
        }

        if total > MAX_TOTAL_BYTES as i64 {
            return InspectionResult { sha256, classes: 0, total: 0, error: "Exceeds portable limit".to_string() };
        }
    }

    if classes == 0 {
        return InspectionResult { sha256, classes: 0, total: 0, error: "No JVM classes".to_string() };
    }

    InspectionResult { sha256, classes, total, error: String::new() }
}

#[no_mangle]
pub extern "system" fn Java_com_wuming_tv_core_jvmspider_JvmSpiderArtifactInspector_nativeInspect(
    mut env: JNIEnv,
    _class: JClass,
    data: JByteArray,
    expected_sha256: JString,
) -> jstring {
    let data_bytes = match env.convert_byte_array(&data) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };

    let expected = env.get_string(&expected_sha256)
        .map(|js| js.to_str().unwrap_or("").to_owned())
        .unwrap_or_default();

    let expected_opt = if expected.is_empty() { None } else { Some(expected.as_str()) };

    let result = inspect_jar(&data_bytes, expected_opt);

    let json = if result.error.is_empty() {
        format!(r#"{{"sha256":"{}","classes":{},"total":{}}}"#, result.sha256, result.classes, result.total)
    } else {
        format!(r#"{{"sha256":"{}","classes":0,"total":0,"error":"{}"}}"#, result.sha256, result.error)
    };

    match env.new_string(&json) {
        Ok(js) => js.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}
