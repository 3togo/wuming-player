package com.wuming.android.tv.api.parser;

import androidx.media3.common.MimeTypes;

import com.wuming.android.tv.bean.Catchup;
import com.wuming.android.tv.bean.Channel;
import com.wuming.android.tv.bean.ClearKey;
import com.wuming.android.tv.bean.Drm;
import com.wuming.android.tv.bean.Group;
import com.wuming.android.tv.bean.Live;
import com.wuming.android.tv.utils.AppLog;
import com.wuming.android.tv.utils.UrlUtil;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Json;
import com.wuming.tv.core.live.LiveChannelNumbering;
import com.wuming.tv.core.live.LivePlaylistEncoding;
import com.wuming.tv.core.live.LivePlaylistJsonCodec;
import com.wuming.tv.core.live.M3uDirectives;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

public class LiveParser {
    private static final String TAG = "LiveParser";

    private static final Pattern M3U = Pattern.compile("^(?!.*#genre#).*#EXTM3U.*", Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);
    public static void start(Live live) throws Exception {
        if (!live.getGroups().isEmpty()) return;
        String text = LivePlaylistEncoding.stripMark(getText(live));
        if (Json.isArray(text)) json(live, text);
        else text(live, text);
    }

    private static String getText(Live live) throws Exception {
        if (!live.getApi().isEmpty()) return live.spider().liveContent(live.getUrl());
        return OkHttp.string(UrlUtil.convert(live.getUrl()), live.getHeaders());
    }

    public static void text(Live live, String text) {
        if (!live.getGroups().isEmpty()) return;
        text = LivePlaylistEncoding.stripMark(text);
        if (Json.isArray(text)) {
            json(live, text);
            return;
        }
        if (M3U.matcher(text).find()) m3u(live, text);
        else txt(live, text);
        apply(live);
    }

    private static void json(Live live, String text) {
        live.getGroups().addAll(Group.arrayFrom(LivePlaylistJsonCodec.normalize(text)));
        apply(live);
    }

    private static void apply(Live live) {
        int number = 0;
        for (Group group : live.getGroups()) {
            for (Channel channel : group.trans().getChannel()) {
                if (channel.getNumber().isEmpty()) channel.setNumber(LiveChannelNumbering.label(++number));
                channel.trans().live(live);
            }
        }
    }

