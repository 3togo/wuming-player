package com.wuming.android.tv.utils;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import androidx.core.content.FileProvider;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.request.FutureTarget;
import com.wuming.android.tv.R;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class ArtworkInstrumentedTest {
    @Test public void localArtworkUsesLocalLoadersAndHttpKeepsHeaders() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        String resource = "android.resource://" + context.getPackageName() + "/" + R.drawable.push_image;
        assertFalse(ImgUtil.getUrl(resource) instanceof GlideUrl);
        load(context, resource);
        File file = File.createTempFile("artwork-smoke-", ".png", context.getCacheDir());
        Bitmap bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
        try {
            try (FileOutputStream out = new FileOutputStream(file)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); }
            assertEquals(Uri.fromFile(file).toString(), ImgUtil.getUrl(Uri.fromFile(file).toString()));
            load(context, Uri.fromFile(file).toString());
            load(context, FileProvider.getUriForFile(context, context.getPackageName() + ".provider", file).toString());
            String data = "data:image/png;base64," + android.util.Base64.encodeToString(
                    java.nio.file.Files.readAllBytes(file.toPath()), android.util.Base64.NO_WRAP);
            load(context, data);
            GlideUrl remote = (GlideUrl) ImgUtil.getUrl("https://example.test/image.png@Referer=https://example.test/@User-Agent=Smoke");
            assertEquals("https://example.test/image.png", remote.toStringUrl());
            assertEquals("https://example.test/", remote.getHeaders().get("Referer"));
            assertEquals("Smoke", remote.getHeaders().get("User-Agent"));
            verifyVisibleFallback();
        } finally { bitmap.recycle(); assertTrue(file.delete()); }
    }
    private static void load(Context context, String uri) throws Exception {
        com.bumptech.glide.RequestBuilder<Drawable> request = Glide.with(context).load(ImgUtil.getUrl(uri));
        FutureTarget<Drawable> target = request.submit(32, 32);
        try { assertNotNull(target.get(15, TimeUnit.SECONDS)); }
        finally { Glide.with(context).clear(target); }
    }
    private static void verifyVisibleFallback() throws Exception {
        try (androidx.test.core.app.ActivityScenario<com.wuming.android.tv.ui.activity.MemoryTestActivity> scenario =
                androidx.test.core.app.ActivityScenario.launch(com.wuming.android.tv.ui.activity.MemoryTestActivity.class)) {
            java.util.concurrent.atomic.AtomicReference<android.widget.ImageView> image = new java.util.concurrent.atomic.AtomicReference<>();
            scenario.onActivity(activity -> {
                android.widget.ImageView view = new android.widget.ImageView(activity);
                ((android.view.ViewGroup)activity.findViewById(android.R.id.custom)).addView(view,
                        new android.view.ViewGroup.LayoutParams(64, 64));
                view.layout(0, 0, 64, 64);
                image.set(view);
                ImgUtil.load("Unavailable", "file:///missing-artwork-" + java.util.UUID.randomUUID() + ".png", view);
            });
            java.util.concurrent.atomic.AtomicBoolean shown = new java.util.concurrent.atomic.AtomicBoolean();
            long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
            while (!shown.get() && android.os.SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity(activity -> shown.set(image.get().getDrawable() != null && image.get().isShown()));
                android.os.SystemClock.sleep(100);
            }
            assertTrue("Unavailable artwork must show the text fallback", shown.get());
            scenario.onActivity(activity -> Glide.with(activity).clear(image.get()));
        }
    }
}
