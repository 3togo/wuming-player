package android.util;

/** Linkage-only typed value. */
public class TypedValue {
    public static final int TYPE_NULL = 0;
    public static final int TYPE_REFERENCE = 1;
    public static final int TYPE_ATTRIBUTE = 2;
    public static final int TYPE_STRING = 4;
    public static final int TYPE_INT = 16;

    public int type;
    public int data;
    public float floatData;
    public CharSequence string;
    public int resourceId;
}
