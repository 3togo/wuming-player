use crate::jstring_to_string;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jdouble, jint, jstring};
use jni::JNIEnv;
use unicode_normalization::UnicodeNormalization;

fn is_letter_or_digit(code: u32) -> bool {
    if (48..=57).contains(&code) || (65..=90).contains(&code) || (97..=122).contains(&code) {
        return true;
    }
    is_unicode_letter_or_digit(code)
}

fn is_unicode_letter_or_digit(code: u32) -> bool {
    if (0x4E00..=0x9FFF).contains(&code) { return true; }
    if (0x3400..=0x4DBF).contains(&code) { return true; }
    if (0xF900..=0xFAFF).contains(&code) { return true; }
    if (0x20000..=0x2FFFF).contains(&code) { return true; }
    if (0x3040..=0x309F).contains(&code) { return true; }
    if (0x30A0..=0x30FF).contains(&code) { return true; }
    if (0xAC00..=0xD7AF).contains(&code) { return true; }
    if (0x0600..=0x06FF).contains(&code) { return true; }
    if (0x0400..=0x04FF).contains(&code) { return true; }
    if (0x0370..=0x03FF).contains(&code) { return true; }
    if (0x0590..=0x05FF).contains(&code) { return true; }
    if (0x0E00..=0x0E7F).contains(&code) { return true; }
    if (0xFF10..=0xFF19).contains(&code) { return true; }
    if (0xFF21..=0xFF3A).contains(&code) || (0xFF41..=0xFF5A).contains(&code) { return true; }
    if (0x0100..=0x024F).contains(&code) { return true; }
    if (0x0250..=0x02AF).contains(&code) { return true; }
    if (0x1E00..=0x1EFF).contains(&code) { return true; }
    if (0x2150..=0x218F).contains(&code) { return true; }
    if (0x2070..=0x2079).contains(&code) || (0x2080..=0x2089).contains(&code) { return true; }
    false
}

fn nfkc_normalize_lower(input: &str) -> String {
    let normalized: String = input.nfkc().collect();
    normalized.to_lowercase()
}

fn clean_internal(value: &str) -> String {
    let normalized = nfkc_normalize_lower(value);
    let normalized = normalized.trim();
    let mut result = String::with_capacity(normalized.len());
    let mut pending_space = false;
    for code_point in normalized.chars() {
        let code = code_point as u32;
        if is_letter_or_digit(code) {
            if pending_space && !result.is_empty() {
                result.push(' ');
            }
            result.push(code_point);
            pending_space = false;
        } else {
            pending_space = true;
        }
    }
    result
}

fn key_internal(value: &str) -> String {
    clean_internal(value).replace(' ', "")
}

fn levenshtein_distance(first: &str, second: &str) -> usize {
    let first_chars: Vec<char> = first.chars().collect();
    let second_chars: Vec<char> = second.chars().collect();
    let (shorter, longer) = if first_chars.len() <= second_chars.len() {
        (&first_chars, &second_chars)
    } else {
        (&second_chars, &first_chars)
    };
    let mut previous: Vec<usize> = (0..=shorter.len()).collect();
    let mut current: Vec<usize> = vec![0; shorter.len() + 1];
    for (long_idx, &long_char) in longer.iter().enumerate() {
        current[0] = long_idx + 1;
        for (short_idx, &short_char) in shorter.iter().enumerate() {
            let cost = if short_char == long_char { 0 } else { 1 };
            current[short_idx + 1] = (previous[short_idx + 1] + 1)
                .min(current[short_idx] + 1)
                .min(previous[short_idx] + cost);
        }
        std::mem::swap(&mut previous, &mut current);
    }
    previous[shorter.len()]
}

fn fuzzy_match(text: &str, query: &str) -> bool {
    if query.is_empty() { return true; }
    if query.chars().count() > text.chars().count() { return false; }
    let mut query_chars = query.chars().peekable();
    for text_char in text.chars() {
        if let Some(&query_char) = query_chars.peek() {
            if text_char == query_char {
                query_chars.next();
            }
        }
    }
    query_chars.peek().is_none()
}

fn similarity(first: &str, second: &str) -> f64 {
    if first == second { return 1.0; }
    let max_len = first.chars().count().max(second.chars().count());
    if max_len == 0 { return 1.0; }
    let dist = levenshtein_distance(first, second) as f64;
    1.0 - dist / max_len as f64
}

fn contains_all_words(text: &str, query: &str) -> bool {
    let haystack = text.to_lowercase();
    let query_lower = query.to_lowercase();
    let words: Vec<&str> = query_lower.split_whitespace().filter(|s| !s.is_empty()).collect();
    words.iter().all(|word| haystack.contains(word))
}

