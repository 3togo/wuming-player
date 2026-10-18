package com.wuming.tv.core.playback.hls;

import java.util.List;

/**
 * JAVA-RETENTION: hls-nullable-varargs (docs/java-retention.json).
 * Keep a nullable array AND JVM varargs entry point; ordinary Kotlin vararg rejects a null array.
 * All normalization and transformation behavior belongs in HlsPipelineEngine.kt.
 */
public final class HlsPipeline {
    public static final Companion Companion = new Companion();
    private final HlsPipelineEngine engine;

    public HlsPipeline(List<? extends HlsTransform> transforms) {
        this(new HlsPipelineEngine(transforms));
    }

    private HlsPipeline(HlsPipelineEngine engine) {
        this.engine = engine;
    }

    public final HlsTransformationResult transform(String content, HlsTransformContext context) {
        return engine.transform(content, context);
    }

    public static final HlsPipeline of(HlsTransform... transforms) {
        return new HlsPipeline(HlsPipelineEngine.fromArray(transforms));
    }

    /** Retains the companion entry point emitted by the previous Kotlin facade. */
    public static final class Companion {
        private Companion() {}

        public final HlsPipeline of(HlsTransform... transforms) {
            return HlsPipeline.of(transforms);
        }
    }
}
