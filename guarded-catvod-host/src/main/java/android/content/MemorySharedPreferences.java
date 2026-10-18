package android.content;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class MemorySharedPreferences implements SharedPreferences {
    private final Map<String, Object> values = new ConcurrentHashMap<>();

    public Map<String, ?> getAll() { return Map.copyOf(values); }
    public String getString(String key, String fallback) { return value(key, String.class, fallback); }
    @SuppressWarnings("unchecked")
    public Set<String> getStringSet(String key, Set<String> fallback) {
        Object value = values.get(key);
        return value instanceof Set<?> ? new HashSet<>((Set<String>) value) : fallback;
    }
    public int getInt(String key, int fallback) { return value(key, Integer.class, fallback); }
    public long getLong(String key, long fallback) { return value(key, Long.class, fallback); }
    public float getFloat(String key, float fallback) { return value(key, Float.class, fallback); }
    public boolean getBoolean(String key, boolean fallback) { return value(key, Boolean.class, fallback); }
    public boolean contains(String key) { return values.containsKey(key); }
    public Editor edit() { return new MemoryEditor(); }

    private <T> T value(String key, Class<T> type, T fallback) {
        Object value = values.get(key);
        return type.isInstance(value) ? type.cast(value) : fallback;
    }

    private final class MemoryEditor implements Editor {
        private final Map<String, Object> updates = new HashMap<>();
        private final Set<String> removals = new HashSet<>();
        private boolean clear;
        public Editor putString(String key, String value) { return put(key, value); }
        public Editor putStringSet(String key, Set<String> value) { return put(key, new HashSet<>(value)); }
        public Editor putInt(String key, int value) { return put(key, value); }
        public Editor putLong(String key, long value) { return put(key, value); }
        public Editor putFloat(String key, float value) { return put(key, value); }
        public Editor putBoolean(String key, boolean value) { return put(key, value); }
        public Editor remove(String key) { removals.add(key); updates.remove(key); return this; }
        public Editor clear() { clear = true; return this; }
        public boolean commit() { apply(); return true; }
        public void apply() {
            if (clear) values.clear();
            removals.forEach(values::remove);
            values.putAll(updates);
        }
        private Editor put(String key, Object value) { updates.put(key, value); removals.remove(key); return this; }
    }
}
