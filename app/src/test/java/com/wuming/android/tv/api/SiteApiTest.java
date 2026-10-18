package com.wuming.android.tv.api;
import com.wuming.android.tv.setting.AppSettings;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.wuming.android.tv.bean.Result;
import com.wuming.android.tv.bean.Vod;

import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Collections;

public class SiteApiTest {

    @Test
    public void detectsOnlyLoginActionPagesForInteractivePromotion() throws Exception {
        assertTrue(SiteApi.hasLoginAction(resultWithAction("LoginShow")));
        assertFalse(SiteApi.hasLoginAction(resultWithAction("aliClean")));
        assertFalse(SiteApi.hasLoginAction(Result.empty()));
    }

    @Test
    public void catVodDirectFallsBackOnlyAfterTerminalResolutionFailure() {
        Result error = Result.error("invalid cloud response");
        Result empty = Result.empty();
        Result playable = Result.empty();
        playable.setUrl("https://example.com/video.mp4");

        assertTrue(SiteApi.shouldTryOpenDriveFallback(
                AppSettings.LINK_ROUTE_DIRECT, "external-cloud-payload", error));
        assertTrue(SiteApi.shouldTryOpenDriveFallback(
                AppSettings.LINK_ROUTE_DIRECT, "external-cloud-payload", empty));
        assertFalse(SiteApi.shouldTryOpenDriveFallback(
                AppSettings.LINK_ROUTE_DIRECT, "external-cloud-payload", playable));
        assertFalse(SiteApi.shouldTryOpenDriveFallback(
                AppSettings.LINK_ROUTE_BUILTIN, "external-cloud-payload", error));
        assertFalse(SiteApi.shouldTryOpenDriveFallback(
                AppSettings.LINK_ROUTE_DIRECT, "opendrive-file:uc:Zm9v", error));
        assertFalse(SiteApi.shouldTryOpenDriveFallback(
                AppSettings.LINK_ROUTE_DIRECT, "opendrive://local/asset/example", error));
    }

    private static Result resultWithAction(String action) throws Exception {
        Vod vod = new Vod();
        Field field = Vod.class.getDeclaredField("action");
        field.setAccessible(true);
        field.set(vod, action);
        return Result.list(Collections.singletonList(vod));
    }
}
