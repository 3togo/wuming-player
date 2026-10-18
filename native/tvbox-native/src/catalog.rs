use crate::jstring_to_string;
use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use serde::{Deserialize, Serialize};
use serde_json::{Value, Map};
use std::collections::HashMap;

#[derive(Serialize, Deserialize)]
struct CatalogItemJson {
    source_key: String,
    id: String,
    name: String,
    picture: String,
    remarks: String,
    year: String,
    kind: String,
    action: String,
    type_name: String,
    area: String,
    director: String,
    actor: String,
    content: String,
}

#[derive(Serialize, Deserialize)]
struct CatalogTypeJson {
    id: String,
    name: String,
    folder: bool,
    filters: Vec<CatalogFilterJson>,
}

#[derive(Serialize, Deserialize)]
struct CatalogFilterJson {
    key: String,
    name: String,
    init: Option<String>,
    values: Vec<CatalogFilterValueJson>,
}

#[derive(Serialize, Deserialize)]
struct CatalogFilterValueJson {
    name: String,
    value: String,
}

#[derive(Serialize, Deserialize)]
struct CatalogHomeJson {
    items: Vec<CatalogItemJson>,
    types: Vec<CatalogTypeJson>,
}

#[derive(Serialize, Deserialize)]
struct CatalogPageJson {
    items: Vec<CatalogItemJson>,
    page: i32,
    page_count: i32,
    total: i32,
}

#[derive(Serialize, Deserialize)]
struct CatalogDetailJson {
    item: CatalogItemJson,
    content: String,
    actor: String,
    director: String,
    area: String,
    lines: Vec<CatalogLineJson>,
}

#[derive(Serialize, Deserialize)]
struct CatalogLineJson {
    flag: String,
    name: String,
    episodes: Vec<CatalogEpisodeJson>,
}

#[derive(Serialize, Deserialize)]
struct CatalogEpisodeJson {
    name: String,
    id: String,
}

#[derive(Serialize, Deserialize)]
struct CatalogPlaybackJson {
    supported: bool,
    url: String,
    format: String,
    message: String,
    headers: HashMap<String, String>,
}

fn str_val(obj: &Map<String, Value>, key: &str) -> String {
    obj.get(key)
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .trim()
        .to_string()
}

fn int_val(obj: &Map<String, Value>, key: &str, fallback: i32) -> i32 {
    obj.get(key)
        .and_then(|v| {
            v.as_i64().map(|n| n as i32)
                .or_else(|| v.as_str().and_then(|s| s.parse().ok()))
        })
        .unwrap_or(fallback)
}

fn is_http(value: &str) -> bool {
    let lower = value.to_lowercase();
    (lower.starts_with("http://") || lower.starts_with("https://")) && {
        let after_scheme = &value[lower.find("://").unwrap() + 3..];
        !after_scheme.is_empty()
    }
}

fn looks_like_media(value: &str) -> bool {
    let path = value.split('?').next().unwrap_or("").to_lowercase();
    [".m3u8", ".mp4", ".mkv", ".webm", ".flv", ".mp3", ".aac"].iter().any(|ext| path.ends_with(ext))
}

fn parse_item(obj: &Map<String, Value>, source_key: &str, requested_id: &str) -> Option<CatalogItemJson> {
    let id = str_val(obj, "vod_id");
    let id = if id.is_empty() { requested_id.trim().to_string() } else { id };
    if id.is_empty() { return None; }

    let name = str_val(obj, "vod_name");
    let name = if name.is_empty() { id.clone() } else { name };

    let kind = if !str_val(obj, "action").is_empty() {
        "ACTION"
    } else if str_val(obj, "vod_tag").eq_ignore_ascii_case("folder") || obj.get("cate").is_some() {
        "FOLDER"
    } else {
        "MEDIA"
    };

    Some(CatalogItemJson {
        source_key: source_key.to_string(),
        id,
        name,
        picture: str_val(obj, "vod_pic"),
        remarks: str_val(obj, "vod_remarks"),
        year: str_val(obj, "vod_year"),
        kind: kind.to_string(),
        action: str_val(obj, "action"),
        type_name: str_val(obj, "type_name"),
        area: str_val(obj, "vod_area"),
        director: str_val(obj, "vod_director"),
        actor: str_val(obj, "vod_actor"),
        content: str_val(obj, "vod_content"),
    })
}