    private static void m3u(Live live, String text) {
        Setting setting = Setting.create();
        Catchup catchup = Catchup.create();
        Channel channel = Channel.create("");
        String pendingInfo = "";
        String pendingGroup = "";
        text = text.replace("\r\n", "\n").replace("\r", "");
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (Thread.interrupted()) break;
            if (setting.find(line)) {
                setting.check(line);
            } else if (line.regionMatches(true, 0, "#EXTM3U", 0, 7)) {
                Map<String, String> attrs = M3uDirectives.attributes(line);
                catchup.setType(attrs.getOrDefault("catchup", ""));
                catchup.setSource(attrs.getOrDefault("catchup-source", ""));
                catchup.setReplace(attrs.getOrDefault("catchup-replace", ""));
                if (live.getEpg().isEmpty()) {
                    java.util.List<String> guides = M3uDirectives.epgUrls(line, live.getUrl());
                    if (!guides.isEmpty()) live.setEpg(String.join(",", guides));
                }
            } else if (line.regionMatches(true, 0, "#EXTINF:", 0, 8)) {
                pendingInfo = line;
                pendingGroup = M3uDirectives.attributes(line).getOrDefault("group-title", "");
                channel = null;
            } else if (line.regionMatches(true, 0, "#EXTGRP:", 0, 8) && channel == null) {
                pendingGroup = line.substring(8).trim();
            } else if (!line.startsWith("#") && line.contains("://")) {
                if (channel == null) {
                    Map<String, String> attrs = M3uDirectives.attributes(pendingInfo);
                    Group group = live.find(Group.create(pendingGroup, live.isPass()));
                    channel = group.find(Channel.create(M3uDirectives.name(pendingInfo)));
                    channel.setUa(attrs.getOrDefault("http-user-agent", ""));
                    channel.setTvgName(attrs.getOrDefault("tvg-name", ""));
                    channel.setNumber(attrs.getOrDefault("tvg-chno", ""));
                    channel.setLogo(attrs.getOrDefault("tvg-logo", ""));
                    channel.setTvgId(attrs.getOrDefault("tvg-id", ""));
                    Catchup unknown = Catchup.create();
                    unknown.setType(attrs.getOrDefault("catchup", ""));
                    unknown.setSource(attrs.getOrDefault("catchup-source", ""));
                    unknown.setReplace(attrs.getOrDefault("catchup-replace", ""));
                    channel.setCatchup(Catchup.decide(unknown, catchup));
                }
                String[] parts = line.split("\\|", 2);
                if (parts.length > 1) setting.headers(parts[1]);
                String url = parts[0];
                if (url.contains("[") && url.contains("]")) {
                    AppLog.i(TAG, "IPv6 playlist URL extracted");
                }
                channel.getUrls().add(url);
                setting.copy(channel).clear();
            }
        }
    }

    private static void txt(Live live, String text) {
        Setting setting = Setting.create();
        text = text.replace("\r\n", "\n").replace("\r", "");
        for (String line : text.split("\n")) {
            if (Thread.interrupted()) break;
            String[] split = line.split(",", 2);
            if (setting.find(line)) setting.check(line);
            if (line.contains("#genre#")) setting.clear();
            if (line.contains("#genre#")) live.getGroups().add(Group.create(split[0], live.isPass()));
            if (split.length > 1 && live.getGroups().isEmpty()) live.getGroups().add(Group.create());
            if (split.length > 1 && split[1].contains("://")) {
                if (live.getGroups().isEmpty()) live.getGroups().add(Group.create());
                Group group = live.getGroups().get(live.getGroups().size() - 1);
                Channel channel = group.find(Channel.create(split[0]));
                for (String url : split[1].split("#")) {
                    String[] parts = url.split("\\|", 2);
                    if (parts.length > 1) setting.headers(parts[1]);
                    String finalUrl = parts[0];
                    if (finalUrl.contains("[") && finalUrl.contains("]")) {
                        AppLog.i(TAG, "IPv6 text-playlist URL extracted");
                    }
                    channel.getUrls().add(finalUrl);
                    setting.copy(channel);
                }
            }
        }
    }

    private static class Setting {

        private String ua;
        private String key;
        private String type;
        private String click;
        private String format;
        private String origin;
        private String referer;
        private Integer parse;
        private boolean forceKey;
        private Map<String, String> header;
        private Map<String, String> drmHeader;

        private static Setting create() {
            return new Setting();
        }

        public Setting() {
            header = new HashMap<>();
            drmHeader = new HashMap<>();
        }

        private boolean find(String line) {
            return line.startsWith("ua") || line.startsWith("parse") || line.startsWith("click") || line.startsWith("header") || line.startsWith("format") || line.startsWith("origin") || line.startsWith("referer") || line.startsWith("forceKey") || line.startsWith("#EXTHTTP:") || line.startsWith("#EXTVLCOPT:") || line.startsWith("#KODIPROP:");
        }

        private void check(String line) {
            if (line.startsWith("ua")) ua(line);
            else if (line.startsWith("parse")) parse(line);
            else if (line.startsWith("click")) click(line);
            else if (line.startsWith("header")) header(line);
            else if (line.startsWith("format")) format(line);
            else if (line.startsWith("origin")) origin(line);
            else if (line.startsWith("referer")) referer(line);
            else if (line.startsWith("#EXTHTTP:")) header(line);
            else if (line.startsWith("forceKey")) forceKey(line);
            else if (line.startsWith("#EXTVLCOPT:http-origin")) origin(line);
            else if (line.startsWith("#EXTVLCOPT:http-user-agent")) ua(line);
            else if (line.startsWith("#EXTVLCOPT:http-referrer")) referrer(line);
            else if (line.startsWith("#KODIPROP:inputstream.adaptive.license_key")) key(line);
            else if (line.startsWith("#KODIPROP:inputstream.adaptive.license_type")) type(line);
            else if (line.startsWith("#KODIPROP:inputstream.adaptive.drm_legacy")) drmLegacy(line);
            else if (line.startsWith("#KODIPROP:inputstream.adaptive.manifest_type")) format(line);
            else if (line.startsWith("#KODIPROP:inputstream.adaptive.stream_headers")) headers(line);
            else if (line.startsWith("#KODIPROP:inputstream.adaptive.common_headers")) headers(line);
        }

        private Setting copy(Channel channel) {
            if (ua != null) channel.setUa(ua);
            if (parse != null) channel.setParse(parse);
            if (click != null) channel.setClick(click);
            if (format != null) channel.setFormat(format);
            if (origin != null) channel.setOrigin(origin);
            if (referer != null) channel.setReferer(referer);
            if (!header.isEmpty()) channel.setHeader(header);
            if (key != null && type != null) channel.setDrm(Drm.create(key, type, drmHeader, forceKey));
            return this;
        }

        private void ua(String line) {
            try {
                if (line.contains("user-agent=")) ua = line.split("(?i)user-agent=")[1].trim().replace("\"", "");
                if (line.contains("ua=")) ua = line.split("ua=")[1].trim().replace("\"", "");
            } catch (Exception e) {
                AppLog.e(TAG, e);
                ua = null;
            }
        }

        private void referer(String line) {
            referer = extractValue(line, "(?i)referer=");
        }

        // referrer is an alias for referer (VLC-style)
        private void referrer(String line) {
            referer(line);
        }

        private void parse(String line) {
            parse = extractInt(line, "parse=");
        }

        // Helper to extract value after "key=" pattern with consistent error handling
        private String extractValue(String line, String key) {
            try {
                return line.split(key)[1].trim();
            } catch (Exception e) {
                AppLog.e(TAG, e);
                return null;
            }
        }

        // Helper to extract integer value with consistent error handling
        private Integer extractInt(String line, String key) {
            try {
                return Integer.parseInt(line.split(key)[1].trim());
            } catch (Exception e) {
                AppLog.e(TAG, e);
                return null;
            }
        }

        private void click(String line) {
            click = extractValue(line, "click=");
        }

        private void format(String line) {
            try {
                if (line.startsWith("format=")) format = line.split("format=")[1].trim();
                if (line.contains("manifest_type=")) format = line.split("manifest_type=")[1].trim();
                if ("mpd".equals(format) || "dash".equals(format)) format = MimeTypes.APPLICATION_MPD;
                if ("hls".equals(format)) format = MimeTypes.APPLICATION_M3U8;
            } catch (Exception e) {
                AppLog.e(TAG, e);
                format = null;
            }
        }

        private void origin(String line) {
            origin = extractValue(line, "(?i)origin=");
        }

        private void key(String line) {
            try {
                key = line.contains("license_key=") ? line.split("license_key=")[1].trim() : line;
                if (UrlUtil.isHttp(key)) httpKey();
                else localKey();
            } catch (Exception e) {
                AppLog.e(TAG, e);
                key = null;
            }
        }

        private void type(String line) {
            try {
                type = line.contains("license_type=") ? line.split("license_type=")[1].trim() : line;
            } catch (Exception e) {
                AppLog.e(TAG, e);
                type = null;
            }
        }

        private void drmLegacy(String line) {
            try {
                line = line.split("drm_legacy=")[1].trim();
                String[] split = line.split("\\|", 2);
                type(split[0].trim());
                key(split[1].trim());
            } catch (Exception e) {
                AppLog.e(TAG, e);
                type = null;
                key = null;
            }
        }

        private void header(String line) {
            try {
                if (line.contains("#EXTHTTP:")) header.putAll(Json.toMap(Json.parse(line.split("#EXTHTTP:")[1].trim())));
                if (line.contains("header=")) header.putAll(Json.toMap(Json.parse(line.split("header=")[1].trim())));
            } catch (Exception e) {
                AppLog.e(TAG, e);
            }
        }

        private void headers(String line) {
            try {
                if (line.contains("headers=")) headers(header, line.split("headers=")[1].trim().split("&"));
                else if (line.contains("|")) for (String text : line.split("\\|")) headers(text);
                else headers(header, line.trim().split("&"));
            } catch (Exception e) {
                AppLog.e(TAG, e);
            }
        }

        private void drmHeaders(String line) {
            try {
                if (line.contains("|")) for (String text : line.split("\\|")) drmHeaders(text);
                else headers(drmHeader, line.trim().split("&"));
            } catch (Exception e) {
                AppLog.e(TAG, e);
            }
        }

        private void headers(Map<String, String> map, String[] params) {
            for (String param : params) {
                if (!param.contains("=")) continue;
                String[] a = param.split("=", 2);
                String k = a[0].trim().replace("\"", "");
                String v = a[1].trim().replace("\"", "");
                if ("drmScheme".equals(k)) type(v);
                else if ("drmLicense".equals(k)) key(v);
                else map.put(k, v);
            }
        }

        private void forceKey(String line) {
            try {
                forceKey = Boolean.parseBoolean(line.split("forceKey=")[1].trim());
            } catch (Exception e) {
                AppLog.e(TAG, e);
            }
        }

        private void httpKey() {
            String[] parts = key.split("\\|", 2);
            if (parts.length > 1) drmHeaders(parts[1]);
            key = parts[0].trim();
        }

        private void localKey() {
            try {
                ClearKey.objectFrom(key);
            } catch (Exception e) {
                AppLog.w(TAG, "ClearKey parse failed, trying fallback: " + e.getMessage());
                key = ClearKey.get(key.replace("\"", "").replace("{", "").replace("}", "")).toString();
            }
        }

        private void clear() {
            ua = null;
            key = null;
            type = null;
            parse = null;
            click = null;
            format = null;
            origin = null;
            referer = null;
            forceKey = false;
            header = new HashMap<>();
            drmHeader = new HashMap<>();
        }
    }
}
