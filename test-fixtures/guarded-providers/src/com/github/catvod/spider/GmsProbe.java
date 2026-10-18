package com.github.catvod.spider;

import com.github.catvod.crawler.Spider;
import java.util.List;

/** Project-authored fixture. Reflects on GMS; contains no Google implementation. */
public final class GmsProbe extends Spider {
    private String resolvedClass() throws Exception {
        return Class.forName("com.google.android.gms.common.api.Status", false,
                getClass().getClassLoader()).getName();
    }

    @Override public String detailContent(List<String> ids) throws Exception {
        String cls = resolvedClass();
        return "{\"class\":\"com.github.catvod.spider.GmsProbe\",\"list\":[{\"vod_id\":\"probe-id\","
                + "\"vod_name\":\"GmsProbe-" + cls + "\",\"vod_play_from\":\"gms\","
                + "\"vod_play_url\":\"GmsProbe$https://gms.example.com/play?cls=" + cls + "\"}]}";
    }

    @Override public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        return "{\"url\":\"https://gms.example.com/resolved?cls=" + resolvedClass() + "\"}";
    }
}
