package android.view;

import android.graphics.Point;
import android.util.DisplayMetrics;

/** Linkage-only display. Reports a fixed default size on the desktop host. */
public class Display {
    public static final int DEFAULT_DISPLAY = 0;

    public void getMetrics(DisplayMetrics outMetrics) {
        outMetrics.widthPixels = 1280;
        outMetrics.heightPixels = 720;
        outMetrics.density = 1.0f;
        outMetrics.densityDpi = 160;
        outMetrics.scaledDensity = 1.0f;
    }

    public void getSize(Point outSize) {
        outSize.x = 1280;
        outSize.y = 720;
    }

    public void getRealSize(Point outSize) {
        outSize.x = 1280;
        outSize.y = 720;
    }

    public int getDisplayId() {
        return DEFAULT_DISPLAY;
    }
}
