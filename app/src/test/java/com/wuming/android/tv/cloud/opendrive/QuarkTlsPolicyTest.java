package com.wuming.android.tv.cloud.opendrive;

import static org.junit.Assert.*;

import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;
import okhttp3.OkHttpClient;
import org.junit.Test;

public class QuarkTlsPolicyTest {
    @Test public void cloudClientsReplaceLegacyTrustHostnameAndConnectionPool() throws Exception {
        X509TrustManager permissive = new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] chain, String authType) { }
            public void checkServerTrusted(X509Certificate[] chain, String authType) { }
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new javax.net.ssl.TrustManager[]{permissive}, null);
        OkHttpClient legacy = new OkHttpClient.Builder()
                .sslSocketFactory(context.getSocketFactory(), permissive)
                .hostnameVerifier((host, session) -> true).build();
        for (OkHttpClient secure : new OkHttpClient[]{
                QuarkNetwork.configureNetworkClient(legacy.newBuilder()).build(),
                QuarkNetwork.configureStreamingClient(legacy.newBuilder()).build()}) {
            assertNotSame(permissive, secure.x509TrustManager());
            assertNotSame(legacy.connectionPool(), secure.connectionPool());
            assertFalse(secure.hostnameVerifier().verify("drive-pc.quark.cn", context.createSSLEngine().getSession()));
            assertThrows(IllegalArgumentException.class,
                    () -> secure.x509TrustManager().checkServerTrusted(new X509Certificate[0], "RSA"));
            assertSame(legacy.cookieJar(), secure.cookieJar());
            assertSame(legacy.dns(), secure.dns());
        }
    }
}
