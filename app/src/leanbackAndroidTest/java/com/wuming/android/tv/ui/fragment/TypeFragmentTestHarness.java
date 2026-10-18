package com.wuming.android.tv.ui.fragment;

import androidx.leanback.widget.ArrayObjectAdapter;

import com.wuming.android.tv.bean.Result;

import java.lang.reflect.Field;

/** Package-level bridge for feeding deterministic provider fixtures into a real fragment. */
public final class TypeFragmentTestHarness {

    private TypeFragmentTestHarness() {
    }

    public static void deliver(LeanbackTypeFragment fragment, Result result) {
        fragment.addVideo(result);
    }

    public static ArrayObjectAdapter adapter(LeanbackTypeFragment fragment) {
        try {
            Field field = LeanbackTypeFragment.class.getDeclaredField("mAdapter");
            field.setAccessible(true);
            return (ArrayObjectAdapter) field.get(fragment);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }
}
