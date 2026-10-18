package com.wuming.android.tv.server.process;

import android.util.Log;

import com.wuming.android.tv.App;
import com.wuming.android.tv.api.SiteApi;
import com.wuming.android.tv.api.config.VodConfig;
import com.wuming.android.tv.bean.Episode;
import com.wuming.android.tv.bean.Flag;
import com.wuming.android.tv.bean.Result;
import com.wuming.android.tv.bean.Site;
import com.wuming.android.tv.bean.Vod;
import com.wuming.android.tv.server.CastMediaAccess;
import com.wuming.android.tv.server.Nano;
import com.wuming.android.tv.server.impl.Process;
import com.wuming.tv.core.catalog.CatalogDetail;
import com.wuming.tv.core.catalog.CatalogEpisode;
import com.wuming.tv.core.catalog.CatalogError;
import com.wuming.tv.core.catalog.CatalogItem;
import com.wuming.tv.core.catalog.CatalogItemKind;
import com.wuming.tv.core.catalog.CatalogLine;
import com.wuming.tv.core.catalog.CatalogPlayback;
import com.wuming.tv.core.catalog.CatalogPlaybackPolicy;
import com.wuming.tv.core.catalog.CatalogSource;
import com.wuming.tv.core.source.CompanionProtocol;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;

/** Versioned, paired companion API for the physically separate desktop client. */
public final class Desktop implements Process {

    private static final String TAG = "DesktopApi";
    @Override
    public boolean isRequest(IHTTPSession session, String url) {
        return url.startsWith(CompanionProtocol.ROOT);
    }

    @Override
    public Response doResponse(IHTTPSession session, String url, Map<String, String> files) {
        if (session.getMethod() != NanoHTTPD.Method.GET) {
            return error(Response.Status.METHOD_NOT_ALLOWED, "GET required");
        }
        try {
            return switch (session.getUri()) {
                case CompanionProtocol.SOURCES -> sources();
                case CompanionProtocol.SEARCH -> search(Nano.params(session));
                case CompanionProtocol.DETAIL -> detail(Nano.params(session));
                case CompanionProtocol.PLAY -> play(Nano.params(session));
                default -> error(Response.Status.NOT_FOUND, "Unknown desktop endpoint");
            };
        } catch (IllegalArgumentException e) {
            return error(Response.Status.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Desktop API request failed", e);
            return error(Response.Status.INTERNAL_ERROR, "Companion request failed");
        }
    }

    private Response sources() {
        List<CatalogSource> result = new ArrayList<>();
        for (Site site : VodConfig.get().getSites()) {
            if (site.getHide() == 1 || site.getSearchable() == 0) continue;
            result.add(new CatalogSource(site.getKey(), site.getName()));
        }
        return json(result);
    }

    private Response search(Map<String, String> params) throws Exception {
        Site site = source(CompanionProtocol.requireParameter(params.get("source"), "source"));
        String query = CompanionProtocol.requireParameter(params.get("query"), "query");
        Result result = SiteApi.searchContent(site, query, false, "1");
        List<CatalogItem> items = new ArrayList<>();
        for (Vod vod : result.getList()) {
            String handle = DesktopCatalogSessions.issueItem(site.getKey(), vod.getId());
            items.add(item(site.getKey(), handle, vod));
        }
        return json(items);
    }

    private Response detail(Map<String, String> params) throws Exception {
        Site site = source(CompanionProtocol.requireParameter(params.get("source"), "source"));
        String handle = CompanionProtocol.requireParameter(params.get("id"), "id");
        String providerId = DesktopCatalogSessions.resolveItem(site.getKey(), handle);
        if (providerId == null) throw new IllegalArgumentException("Catalogue selection expired; search again");
        Vod vod = SiteApi.detailContent(site.getKey(), providerId).getVod();
        if (vod.getId().isEmpty() && vod.getName().isEmpty()) {
            return error(Response.Status.NOT_FOUND, "Catalogue item not found");
        }
        List<CatalogLine> lines = new ArrayList<>();
        for (Flag flag : vod.getFlags()) {
            List<CatalogEpisode> episodes = new ArrayList<>();
            for (Episode episode : flag.getEpisodes()) {
                String episodeHandle = DesktopCatalogSessions.issueEpisode(
                        site.getKey(), flag.getFlag(), episode.getUrl());
                episodes.add(new CatalogEpisode(episode.getName(), episodeHandle));
            }
            lines.add(new CatalogLine(flag.getFlag(), flag.getShow(), episodes));
        }
        return json(new CatalogDetail(
                item(site.getKey(), handle, vod), vod.getContent(), vod.getActor(), vod.getDirector(),
                vod.getArea(), lines));
    }

    private Response play(Map<String, String> params) throws Exception {
        Site site = source(CompanionProtocol.requireParameter(params.get("source"), "source"));
        String flag = CompanionProtocol.requireParameter(params.get("flag"), "flag");
        String handle = CompanionProtocol.requireParameter(params.get("id"), "id");
        String providerId = DesktopCatalogSessions.resolveEpisode(site.getKey(), flag, handle);
        if (providerId == null) throw new IllegalArgumentException("Episode selection expired; reload details");
        if (CatalogPlaybackPolicy.requiresInteractiveResolution(providerId)) {
            return json(new CatalogPlayback(false, "", "",
                    "This cloud episode requires interactive Android playback."));
        }
        Result result = SiteApi.playerContent(site.getKey(), flag, providerId);
        String format = result.getFormat() == null ? "" : result.getFormat();
        if (result.hasMsg()) {
            String message = result.getMsg() == null ? "The provider could not resolve this episode." : result.getMsg();
            return json(new CatalogPlayback(false, "", format, message));
        }
        if (result.getDrm() != null) {
            return json(new CatalogPlayback(false, "", format,
                    "This episode requires Android DRM playback."));
        }
        if (result.needParse()) {
            return json(new CatalogPlayback(false, "", format,
                    "This episode requires an Android parser and cannot yet play on desktop."));
        }
        String resolved = result.getRealUrl();
        if (resolved == null || resolved.isEmpty()) {
            return json(new CatalogPlayback(false, "", format,
                    "The provider returned no playable URL."));
        }
        if (!CatalogPlaybackPolicy.isHttpRelayCandidate(resolved)) {
            return json(new CatalogPlayback(false, "", format,
                    "The provider returned an Android-specific media token."));
        }
        boolean hls = format.toLowerCase(Locale.ROOT).contains("mpegurl")
                || resolved.toLowerCase(Locale.ROOT).contains(".m3u8");
        String relay = CastMediaAccess.relayDesktop(resolved, result.getHeader(), hls);
        return json(new CatalogPlayback(true, relay, format, ""));
    }

    private Site source(String key) {
        Site site = VodConfig.get().getSite(key);
        if (!site.getKey().equals(key) || site.getHide() == 1 || site.getSearchable() == 0) {
            throw new IllegalArgumentException("Unknown catalogue source");
        }
        return site;
    }

    private CatalogItem item(String sourceKey, String handle, Vod vod) {
        return new CatalogItem(sourceKey, handle, vod.getName(), vod.getPic(),
                vod.getRemarks(), vod.getYear(), CatalogItemKind.MEDIA, "",
                vod.getTypeName(), vod.getArea(), vod.getDirector(), vod.getActor(), vod.getContent());
    }

    private Response json(Object value) {
        return Nano.json(App.gson().toJson(value));
    }

    private Response error(Response.Status status, String message) {
        return Nano.jsonError(status, App.gson().toJson(new CatalogError(message)));
    }
}
