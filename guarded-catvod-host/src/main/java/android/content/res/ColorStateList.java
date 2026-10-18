package android.content.res;

/** Linkage-only color state list. Always resolves to the transparent/default color. */
public class ColorStateList {
    public int getColorForState(int[] stateSet, int defaultColor) {
        return defaultColor;
    }

    public int getDefaultColor() {
        return 0;
    }

    public boolean isStateful() {
        return false;
    }
}
