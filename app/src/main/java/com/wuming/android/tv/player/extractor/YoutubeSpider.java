package com.wuming.android.tv.player.extractor;

import android.content.Context;

import com.wuming.android.tv.bean.Sub;
import com.wuming.android.tv.impl.NewPipeImpl;
import com.github.catvod.crawler.Spider;

import org.json.JSONArray;
import org.json.JSONObject;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.kiosk.KioskInfo;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.search.SearchInfo;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.SubtitlesStream;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public class YoutubeSpider extends Spider {

    private static final String CATEGORY_TRENDING = "trending";
    private static final String CATEGORY_MUSIC = "music";
    private static final String CATEGORY_GAMING = "gaming";
    private static final String CATEGORY_MOVIES = "movies";

    @Override
    public void init(Context context, String extend) throws Exception {
        NewPipe.init(NewPipeImpl.get(), Localization.fromLocale(Locale.getDefault()));
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        JSONObject result = new JSONObject();
        JSONArray classes = new JSONArray();
        classes.put(new JSONObject().put("type_id", CATEGORY_TRENDING).put("type_name", "Trending"));
        classes.put(new JSONObject().put("type_id", CATEGORY_MUSIC).put("type_name", "Music"));
        classes.put(new JSONObject().put("type_id", CATEGORY_GAMING).put("type_name", "Gaming"));
        classes.put(new JSONObject().put("type_id", CATEGORY_MOVIES).put("type_name", "Movies"));
        result.put("class", classes);
        return result.toString();
    }

    @Override
    public String homeVideoContent() throws Exception {
        return getTrendingContent(CATEGORY_TRENDING, "1");
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        return getTrendingContent(tid, pg);
    }

    private String getTrendingContent(String tid, String pg) throws Exception {
        StreamingService service = ServiceList.YouTube;
        String kioskId = getKioskId(tid);
        KioskInfo info = KioskInfo.getInfo(service, kioskId);
        List<StreamInfoItem> items = info.getRelatedItems().stream()
                .filter(item -> item instanceof StreamInfoItem)
                .map(item -> (StreamInfoItem) item)
                .collect(Collectors.toList());
        return toVodList(items, pg);
    }

    private String getKioskId(String tid) {
        switch (tid) {
            case CATEGORY_MUSIC:
                return "Trending - Music";
            case CATEGORY_GAMING:
                return "Trending - Gaming";
            case CATEGORY_MOVIES:
                return "Trending - Movies";
            default:
                return "Trending";
        }
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        return searchContent(key, quick, "1");
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) throws Exception {
        StreamingService service = ServiceList.YouTube;
        SearchInfo info = SearchInfo.getInfo(service, service.getSearchQHFactory().fromQuery(key));
        List<StreamInfoItem> items = info.getRelatedItems().stream()
                .filter(item -> item instanceof StreamInfoItem)
                .map(item -> (StreamInfoItem) item)
                .collect(Collectors.toList());
        return toVodList(items, pg);
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String url = ids.get(0);
        StreamInfo info = StreamInfo.getInfo(url);

        JSONObject result = new JSONObject();
        JSONArray list = new JSONArray();
        JSONObject vod = new JSONObject();
        vod.put("vod_id", url);
        vod.put("vod_name", info.getName());
        vod.put("vod_pic", getThumbnail(info));
        vod.put("vod_content", info.getDescription().getContent());
        vod.put("vod_director", info.getUploaderName());
        vod.put("vod_play_from", "YouTube");
        vod.put("vod_play_url", url);
        list.put(vod);
        result.put("list", list);
        return result.toString();
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        StreamInfo info = StreamInfo.getInfo(id);
        JSONObject result = new JSONObject();
        result.put("parse", 0);
        result.put("playUrl", "");

        if (StreamType.LIVE_STREAM.equals(info.getStreamType())) {
            String playUrl = !info.getHlsUrl().isEmpty() ? info.getHlsUrl() : info.getDashMpdUrl();
            result.put("url", playUrl);
        } else {
            result.put("url", YoutubeHelper.getMpd(info));
        }

        List<SubtitlesStream> subtitles = info.getSubtitles();
        if (subtitles != null && !subtitles.isEmpty()) {
            JSONArray subs = new JSONArray();
            for (Sub sub : YoutubeHelper.getSubtitles(subtitles)) {
                JSONObject s = new JSONObject();
                s.put("url", sub.getUrl());
                s.put("name", sub.getName());
                s.put("lang", sub.getLang());
                s.put("format", sub.getFormat());
                subs.put(s);
            }
            result.put("subs", subs);
        }

        return result.toString();
    }

    // --- Vod list conversion ---

    private String toVodList(List<StreamInfoItem> items, String pg) throws Exception {
        JSONObject result = new JSONObject();
        JSONArray list = new JSONArray();
        if (items != null) {
            for (StreamInfoItem item : items) {
                if (item.getDuration() < 0) continue;
                JSONObject vod = new JSONObject();
                vod.put("vod_id", item.getUrl());
                vod.put("vod_name", item.getName());
                vod.put("vod_pic", getThumbnail(item));
                vod.put("vod_remarks", formatDuration(item.getDuration()));
                list.put(vod);
            }
        }
        result.put("list", list);
        result.put("pagecount", 1);
        return result.toString();
    }

    private String getThumbnail(StreamInfo info) {
        List<org.schabi.newpipe.extractor.Image> thumbnails = info.getThumbnails();
        return thumbnails.isEmpty() ? "" : thumbnails.get(thumbnails.size() - 1).getUrl();
    }

    private String getThumbnail(StreamInfoItem item) {
        List<org.schabi.newpipe.extractor.Image> thumbnails = item.getThumbnails();
        return thumbnails.isEmpty() ? "" : thumbnails.get(thumbnails.size() - 1).getUrl();
    }

    private String formatDuration(long seconds) {
        if (seconds <= 0) return "";
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        if (h > 0) return String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s);
        return String.format(Locale.getDefault(), "%d:%02d", m, s);
    }
}
