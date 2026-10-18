use crate::jstring_to_string;
use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use regex::Regex;

const CJK: &str = "\u{4e00}-\u{9fff}";

fn size_prefix_re() -> Regex {
    Regex::new(r"(?i)^[\[\(（【]?\s*[\d.]+\s*(?:KB|MB|GB|TB)[\]\)）】]?\s*[.\-_]?\s*").unwrap()
}

fn empty_book_re() -> Regex {
    Regex::new(r"《\s*》").unwrap()
}

fn group_tail_re() -> Regex {
    Regex::new(&format!("-[\\w@${CJK}．]{{2,}}\\s*$")).unwrap()
}

fn video_extension_re() -> Regex {
    Regex::new(r"(?i)\.(?:mp4|m4v|mkv|webm|mov|avi|flv|ts|m2ts|wmv|iso|rmvb|mpg|mpeg)\s*$").unwrap()
}

fn copy_paren_re() -> Regex {
    Regex::new(r"\s*[\(（]\d{1,3}[\)）]\s*$").unwrap()
}

fn copy_underscore_re() -> Regex {
    Regex::new(r"_\d+$").unwrap()
}

fn spam_prefix_re() -> Regex {
    Regex::new(&format!("^[A-Za-z](?=[{}])", CJK)).unwrap()
}

fn hash_id_re() -> Regex {
    Regex::new(r"(?i)\b[A-Za-z]{1,4}-\d{2,6}-[a-f0-9]{4,}\b").unwrap()
}

fn id_field_re() -> Regex {
    Regex::new(r"(?i)\bID\s*[:：]\s*\d+").unwrap()
}

fn year_bracket_re() -> Regex {
    Regex::new(r"[\(（【]\s*(?:19|20)\d{2}\s*[\)）】]").unwrap()
}

fn year_plain_re() -> Regex {
    Regex::new(r"(?<!\d)(?:19|20)\d{2}(?!\d)").unwrap()
}

fn genre_re() -> Regex {
    Regex::new("惊悚片|恐怖片|剧情片|科幻片|动作片|喜剧片|爱情片|战争片|纪录片|动画片|犯罪片|悬疑片|奇幻片|冒险片").unwrap()
}

fn region_re() -> Regex {
    Regex::new("中国大陆|内地|美国|英国|法国|德国|日本|韩国|印度|泰国|澳大利亚|加拿大|土耳其|西班牙|意大利|俄罗斯").unwrap()
}

fn separators_re() -> Regex {
    Regex::new(r"[.\[\]【】《》「」·（）()]+").unwrap()
}

fn whitespace_re() -> Regex {
    Regex::new(r"\s+").unwrap()
}

fn edge_junk_re() -> Regex {
    Regex::new(r"^[-_.,，、|\s]+|[-_.,，、|\s]+$").unwrap()
}

fn season_episode_marker_re() -> Regex {
    Regex::new(r"(?i)S\d+E\d+").unwrap()
}

fn episode_marker_re() -> Regex {
    Regex::new(r"(?i)(EP?\s*\d{1,4}|第\s*\d{1,4}\s*[集话期]|(?<=^|\s)\d{1,2})\s*$").unwrap()
}

fn glued_resolution_re() -> Regex {
    Regex::new(&format!(r"(?i)(?<=[a-z0-9{}])(?:1080|2160|720)p", CJK)).unwrap()
}

fn glued_4k_re() -> Regex {
    Regex::new(&format!(r"(?i)(?<=[a-z0-9{}])4k", CJK)).unwrap()
}

fn cn_variant_re() -> Regex {
    Regex::new("官方.{0,2}中字|人工.{0,2}中字").unwrap()
}

fn compile_boundary(regexes: &[&str]) -> Vec<Regex> {
    regexes.iter().map(|r| {
        Regex::new(&format!("(?i)(?<![A-Za-z0-9]){}(?![A-Za-z0-9])", r)).unwrap()
    }).collect()
}