fn contains_any_word(text: &str, query: &str) -> bool {
    let haystack = text.to_lowercase();
    let query_lower = query.to_lowercase();
    let words: Vec<&str> = query_lower.split_whitespace().filter(|s| !s.is_empty()).collect();
    words.iter().any(|word| haystack.contains(word))
}

fn word_similarity(first: &str, second: &str) -> f64 {
    let first_lower = first.to_lowercase();
    let second_lower = second.to_lowercase();
    let first_words: std::collections::HashSet<&str> =
        first_lower.split_whitespace().filter(|s| !s.is_empty()).collect();
    let second_words: std::collections::HashSet<&str> =
        second_lower.split_whitespace().filter(|s| !s.is_empty()).collect();
    let union_size = first_words.union(&second_words).count();
    if union_size == 0 { return 1.0; }
    let intersection_size = first_words.intersection(&second_words).count();
    intersection_size as f64 / union_size as f64
}

#[no_mangle]
pub extern "system" fn Java_com_wuming_tv_core_search_SearchTextMatcher_nativeLevenshteinDistance(
    mut env: JNIEnv,
    _class: JClass,
    first: JString,
    second: JString,
) -> jint {
    let first_str = jstring_to_string(&mut env, &first);
    let second_str = jstring_to_string(&mut env, &second);
    levenshtein_distance(&first_str, &second_str) as jint
}

#[no_mangle]
pub extern "system" fn Java_com_wuming_tv_core_search_SearchTextMatcher_nativeSimilarity(
    mut env: JNIEnv,
    _class: JClass,
    first: JString,
    second: JString,
) -> jdouble {
    let first_str = jstring_to_string(&mut env, &first);
    let second_str = jstring_to_string(&mut env, &second);
    similarity(&first_str, &second_str)
}

#[no_mangle]
pub extern "system" fn Java_com_wuming_tv_core_search_SearchTextMatcher_nativeFuzzyMatch(
    mut env: JNIEnv,
    _class: JClass,
    text: JString,
    query: JString,
) -> jboolean {
    let text_str = jstring_to_string(&mut env, &text);
    let query_str = jstring_to_string(&mut env, &query);
    if fuzzy_match(&text_str, &query_str) { 1 } else { 0 }
}

#[no_mangle]
pub extern "system" fn Java_com_wuming_tv_core_search_SearchTextMatcher_nativeContainsAllWords(
    mut env: JNIEnv,
    _class: JClass,
    text: JString,
    query: JString,
) -> jboolean {
    let text_str = jstring_to_string(&mut env, &text);
    let query_str = jstring_to_string(&mut env, &query);
    if contains_all_words(&text_str, &query_str) { 1 } else { 0 }
}

#[no_mangle]
pub extern "system" fn Java_com_wuming_tv_core_search_SearchTextMatcher_nativeContainsAnyWord(
    mut env: JNIEnv,
    _class: JClass,
    text: JString,
    query: JString,
) -> jboolean {
    let text_str = jstring_to_string(&mut env, &text);
    let query_str = jstring_to_string(&mut env, &query);
    if contains_any_word(&text_str, &query_str) { 1 } else { 0 }
}

#[no_mangle]
pub extern "system" fn Java_com_wuming_tv_core_search_SearchTextMatcher_nativeWordSimilarity(
    mut env: JNIEnv,
    _class: JClass,
    first: JString,
    second: JString,
) -> jdouble {
    let first_str = jstring_to_string(&mut env, &first);
    let second_str = jstring_to_string(&mut env, &second);
    word_similarity(&first_str, &second_str)
}

#[no_mangle]
pub extern "system" fn Java_com_wuming_tv_core_search_TitleNormalizer_nativeClean(
    mut env: JNIEnv,
    _class: JClass,
    value: JString,
) -> jstring {
    let value_str = jstring_to_string(&mut env, &value);
    let result = clean_internal(&value_str);
    crate::return_jstring(&mut env, &result)
}

#[no_mangle]
pub extern "system" fn Java_com_wuming_tv_core_search_TitleNormalizer_nativeKey(
    mut env: JNIEnv,
    _class: JClass,
    value: JString,
) -> jstring {
    let value_str = jstring_to_string(&mut env, &value);
    let result = key_internal(&value_str);
    crate::return_jstring(&mut env, &result)
}

#[no_mangle]
pub extern "system" fn Java_com_wuming_tv_core_search_TitleNormalizer_nativeComparisonText(
    mut env: JNIEnv,
    _class: JClass,
    value: JString,
) -> jstring {
    let value_str = jstring_to_string(&mut env, &value);
    let result = key_internal(&value_str);
    crate::return_jstring(&mut env, &result)
}
