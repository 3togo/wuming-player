package com.wuming.android.tv.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SystemToastInterceptorTest {

    @Test
    public void recognizesUcLoginReminderVariants() {
        assertTrue(SystemToastInterceptor.isUcLoginReminder(
                "网盘资源需使用UC浏览器APP扫码登录"));
        assertTrue(SystemToastInterceptor.isUcLoginReminder(
                "網盤源需使用 UC瀏覽器 APP 掃碼登入"));
    }

    @Test
    public void preservesOtherProviderAndPlaybackMessages() {
        assertFalse(SystemToastInterceptor.isUcLoginReminder("UC Drive session expired"));
        assertFalse(SystemToastInterceptor.isUcLoginReminder("正在获取网盘登录信息…"));
        assertFalse(SystemToastInterceptor.isUcLoginReminder("网盘资源加载失败"));
    }
}
