use crate::jstring_to_string;
use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use regex::Regex;
use std::collections::HashMap;

const TAG_DISCONTINUITY: &str = "#EXT-X-DISCONTINUITY";
const TAG_MEDIA_DURATION: &str = "#EXTINF";
const TAG_ENDLIST: &str = "#EXT-X-ENDLIST";
const TAG_KEY: &str = "#EXT-X-KEY";
const TIMES_NO_AD: i32 = 15;

fn regex_x_discontinuity() -> Regex {
    Regex::new(r"#EXT-X-DISCONTINUITY[\s\S]*?(?=#EXT-X-DISCONTINUITY|$)").unwrap()
}

fn regex_media_duration() -> Regex {
    Regex::new(r"#EXTINF:([\d.]+)\b").unwrap()
}

fn regex_uri() -> Regex {
    Regex::new(r#"URI="(.+?)""#).unwrap()
}

fn is_double(ad: &str) -> bool {
    ad.parse::<f64>().map(|v| v != 0.0).unwrap_or(false)
}

fn is_ad(regex: &str) -> bool {
    regex.contains(TAG_DISCONTINUITY) || regex.contains(TAG_MEDIA_DURATION) ||
        regex.contains(TAG_ENDLIST) || regex.contains(TAG_KEY) || is_double(regex)
}

fn max_percent(pre_url_map: &HashMap<String, i32>) -> f64 {
    let mut max_times = 0;
    let mut total_times = 0;
    for &value in pre_url_map.values() {
        if value > max_times { max_times = value; }
        total_times += value;
    }
    if total_times == 0 { return 0.0; }
    max_times as f64 / total_times as f64
}

fn remove_minority_url(ts_url_pre: &str, m3u8_content: &str, removed_segments: &mut i32) -> Option<String> {
    let line_split = if m3u8_content.contains("\r\n") { "\r\n" } else { "\n" };
    let mut lines: Vec<String> = m3u8_content.split(line_split).map(|s| s.to_string()).collect();

    let mut pre_url_map: HashMap<String, i32> = HashMap::new();
    for line in &lines {
        if line.is_empty() || line.starts_with('#') { continue; }
        let ilast = match line.rfind('.') { Some(i) => i, None => continue };
        if ilast <= 4 { continue; }
        let pre_url = &line[..ilast - 4];
        *pre_url_map.entry(pre_url.to_string()).or_insert(0) += 1;
    }

    if pre_url_map.len() <= 1 { return None; }

    let mut domain_filtering = false;
    if max_percent(&pre_url_map) < 0.8 {
        pre_url_map.clear();
        for line in &lines {
            if line.is_empty() || line.starts_with('#') { continue; }
            if !line.starts_with("http://") && !line.starts_with("https://") { return None; }
            let ifirst = match line[9..].find('/') { Some(i) => i + 9, None => continue };
            if ifirst <= 0 { continue; }
            let pre_url = &line[..ifirst];
            *pre_url_map.entry(pre_url.to_string()).or_insert(0) += 1;
        }
        if pre_url_map.len() <= 1 { return None; }
        if max_percent(&pre_url_map) < 0.8 { return None; }
        let mut all_domains_exceed_threshold = true;
        for &count in pre_url_map.values() {
            if count <= TIMES_NO_AD { all_domains_exceed_threshold = false; break; }
        }
        if all_domains_exceed_threshold { return None; }
        domain_filtering = true;
    }

    let mut max_times = 0;
    let mut max_times_pre_url = String::new();
    for (key, &value) in &pre_url_map {
        if value > max_times { max_times_pre_url = key.clone(); max_times = value; }
    }
    if max_times == 0 { return None; }

    let mut dealed_ext_x_key = false;
    for i in 0..lines.len() {
        if !dealed_ext_x_key && lines[i].starts_with("#EXT-X-KEY") {
            if let Some(start) = lines[i].find("URI=\"") {
                let start_idx = start + 5;
                if let Some(end) = lines[i][start_idx..].find('"') {
                    let key_url = &lines[i][start_idx..start_idx + end];
                    if !key_url.is_empty() && !key_url.starts_with("http://") && !key_url.starts_with("https://") {
                        let new_key_url = if key_url.starts_with('/') {
                            if let Some(ifirst) = ts_url_pre[9..].find('/') {
                                format!("{}{}", &ts_url_pre[..ifirst + 9], key_url)
                            } else {
                                format!("{}{}", ts_url_pre, key_url)
                            }
                        } else {
                            format!("{}{}", ts_url_pre, key_url)
                        };
                        lines[i] = lines[i].replace(&format!("URI=\"{}\"", key_url), &format!("URI=\"{}\"", new_key_url));
                    }
                    dealed_ext_x_key = true;
                }
            }
        }

        if lines[i].is_empty() || lines[i].starts_with('#') { continue; }

        if !domain_filtering {
            if lines[i].starts_with(&max_times_pre_url) {
                if !lines[i].starts_with("http://") && !lines[i].starts_with("https://") {
                    let absolute = if lines[i].starts_with('/') {
                        if let Some(ifirst) = ts_url_pre[9..].find('/') {
                            format!("{}{}", &ts_url_pre[..ifirst + 9], lines[i])
                        } else {
                            format!("{}{}", ts_url_pre, lines[i])
                        }
                    } else {
                        format!("{}{}", ts_url_pre, lines[i])
                    };
                    lines[i] = absolute;
                }
            } else {
                if i > 0 && !lines[i - 1].is_empty() && lines[i - 1].starts_with('#') { lines[i - 1] = String::new(); }
                lines[i] = String::new();
                *removed_segments += 1;
            }
        } else {
            let mut absolute_url = lines[i].clone();
            if !absolute_url.starts_with("http://") && !absolute_url.starts_with("https://") {
                absolute_url = if absolute_url.starts_with('/') {
                    if let Some(ifirst) = ts_url_pre[9..].find('/') {
                        format!("{}{}", &ts_url_pre[..ifirst + 9], absolute_url)
                    } else {
                        format!("{}{}", ts_url_pre, absolute_url)
                    }
                } else {
                    format!("{}{}", ts_url_pre, absolute_url)
                };
            }
            let ifirst = absolute_url[9..].find('/').map(|i| i + 9).unwrap_or(absolute_url.len());
            let domain = &absolute_url[..ifirst];
            let cnt = pre_url_map.get(domain).copied().unwrap_or(0);
            if domain == max_times_pre_url || cnt > TIMES_NO_AD {
                lines[i] = absolute_url;
            } else {
                if i > 0 && !lines[i - 1].is_empty() && lines[i - 1].starts_with('#') { lines[i - 1] = String::new(); }
                lines[i] = String::new();
                *removed_segments += 1;
            }
        }
    }

    Some(lines.join(line_split))
}

fn should_resolve(line: &str) -> bool {
    (!line.starts_with('#') && !line.starts_with("http")) || line.starts_with(TAG_KEY)
}

fn apply_rules(ts_url_pre: &str, m3u8_content: &str, ad_rules: Option<&Vec<String>>, removed_segments: &mut i32) -> Option<String> {
    let content = m3u8_content.replace("\r\n", "\n");
    let mut sb = String::new();
    for line in content.split('\n') {
        if should_resolve(line) {
            if line.starts_with(TAG_KEY) {
                let re = regex_uri();
                if let Some(caps) = re.captures(line) {
                    let value = caps.get(1).map(|m| m.as_str()).unwrap_or("");
                    let resolved = resolve_url(ts_url_pre, value);
                    sb.push_str(&line.replace(value, &resolved));
                } else {
                    sb.push_str(line);
                }
            } else {
                let resolved = resolve_url(ts_url_pre, line);
                sb.push_str(&resolved);
            }
        } else {
            sb.push_str(line);
        }
        sb.push('\n');
    }

    let ad_rules = ad_rules?;
    if ad_rules.is_empty() { return None; }

    Some(clean(&sb, ad_rules, removed_segments))
}

fn resolve_url(base: &str, reference: &str) -> String {
    if reference.is_empty() { return String::new(); }
    if reference.starts_with("http://") || reference.starts_with("https://") { return reference.to_string(); }
    if reference.starts_with('/') {
        if let Some(ifirst) = base[9..].find('/') {
            return format!("{}{}", &base[..ifirst + 9], reference);
        }
        return format!("{}{}", base, reference);
    }
    if base.ends_with('/') {
        format!("{}{}", base, reference)
    } else {
        if let Some(last_slash) = base.rfind('/') {
            format!("{}/{}", &base[..last_slash], reference)
        } else {
            format!("{}/{}", base, reference)
        }
    }
}

fn clean(line: &str, ads: &[String], removed_segments: &mut i32) -> String {
    let mut result = line.to_string();
    let mut scan_flag = false;
    for ad in ads {
        if ad.contains(TAG_DISCONTINUITY) || ad.contains(TAG_MEDIA_DURATION) {
            result = scan_ad(&result, ad, removed_segments);
        } else if is_double(ad) {
            scan_flag = true;
        }
    }
    if scan_flag { scan(&result, ads, removed_segments) } else { result }
}

fn scan_ad(line: &str, tag_ad: &str, removed_segments: &mut i32) -> String {
    let re = Regex::new(tag_ad).unwrap();
    let mut need_remove_ad: Vec<String> = Vec::new();
    let dur_re = regex_media_duration();

    for caps in re.captures_iter(line) {
        let group = caps.get(0).map(|m| m.as_str()).unwrap_or("");
        let group_cleaned = group.replace(TAG_ENDLIST, "");
        let mut t_count = 0;
        for _ in dur_re.captures_iter(&group_cleaned) { t_count += 1; }
        need_remove_ad.push(group_cleaned);
        *removed_segments += t_count;
    }

    if need_remove_ad.is_empty() { return line.to_string(); }
    need_remove_ad.sort_by(|a, b| b.len().cmp(&a.len()));

    let mut result = line.to_string();
    for rem in &need_remove_ad {
        result = result.replace(rem, "");
    }
    result
}

fn scan(line: &str, ads: &[String], removed_segments: &mut i32) -> String {
    let re = regex_x_discontinuity();
    let dur_re = regex_media_duration();
    let mut need_remove_ad: Vec<String> = Vec::new();

    for caps in re.captures_iter(line) {
        let group = caps.get(0).map(|m| m.as_str()).unwrap_or("");
        let group_cleaned = group.replace(TAG_ENDLIST, "");

        let mut ft = 0.0f64;
        let mut lt = 0.0f64;
        let mut t = 0.0f64;
        let mut t_count = 0i32;

        for dur_caps in dur_re.captures_iter(&group_cleaned) {
            let val: f64 = dur_caps.get(1).map(|m| m.as_str()).unwrap_or("0").parse().unwrap_or(0.0);
            if ft == 0.0 { ft = val; }
            lt = val;
            t += val;
            t_count += 1;
        }

        let ft_str = format!("{}", ft);
        let lt_str = format!("{}", lt);
        let t_str = format!("{}", t);

        for ad in ads {
            if ad.starts_with('-') {
                let ad_clean = &ad[1..];
                if lt_str.starts_with(ad_clean) {
                    need_remove_ad.push(group_cleaned.clone());
                    *removed_segments += t_count;
                    break;
                }
            } else {
                if ft_str.starts_with(ad) || t_str.starts_with(ad) {
                    need_remove_ad.push(group_cleaned.clone());
                    *removed_segments += t_count;
                    break;
                }
            }
        }
    }

    let mut result = line.to_string();
    for rem in &need_remove_ad {
        result = result.replace(rem, "");
    }
    result
}

fn purify(ts_url_pre: &str, m3u8_content: &str, ad_rules: Option<&Vec<String>>) -> Option<String> {
    if m3u8_content.is_empty() { return None; }
    if !m3u8_content.starts_with("#EXTM3U") { return None; }

    let mut removed_segments = 0i32;
    let result = remove_minority_url(ts_url_pre, m3u8_content, &mut removed_segments);
    if let Some(r) = &result {
        if removed_segments > 0 { return Some(r.clone()); }
    }

    apply_rules(ts_url_pre, m3u8_content, ad_rules, &mut removed_segments)
}

#[no_mangle]
pub extern "system" fn Java_com_wuming_tv_core_playback_HlsPlaylistPurifier_nativePurify(
    mut env: JNIEnv,
    _class: JClass,
    ts_url_pre: JString,
    m3u8_content: JString,
    ad_rules_json: JString,
) -> jstring {
    let ts_url_pre_str = jstring_to_string(&mut env, &ts_url_pre);
    let m3u8_content_str = jstring_to_string(&mut env, &m3u8_content);
    let ad_rules_json_str = jstring_to_string(&mut env, &ad_rules_json);

    let ad_rules: Option<Vec<String>> = if ad_rules_json_str.is_empty() {
        None
    } else {
        serde_json::from_str(&ad_rules_json_str).ok()
    };

    let result = purify(&ts_url_pre_str, &m3u8_content_str, ad_rules.as_ref());
    match result {
        Some(s) => crate::return_jstring(&mut env, &s),
        None => std::ptr::null_mut(),
    }
}