fn tech_patterns() -> Vec<Regex> {
    compile_boundary(&[
        "2160p", "1080[pi]", "720p", "4k", "uhd",
        "blu-ray", "bluray", "bd1080p?", "hd1080p?", "web-dl", "web-?leaked",
        "dvdrip", "remux", "itunes", "viutv", "hkg", "hdtv", "bd",
        "x26[45]", "h.?26[45]", "avc", "hevc", "10bit", "dv",
        "aac2?\\.?0?", "ac3", "2ac3", "ddp\\d?(\\.5)?\\.?1?", "truehd\\d?(\\.\\d)?",
        "dts-?hd(\\.ma)?", "atmos", "flac",
        "chs", "cht", "che", "eng", "big5", "rus", "mandarinenglish", "mandarincantonese",
        "mandarin", "cantonese", "japanese", "english",
        "hc", "web", "bde4", "hkfact", "mp4ba", "movieshaw", "52zygx",
        "ffans", "bucrt", "dreamhd", "fgt", "rarbg",
        "amzn", "viu", "jkyy", "3audios", "[57]\\.1",
    ])
}

const CN_TAGS: &[&str] = &[
    "微博影音臻享", "特效中英字幕", "粤语中文字幕", "内封简繁字幕", "国英双语", "国粤双语",
    "中日双语", "国英双字", "繁体中字", "粤语繁中", "中英字幕", "中英双字",
    "简繁字幕", "粤语中字", "日语中字", "法语中字", "韩语中字", "英语中字",
    "中文字幕", "双语字幕", "无字幕", "完整版资源", "加长版资源",
    "无水印", "高码率", "内封", "双语", "简繁", "字幕", "中字", "双字",
    "繁中", "中英", "微博", "臻享", "德影",
];

fn episode_clean(original: &str) -> String {
    let mut s = size_prefix_re().replace(original, "").to_string();
    s = copy_paren_re().replace(&s, "").to_string();
    s = copy_underscore_re().replace(&s, "").to_string();
    s = video_extension_re().replace(&s, "").to_string();
    s = spam_prefix_re().replace(&s, "").to_string();
    s = hash_id_re().replace_all(&s, "").to_string();
    s = id_field_re().replace_all(&s, "").to_string();
    if s.contains('…') { s = s.replace('…', " "); }
    s = group_tail_re().replace(&s, "").to_string();
    s = empty_book_re().replace_all(&s, "").to_string();

    let tech = tech_patterns();
    let mut changed = true;
    let mut iterations = 0;
    while changed && iterations < 4 {
        changed = false;
        for token in &tech {
            let replaced = token.replace_all(&s, " ").to_string();
            if replaced != s { s = replaced; changed = true; }
        }
        iterations += 1;
    }

    s = glued_resolution_re().replace_all(&s, "").to_string();
    s = glued_4k_re().replace_all(&s, "").to_string();
    s = year_bracket_re().replace_all(&s, " ").to_string();
    s = genre_re().replace_all(&s, " ").to_string();
    s = region_re().replace_all(&s, " ").to_string();
    s = year_plain_re().replace_all(&s, " ").to_string();
    s = cn_variant_re().replace_all(&s, " ").to_string();

    for tag in CN_TAGS {
        if s.contains(tag) { s = s.replace(tag, " "); }
    }

    s = separators_re().replace_all(&s, " ").to_string();
    s = whitespace_re().replace_all(&s, " ").to_string();
    s = edge_junk_re().replace_all(&s, "").trim().to_string();
    s
}

fn episode_format(value: &str) -> String {
    let original = value.trim();
    if original.is_empty() { return String::new(); }
    if let Some(m) = season_episode_marker_re().find(original) {
        return m.as_str().to_uppercase();
    }
    let cleaned = episode_clean(original);
    if let Some(marker) = extract_episode_marker(&cleaned) {
        return marker;
    }
    if cleaned.is_empty() { "正片".to_string() } else { cleaned }
}

fn extract_episode_marker(cleaned: &str) -> Option<String> {
    if cleaned.is_empty() { return None; }
    let re = episode_marker_re();
    let m = re.find(cleaned)?;
    let marker = m.as_str().trim();
    let prefix = cleaned[..m.start()].trim();

    if marker.len() <= 2 && marker.chars().all(|c| c.is_ascii_digit()) {
        if !prefix.is_empty() && contains_latin(prefix) { return None; }
        return Some(format!("第{}集", marker));
    }
    Some(marker.replace(|c: char| c.is_whitespace(), "").to_uppercase())
}

