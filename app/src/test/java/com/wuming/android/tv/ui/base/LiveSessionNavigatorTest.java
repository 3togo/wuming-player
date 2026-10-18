package com.wuming.android.tv.ui.base;

import static org.junit.Assert.assertEquals;

import com.wuming.android.tv.bean.Channel;
import com.wuming.android.tv.bean.Group;

import org.junit.Test;

import java.util.List;

public class LiveSessionNavigatorTest {

    @Test
    public void wrapsWithinGroupWhenCrossGroupNavigationIsDisabled() {
        Group group = group("News", "One", "Two");
        assertEquals(new LiveSessionNavigator.Selection(0, 1),
                LiveSessionNavigator.move(List.of(group), 0, 0, -1, false));
    }

    @Test
    public void crossesGroupsAndSkipsKeepGroup() {
        Group first = group("News", "One", "Two");
        Group keep = group("Keep", "Saved");
        // Keep groups are identified by their localized display name; overriding skip keeps this
        // unit test independent from Android resources.
        Group skipped = new Group("Skipped") {
            @Override
            public boolean skip() {
                return true;
            }
        };
        skipped.setChannel(keep.getChannel());
        Group last = group("Sports", "Three", "Four");

        assertEquals(new LiveSessionNavigator.Selection(2, 0),
                LiveSessionNavigator.move(List.of(first, skipped, last), 0, 1, 1, true));
        assertEquals(new LiveSessionNavigator.Selection(0, 1),
                LiveSessionNavigator.move(List.of(first, skipped, last), 2, 0, -1, true));
    }

    private static Group group(String name, String... channels) {
        Group group = new Group(name) {
            @Override
            public boolean skip() {
                return false;
            }
        };
        group.setChannel(java.util.Arrays.stream(channels).map(Channel::create).toList());
        return group;
    }
}