fn parse_filter(obj: &Map<String, Value>) -> Option<CatalogFilterJson> {
    let key = str_val(obj, "key");
    if key.is_empty() { return None; }

    let values: Vec<CatalogFilterValueJson> = obj.get("value")
        .and_then(|v| v.as_array())
        .map(|arr| arr.iter().filter_map(|opt| {
            opt.as_object().map(|item| {
                let id = str_val(item, "v");
                let name = str_val(item, "n");
                let name = if name.is_empty() { if id.is_empty() { "All".to_string() } else { id.clone() } } else { name };
                CatalogFilterValueJson { name, value: id }
            }).filter(|v| !v.value.is_empty())
        }).collect())
        .unwrap_or_default();

    if values.is_empty() { return None; }

    Some(CatalogFilterJson {
        key,
        name: { let n = str_val(obj, "name"); if n.is_empty() { str_val(obj, "key") } else { n } },
        init: obj.get("init").and_then(|v| v.as_str()).map(|s| s.to_string()),
        values,
    })
}

fn parse_lines(from: &str, urls: &str) -> Vec<CatalogLineJson> {
    let flags: Vec<&str> = from.split("$$$").collect();
    urls.split("$$$").enumerate().filter_map(|(index, encoded)| {
        let episodes: Vec<CatalogEpisodeJson> = encoded.split('#').filter_map(|token| {
            let separator = token.find('$');
            match separator {
                None => None,
                Some(pos) if pos == token.len() - 1 => None,
                Some(pos) => {
                    let name = token[..pos].trim();
                    let url = token[pos + 1..].trim();
                    Some(CatalogEpisodeJson {
                        name: if name.is_empty() { "Play".to_string() } else { name.to_string() },
                        id: url.to_string(),
                    })
                }
            }
        }).collect();

        if episodes.is_empty() { return None; }

        let flag_raw = flags.get(index).copied().unwrap_or("");
        let flag = if flag_raw.is_empty() { format!("line-{}", index + 1) } else { flag_raw.to_string() };
        let name = if flag_raw.is_empty() { format!("Line {}", index + 1) } else { flag_raw.to_string() };

        Some(CatalogLineJson { flag, name, episodes })
    }).collect()
}

fn parse_playback_url(value: &Map<String, Value>) -> String {
    let direct = value.get("url");
    match direct {
        Some(Value::String(s)) => s.trim().to_string(),
        Some(Value::Array(arr)) => {
            arr.iter().filter_map(|v| v.as_str())
                .find(|s| is_http(s))
                .map(|s| s.trim().to_string())
                .unwrap_or_default()
        }
        Some(Value::Object(obj)) => {
            let values: Vec<String> = obj.get("values")
                .and_then(|v| v.as_array())
                .map(|arr| arr.iter().filter_map(|entry| {
                    entry.as_object().and_then(|o| o.get("v")).and_then(|v| v.as_str()).map(|s| s.trim().to_string())
                }).filter(|s| is_http(s)).collect())
                .unwrap_or_default();

            let position = int_val(obj, "position", 0) as usize;
            if position < values.len() { values[position].clone() }
            else { values.first().cloned().unwrap_or_default() }
        }
        _ => String::new(),
    }
}

fn parse_headers(value: Option<&Value>) -> HashMap<String, String> {
    let mut result = HashMap::new();
    let obj = match value {
        Some(Value::Object(o)) => Some(o.clone()),
        Some(Value::String(s)) => serde_json::from_str::<Value>(s).ok().and_then(|v| v.as_object().map(|o| o.clone())),
        _ => None,
    };

    if let Some(obj) = obj {
        for (key, val) in obj.iter() {
            if let Some(s) = val.as_str() {
                result.insert(key.clone(), s.to_string());
            }
        }
    }
    result
}

