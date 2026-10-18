package android.text.format;

import android.content.Context;

/** Linkage-only formatter. */
public class Formatter {
    public static String formatFileSize(Context context, long sizeBytes) {
        return String.valueOf(sizeBytes);
    }
}
