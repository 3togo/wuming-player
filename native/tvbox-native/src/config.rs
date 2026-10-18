use crate::jstring_to_string;
use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use serde::{Deserialize, Serialize};
use serde_json::{Value, Map};
use std::collections::HashMap;

#[derive(Serialize, Deserialize)]
struct TvBoxSiteConfigJson {
    key: String,
    name: String,
    type_id: i32,
    api: String,
    ext: String,
    jar: String,
    click: String,
    play_url: String,
    searchable: i32,
    quick_search: i32,
    changeable: i32,
    hide: bool,
    indexs: String,
    timeout: i32,
    categories: Vec<String>,
    header: HashMap<String, String>,
    runtime: String,
    fallback_kind: String,
    fallback_ext: String,
    fallback_download_url: String,
    fallback_raw: String,
}

#[derive(Serialize, Deserialize)]
struct TvBoxLiveConfigJson {
    name: String,
    url: String,
    type_id: i32,
    epg: String,
    logo: String,
}

#[derive(Serialize, Deserialize)]
struct TvBoxAdRuleConfigJson {
    name: String,
    hosts: Vec<String>,
    regex: Vec<String>,
}

#[derive(Serialize, Deserialize)]
struct TvBoxConfigJson {
    sites: Vec<TvBoxSiteConfigJson>,
    lives: Vec<TvBoxLiveConfigJson>,
    ad_rules: Vec<TvBoxAdRuleConfigJson>,
    spider: String,
}

fn str_val(obj: &Map<String, Value>, key: &str) -> String {
    obj.get(key).and_then(|v| v.as_str()).unwrap_or("").trim().to_string()
}

fn str_or_empty(obj: &Map<String, Value>, key: &str) -> String {
    obj.get(key).and_then(|v| v.as_str()).unwrap_or("").to_string()
}

fn int_or(obj: &Map<String, Value>, key: &str, fallback: i32) -> i32 {
    obj.get(key).and_then(|v| {
        v.as_i64().map(|n| n as i32)
            .or_else(|| v.as_str().and_then(|s| s.parse().ok()))
    }).unwrap_or(fallback)
}

fn bool_or(obj: &Map<String, Value>, key: &str, default: bool) -> bool {
    obj.get(key).and_then(|v| {
        if v.is_boolean() { v.as_bool() }
        else if v.is_number() { Some(v.as_i64().map(|n| n != 0).unwrap_or(default)) }
        else if v.is_string() {
            let s = v.as_str().unwrap_or("");
            s.parse::<bool>().ok().or_else(|| Some(s == "1"))
        }
        else { None }
    }).unwrap_or(default)
}

fn ext_string(obj: &Map<String, Value>) -> String {
    match obj.get("ext") {
        Some(Value::String(s)) => s.clone(),
        Some(v) => v.to_string(),
        None => String::new(),
    }
}

fn classify_runtime(type_id: i32, api: &str) -> &'static str {
    if type_id == 0 { "DIRECT_JAVASCRIPT" }
    else if type_id == 1 { "SPIDER_RUNTIME_REQUIRED" }
    else if api.ends_with(".jar") { "SPIDER_RUNTIME_REQUIRED" }
    else { "DIRECT_JAVASCRIPT" }
}

fn parse_fallback(runtime: &str, api: &str, ext: &str, jar: &str) -> Option<(String, String, String, String)> {
    if runtime == "DIRECT_JAVASCRIPT" && (api.starts_with("http") || ext.starts_with("http")) {
        let fb_ext = if ext.is_empty() { api.to_string() } else { ext.to_string() };
        let download_url = if api.starts_with("http") { api.to_string() } else { String::new() };
        let raw = if api.starts_with("http") { api.to_string() } else { String::new() };
        Some(("DIRECT_JAVASCRIPT".to_string(), fb_ext, download_url, raw))
    } else if runtime == "SPIDER_RUNTIME_REQUIRED" && (!jar.is_empty() || !ext.is_empty()) {
        let fb_jar = if jar.is_empty() { ext.to_string() } else { jar.to_string() };
        let download_url = if api.starts_with("http") { api.to_string() } else { String::new() };
        let raw = if api.starts_with("http") { api.to_string() } else { String::new() };
        Some(("SPIDER_RUNTIME_REQUIRED".to_string(), fb_jar, download_url, raw))
    } else {
        None
    }
}

