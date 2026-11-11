package com.wuming.android.tv.api.parser;

import com.wuming.android.tv.bean.Live;
import com.wuming.android.tv.bean.Channel;
import org.junit.Test;
import static org.junit.Assert.*;

public class LiveParserTest {
    @Test public void literalApostropheInUnquotedLogoDoesNotConsumeTheChannelName() {
        Live live = new Live();
        LiveParser.text(live, "#EXTM3U\n#EXTINF:-1 tvg-logo=https://img.test/o'news.png group-title=News,Channel, HD\nhttps://media.test/live");
        Channel channel = live.getGroups().get(0).getChannel().get(0);
        assertEquals("Channel, HD", channel.getName());
        assertEquals("https://img.test/o'news.png", channel.getLogo());
    }

    @Test public void groupOverrideKeepsCatchupHeadersAndPlayerDirectives() {
        Live live = new Live();
        LiveParser.text(live, "#EXTM3U catchup=\"append\" catchup-source=\"?utc={utc}\"\n"
                + "#EXTINF:-1 group-title=old tvg-chno='7',Protected\n#EXTGRP:News\n"
                + "#EXTVLCOPT:http-user-agent=FixtureAgent\n"
                + "#KODIPROP:inputstream.adaptive.manifest_type=mpd\n"
                + "#KODIPROP:inputstream.adaptive.license_type=com.widevine.alpha\n"
                + "#KODIPROP:inputstream.adaptive.license_key=https://license.test/key\n"
                + "https://media.test/live.mpd|Referer=https://portal.test/\n"
                + "#EXTINF:-1 group-title=News,Plain\nhttps://media.test/plain");
        Channel protectedChannel = live.getGroups().get(0).getChannel().get(0);
        assertEquals("7", protectedChannel.getNumber());
        assertEquals("append", protectedChannel.getCatchup().getType());
        assertEquals("?utc={utc}", protectedChannel.getCatchup().getSource());
        assertEquals("FixtureAgent", protectedChannel.getHeaders().get("User-Agent"));
        assertEquals("https://portal.test/", protectedChannel.getHeaders().get("Referer"));
        assertEquals("application/dash+xml", protectedChannel.getFormat());
        assertNotNull(protectedChannel.getDrm());
        Channel plain = live.getGroups().get(0).getChannel().get(1);
        assertNull(plain.getDrm());
        assertTrue(plain.getHeaders().isEmpty());
    }

    @Test public void sharedM3uGrammarPreservesGroupNameAndRelativeGuide() {
        Live live = new Live();
        live.setUrl("https://playlist.test/config/list.m3u");
        LiveParser.text(live, "#extm3u url-tvg='../guide?ch={name},../other.xml'\n"
                + "#extinf:-1 tvg-id='one' tvg-name='Guide, HD' group-title=old,News, HD\n"
                + "#extgrp:News\nhttps://media.test/live\n"
                + "#EXTINF:-1 tvg-id=two group-title=Other,Other\nhttps://media.test/other");
        assertEquals("https://playlist.test/guide?ch={name},https://playlist.test/other.xml", live.getEpg());
        assertEquals(2, live.getGroups().size());
        assertEquals("News", live.getGroups().get(0).getName());
        Channel channel = live.getGroups().get(0).getChannel().get(0);
        assertEquals("News, HD", channel.getName());
        assertEquals("one", channel.getTvgId());
        assertEquals("Guide, HD", channel.getTvgName());
        assertEquals("two", live.getGroups().get(1).getChannel().get(0).getTvgId());
    }
}
