package com.wuming.android.tv.api.provider;

import android.os.Bundle;
import android.os.Looper;

import com.wuming.android.tv.Constant;
import com.github.catvod.crawler.Spider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class RemoteSpider extends Spider {

    private final ProviderClient client;
    private final String key;
    private final String api;
    private final String ext;
    private final String jar;

    public RemoteSpider(String key, String api, String ext, String jar) {
        this.client = ProviderClient.get();
        this.key = key;
        this.api = api;
        this.ext = ext;
        this.jar = jar;
        this.siteKey = key;
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        Bundle request = request(ProviderProtocol.HOME);
        request.putBoolean("filter", filter);
        return client.callString(request, Constant.TIMEOUT_VOD + 5_000L);
    }

    @Override
    public String homeVideoContent() throws Exception {
        return client.callString(request(ProviderProtocol.HOME_VIDEO), Constant.TIMEOUT_VOD + 5_000L);
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        Bundle request = request(ProviderProtocol.CATEGORY);
        request.putStringArrayList(ProviderProtocol.ARGS, values(tid, pg));
        request.putBoolean("filter", filter);
        request.putBundle(ProviderProtocol.PARAMS, ProviderClient.toBundle(extend));
        return client.callString(request, Constant.TIMEOUT_VOD + 5_000L);
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        Bundle request = request(ProviderProtocol.DETAIL);
        request.putStringArrayList(ProviderProtocol.IDS, copy(ids));
        return client.callString(request, Constant.TIMEOUT_VOD + 5_000L);
    }

    @Override
    public String searchContent(String keyword, boolean quick) throws Exception {
        Bundle request = request(ProviderProtocol.SEARCH);
        request.putStringArrayList(ProviderProtocol.ARGS, values(keyword));
        request.putBoolean("quick", quick);
        return client.callString(request, Constant.TIMEOUT_SEARCH + 5_000L);
    }

    @Override
    public String searchContent(String keyword, boolean quick, String page) throws Exception {
        Bundle request = request(ProviderProtocol.SEARCH_PAGE);
        request.putStringArrayList(ProviderProtocol.ARGS, values(keyword, page));
        request.putBoolean("quick", quick);
        return client.callString(request, Constant.TIMEOUT_SEARCH + 5_000L);
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        Bundle request = request(ProviderProtocol.PLAYER);
        request.putStringArrayList(ProviderProtocol.ARGS, values(flag, id));
        request.putStringArrayList(ProviderProtocol.FLAGS, copy(vipFlags));
        return client.callString(request, Constant.TIMEOUT_PLAYER);
    }

    @Override
    public String liveContent(String url) throws Exception {
        Bundle request = request(ProviderProtocol.LIVE);
        request.putStringArrayList(ProviderProtocol.ARGS, values(url));
        return client.callString(request, Constant.TIMEOUT_LIVE + 5_000L);
    }

    @Override
    public boolean manualVideoCheck() throws Exception {
        boolean mainThread = Looper.myLooper() == Looper.getMainLooper();
        if (mainThread && !client.isConnected()) return false;
        return client.callBoolean(request(ProviderProtocol.MANUAL_VIDEO), mainThread ? 500L : Constant.TIMEOUT_VOD);
    }

    @Override
    public boolean isVideoFormat(String url) throws Exception {
        boolean mainThread = Looper.myLooper() == Looper.getMainLooper();
        if (mainThread && !client.isConnected()) return false;
        Bundle request = request(ProviderProtocol.VIDEO_FORMAT);
        request.putStringArrayList(ProviderProtocol.ARGS, values(url));
        return client.callBoolean(request, mainThread ? 500L : Constant.TIMEOUT_VOD);
    }

    @Override
    public Object[] proxy(Map<String, String> params) throws Exception {
        Bundle request = request(ProviderProtocol.PROXY);
        request.putBundle(ProviderProtocol.PARAMS, ProviderClient.toBundle(params));
        return client.callProxy(request);
    }

    @Override
    public String action(String action) throws Exception {
        Bundle request = request(ProviderProtocol.ACTION);
        request.putStringArrayList(ProviderProtocol.ARGS, values(action));
        return client.callString(request, Constant.TIMEOUT_VOD + 5_000L);
    }

    private Bundle request(int operation) {
        return ProviderClient.request(operation, key, api, ext, jar);
    }

    private static ArrayList<String> values(String... values) {
        ArrayList<String> result = new ArrayList<>(values.length);
        for (String value : values) result.add(value == null ? "" : value);
        return result;
    }

    private static ArrayList<String> copy(List<String> values) {
        return values == null ? new ArrayList<>() : new ArrayList<>(values);
    }
}
