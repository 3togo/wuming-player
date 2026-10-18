package android.text;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public class TextUtilsTest {

    @Test
    public void joinsObjectArraysWithAndroidCompatibleEmptyAndNullHandling() {
        assertEquals("", TextUtils.join(",", new Object[0]));
        assertEquals("first||null", TextUtils.join("|", new Object[]{"first", "", null}));
    }

    @Test
    public void iterableOverloadPreservesEmptyLeadingValues() {
        assertEquals("", TextUtils.join(",", Collections.emptyList()));
        assertEquals("|second|null", TextUtils.join("|", Arrays.asList("", "second", null)));
    }
}
