import android.content.SharedPreferences;
import com.github.catvod.utils.Prefers;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.ToNumberPolicy;
import com.wuming.tv.core.library.DatabaseUpgradePolicy;
import com.wuming.tv.core.playback.PlaybackTransportPlan;
import java.io.File;
import java.lang.reflect.Proxy;
import java.util.*;

/** Read-only application-code probes; preferences use a typed, in-memory test double. */
public class DeepReviewProbe {
    public static void main(String[] args) throws Exception {
        if (args[0].equals("wal")) {
            Map<String, Object> result = new LinkedHashMap<>();
            try {
                DatabaseUpgradePolicy.requireSupported(new File(args[1]), 30, 46);
                result.put("preflight", "accepted");
            } catch (DatabaseUpgradePolicy.UnsupportedVersion failure) {
                result.put("preflight", "recovery");
                result.put("reported_version", failure.getVersion());
            }
            System.out.println(new Gson().toJson(result));
        } else if (args[0].equals("transport")) {
            List<Object> results = new ArrayList<>();
            for (String url : List.of("http://127.0.0.1:9978/proxy?do=m3u8",
                    "http://[::1]:9978/proxy?do=m3u8", "http://[0:0:0:0:0:0:0:1]:9978/proxy?do=m3u8",
                    "http://127.0.0.2:9978/proxy?do=m3u8", "http://player.localhost:9978/proxy?do=m3u8")) {
                var plan = PlaybackTransportPlan.parse(url, null, Map.of());
                results.add(Map.of("url", url, "kind", plan.kind().name(), "cache", plan.cachePolicy().name()));
            }
            System.out.println(new Gson().toJson(results));
        } else if (args[0].equals("preferences")) {
            Map<String, Object> values = new HashMap<>();
            var loader = DeepReviewProbe.class.getClassLoader();
            var editor = (SharedPreferences.Editor) Proxy.newProxyInstance(loader, new Class<?>[]{SharedPreferences.Editor.class},
                (proxy, method, parameters) -> {
                    if (method.getName().startsWith("put")) { values.put((String) parameters[0], parameters[1]); return proxy; }
                    if (method.getName().equals("apply")) return null;
                    throw new AssertionError(method.getName());
                });
            var preferences = (SharedPreferences) Proxy.newProxyInstance(loader, new Class<?>[]{SharedPreferences.class},
                (proxy, method, parameters) -> switch (method.getName()) {
                    case "edit" -> editor;
                    case "getLong" -> (Long) values.getOrDefault(parameters[0], parameters[1]);
                    default -> throw new AssertionError(method.getName());
                });
            var field = Prefers.class.getDeclaredField("instance");
            field.setAccessible(true);
            Object previous = field.get(null);
            try {
                field.set(null, preferences);
                Prefers.put("danmaku_duration", 12000L);
                long before = Prefers.getLong("danmaku_duration", 8000L);
                String json = new Gson().toJson(Map.of("danmaku_duration", values.get("danmaku_duration")));
                Map<?, ?> restored = new GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LAZILY_PARSED_NUMBER)
                        .create().fromJson(json, Map.class);
                Prefers.put("danmaku_duration", restored.get("danmaku_duration"));
                System.out.println(new Gson().toJson(Map.of("before_ms", before,
                        "serialized", json, "restored_storage_type", values.get("danmaku_duration").getClass().getName(),
                        "after_ms", Prefers.getLong("danmaku_duration", 8000L), "fixture", "typed SharedPreferences test double")));
            } finally { field.set(null, previous); }
        } else throw new IllegalArgumentException("Unknown probe");
    }
}
