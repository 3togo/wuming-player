use crate::jstring_to_string;
use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use serde::{Deserialize, Serialize};

type LinkedMap<K, V> = std::collections::HashMap<K, V>;

#[derive(Serialize, Deserialize)]
struct MacCmsXmlResult {
    items: Vec<MacCmsItemJson>,
    types: Vec<MacCmsTypeJson>,
    page: i32,
    page_count: i32,
    total: i32,
    detail: Option<MacCmsDetailJson>,
}

#[derive(Serialize, Deserialize)]
struct MacCmsItemJson {
    id: String,
    name: String,
    pic: String,
    note: String,
    year: String,
}

#[derive(Serialize, Deserialize)]
struct MacCmsTypeJson {
    id: String,
    name: String,
}

#[derive(Serialize, Deserialize)]
struct MacCmsDetailJson {
    id: String,
    name: String,
    pic: String,
    note: String,
    year: String,
    des: String,
    actor: String,
    director: String,
    area: String,
    lines: Vec<MacCmsLineJson>,
}

#[derive(Serialize, Deserialize)]
struct MacCmsLineJson {
    flag: String,
    name: String,
    episodes: Vec<MacCmsEpisodeJson>,
}

#[derive(Serialize, Deserialize)]
struct MacCmsEpisodeJson {
    name: String,
    url: String,
}

