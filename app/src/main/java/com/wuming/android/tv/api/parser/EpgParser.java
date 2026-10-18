package com.wuming.android.tv.api.parser;

import com.wuming.android.tv.bean.Channel;
import com.wuming.android.tv.bean.Epg;
import com.wuming.android.tv.bean.EpgData;
import com.wuming.android.tv.bean.Live;
import com.wuming.android.tv.bean.Tv;
import com.wuming.android.tv.utils.AppLog;
import com.wuming.android.tv.utils.Download;
import com.wuming.android.tv.utils.Formatters;
import com.wuming.android.tv.utils.UrlUtil;
import com.github.catvod.utils.Path;
import com.wuming.tv.core.epg.XmlTvStreamReader;
import org.xml.sax.SAXException;
import com.wuming.tv.core.epg.ChannelNameMatch;
import com.wuming.tv.core.epg.ChannelNameMatcher;

import org.simpleframework.xml.core.Persister;

import java.io.File;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class EpgParser {

    private static final String TAG = EpgParser.class.getSimpleName();

    private static ZoneId zoneIdOf(String tz) {
        if (tz.isEmpty()) return ZoneId.systemDefault();
        try {
            return ZoneId.of(tz);
        } catch (Exception e) {
            AppLog.w(TAG, "zoneIdOf failed tz=" + tz + ": " + e.getMessage());
            return ZoneId.systemDefault();
        }
    }

    private static OffsetDateTime parseFull(String source, ZoneId zoneId) {
        String s = source.trim();
        int len = s.length();
        try {
            if (len >= 20) return OffsetDateTime.parse(s, s.charAt(len - 3) == ':' ? Formatters.EPG_FULL_COLON : Formatters.EPG_FULL);
            return LocalDateTime.parse(len > 14 ? s.substring(0, 14) : s, Formatters.EPG_FULL_NO_TZ).atZone(zoneId).toOffsetDateTime();
        } catch (Exception e) {
            AppLog.w(TAG, "parseFull failed: " + s + " -> " + e.getMessage());
            return OffsetDateTime.ofInstant(Instant.EPOCH, ZoneOffset.UTC);
        }
    }

    public static void start(Live live, String url) throws Exception {
        long t0 = System.currentTimeMillis();
        File file = Path.epg(UrlUtil.path(url));
        String reason = refreshReason(file);
        boolean refresh = reason != null;
        AppLog.i(TAG, "start file=" + file.getName() + " refresh=" + refresh
                + (refresh ? " reason=" + reason : ""));
        if (refresh) Download.create(url, file).get();
        readXml(live, file);
        AppLog.i(TAG, "start done elapsed=" + (System.currentTimeMillis() - t0) + "ms");
    }

    public static Epg getEpg(String xml, String key, ZoneId zoneId) {
        try {
            Tv tv = new Persister().read(Tv.class, xml, false);
            String rawDate = tv.getDate();
            String date = rawDate.isEmpty() ? LocalDate.now(zoneId).format(Formatters.DATE) : parseFull(rawDate, zoneId).atZoneSameInstant(zoneId).format(Formatters.DATE);
            Epg epg = Epg.create(key, date);
            tv.getProgramme().forEach(programme -> epg.getList().add(getEpgData(programme, zoneId)));
            return epg;
        } catch (Exception e) {
            AppLog.w(TAG, "getEpg parse failed key=" + key + ": " + e.getMessage());
            return new Epg();
        }
    }

    private static String refreshReason(File file) {
        if (!Path.exists(file)) return "file-missing";
        if (!isToday(file.lastModified())) return "not-today";
        if (System.currentTimeMillis() - file.lastModified() > TimeUnit.HOURS.toMillis(6)) return "older-than-6h";
        return null;
    }

    private static boolean isToday(long millis) {
        return LocalDate.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()).equals(LocalDate.now());
    }

    // Keep all existing dates for catch-up navigation. Bound the import rather than silently
    // truncating it; results are published only after both complete passes succeed.
    private static final int MAX_CHANNEL_RECORDS = 50_000;
    private static final int MAX_RETAINED_PROGRAMMES = 250_000;
    private static final long MAX_RETAINED_CHARACTERS = 16L * 1024 * 1024;

    static void readXml(Live live, File file) throws Exception {
        ZoneId zoneId = zoneIdOf(live.getTimeZone());
        Map<String, Channel> liveChannelMap = prepareLiveChannels(live);
        Map<String, List<XmlTvStreamReader.Channel>> channels = new HashMap<>();
        long[] characters = {0};
        int[] channelCount = {0};
        XmlTvStreamReader.read(file, new XmlTvStreamReader.Visitor() {
            @Override public boolean readChannels() { return true; }
            @Override public void channel(XmlTvStreamReader.Channel channel) throws SAXException {
                characters[0] += channel.id().length() + channel.icon().length();
                for (String name : channel.names()) characters[0] += name.length();
                if (++channelCount[0] > MAX_CHANNEL_RECORDS || characters[0] > MAX_RETAINED_CHARACTERS)
                    throw new SAXException("XMLTV channel metadata limit exceeded");
                channels.computeIfAbsent(channel.id(), key -> new ArrayList<>()).add(channel);
            }
        });
        ProgrammeResult result = processProgramme(file, channels, liveChannelMap, zoneId, characters[0]);
        bindResultsToLive(live, result);
    }

    private static Map<String, Channel> prepareLiveChannels(Live live) {
        Map<String, Channel> map = new HashMap<>();
        live.getGroups().stream()
                .flatMap(group -> group.getChannel().stream())
                .forEach(channel -> {
                    if (!channel.getTvgId().isEmpty()) map.putIfAbsent(channel.getTvgId(), channel);
                    if (!channel.getTvgName().isEmpty()) map.putIfAbsent(channel.getTvgName(), channel);
                    if (!channel.getName().isEmpty()) map.putIfAbsent(channel.getName(), channel);
                });
        return map;
    }

    private static ProgrammeResult processProgramme(File file,
            Map<String, List<XmlTvStreamReader.Channel>> channels,
            Map<String, Channel> liveChannelMap, ZoneId zoneId, long metadataCharacters) throws Exception {
        Map<String, Map<String, Epg>> epgMap = new HashMap<>();
        Map<String, String> srcMap = new HashMap<>();
        Map<String, Channel> channelCache = new HashMap<>();
        // Only cache metadata IDs. Arbitrary programme-only IDs must not grow an unbounded miss set.
        Set<String> channelMiss = new HashSet<>();
        XmlTvStreamReader.read(file, new XmlTvStreamReader.Visitor() {
            private Channel target;
            private int retained;
            private long characters = metadataCharacters;
            @Override public boolean selectProgramme(String id) throws SAXException {
                target = liveChannelMap.get(id);
                if (target == null) target = channelCache.get(id);
                if (target == null && channels.containsKey(id) && !channelMiss.contains(id)) {
                    target = findTargetChannel(id, liveChannelMap, channels);
                    if (target == null) channelMiss.add(id);
                    else channelCache.put(id, target);
                }
                if (target != null && ++retained > MAX_RETAINED_PROGRAMMES)
                    throw new SAXException("XMLTV retained programme limit exceeded");
                return target != null;
            }
            @Override public void programme(XmlTvStreamReader.Programme programme) throws SAXException {
                characters += programme.title().length();
                if (characters > MAX_RETAINED_CHARACTERS)
                    throw new SAXException("XMLTV retained text limit exceeded");
                OffsetDateTime startDate = parseFull(programme.start(), zoneId);
                OffsetDateTime endDate = parseFull(programme.stop(), zoneId);
                String liveTvgId = target.getTvgId();
                String programmeDate = startDate.atZoneSameInstant(zoneId).format(Formatters.DATE);
                epgMap.computeIfAbsent(liveTvgId, k -> new HashMap<>())
                        .computeIfAbsent(programmeDate, d -> Epg.create(liveTvgId, d))
                        .getList().add(getEpgData(startDate, endDate, zoneId, programme.title()));
                if (!srcMap.containsKey(liveTvgId)) {
                    for (XmlTvStreamReader.Channel channel : channels.getOrDefault(programme.channel(), List.of())) {
                        if (!channel.icon().isEmpty()) {
                            srcMap.put(liveTvgId, channel.icon());
                            break;
                        }
                    }
                }
            }
        });
        return new ProgrammeResult(epgMap, srcMap);
    }

    private static Channel findTargetChannel(String xmlChannelId, Map<String, Channel> liveChannelMap, Map<String, List<XmlTvStreamReader.Channel>> xmlChannelIdMap) {
        Channel targetChannel = liveChannelMap.get(xmlChannelId);
        if (targetChannel != null) return targetChannel;
        List<XmlTvStreamReader.Channel> channels = xmlChannelIdMap.get(xmlChannelId);
        if (channels == null) return null;
        List<String> displayNames = channels.stream()
                .flatMap(xmlChannel -> xmlChannel.names().stream())
                .filter(name -> !name.isEmpty())
                .collect(Collectors.toList());
        for (String name : displayNames) {
            targetChannel = liveChannelMap.get(name);
            if (targetChannel != null) return targetChannel;
        }
        ChannelNameMatch match = ChannelNameMatcher.findBest(
                displayNames, liveChannelMap.keySet(), 0.86);
        return match == null ? null : liveChannelMap.get(match.getCandidate());
    }

    private static void bindResultsToLive(Live live, ProgrammeResult result) {
        int[] counts = {0, 0};
        live.getGroups().stream()
                .flatMap(group -> group.getChannel().stream())
                .forEach(channel -> {
                    String tvgId = channel.getTvgId();
                    Map<String, Epg> dateMap = result.epgMap.get(tvgId);
                    if (dateMap != null) {
                        channel.setDataList(new ArrayList<>(dateMap.values()));
                        counts[0]++;
                    } else {
                        counts[1]++;
                    }
                    if (channel.getLogo().isEmpty()) {
                        String src = result.srcMap.get(tvgId);
                        if (src != null) channel.setLogo(src);
                    }
                });
        AppLog.i(TAG, "bindResultsToLive with-epg=" + counts[0]
                + " without-epg=" + counts[1]);
    }

    private static EpgData getEpgData(Tv.Programme programme, ZoneId zoneId) {
        OffsetDateTime startDate = parseFull(programme.getStart(), zoneId);
        OffsetDateTime endDate = parseFull(programme.getStop(), zoneId);
        return getEpgData(startDate, endDate, zoneId, programme.getTitle());
    }

    private static EpgData getEpgData(OffsetDateTime startDate, OffsetDateTime endDate, ZoneId zoneId, String title) {
        try {
            EpgData epgData = new EpgData();
            epgData.setTitle(title);
            epgData.setStart(startDate.atZoneSameInstant(zoneId).format(Formatters.TIME));
            epgData.setEnd(endDate.atZoneSameInstant(zoneId).format(Formatters.TIME));
            epgData.setStartTime(startDate.toInstant().toEpochMilli());
            epgData.setEndTime(endDate.toInstant().toEpochMilli());
            epgData.trans();
            return epgData;
        } catch (Exception e) {
            return new EpgData();
        }
    }

    private static class ProgrammeResult {

        Map<String, Map<String, Epg>> epgMap;
        Map<String, String> srcMap;

        public ProgrammeResult(Map<String, Map<String, Epg>> epgMap, Map<String, String> srcMap) {
            this.epgMap = epgMap;
            this.srcMap = srcMap;
        }
    }
}
