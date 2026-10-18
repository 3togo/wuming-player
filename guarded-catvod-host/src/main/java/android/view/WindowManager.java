package android.view;

/** Linkage-only window manager. */
public class WindowManager {
    public static final class LayoutParams {
        public static final int FLAG_FULLSCREEN = 0x00000400;
        public static final int FLAG_KEEP_SCREEN_ON = 0x00000080;
        public int width;
        public int height;
        public int flags;
    }

    public Display getDefaultDisplay() {
        return new Display();
    }
}
