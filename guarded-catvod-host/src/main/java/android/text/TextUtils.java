package android.text;

import java.util.Iterator;

public final class TextUtils {
    private TextUtils() {
    }

    public static boolean isEmpty(CharSequence value) {
        return value == null || value.length() == 0;
    }

    public static boolean equals(CharSequence left, CharSequence right) {
        if (left == right) return true;
        if (left == null || right == null || left.length() != right.length()) return false;
        for (int index = 0; index < left.length(); index++) {
            if (left.charAt(index) != right.charAt(index)) return false;
        }
        return true;
    }

    public static String join(CharSequence delimiter, Iterable<?> values) {
        Iterator<?> iterator = values.iterator();
        if (!iterator.hasNext()) return "";
        StringBuilder result = new StringBuilder();
        result.append(iterator.next());
        while (iterator.hasNext()) {
            result.append(delimiter);
            result.append(iterator.next());
        }
        return result.toString();
    }

    public static String join(CharSequence delimiter, Object[] values) {
        if (values.length == 0) return "";
        StringBuilder result = new StringBuilder();
        result.append(values[0]);
        for (int index = 1; index < values.length; index++) {
            result.append(delimiter);
            result.append(values[index]);
        }
        return result.toString();
    }
}
