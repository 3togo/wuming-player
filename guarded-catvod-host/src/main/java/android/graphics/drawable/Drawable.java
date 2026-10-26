package android.graphics.drawable;

import android.content.res.Resources;

/** Linkage-only base type; drawing is unsupported in the guarded desktop host. */
public abstract class Drawable {
    public ConstantState getConstantState() {
        return null;
    }

    /** Android ABI used by provider method signatures even during non-UI playback. */
    public abstract static class ConstantState {
        public abstract Drawable newDrawable();

        public Drawable newDrawable(Resources resources) {
            return newDrawable();
        }

        public abstract int getChangingConfigurations();
    }
}
