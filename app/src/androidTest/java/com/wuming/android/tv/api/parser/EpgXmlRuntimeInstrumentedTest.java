package com.wuming.android.tv.api.parser;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.wuming.tv.core.epg.XmlTvStreamReader;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import static org.junit.Assert.*;

/** Run only on a device authorized for EPG validation; local compilation is not execution. */
@RunWith(AndroidJUnit4.class)
public class EpgXmlRuntimeInstrumentedTest {
    @Test public void platformSaxSupportsUnicodeEntitiesAndTheLexicalGuard() throws Exception {
        for (var charset : List.of(StandardCharsets.UTF_8, StandardCharsets.UTF_16)) {
            List<String> titles = new ArrayList<>();
            String xml = "<?xml version='1.0' encoding='" + charset.name() + "'?><tv>"
                    + "<programme channel='yes'><title>新闻 &amp; <![CDATA[天气]]></title></programme></tv>";
            XmlTvStreamReader.read(new ByteArrayInputStream(xml.getBytes(charset)), new XmlTvStreamReader.Visitor() {
                public boolean selectProgramme(String id) { return id.equals("yes"); }
                public void programme(XmlTvStreamReader.Programme programme) { titles.add(programme.title()); }
            }, XmlTvStreamReader.DEFAULT_LIMITS);
            assertEquals(List.of("新闻 & 天气"), titles);
        }
    }

    @Test public void platformSaxRejectsDtdAndExpandedGzipOverBudget() throws Exception {
        // External XMLTV declarations are accepted without loading the subset.
        XmlTvStreamReader.read(new ByteArrayInputStream(
                "<!DOCTYPE tv SYSTEM 'file:///nonexistent-epg-test.dtd'><tv/>"
                        .getBytes(StandardCharsets.UTF_8)),
                new XmlTvStreamReader.Visitor() {}, XmlTvStreamReader.DEFAULT_LIMITS);
        for (String xml : List.of("<!DOCTYPE tv [<!ENTITY x 'expanded'>]><tv>&x;</tv>",
                "<!DOCTYPE tv SYSTEM 'xmltv.dtd' [<!ENTITY xxe SYSTEM 'file:///nonexistent-epg-test'>]>"
                        + "<tv>&xxe;</tv>")) {
            byte[] input = xml.getBytes(StandardCharsets.UTF_8);
            assertThrows(Exception.class, () -> XmlTvStreamReader.read(new ByteArrayInputStream(input),
                    new XmlTvStreamReader.Visitor() {}, XmlTvStreamReader.DEFAULT_LIMITS));
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(("<tv>" + " ".repeat(10000) + "</tv>").getBytes(StandardCharsets.UTF_8));
        }
        try (GZIPInputStream expanded = new GZIPInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            assertThrows(Exception.class, () -> XmlTvStreamReader.read(expanded,
                    new XmlTvStreamReader.Visitor() {}, new XmlTvStreamReader.Limits(1000, 10, 100, 10)));
        }
    }
}
