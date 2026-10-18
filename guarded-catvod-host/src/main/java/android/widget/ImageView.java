package android.widget;

import android.view.View;

/** Linkage-only widget type. */
public class ImageView extends View {
    public enum ScaleType {
        CENTER,
        CENTER_CROP,
        CENTER_INSIDE,
        FIT_CENTER,
        FIT_END,
        FIT_START,
        FIT_XY,
        MATRIX,
    }
}
