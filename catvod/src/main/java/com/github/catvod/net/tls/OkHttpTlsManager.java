package com.github.catvod.net.tls;

import android.annotation.SuppressLint;

import com.github.catvod.utils.Prefers;

import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** TLS/SSL configuration and legacy-certificate compatibility management for OkHttp. */
public final class OkHttpTlsManager {

    private static final String LEGACY_TLS = "legacy_tls";

    private static volatile SSLSocketFactory cachedSslSocketFactory;
    private static volatile X509TrustManager cachedTrustManager;
    private static volatile Boolean legacyTlsOverride;

    private OkHttpTlsManager() {
    }

    public static boolean isLegacyTlsEnabled() {
        Boolean override = legacyTlsOverride;
        return override != null ? override : Prefers.getBoolean(LEGACY_TLS);
    }

    public static void setLegacyTlsOverride(Boolean override) {
        legacyTlsOverride = override;
    }

    public static void persistLegacyTls(boolean enabled) {
        Prefers.put(LEGACY_TLS, enabled);
    }

    public static SSLSocketFactory getCachedSslSocketFactory() {
        if (cachedSslSocketFactory == null) {
            synchronized (OkHttpTlsManager.class) {
                if (cachedSslSocketFactory == null) {
                    try {
                        SSLContext context = SSLContext.getInstance("TLS");
                        X509TrustManager tm = trustAllCertificates();
                        context.init(null, new TrustManager[]{tm}, new SecureRandom());
                        cachedSslSocketFactory = context.getSocketFactory();
                        cachedTrustManager = tm;
                    } catch (Throwable e) {
                        return null;
                    }
                }
            }
        }
        return cachedSslSocketFactory;
    }

    public static X509TrustManager getCachedTrustManager() {
        if (cachedTrustManager == null) getCachedSslSocketFactory();
        return cachedTrustManager;
    }

    public static SSLContext getSSLContext() {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{trustAllCertificates()}, new SecureRandom());
            return context;
        } catch (Throwable e) {
            return null;
        }
    }

    @SuppressLint({"TrustAllX509TrustManager", "CustomX509TrustManager"})
    public static X509TrustManager trustAllCertificates() {
        return new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
    }
}
