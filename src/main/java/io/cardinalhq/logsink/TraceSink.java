package io.cardinalhq.logsink;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.TracerProvider;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporterBuilder;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An injectable OpenTelemetry tracer provider that exports spans over OTLP/HTTP.
 *
 * <p>Applications can inject this class as a {@link TracerProvider}, or obtain
 * named tracers from it directly. Call {@link #close()} during application
 * shutdown so queued spans are flushed.</p>
 */
public final class TraceSink implements TracerProvider, AutoCloseable {
    private static final Duration EXPORT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration SCHEDULE_DELAY = Duration.ofSeconds(1);
    private static final long CLOSE_TIMEOUT_SECONDS = 15;

    private final SdkTracerProvider tracerProvider;
    private final AtomicBoolean closed = new AtomicBoolean();

    /** Creates a provider that exports to the traces endpoint in {@code config}. */
    public TraceSink(LogSinkConfig config) {
        this(config, createExporter(config));
    }

    TraceSink(LogSinkConfig config, SpanExporter exporter) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(exporter, "exporter");

        int queueSize = Math.max(1, config.getQueueSize());
        int batchSize = Math.min(queueSize, Math.max(1, config.getMaxBatchSize()));
        BatchSpanProcessor processor = BatchSpanProcessor.builder(exporter)
                .setMaxQueueSize(queueSize)
                .setMaxExportBatchSize(batchSize)
                .setScheduleDelay(SCHEDULE_DELAY)
                .build();

        this.tracerProvider = SdkTracerProvider.builder()
                .setResource(toSdkResource(config))
                .addSpanProcessor(processor)
                .build();
    }

    @Override
    public Tracer get(String instrumentationScopeName) {
        return tracerProvider.get(requireScopeName(instrumentationScopeName));
    }

    @Override
    public Tracer get(String instrumentationScopeName, String instrumentationScopeVersion) {
        return tracerProvider.get(
                requireScopeName(instrumentationScopeName),
                instrumentationScopeVersion);
    }

    /** Returns a tracer named for the supplied class. */
    public Tracer getTracer(Class<?> owner) {
        return get(Objects.requireNonNull(owner, "owner").getName());
    }

    /** Returns a tracer for a named instrumentation scope. */
    public Tracer getTracer(String instrumentationScopeName) {
        return get(instrumentationScopeName);
    }

    /** Flushes all spans that have ended so far. */
    public void flush() {
        tracerProvider.forceFlush().join(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /** Flushes queued spans and releases exporter resources. */
    public void shutdown() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        tracerProvider.shutdown().join(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        shutdown();
    }

    private static SpanExporter createExporter(LogSinkConfig config) {
        Objects.requireNonNull(config, "config");
        OtlpHttpSpanExporterBuilder builder = OtlpHttpSpanExporter.builder()
                .setEndpoint(config.getTracesEndpoint())
                .setCompression("gzip")
                .setTimeout(EXPORT_TIMEOUT);
        if (config.getApiKey() != null && !config.getApiKey().isBlank()) {
            builder.addHeader("x-cardinalhq-api-key", config.getApiKey());
        }
        return builder.build();
    }

    private static Resource toSdkResource(LogSinkConfig config) {
        AttributesBuilder attributes = Attributes.builder();
        for (KeyValue attribute : config.getResource().getAttributesList()) {
            if (attribute.hasValue() && attribute.getValue().hasStringValue()) {
                attributes.put(AttributeKey.stringKey(attribute.getKey()), attribute.getValue().getStringValue());
            }
        }
        return Resource.create(attributes.build());
    }

    private static String requireScopeName(String scopeName) {
        if (scopeName == null || scopeName.isBlank()) {
            throw new IllegalArgumentException("instrumentationScopeName must not be blank");
        }
        return scopeName;
    }
}