fn contains_latin(s: &str) -> bool {
    s.chars().any(|c| c.is_ascii_alphabetic())
}

fn vod_format(value: &str) -> String {
    let original = value.trim();
    if original.is_empty() { return String::new(); }

    let mut s = size_prefix_re().replace(original, "").to_string();
    s = empty_book_re().replace_all(&s, "").to_string();

    let has_brackets = s.chars().any(|c| matches!(c, '[' | '【' | '(' | '（' | ']' | '】' | ')' | '）'));
    if has_brackets {
        s = bracket_tech_replace(&s);
        s = bracket_region_re().replace_all(&s, " ").to_string();
        s = bracket_genre_re().replace_all(&s, " ").to_string();
    }

    let tech = vod_tech_patterns();
    let mut changed = true;
    let mut iterations = 0;
    while changed && iterations < 4 {
        changed = false;
        for p in &tech {
            let r = p.replace_all(&s, " ").to_string();
            if r != s { s = r; changed = true; }
        }
        iterations += 1;
    }

    s = vod_year_re().replace_all(&s, " ").to_string();
    s = group_tail_re().replace(&s, "").to_string();
    s = vod_brackets_re().replace_all(&s, " ").to_string();
    s = vod_sep_re().replace_all(&s, " ").to_string();
    s = latin_tail_re().replace_all(&s, "").to_string();
    s = whitespace_re().replace_all(&s, " ").trim().to_string();

    if s.is_empty() { original.to_string() } else { s }
}

fn vod_tech_patterns() -> Vec<Regex> {
    let buckets: &[&[&str]] = &[
        &["1080p", "2160p", "720p"],
        &["蓝光原盘", "蓝光1080P", "HD1080P", "hdr10\\+?"],
        &[
            "4K画质", "4K修复", "web-?dl", "web-?rip", "dvdrip?", "Blu-?Ray", "bd1080p?", "hd1080p?", "TrueHD",
            "dts-?hd(\\.ma)?", "全景声", "60fps", "60帧", "加长版资源", "完整版资源",
            "内封简繁字幕", "简繁字幕", "特效中英字幕", "中英字幕", "中英双字",
            "中文字幕", "粤语中字", "双语字幕", "无字幕", "内封字幕?",
            "日语中字", "法语中字", "韩语中字", "英语中字", "官方中字", "人工中字", "字幕组",
            "x26[45]", "h.?26[45]", "aac2?\\.?0?", "ddp\\d?(\\.5)?\\.?1?",
        ],
        &[
            "4K", "UHD", "HD", "BDRip", "BLURAY", "BD", "HDR", "SDR", "DV", "杜比视界", "WEB-DL", "WEBRip",
            "蓝光", "DVD", "iMAX", "DTS", "Atmos", "国配", "台配", "公映版", "高码率", "无水印",
            "10bit", "avc", "hevc", "ac3", "flac", "内封简繁", "无删减", "HDR10", "REMUX",
            "中字", "字幕", "简繁", "典藏版", "杜比",
            "bilibili", "bde4", "imax", "chs", "cht", "che", "eng", "hc", "hdrip", "hdtv",
        ],
    ];
    let bracket_only: &[&str] = &["加长", "完整", "繁中", "中英", "双字", "导演版", "导演剪辑版"];

    let mut flat: Vec<&str> = Vec::new();
    for bucket in buckets { flat.extend(*bucket); }
    flat.extend(bracket_only);

    compile_boundary(&flat)
}

