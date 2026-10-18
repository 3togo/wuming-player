use crate::jstring_to_string;
use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use serde::{Deserialize, Serialize};

type LinkedMap<K, V> = std::collections::HashMap<K, V>;

#[derive(Serialize, Deserialize)]
struct LivePlaylistJson {
    groups: Vec<LiveGroupJson>,
    epg_urls: Vec<String>,
}

#[derive(Serialize, Deserialize)]
struct LiveGroupJson {
    name: String,
    channels: Vec<LiveChannelJson>,
}

#[derive(Serialize, Deserialize)]
struct LiveChannelJson {
    name: String,
    tvg_id: String,
    tvg_name: String,
    logo: String,
    number: String,
    streams: Vec<LiveStreamJson>,
}

#[derive(Serialize, Deserialize)]
struct LiveStreamJson {
    url: String,
    headers: LinkedMap<String, String>,
}

fn parse_m3u(content: &str) -> String {
    let mut groups: LinkedMap<String, LinkedMap<String, LiveChannelJson>> = LinkedMap::new();
    let mut epg_urls: Vec<String> = Vec::new();
    let mut current_group = String::new();
    let mut current_name = String::new();
    let mut current_tvg_id = String::new();
    let mut current_tvg_name = String::new();
    let mut current_logo = String::new();
    let mut current_number = String::new();
    let mut current_headers: LinkedMap<String, String> = LinkedMap::new();
    let mut directive_headers: LinkedMap<String, String> = LinkedMap::new();
    let mut has_extinf = false;

    for line in content.lines() {
        let trimmed = line.trim();
        if trimmed.is_empty() { continue; }
        if trimmed.starts_with('#') {
            if trimmed.to_lowercase().starts_with("#extm3u") {
                let attrs = parse_m3u_attributes(trimmed);
                for key in &["tvg-url", "x-tvg-url", "url-tvg"] {
                    if let Some(val) = attrs.get(*key) {
                        for url in val.split(',') {
                            let url = url.trim();
                            if !url.is_empty() { epg_urls.push(url.to_string()); }
                        }
                    }
                }
            } else if trimmed.to_lowercase().starts_with("#extinf:") {
                let attrs = parse_m3u_attributes(trimmed);
                current_group = attrs.get("group-title").cloned().unwrap_or_default();
                if current_group.is_empty() { current_group = "Live TV".to_string(); }
                current_name = extract_name_after_comma(trimmed, &attrs);
                if current_name.is_empty() { current_name = attrs.get("tvg-name").cloned().unwrap_or_default(); }
                if current_name.is_empty() { current_name = "Unnamed channel".to_string(); }
                current_tvg_id = attrs.get("tvg-id").cloned().unwrap_or_default();
                current_tvg_name = attrs.get("tvg-name").cloned().unwrap_or_default();
                current_logo = attrs.get("tvg-logo").cloned().unwrap_or_default();
                current_number = attrs.get("tvg-chno").cloned().unwrap_or_default();
                current_headers = LinkedMap::new();
                if let Some(ua) = attrs.get("http-user-agent") {
                    if !ua.is_empty() {
                        current_headers.insert("User-Agent".to_string(), ua.clone());
                    }
                }
                directive_headers = LinkedMap::new();
                has_extinf = true;
            } else if trimmed.to_lowercase().starts_with("#extvlcopt:http-user-agent=") {
                directive_headers.insert("User-Agent".to_string(), trimmed['='.len_utf8()..].trim().to_string());
            } else if trimmed.to_lowercase().starts_with("#extvlcopt:http-referrer=") {
                directive_headers.insert("Referer".to_string(), trimmed['='.len_utf8()..].trim().to_string());
            } else if trimmed.to_lowercase().starts_with("#extvlcopt:http-origin=") {
                directive_headers.insert("Origin".to_string(), trimmed['='.len_utf8()..].trim().to_string());
            }
        } else if !trimmed.starts_with('#') && trimmed.contains("://") && has_extinf {
            let mut combined_headers = current_headers.clone();
            for (k, v) in &directive_headers {
                combined_headers.insert(k.clone(), v.clone());
            }
            let stream = parse_stream(trimmed, &combined_headers);
            add_channel(&mut groups, &current_group, &current_name, &current_tvg_id, &current_tvg_name, &current_logo, &current_number, stream);
            directive_headers = LinkedMap::new();
        }
    }

    let group_list: Vec<LiveGroupJson> = groups.into_iter().map(|(name, channels)| {
        LiveGroupJson { name, channels: channels.into_values().collect() }
    }).filter(|g| !g.channels.is_empty()).collect();

    let result = LivePlaylistJson { groups: group_list, epg_urls };
    serde_json::to_string(&result).unwrap_or_default()
}

