package io.cardinalhq.logsink;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.TracerProvider;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Process-wide access to the application's OpenTelemetry tracer provider. */
public final class Tracing {
    private static final String DEFAULT_SCOPE = "io.cardinalhq.logsink";
    private static final AtomicReference<TracerProvider> installedProvider = new AtomicReference<>();

    private Tracing() {
    }

    /**
     * Creates and installs a {@link TraceSink}. A previously initialized
     * TraceSink is closed; externally supplied providers remain caller-owned.
     */
    public static synchronized TraceSink initialize(LogSinkConfig config) {
        TraceSink traceSink = new TraceSink(config);
        TracerProvider previous = installedProvider.getAndSet(traceSink);
        if (previous instanceof TraceSink) {
            ((TraceSink) previous).close();
        }
        return traceSink;
    }

    /**
     * Installs a provider for static lookup and returns the prior override, or
     * {@code null} when lookup previously fell back to GlobalOpenTelemetry.
     * The provider remains caller-owned.
     */
    public static TracerProvider install(TracerProvider provider) {
        return installedProvider.getAndSet(Objects.requireNonNull(provider, "provider"));
    }

    /** Removes and returns the installed override without shutting it down. */
    public static TracerProvider clear() {
        return installedProvider.getAndSet(null);
    }

    /** Returns the installed provider, falling back to standard global OpenTelemetry. */
    public static TracerProvider getTracerProvider() {
        TracerProvider provider = installedProvider.get();
        return provider == null ? GlobalOpenTelemetry.getTracerProvider() : provider;
    }

    /** Returns a tracer with the default logsink instrumentation scope. */
    public static Tracer getTracer() {
        return getTracer(DEFAULT_SCOPE);
    }

    /** Returns a tracer named for the supplied class. */
    public static Tracer getTracer(Class<?> owner) {
        return getTracer(Objects.requireNonNull(owner, "owner").getName());
    }

    /** Returns a tracer for a named instrumentation scope. */
    public static Tracer getTracer(String instrumentationScopeName) {
        if (instrumentationScopeName == null || instrumentationScopeName.isBlank()) {
            throw new IllegalArgumentException("instrumentationScopeName must not be blank");
        }
        return getTracerProvider().get(instrumentationScopeName);
    }

    /** Closes an installed TraceSink and restores GlobalOpenTelemetry fallback. */
    public static synchronized void shutdown() {
        TracerProvider provider = installedProvider.getAndSet(null);
        if (provider instanceof TraceSink) {
            ((TraceSink) provider).close();
        }
    }
}