fn native_search(json: &str, source_key: &str) -> String {
    let root: Value = match serde_json::from_str(json) { Ok(v) => v, Err(_) => return String::new() };
    let items: Vec<CatalogItemJson> = root.get("list")
        .and_then(|v| v.as_array())
        .map(|arr| arr.iter().filter_map(|e| {
            e.as_object().and_then(|obj| parse_item(obj, source_key, ""))
        }).collect())
        .unwrap_or_default();

    serde_json::to_string(&items).unwrap_or_default()
}

fn native_home(json: &str, source_key: &str) -> String {
    let root: Value = match serde_json::from_str(json) { Ok(v) => v, Err(_) => return String::new() };
    let root_obj = root.as_object();

    let root_filters = root_obj.and_then(|o| o.get("filters")).and_then(|v| v.as_object());

    let types: Vec<CatalogTypeJson> = root_obj.and_then(|o| o.get("class"))
        .and_then(|v| v.as_array())
        .map(|arr| arr.iter().filter_map(|element| {
            let type_obj = element.as_object()?;
            let id = { let i = str_val(type_obj, "type_id"); if i.is_empty() { str_val(type_obj, "id") } else { i } };
            if id.is_empty() { return None; }

            let mut embedded: Vec<CatalogFilterJson> = Vec::new();
            if let Some(Value::Array(arr)) = type_obj.get("filters") {
                for e in arr {
                    if let Some(o) = e.as_object() {
                        if let Some(f) = parse_filter(o) { embedded.push(f); }
                    }
                }
            } else if let Some(Value::Object(o)) = type_obj.get("filters") {
                if let Some(f) = parse_filter(o) { embedded.push(f); }
            }

            let mut scoped: Vec<CatalogFilterJson> = Vec::new();
            if let Some(rf) = root_filters {
                if let Some(Value::Array(arr)) = rf.get(&id) {
                    for e in arr {
                        if let Some(o) = e.as_object() {
                            if let Some(f) = parse_filter(o) { scoped.push(f); }
                        }
                    }
                } else if let Some(o) = rf.get(&id).and_then(|v| v.as_object()) {
                    if let Some(f) = parse_filter(o) { scoped.push(f); }
                }
            }

            let mut all_filters = embedded;
            all_filters.extend(scoped);
            all_filters.dedup_by(|a, b| a.key == b.key);

            let name = { let n = str_val(type_obj, "type_name"); if n.is_empty() { str_val(type_obj, "name") } else { n } };
            let name = if name.is_empty() { id.clone() } else { name };

            Some(CatalogTypeJson {
                id,
                name,
                folder: str_val(type_obj, "type_flag") == "1",
                filters: all_filters,
            })
        }).collect())
        .unwrap_or_default();

    let items: Vec<CatalogItemJson> = root_obj.and_then(|o| o.get("list"))
        .and_then(|v| v.as_array())
        .map(|arr| arr.iter().filter_map(|e| {
            e.as_object().and_then(|obj| parse_item(obj, source_key, ""))
        }).collect())
        .unwrap_or_default();

    serde_json::to_string(&CatalogHomeJson { items, types }).unwrap_or_default()
}

fn native_category(json: &str, source_key: &str, requested_page: i32) -> String {
    let root: Value = match serde_json::from_str(json) { Ok(v) => v, Err(_) => return String::new() };
    let root_obj = root.as_object();

    let items: Vec<CatalogItemJson> = root_obj.and_then(|o| o.get("list"))
        .and_then(|v| v.as_array())
        .map(|arr| arr.iter().filter_map(|e| {
            e.as_object().and_then(|obj| parse_item(obj, source_key, ""))
        }).collect())
        .unwrap_or_default();

    let page = root_obj.map(|o| int_val(o, "page", requested_page)).unwrap_or(requested_page).max(1);
    let page_count = root_obj.map(|o| int_val(o, "pagecount", 0)).unwrap_or(0).max(0);
    let total = root_obj.map(|o| int_val(o, "total", 0)).unwrap_or(0).max(0);

    serde_json::to_string(&CatalogPageJson { items, page, page_count, total }).unwrap_or_default()
}

