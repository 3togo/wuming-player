package android.graphics;

import java.io.InputStream;
import java.io.File;

/** Linkage-only bitmap decoder. The desktop provider host does not decode Android bitmaps. */
public class BitmapFactory {
    public static Bitmap decodeFile(String pathName) {
        return null;
    }

    public static Bitmap decodeFile(File file) {
        return null;
    }

    public static Bitmap decodeStream(InputStream is) {
        return null;
    }

    public static Bitmap decodeByteArray(byte[] data, int offset, int length) {
        return null;
    }
}
