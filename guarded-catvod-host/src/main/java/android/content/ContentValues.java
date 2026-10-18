package android.content;

import java.util.HashMap;
import java.util.Map;

/** Value holder used by the Android SQLite insert signature. */
public final class ContentValues {
    private final Map<String, Object> values = new HashMap<>();

    public ContentValues() {
    }

    public void put(String key, String value) {
        values.put(key, value);
    }

    public void put(String key, Integer value) {
        values.put(key, value);
    }

    public void put(String key, Long value) {
        values.put(key, value);
    }

    public Object get(String key) {
        return values.get(key);
    }

    public int size() {
        return values.size();
    }
}