fn parse_maccms_xml(payload: &str) -> String {
    use quick_xml::events::Event;
    use quick_xml::Reader;

    let mut reader = Reader::from_str(payload);
    reader.config_mut().trim_text(true);

    let mut items: Vec<MacCmsItemJson> = Vec::new();
    let mut types: Vec<MacCmsTypeJson> = Vec::new();
    let mut page: i32 = 0;
    let mut page_count: i32 = 0;
    let mut total: i32 = 0;
    let mut detail: Option<MacCmsDetailJson> = None;

    let mut current_video: Option<MacCmsItemJson> = None;
    let mut current_video_fields: LinkedMap<String, String> = LinkedMap::new();
    let mut current_text = String::new();
    let mut in_class = false;
    let mut in_list = false;
    let mut in_video = false;
    let mut in_dd = false;
    let mut dd_flag = String::new();
    let mut dd_content = String::new();
    let mut dd_index = 0;
    let mut detail_lines: Vec<MacCmsLineJson> = Vec::new();
    let detail_fields: LinkedMap<String, String> = LinkedMap::new();

    let mut buf = Vec::new();

    loop {
        match reader.read_event_into(&mut buf) {
            Ok(Event::Start(e)) => {
                let name = String::from_utf8_lossy(e.name().as_ref()).to_string();
                current_text.clear();

                match name.as_str() {
                    "list" => { in_list = true; }
                    "class" => { in_class = true; }
                    "video" => {
                        in_video = true;
                        current_video_fields.clear();
                        current_video = Some(MacCmsItemJson {
                            id: String::new(), name: String::new(),
                            pic: String::new(), note: String::new(), year: String::new(),
                        });
                    }
                    "dd" => {
                        in_dd = true;
                        dd_content.clear();
                        dd_flag = e.attributes().find_map(|a| {
                            a.ok().and_then(|a| {
                                if a.key.as_ref() == b"flag" {
                                    Some(String::from_utf8_lossy(&a.value).to_string())
                                } else { None }
                            })
                        }).unwrap_or_else(|| format!("line-{}", dd_index + 1));
                    }
                    "ty" => {
                        if in_class {
                            let id = e.attributes().find_map(|a| {
                                a.ok().and_then(|a| {
                                    if a.key.as_ref() == b"id" {
                                        Some(String::from_utf8_lossy(&a.value).to_string())
                                    } else { None }
                                })
                            }).unwrap_or_default();
                            if !id.is_empty() {
                                types.push(MacCmsTypeJson { id: id.clone(), name: id });
                            }
                        }
                    }
                    _ => {}
                }
            }
            Ok(Event::Text(e)) => {
                let text = e.unescape().map(|s| s.to_string()).unwrap_or_default();
                if in_dd {
                    dd_content.push_str(&text);
                } else if in_video {
                    current_text.push_str(&text);
                }
            }
            Ok(Event::End(e)) => {
                let name = String::from_utf8_lossy(e.name().as_ref()).to_string();

                match name.as_str() {
                    "list" => { in_list = false; }
                    "class" => { in_class = false; }
                    "page" => { if in_list { page = current_text.trim().parse().unwrap_or(0); } }
                    "pagecount" => { if in_list { page_count = current_text.trim().parse().unwrap_or(0); } }
                    "recordcount" => { if in_list { total = current_text.trim().parse().unwrap_or(0); } }
                    "ty" => {
                        if in_class && !types.is_empty() {
                            let text = current_text.trim();
                            if !text.is_empty() {
                                types.last_mut().map(|t| t.name = text.to_string());
                            }
                        }
                    }
                    "id" | "name" | "pic" | "note" | "year" | "des" | "actor" | "director" | "area" => {
                        if in_video {
                            current_video_fields.insert(name.clone(), current_text.trim().to_string());
                        }
                    }
                    "dd" => {
                        in_dd = false;
                        dd_index += 1;
                        let episodes = parse_dd_episodes(&dd_content);
                        if !episodes.is_empty() {
                            detail_lines.push(MacCmsLineJson {
                                flag: dd_flag.clone(),
                                name: dd_flag.clone(),
                                episodes,
                            });
                        }
                    }
                    "video" => {
                        in_video = false;
                        if let Some(mut item) = current_video.take() {
                            item.id = current_video_fields.get("id").cloned().unwrap_or_default();
                            if !item.id.is_empty() {
                                item.name = current_video_fields.get("name").cloned()
                                    .unwrap_or_else(|| item.id.clone());
                                item.pic = current_video_fields.get("pic").cloned().unwrap_or_default();
                                item.note = current_video_fields.get("note").cloned().unwrap_or_default();
                                item.year = current_video_fields.get("year").cloned().unwrap_or_default();
                                items.push(item);
                            }
                        }
                    }
                    _ => {}
                }
            }
            Ok(Event::Eof) => break,
            Err(_) => break,
            _ => {}
        }
        buf.clear();
    }

    if !detail_fields.is_empty() {
        let id = detail_fields.get("id").cloned().unwrap_or_default();
        if !id.is_empty() {
            detail = Some(MacCmsDetailJson {
                id: id.clone(),
                name: detail_fields.get("name").cloned().unwrap_or_else(|| id),
                pic: detail_fields.get("pic").cloned().unwrap_or_default(),
                note: detail_fields.get("note").cloned().unwrap_or_default(),
                year: detail_fields.get("year").cloned().unwrap_or_default(),
                des: detail_fields.get("des").cloned().unwrap_or_default(),
                actor: detail_fields.get("actor").cloned().unwrap_or_default(),
                director: detail_fields.get("director").cloned().unwrap_or_default(),
                area: detail_fields.get("area").cloned().unwrap_or_default(),
                lines: detail_lines,
            });
        }
    }

    let result = MacCmsXmlResult { items, types, page, page_count, total, detail };
    serde_json::to_string(&result).unwrap_or_default()
}

fn parse_dd_episodes(content: &str) -> Vec<MacCmsEpisodeJson> {
    content.split('#').enumerate().filter_map(|(i, token)| {
        let value = token.trim();
        if value.is_empty() { return None; }
        let number = format!("{:02}", i + 1);
        match value.find('$') {
            None => Some(MacCmsEpisodeJson { name: number, url: value.to_string() }),
            Some(pos) if pos == value.len() - 1 => {
                Some(MacCmsEpisodeJson { name: number, url: value.to_string() })
            }
            Some(pos) => {
                let name = value[..pos].trim();
                let url = value[pos + 1..].trim();
                Some(MacCmsEpisodeJson {
                    name: if name.is_empty() { number } else { name.to_string() },
                    url: url.to_string(),
                })
            }
        }
    }).collect()
}

#[no_mangle]
pub extern "system" fn Java_com_wuming_tv_core_maccms_MacCmsPayloadParser_nativeParseXml(
    mut env: JNIEnv,
    _class: JClass,
    payload: JString,
) -> jstring {
    let payload_str = jstring_to_string(&mut env, &payload);
    let result = parse_maccms_xml(&payload_str);
    crate::return_jstring(&mut env, &result)
}
