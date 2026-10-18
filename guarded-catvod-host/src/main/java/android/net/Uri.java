package android.net;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal functional URI. The guarded desktop host only needs parsing and query-parameter access,
 * which is exercised by some providers on their URL path.
 */
public abstract class Uri {
    private final String scheme;
    private final String authority;
    private final String path;
    private final Map<String, String> query = new LinkedHashMap<>();

    private Uri(String scheme, String authority, String path, Map<String, String> query) {
        this.scheme = scheme;
        this.authority = authority;
        this.path = path;
        this.query.putAll(query);
    }

    public static Uri parse(String uriString) {
        if (uriString == null) return null;
        String scheme = null;
        String rest = uriString;
        int colon = uriString.indexOf(':');
        if (colon > 0 && uriString.indexOf("//") != colon + 1) {
            scheme = uriString.substring(0, colon);
            rest = uriString.substring(colon + 1);
        }
        String authority = null;
        String pathAndQuery = rest;
        if (rest.startsWith("//")) {
            int slash = rest.indexOf('/', 2);
            if (slash < 0) {
                authority = rest.substring(2);
                pathAndQuery = "";
            } else {
                authority = rest.substring(2, slash);
                pathAndQuery = rest.substring(slash);
            }
        }
        String path = pathAndQuery;
        Map<String, String> q = new LinkedHashMap<>();
        int qmark = pathAndQuery.indexOf('?');
        if (qmark >= 0) {
            path = pathAndQuery.substring(0, qmark);
            for (String pair : pathAndQuery.substring(qmark + 1).split("&")) {
                if (pair.isEmpty()) continue;
                int eq = pair.indexOf('=');
                if (eq < 0) q.put(pair, "");
                else q.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
        return new Built(scheme, authority, path, q);
    }

    public String getScheme() {
        return scheme;
    }

    public String getAuthority() {
        return authority;
    }

    public String getHost() {
        if (authority == null) return null;
        int at = authority.indexOf('@');
        String host = at >= 0 ? authority.substring(at + 1) : authority;
        int colon = host.indexOf(':');
        return colon >= 0 ? host.substring(0, colon) : host;
    }

    public String getPath() {
        return path;
    }

    public String getQueryParameter(String key) {
        return query.get(key);
    }

    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (scheme != null) sb.append(scheme).append(':');
        if (authority != null) sb.append("//").append(authority);
        if (path != null) sb.append(path);
        if (!query.isEmpty()) {
            sb.append('?');
            boolean first = true;
            for (Map.Entry<String, String> e : query.entrySet()) {
                if (!first) sb.append('&');
                sb.append(e.getKey()).append('=').append(e.getValue());
                first = false;
            }
        }
        return sb.toString();
    }

    public Builder buildUpon() {
        return new Builder(scheme, authority, path, new LinkedHashMap<>(query));
    }

    public static final class Builder {
        private String scheme;
        private String authority;
        private String path;
        private final Map<String, String> query;

        private Builder(String scheme, String authority, String path, Map<String, String> query) {
            this.scheme = scheme;
            this.authority = authority;
            this.path = path;
            this.query = query;
        }

        public Builder scheme(String scheme) {
            this.scheme = scheme;
            return this;
        }

        public Builder authority(String authority) {
            this.authority = authority;
            return this;
        }

        public Builder path(String path) {
            this.path = path;
            return this;
        }

        public Builder appendQueryParameter(String key, String value) {
            query.put(key, value);
            return this;
        }

        public Uri build() {
            return new Built(scheme, authority, path, query);
        }
    }

    private static final class Built extends Uri {
        private Built(String scheme, String authority, String path, Map<String, String> query) {
            super(scheme, authority, path, query);
        }
    }
}