fn parse_txt(content: &str) -> String {
    let mut groups: LinkedMap<String, LinkedMap<String, LiveChannelJson>> = LinkedMap::new();
    let mut group = "Live TV".to_string();
    let mut directive_headers: LinkedMap<String, String> = LinkedMap::new();

    for line in content.lines() {
        let trimmed = line.trim();
        if trimmed.is_empty() || trimmed.starts_with("//") { continue; }
        if trimmed.to_lowercase().starts_with("ua=") {
            directive_headers.insert("User-Agent".to_string(), trimmed[3..].trim().trim_matches('"').to_string());
        } else if trimmed.to_lowercase().starts_with("referer=") {
            directive_headers.insert("Referer".to_string(), trimmed[8..].trim().trim_matches('"').to_string());
        } else if trimmed.to_lowercase().starts_with("origin=") {
            directive_headers.insert("Origin".to_string(), trimmed[7..].trim().trim_matches('"').to_string());
        } else if trimmed.to_lowercase().contains("#genre#") {
            group = trimmed.split(',').next().unwrap_or("").trim().to_string();
            if group.is_empty() { group = "Live TV".to_string(); }
            directive_headers = LinkedMap::new();
        } else if let Some(comma_pos) = trimmed.find(',') {
            let name = trimmed[..comma_pos].trim();
            let locations = trimmed[comma_pos + 1..].trim();
            if !name.is_empty() && locations.contains("://") {
                for location in locations.split('#') {
                    let location = location.trim();
                    if location.contains("://") {
                        let stream = parse_stream(location, &directive_headers);
                        add_channel(&mut groups, &group, name, "", "", "", "", stream);
                    }
                }
            }
        }
    }

    let group_list: Vec<LiveGroupJson> = groups.into_iter().map(|(name, channels)| {
        LiveGroupJson { name, channels: channels.into_values().collect() }
    }).filter(|g| !g.channels.is_empty()).collect();

    let result = LivePlaylistJson { groups: group_list, epg_urls: Vec::new() };
    serde_json::to_string(&result).unwrap_or_default()
}

fn parse_m3u_attributes(line: &str) -> std::collections::HashMap<String, String> {
    let mut result = std::collections::HashMap::new();
    let chars: Vec<char> = line.chars().collect();
    let mut i = 0;
    while i < chars.len() {
        if chars[i] == '=' && i + 1 < chars.len() && chars[i + 1] == '"' {
            let mut key_start = i;
            while key_start > 0 && (chars[key_start - 1].is_alphanumeric() || chars[key_start - 1] == '-' || chars[key_start - 1] == '_') {
                key_start -= 1;
            }
            let key: String = chars[key_start..i].iter().collect();
            i += 2;
            let mut value = String::new();
            while i < chars.len() {
                if chars[i] == '"' { i += 1; break; }
                value.push(chars[i]);
                i += 1;
            }
            result.insert(key.to_lowercase(), value);
        } else {
            i += 1;
        }
    }
    result
}

fn extract_name_after_comma(line: &str, attrs: &std::collections::HashMap<String, String>) -> String {
    if let Some(comma_pos) = line.rfind(',') {
        let name = line[comma_pos + 1..].trim();
        if !name.is_empty() { return name.to_string(); }
    }
    attrs.get("tvg-name").cloned().unwrap_or_default()
}

fn parse_stream(value: &str, inherited_headers: &LinkedMap<String, String>) -> LiveStreamJson {
    let parts: Vec<&str> = value.splitn(2, '|').collect();
    let url = parts[0].trim().to_string();
    let mut headers = LinkedMap::new();
    for (k, v) in inherited_headers {
        headers.insert(k.clone(), v.clone());
    }
    if parts.len() == 2 {
        for parameter in parts[1].split('&') {
            let pair: Vec<&str> = parameter.splitn(2, '=').collect();
            if pair.len() == 2 && !pair[0].trim().is_empty() {
                let key = url_decode(pair[0].trim());
                let val = url_decode(pair[1].trim().trim_matches('"'));
                headers.insert(key, val);
            }
        }
    }
    LiveStreamJson { url, headers }
}

fn url_decode(value: &str) -> String {
    let mut result = String::with_capacity(value.len());
    let bytes = value.as_bytes();
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' && i + 2 < bytes.len() {
            let hex = &value[i + 1..i + 3];
            if let Ok(byte) = u8::from_str_radix(hex, 16) {
                result.push(byte as char);
                i += 3;
                continue;
            }
        }
        result.push(bytes[i] as char);
        i += 1;
    }
    result
}

fn add_channel(
    groups: &mut LinkedMap<String, LinkedMap<String, LiveChannelJson>>,
    group: &str,
    name: &str,
    tvg_id: &str,
    tvg_name: &str,
    logo: &str,
    number: &str,
    stream: LiveStreamJson,
) {
    if stream.url.is_empty() { return; }
    let channels = groups.entry(group.to_string()).or_insert_with(LinkedMap::new);
    let key = channel_key(tvg_id, tvg_name, name);
    let entry = channels.entry(key).or_insert_with(|| LiveChannelJson {
        name: name.to_string(),
        tvg_id: tvg_id.to_string(),
        tvg_name: tvg_name.to_string(),
        logo: logo.to_string(),
        number: number.to_string(),
        streams: Vec::new(),
    });
    entry.streams.push(stream);
}

fn channel_key(tvg_id: &str, tvg_name: &str, name: &str) -> String {
    let raw = if !tvg_id.is_empty() { tvg_id }
        else if !tvg_name.is_empty() { tvg_name }
        else { name };
    raw.to_lowercase().chars().filter(|c| c.is_alphanumeric() || is_cjk_letter(*c)).collect()
}

fn is_cjk_letter(c: char) -> bool {
    let code = c as u32;
    (0x4E00..=0x9FFF).contains(&code) || (0x3400..=0x4DBF).contains(&code)
        || (0xF900..=0xFAFF).contains(&code) || (0x3040..=0x309F).contains(&code)
        || (0x30A0..=0x30FF).contains(&code)
}

#[no_mangle]
pub extern "system" fn Java_com_wuming_tv_core_live_LivePlaylistParser_nativeParse(
    mut env: JNIEnv,
    _class: JClass,
    content: JString,
) -> jstring {
    let content_str = jstring_to_string(&mut env, &content);
    let is_m3u = content_str.lines().any(|l| l.trim().to_lowercase().starts_with("#extm3u"));
    let result = if is_m3u { parse_m3u(&content_str) } else { parse_txt(&content_str) };
    crate::return_jstring(&mut env, &result)
}
