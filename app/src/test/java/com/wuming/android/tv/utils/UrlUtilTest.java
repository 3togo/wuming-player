package com.wuming.android.tv.utils;

import com.google.common.net.HttpHeaders;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class UrlUtilTest {

    @Test
    public void fixHeader_userAgent_exact() {
        assertEquals(HttpHeaders.USER_AGENT, UrlUtil.fixHeader("User-Agent"));
    }

    @Test
    public void fixHeader_userAgent_lowercase() {
        assertEquals(HttpHeaders.USER_AGENT, UrlUtil.fixHeader("user-agent"));
    }

    @Test
    public void fixHeader_userAgent_uppercase() {
        assertEquals(HttpHeaders.USER_AGENT, UrlUtil.fixHeader("USER-AGENT"));
    }

    @Test
    public void fixHeader_referer_exact() {
        assertEquals(HttpHeaders.REFERER, UrlUtil.fixHeader("Referer"));
    }

    @Test
    public void fixHeader_referer_lowercase() {
        assertEquals(HttpHeaders.REFERER, UrlUtil.fixHeader("referer"));
    }

    @Test
    public void fixHeader_cookie_exact() {
        assertEquals(HttpHeaders.COOKIE, UrlUtil.fixHeader("Cookie"));
    }

    @Test
    public void fixHeader_cookie_lowercase() {
        assertEquals(HttpHeaders.COOKIE, UrlUtil.fixHeader("cookie"));
    }

    @Test
    public void fixHeader_unknown_passthrough() {
        assertEquals("X-Custom-Header", UrlUtil.fixHeader("X-Custom-Header"));
    }

    @Test
    public void fixHeader_accept_passthrough() {
        assertEquals("Accept", UrlUtil.fixHeader("Accept"));
    }

    @Test
    public void fixHeader_empty_string() {
        assertEquals("", UrlUtil.fixHeader(""));
    }
}