fn native_detail(json: &str, source_key: &str, requested_id: &str) -> String {
    let root: Value = match serde_json::from_str(json) { Ok(v) => v, Err(_) => return String::new() };

    let list_item = root.get("list")
        .and_then(|v| v.as_array())
        .and_then(|arr| arr.first())
        .and_then(|v| v.as_object());

    let value = match list_item { Some(v) => v, None => return String::new() };

    let item = match parse_item(value, source_key, requested_id) { Some(i) => i, None => return String::new() };

    let detail = CatalogDetailJson {
        item,
        content: str_val(value, "vod_content"),
        actor: str_val(value, "vod_actor"),
        director: str_val(value, "vod_director"),
        area: str_val(value, "vod_area"),
        lines: parse_lines(&str_val(value, "vod_play_from"), &str_val(value, "vod_play_url")),
    };

    serde_json::to_string(&detail).unwrap_or_default()
}

fn native_playback(json: &str) -> String {
    let root: Value = match serde_json::from_str(json) { Ok(v) => v, Err(_) => return String::new() };
    let root_obj = match root.as_object() { Some(o) => o, None => return String::new() };

    let location = parse_playback_url(root_obj);
    let valid_http = is_http(&location);
    let interactive = int_val(root_obj, "parse", int_val(root_obj, "jx", 0)) != 0 && !looks_like_media(&location);

    let playback = CatalogPlaybackJson {
        supported: valid_http && !interactive,
        url: if valid_http && !interactive { location.clone() } else { String::new() },
        format: str_val(root_obj, "format"),
        message: if !valid_http {
            "CatVod provider did not return an HTTP(S) playback URL.".to_string()
        } else if interactive {
            "CatVod provider returned a web page that requires browser sniffing.".to_string()
        } else {
            String::new()
        },
        headers: parse_headers(root_obj.get("header")),
    };

    serde_json::to_string(&playback).unwrap_or_default()
}

#[no_mangle]
pub extern "system" fn Java_com_wuming_tv_core_catalog_CatVodCatalogCodec_nativeSearch(
    mut env: JNIEnv,
    _class: JClass,
    json: JString,
    source_key: JString,
) -> jstring {
    let json_str = jstring_to_string(&mut env, &json);
    let source_key_str = jstring_to_string(&mut env, &source_key);
    let result = native_search(&json_str, &source_key_str);
    crate::return_jstring(&mut env, &result)
}

#[no_mangle]
pub extern "system" fn Java_com_wuming_tv_core_catalog_CatVodCatalogCodec_nativeHome(
    mut env: JNIEnv,
    _class: JClass,
    json: JString,
    source_key: JString,
) -> jstring {
    let json_str = jstring_to_string(&mut env, &json);
    let source_key_str = jstring_to_string(&mut env, &source_key);
    let result = native_home(&json_str, &source_key_str);
    crate::return_jstring(&mut env, &result)
}

#[no_mangle]
pub extern "system" fn Java_com_wuming_tv_core_catalog_CatVodCatalogCodec_nativeCategory(
    mut env: JNIEnv,
    _class: JClass,
    json: JString,
    source_key: JString,
    requested_page: i32,
) -> jstring {
    let json_str = jstring_to_string(&mut env, &json);
    let source_key_str = jstring_to_string(&mut env, &source_key);
    let result = native_category(&json_str, &source_key_str, requested_page);
    crate::return_jstring(&mut env, &result)
}

#[no_mangle]
pub extern "system" fn Java_com_wuming_tv_core_catalog_CatVodCatalogCodec_nativeDetail(
    mut env: JNIEnv,
    _class: JClass,
    json: JString,
    source_key: JString,
    requested_id: JString,
) -> jstring {
    let json_str = jstring_to_string(&mut env, &json);
    let source_key_str = jstring_to_string(&mut env, &source_key);
    let requested_id_str = jstring_to_string(&mut env, &requested_id);
    let result = native_detail(&json_str, &source_key_str, &requested_id_str);
    crate::return_jstring(&mut env, &result)
}

#[no_mangle]
pub extern "system" fn Java_com_wuming_tv_core_catalog_CatVodCatalogCodec_nativePlayback(
    mut env: JNIEnv,
    _class: JClass,
    json: JString,
) -> jstring {
    let json_str = jstring_to_string(&mut env, &json);
    let result = native_playback(&json_str);
    crate::return_jstring(&mut env, &result)
}
