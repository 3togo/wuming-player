package com.wuming.android.tv.server.process;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Map;

// JAVA-RETENTION: cast-remux-key-record
/** JVM facade retaining the normalized record; cache and lease ownership live in Kotlin. */
final class CastRemuxCache {
    record Key(String url, Map<String, String> headers) {
        Key { headers = Map.copyOf(headers); }
        @Override public String toString() { return "Cast asset (credentials omitted)"; }
    }

    interface Builder {
        /** Transfers ownership of a newly created complete file, or returns null for fallback. */
        File build() throws Exception;
    }

    private final CastRemuxCacheEngine engine;

    CastRemuxCache(long byteLimit, int entryLimit) {
        engine = new CastRemuxCacheEngine(byteLimit, entryLimit);
    }

    Lease open(Key key, Builder builder) throws IOException {
        return engine.open(key, builder);
    }

    static final class Lease extends CastRemuxLease {
        Lease(FileInputStream input, long length, Runnable release) {
            super(input, length, release);
        }
    }
}
