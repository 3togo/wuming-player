package com.wuming.android.tv.bean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.gson.JsonParser;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class ProviderParsingInstrumentedTest {

    @Test
    public void jsonProviderResponseParsesCategoriesAndVideos() {
        String json = "{\"class\":[{\"type_id\":\"drama\",\"type_name\":\"Drama\"}],"
                + "\"list\":[{\"vod_id\":\"vod-1\",\"vod_name\":\"Example Show\","
                + "\"vod_pic\":\"https://img.example/poster.jpg\",\"vod_remarks\":\"Episode 8\"}]}";

        Result result = Result.fromJson(json);

        assertEquals(1, result.getTypes().size());
        assertEquals("drama", result.getTypes().get(0).getTypeId());
        assertEquals(1, result.getList().size());
        assertEquals("vod-1", result.getList().get(0).getId());
        assertEquals("Example Show", result.getList().get(0).getName());
        assertEquals("Episode 8", result.getList().get(0).getRemarks());
    }

    @Test
    public void xmlProviderResponseParsesCategoriesAndVideos() {
        String xml = "<rss><class><ty id=\"movie\">Movies</ty></class><list><video>"
                + "<id>movie-1</id><name>Example Movie</name><pic>poster.jpg</pic><note>HD</note>"
                + "</video></list></rss>";

        Result result = Result.fromXml(xml);

        assertEquals("movie", result.getTypes().get(0).getTypeId());
        assertEquals("Example Movie", result.getList().get(0).getName());
        assertEquals("HD", result.getList().get(0).getRemarks());
    }

    @Test
    public void malformedProviderResponsesFailToEmptyResults() {
        assertTrue(Result.fromJson("{not-json").getList().isEmpty());
        assertTrue(Result.fromXml("<rss><broken>").getList().isEmpty());
        assertTrue(Result.fromType(1, "null").getList().isEmpty());
    }

    @Test
    public void liveProviderEntryUsesDefaultsAndPreservesHeaders() {
        Live live = Live.objectFrom(JsonParser.parseString(
                "{\"name\":\"News\",\"api\":\"https://live.example/list.m3u\","
                        + "\"header\":{\"User-Agent\":\"TV-Test\"}}"), "spider.jar");

        assertEquals("News", live.getName());
        assertEquals("https://live.example/list.m3u", live.getApi());
        assertEquals("spider.jar", live.getJar());
        assertEquals("TV-Test", live.getHeader().get("User-Agent"));
    }

}
