package com.wuming.android.tv.ui.dialog;

import static org.junit.Assert.assertEquals;

import com.wuming.android.tv.bean.Episode;
import java.lang.reflect.ParameterizedType;
import java.util.List;
import org.junit.Test;

public class EpisodeDialogJavaContractTest {
    // Compile-time regressions: Java overrides must retain List<Episode>, without wildcard clashes.
    private static class GridSubclass extends EpisodeGridDialog {
        @Override public EpisodeGridDialog episodes(List<Episode> episodes) { return this; }
    }

    private static class ListSubclass extends EpisodeListDialog {
        @Override public EpisodeListDialog episodes(List<Episode> episodes) { return this; }
    }

    @Test
    public void gridEpisodesRetainsJavaGenericParameter() throws Exception {
        assertEpisodeParameter(EpisodeGridDialog.class);
    }

    @Test
    public void listEpisodesRetainsJavaGenericParameter() throws Exception {
        assertEpisodeParameter(EpisodeListDialog.class);
    }

    private static void assertEpisodeParameter(Class<?> type) throws Exception {
        ParameterizedType list = (ParameterizedType) type.getMethod("episodes", List.class)
                .getGenericParameterTypes()[0];
        assertEquals(Episode.class, list.getActualTypeArguments()[0]);
    }
}
