use crate::jstring_to_string;
use jni::objects::{JClass, JString};
use jni::sys::{jdouble, jstring};
use jni::JNIEnv;
use unicode_normalization::UnicodeNormalization;

fn levenshtein_similarity(left: &str, right: &str) -> f64 {
    if left == right { return 1.0; }
    if left.is_empty() || right.is_empty() { return 0.0; }
    let left_chars: Vec<char> = left.chars().collect();
    let right_chars: Vec<char> = right.chars().collect();
    let max_len = left_chars.len().max(right_chars.len());
    let mut previous: Vec<usize> = (0..=right_chars.len()).collect();
    let mut current: Vec<usize> = vec![0; right_chars.len() + 1];
    for (i, &lc) in left_chars.iter().enumerate() {
        current[0] = i + 1;
        for (j, &rc) in right_chars.iter().enumerate() {
            let substitution = previous[j] + if lc == rc { 0 } else { 1 };
            current[j + 1] = (previous[j + 1] + 1)
                .min(current[j] + 1)
                .min(substitution);
        }
        std::mem::swap(&mut previous, &mut current);
    }
    1.0 - previous[right_chars.len()] as f64 / max_len as f64
}

fn epg_normalize(value: &str) -> String {
    let normalized: String = value.nfkc().collect();
    let lower = normalized.to_lowercase();
    let after_quality = remove_quality_tokens(&lower);
    let after_separators = replace_separators(&after_quality);
    let trimmed = after_separators.trim();
    let collapsed = collapse_whitespace(trimmed);
    let after_trailing = remove_trailing_type(&collapsed);
    after_trailing.trim().to_string()
}

fn remove_quality_tokens(input: &str) -> String {
    let tokens = ["fhd", "uhd", "hd", "sd", "4k", "8k", "live"];
    let cn_tokens = ["高清", "超清", "标清", "直播"];
    let chars: Vec<char> = input.chars().collect();
    let mut result = String::with_capacity(input.len());
    let mut i = 0;
    while i < chars.len() {
        let remaining = &chars[i..];
        let lower_remaining: String = remaining.iter().take(10).collect::<String>().to_lowercase();
        let mut matched = false;
        for token in &tokens {
            if lower_remaining.starts_with(token) {
                let before_ok = i == 0 || !is_alnum_char(chars[i - 1]);
                let after_idx = i + token.chars().count();
                let after_ok = after_idx >= chars.len() || !is_alnum_char(chars[after_idx]);
                if before_ok && after_ok {
                    result.push(' ');
                    i += token.chars().count();
                    matched = true;
                    break;
                }
            }
        }
        if !matched {
            for token in &cn_tokens {
                if remaining.iter().collect::<String>().starts_with(token) {
                    result.push(' ');
                    i += token.chars().count();
                    matched = true;
                    break;
                }
            }
        }
        if !matched {
            result.push(chars[i]);
            i += 1;
        }
    }
    result
}

fn is_alnum_char(c: char) -> bool {
    c.is_ascii_alphanumeric() || (c as u32) >= 0x4E00 && (c as u32) <= 0x9FFF
}

fn replace_separators(input: &str) -> String {
    let mut result = String::with_capacity(input.len());
    for c in input.chars() {
        if c.is_alphanumeric() || is_cjk(c) {
            result.push(c);
        } else {
            result.push(' ');
        }
    }
    result
}

fn is_cjk(c: char) -> bool {
    let code = c as u32;
    (0x4E00..=0x9FFF).contains(&code) || (0x3400..=0x4DBF).contains(&code)
        || (0xF900..=0xFAFF).contains(&code) || (0x3040..=0x309F).contains(&code)
        || (0x30A0..=0x30FF).contains(&code)
}

fn collapse_whitespace(input: &str) -> String {
    let mut result = String::with_capacity(input.len());
    let mut prev_space = false;
    for c in input.chars() {
        if c == ' ' {
            if !prev_space {
                result.push(' ');
            }
            prev_space = true;
        } else {
            result.push(c);
            prev_space = false;
        }
    }
    result
}

fn remove_trailing_type(input: &str) -> String {
    let suffixes = ["卫视", "电视台", "频道"];
    for suffix in &suffixes {
        if input.ends_with(suffix) {
            let char_count = input.chars().count() - suffix.chars().count();
            return input.chars().take(char_count).collect();
        }
    }
    input.to_string()
}

#[no_mangle]
pub extern "system" fn Java_com_wuming_tv_core_epg_ChannelNameMatcher_nativeLevenshteinSimilarity(
    mut env: JNIEnv,
    _class: JClass,
    left: JString,
    right: JString,
) -> jdouble {
    let left_str = jstring_to_string(&mut env, &left);
    let right_str = jstring_to_string(&mut env, &right);
    levenshtein_similarity(&left_str, &right_str)
}

#[no_mangle]
pub extern "system" fn Java_com_wuming_tv_core_epg_ChannelNameMatcher_nativeNormalize(
    mut env: JNIEnv,
    _class: JClass,
    value: JString,
) -> jstring {
    let value_str = jstring_to_string(&mut env, &value);
    let result = epg_normalize(&value_str);
    crate::return_jstring(&mut env, &result)
}
