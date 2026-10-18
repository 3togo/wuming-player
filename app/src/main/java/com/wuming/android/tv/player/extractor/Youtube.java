package com.wuming.android.tv.player.extractor;

import android.net.Uri;

import com.wuming.android.tv.bean.Episode;
import com.wuming.android.tv.bean.Result;
import com.wuming.android.tv.bean.Vod;
import com.wuming.android.tv.event.RefreshEvent;
import com.wuming.android.tv.impl.NewPipeImpl;
import com.wuming.android.tv.player.Source;
import com.wuming.android.tv.utils.UrlUtil;

import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubePlaylistExtractor;
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubePlaylistLinkHandlerFactory;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import com.wuming.android.tv.utils.AppLog;

public class Youtube implements Source.Extractor {
    private static final String TAG = "Youtube";

    public Youtube() {
        NewPipe.init(NewPipeImpl.get(), Localization.fromLocale(Locale.getDefault()));
    }

    @Override
    public boolean match(Uri uri) {
        String host = UrlUtil.host(uri);
        return host.contains("youtube.com") || host.contains("youtu.be");
    }

    @Override
    public String fetch(String url) throws Exception {
        return fetch(StreamInfo.getInfo(url));
    }

    @Override
    public String fetch(Result result) throws Exception {
        StreamInfo info = StreamInfo.getInfo(result.getUrl().v());
        result.setSubs(YoutubeHelper.getSubtitles(info.getSubtitles()));
        return fetch(info);
    }

    private String fetch(StreamInfo info) throws Exception {
        RefreshEvent.vod(convert(info).trans());
        return getPlayUrl(info);
    }

    private String getPlayUrl(StreamInfo info) throws Exception {
        return isLive(info) ? getLive(info) : YoutubeHelper.getMpd(info);
    }

    private Vod convert(StreamInfo info) {
        try {
            Vod vod = new Vod();
            vod.setName(info.getName());
            vod.setDirector(info.getUploaderName());
            vod.setContent(info.getDescription().getContent());
            vod.setPic(info.getThumbnails().get(info.getThumbnails().size() - 1).getUrl());
            return vod;
        } catch (Exception e) {
            Vod vod = new Vod();
            vod.setName(info.getName());
            vod.setContent(info.getDescription().getContent());
            return vod;
        }
    }

    private boolean isLive(StreamInfo info) {
        return StreamType.LIVE_STREAM.equals(info.getStreamType());
    }

    private String getLive(StreamInfo info) {
        return !info.getHlsUrl().isEmpty() ? info.getHlsUrl() : info.getDashMpdUrl();
    }

    @Override
    public void stop() {
    }

    @Override
    public void exit() {
    }

    public record Parser(String url) implements Callable<List<Episode>> {

        private static final Pattern PATTERN = Pattern.compile("(youtube\\.com|youtu\\.be).*list=");

        public static boolean match(String url) {
            return PATTERN.matcher(url).find();
        }

        public static Parser get(String url) {
            return new Parser(url);
        }

        @Override
        public List<Episode> call() {
            try {
                ListLinkHandler handler = YoutubePlaylistLinkHandlerFactory.getInstance().fromUrl(url);
                YoutubePlaylistExtractor extractor = new YoutubePlaylistExtractor(ServiceList.YouTube, handler);
                extractor.forceLocalization(NewPipe.getPreferredLocalization());
                extractor.fetchPage();
                List<Episode> episodes = new ArrayList<>();
                add(extractor, episodes, extractor.getInitialPage());
                return episodes;
            } catch (Exception e) {
                return Collections.emptyList();
            }
        }

        private void add(YoutubePlaylistExtractor extractor, List<Episode> episodes, ListExtractor.InfoItemsPage<StreamInfoItem> page) {
            for (StreamInfoItem item : page.getItems()) {
                if (item.getDuration() == -1) continue;
                episodes.add(Episode.create(item.getName(), item.getUrl()));
            }
            if (page.hasNextPage()) {
                try {
                    add(extractor, episodes, extractor.getPage(page.getNextPage()));
                } catch (Exception e) {
                    AppLog.e(TAG, e);
                }
            }
        }
    }
}