fn native_decode(json: &str) -> String {
    let root: Value = match serde_json::from_str(json) { Ok(v) => v, Err(_) => return String::new() };
    let root_obj = match root.as_object() { Some(o) => o, None => return String::new() };

    let sites: Vec<TvBoxSiteConfigJson> = root_obj.get("sites")
        .and_then(|v| v.as_array())
        .map(|arr| arr.iter().filter_map(|e| {
            let obj = e.as_object()?;
            let type_id = int_or(obj, "type", 0);
            let api = str_or_empty(obj, "api");
            let ext = ext_string(obj);
            let jar = str_or_empty(obj, "jar");
            let key = str_or_empty(obj, "key");
            let name = str_or_empty(obj, "name");
            let runtime = classify_runtime(type_id, &api);
            let fallback = parse_fallback(runtime, &api, &ext, &jar);

            let indexs_raw = int_or(obj, "indexs", 0);
            let indexs = if indexs_raw == 0 { String::new() } else { "1".to_string() };

            let categories: Vec<String> = obj.get("categories")
                .and_then(|v| v.as_array())
                .map(|arr| arr.iter().filter_map(|c| c.as_str().map(|s| s.trim().to_string()))
                    .filter(|s| !s.is_empty()).collect())
                .unwrap_or_default();

            let header: HashMap<String, String> = obj.get("header")
                .and_then(|v| v.as_object())
                .map(|o| o.iter().filter_map(|(k, v)| v.as_str().map(|s| (k.clone(), s.to_string()))).collect())
                .unwrap_or_default();

            Some(TvBoxSiteConfigJson {
                key,
                name,
                type_id,
                api,
                ext,
                jar,
                click: str_or_empty(obj, "click"),
                play_url: str_or_empty(obj, "playUrl"),
                searchable: int_or(obj, "searchable", 1),
                quick_search: int_or(obj, "quickSearch", 1),
                changeable: int_or(obj, "changeable", 0),
                hide: bool_or(obj, "hide", false),
                indexs,
                timeout: int_or(obj, "timeout", 0),
                categories,
                header,
                runtime: runtime.to_string(),
                fallback_kind: fallback.as_ref().map(|f| f.0.clone()).unwrap_or_default(),
                fallback_ext: fallback.as_ref().map(|f| f.1.clone()).unwrap_or_default(),
                fallback_download_url: fallback.as_ref().map(|f| f.2.clone()).unwrap_or_default(),
                fallback_raw: fallback.as_ref().map(|f| f.3.clone()).unwrap_or_default(),
            })
        }).collect())
        .unwrap_or_default();

    let lives: Vec<TvBoxLiveConfigJson> = root_obj.get("lives")
        .and_then(|v| v.as_array())
        .map(|arr| arr.iter().filter_map(|e| {
            let obj = e.as_object()?;
            Some(TvBoxLiveConfigJson {
                name: str_or_empty(obj, "name"),
                url: str_or_empty(obj, "url"),
                type_id: int_or(obj, "type", 0),
                epg: str_or_empty(obj, "epg"),
                logo: str_or_empty(obj, "logo"),
            })
        }).collect())
        .unwrap_or_default();

    let ad_rules: Vec<TvBoxAdRuleConfigJson> = root_obj.get("rules")
        .and_then(|v| v.as_array())
        .map(|arr| arr.iter().filter_map(|e| {
            let obj = e.as_object()?;
            let hosts: Vec<String> = obj.get("hosts")
                .and_then(|v| v.as_array())
                .map(|arr| arr.iter().filter_map(|h| h.as_str().map(|s| s.to_string())).collect())
                .unwrap_or_default();
            let regex: Vec<String> = obj.get("regex")
                .and_then(|v| v.as_array())
                .map(|arr| arr.iter().filter_map(|r| r.as_str().map(|s| s.to_string())).collect())
                .unwrap_or_default();
            Some(TvBoxAdRuleConfigJson {
                name: str_or_empty(obj, "name"),
                hosts,
                regex,
            })
        }).collect())
        .unwrap_or_default();

    let spider = str_val(root_obj, "spider");

    serde_json::to_string(&TvBoxConfigJson { sites, lives, ad_rules, spider }).unwrap_or_default()
}

#[no_mangle]
pub extern "system" fn Java_com_wuming_tv_core_source_config_TvBoxConfigDecoder_nativeDecode(
    mut env: JNIEnv,
    _class: JClass,
    json: JString,
) -> jstring {
    let json_str = jstring_to_string(&mut env, &json);
    let result = native_decode(&json_str);
    crate::return_jstring(&mut env, &result)
}
