package android.widget;

import android.view.ViewGroup;

/** Linkage-only container type. */
public class LinearLayout extends ViewGroup {
    public static class LayoutParams extends ViewGroup.MarginLayoutParams {
        public float weight;

        public LayoutParams(int width, int height) {
            super(width, height);
        }
    }
}
