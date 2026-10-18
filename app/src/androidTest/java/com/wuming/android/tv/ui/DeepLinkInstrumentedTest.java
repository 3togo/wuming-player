package com.wuming.android.tv.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.wuming.android.tv.ui.activity.HomeActivity;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class DeepLinkInstrumentedTest {

    @Test
    public void webVideoDeepLinkRoutesToHome() {
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(Uri.parse("https://media.example/movie.mp4"), "video/mp4")
                .addCategory(Intent.CATEGORY_DEFAULT)
                .addCategory(Intent.CATEGORY_BROWSABLE);
        assertRoutesToHome(intent);
    }

    @Test
    public void magnetDeepLinkRoutesToHome() {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("magnet:?xt=urn:btih:0123456789"))
                .addCategory(Intent.CATEGORY_DEFAULT)
                .addCategory(Intent.CATEGORY_BROWSABLE);
        assertRoutesToHome(intent);
    }

    @Test
    public void sharedTextRoutesToHome() {
        Intent intent = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "https://media.example/movie.mp4")
                .addCategory(Intent.CATEGORY_DEFAULT);
        assertRoutesToHome(intent);
    }

    @Test
    public void searchIntentRoutesToHomeWithQueryIntact() {
        Intent intent = new Intent(Intent.ACTION_SEARCH)
                .putExtra(SearchManager.QUERY, "Example Show");
        assertRoutesToHome(intent);
        assertEquals("Example Show", intent.getStringExtra(SearchManager.QUERY));
    }

    private static void assertRoutesToHome(Intent intent) {
        Context context = ApplicationProvider.getApplicationContext();
        intent.setPackage(context.getPackageName());
        ResolveInfo info = context.getPackageManager().resolveActivity(intent, 0);
        assertNotNull(info);
        assertEquals(HomeActivity.class.getName(), info.activityInfo.name);
    }
}