fn bracket_tech_replace(s: &str) -> String {
    let tokens: Vec<&str> = {
        let buckets: &[&[&str]] = &[
            &["1080p", "2160p", "720p"],
            &["蓝光原盘", "蓝光1080P", "HD1080P", "hdr10\\+?"],
            &[
                "4K画质", "4K修复", "web-?dl", "web-?rip", "dvdrip?", "Blu-?Ray", "bd1080p?", "hd1080p?", "TrueHD",
                "dts-?hd(\\.ma)?", "全景声", "60fps", "60帧", "加长版资源", "完整版资源",
                "内封简繁字幕", "简繁字幕", "特效中英字幕", "中英字幕", "中英双字",
                "中文字幕", "粤语中字", "双语字幕", "无字幕", "内封字幕?",
                "日语中字", "法语中字", "韩语中字", "英语中字", "官方中字", "人工中字", "字幕组",
                "x26[45]", "h.?26[45]", "aac2?\\.?0?", "ddp\\d?(\\.5)?\\.?1?",
            ],
            &[
                "4K", "UHD", "HD", "BDRip", "BLURAY", "BD", "HDR", "SDR", "DV", "杜比视界", "WEB-DL", "WEBRip",
                "蓝光", "DVD", "iMAX", "DTS", "Atmos", "国配", "台配", "公映版", "高码率", "无水印",
                "10bit", "avc", "hevc", "ac3", "flac", "内封简繁", "无删减", "HDR10", "REMUX",
                "中字", "字幕", "简繁", "典藏版", "杜比",
                "bilibili", "bde4", "imax", "chs", "cht", "che", "eng", "hc", "hdrip", "hdtv",
            ],
        ];
        let bracket_only: &[&str] = &["加长", "完整", "繁中", "中英", "双字", "导演版", "导演剪辑版"];
        let mut flat: Vec<&str> = Vec::new();
        for bucket in buckets { flat.extend(*bucket); }
        flat.extend(bracket_only);
        flat
    };

    let alt: String = tokens.join("|");
    let re_str = format!(r"(?i)[\[【（\(]\s*?(?:(?:{})\s*?){{1,12}}[\]】）\)]", alt);
    let re = Regex::new(&re_str).unwrap();
    re.replace_all(s, " ").to_string()
}

fn bracket_region_re() -> Regex {
    Regex::new(r"[\[【（\(]\s*?(中国大陆|内地|港台|日本|韩国|欧美|美国|英国|法国|德国|土耳其|印度|泰国|西班牙|意大利|加拿大|俄罗斯|澳大利亚|日本动漫|韩剧|美剧|港剧|台剧|泰剧|日剧|国产)\s*?[\]】）\)]").unwrap()
}

fn bracket_genre_re() -> Regex {
    Regex::new(r"[\[【（\(]\s*?(动作|喜剧|爱情|剧情|科幻|悬疑|犯罪|动画|奇幻|冒险|战争|恐怖|惊悚|纪录|家庭|音乐|歌舞|传记|历史|古装|武侠|灾难|西部|同性|运动|短片|真人秀|脱口秀|剧情片|动作片|喜剧片|科幻片|恐怖片|惊悚片|动画片|纪录片)\s*?片?\s*?[\]】）\)]").unwrap()
}

fn vod_year_re() -> Regex {
    Regex::new(r"(?<!\d)(19|20)\d{2}(?!\d)").unwrap()
}

fn vod_sep_re() -> Regex {
    Regex::new(r"[.·]+").unwrap()
}

fn vod_brackets_re() -> Regex {
    Regex::new(r"[\[\]【】（）()]+").unwrap()
}

fn latin_tail_re() -> Regex {
    Regex::new(&format!(r"(?<=[{}])\s+[A-Za-z][A-Za-z0-9\s]*$", CJK)).unwrap()
}

#[no_mangle]
pub extern "system" fn Java_com_wuming_tv_core_catalog_EpisodeDisplayName_nativeFormat(
    mut env: JNIEnv,
    _class: JClass,
    value: JString,
) -> jstring {
    let value_str = jstring_to_string(&mut env, &value);
    let result = episode_format(&value_str);
    crate::return_jstring(&mut env, &result)
}

#[no_mangle]
pub extern "system" fn Java_com_wuming_tv_core_catalog_VodDisplayName_nativeFormat(
    mut env: JNIEnv,
    _class: JClass,
    value: JString,
) -> jstring {
    let value_str = jstring_to_string(&mut env, &value);
    let result = vod_format(&value_str);
    crate::return_jstring(&mut env, &result)
}
