package com.wuming.android.tv.bean;

import static org.junit.Assert.*;

import com.wuming.android.tv.R;
import org.junit.Test;

public class FuncTest {
    @Test
    public void siteRenameChangesContentWithoutChangingIdentity() {
        Func before = Func.createSite("Before");
        Func after = Func.createSite("After");
        assertTrue(before.isSameItem(after));
        assertEquals(before.hashCode(), after.hashCode());
        assertFalse(before.isSameContent(after));
        after.setSiteName("Before");
        assertTrue(before.isSameContent(after));
    }

    @Test
    public void unnamedSiteDoesNotReportMatchingContent() {
        Func first = Func.createSite(null);
        Func second = Func.createSite(null);
        assertTrue(first.isSameItem(second));
        assertFalse(first.isSameContent(second));
        assertNull(first.getText());
    }

    @Test
    public void ordinaryActionsKeepResourceIdentityAndIcon() {
        Func first = Func.create(R.string.home_search);
        Func second = new Func(R.string.home_search);
        assertEquals(first, second);
        assertTrue(first.isSameContent(second));
        assertFalse(first.isSite());
        assertEquals(R.drawable.ic_home_search, first.getDrawable());
        assertNotEquals(first, Func.create(R.string.home_keep));
    }
}
