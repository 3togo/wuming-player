package com.wuming.android.tv.api.parser;

import com.wuming.android.tv.bean.Channel;
import com.wuming.android.tv.bean.Epg;
import com.wuming.android.tv.bean.Group;
import com.wuming.android.tv.bean.Live;
import com.wuming.android.tv.bean.Tv;
import org.simpleframework.xml.core.Persister;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import static org.junit.Assert.*;

public class EpgStreamingImportTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private Live live() {
        Live live = new Live();
        Channel channel = new Channel();
        channel.setTvgId("local");
        channel.setName("News");
        Group group = new Group("test");
        group.getChannel().add(channel);
        live.getGroups().add(group);
        return live;
    }
    private Channel channel(Live live) { return live.getGroups().get(0).getChannel().get(0); }
    private File xml(String xml) throws Exception {
        File file = temp.newFile();
        Files.writeString(file.toPath(), xml);
        return file;
    }
    private String programme(String id, String title) {
        return "<programme channel='" + id + "' start='20260911120000 +0800' stop='20260911133000 +08:00'><title>" + title + "</title></programme>";
    }

    @Test public void resolvesLateMetadataAliasesAndLogosAndPreservesTimes() throws Exception {
        String input = "<tv>" + programme("xml", " A &amp; B ") + programme("irrelevant", "skip")
                + "<channel id='xml'><display-name>News</display-name><icon src='logo'/></channel></tv>";
        Live live = live();
        EpgParser.readXml(live, xml(input));
        var entry = channel(live).getDataList().get(0).getList().get(0);
        assertEquals(new Persister().read(Tv.class, input, false).getProgramme().get(0).getTitle(), entry.getTitle());
        assertEquals(Instant.parse("2026-09-11T04:00:00Z").toEpochMilli(), entry.getStartTime());
        assertEquals(Instant.parse("2026-09-11T05:30:00Z").toEpochMilli(), entry.getEndTime());
        assertEquals("logo", channel(live).getLogo());
        assertEquals(1, channel(live).getDataList().get(0).getList().size());
    }

    @Test public void gzipReadsDirectlyAndKeepsExistingLogo() throws Exception {
        File file = temp.newFile("guide.gz");
        try (GZIPOutputStream out = new GZIPOutputStream(new FileOutputStream(file))) {
            out.write(("<tv>" + programme("local", "Title") + "</tv>").getBytes(StandardCharsets.UTF_8));
        }
        Live live = live();
        channel(live).setLogo("existing");
        EpgParser.readXml(live, file);
        assertEquals("Title", channel(live).getDataList().get(0).getList().get(0).getTitle());
        assertEquals("existing", channel(live).getLogo());
        assertEquals(1, temp.getRoot().list().length);
    }

    @Test public void lateRetainedTextFailureDoesNotPublishPartialGuide() throws Exception {
        Live live = live();
        Epg original = Epg.create("local", "2026-09-10");
        channel(live).setDataList(List.of(original));
        File file = xml("<tv>" + programme("local", "valid") + programme("local", "x".repeat(65537)) + "</tv>");
        assertThrows(Exception.class, () -> EpgParser.readXml(live, file));
        assertSame(original, channel(live).getDataList().get(0));
        assertEquals(1, channel(live).getDataList().size());
    }

    @Test public void keepsHistoricalDatesAndDirectIdsWithoutMetadata() throws Exception {
        Live live = live();
        EpgParser.readXml(live, xml("<tv>" + programme("local", "Old").replace("20260911", "20250801")
                + programme("local", "Current") + "</tv>"));
        assertEquals(2, channel(live).getDataList().size());
        assertEquals(2, channel(live).getDataList().stream().mapToInt(e -> e.getList().size()).sum());
    }

    @Test public void truncatedGzipDoesNotPublish() throws Exception {
        File file = temp.newFile();
        try (GZIPOutputStream out = new GZIPOutputStream(new FileOutputStream(file))) {
            out.write(("<tv>" + programme("local", "Title") + "</tv>").getBytes(StandardCharsets.UTF_8));
        }
        byte[] bytes = Files.readAllBytes(file.toPath());
        Files.write(file.toPath(), java.util.Arrays.copyOf(bytes, bytes.length - 5));
        Live live = live();
        assertThrows(Exception.class, () -> EpgParser.readXml(live, file));
        assertTrue(channel(live).getDataList().isEmpty());
    }

    @Test public void largeMostlyUnrelatedFixtureMatchesLegacySelection() throws Exception {
        StringBuilder input = new StringBuilder("<tv>");
        for (int i = 0; i < 30000; i++) input.append(programme(i % 100 == 0 ? "local" : "other", "Title " + i));
        File file = xml(input.append("</tv>").toString());
        Tv baseline = new Persister().read(Tv.class, file, false);
        List<String> expected = baseline.getProgramme().stream().filter(p -> p.getChannel().equals("local")).map(Tv.Programme::getTitle).toList();
        Live live = live();
        EpgParser.readXml(live, file);
        List<String> actual = channel(live).getDataList().stream().flatMap(e -> e.getList().stream()).map(e -> e.getTitle()).toList();
        assertEquals(expected, actual);
    }
}
