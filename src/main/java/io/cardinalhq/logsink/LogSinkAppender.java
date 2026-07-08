package io.cardinalhq.logsink;

import io.cardinalhq.logsink.bridge.GcJfrOtelBridge;
import io.cardinalhq.logsink.bridge.StdStreamsOtelBridge;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.SeverityNumber;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.*;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginElement;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.apache.logging.log4j.util.ReadOnlyStringMap;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Plugin(
        name = "LogSink", // <LogSink .../> in log4j2.xml
        category = Core.CATEGORY_NAME,
        elementType = Appender.ELEMENT_TYPE,
        printObject = true
)
public final class LogSinkAppender extends AbstractAppender {

    // ---- Context keys provided by ContextDataProvider / MDC ----
    private static final String CTX_ENDPOINT  = "OTEL_EXPORTER_OTLP_ENDPOINT"; // e.g. https://otel:4318/v1/logs
    private static final String CTX_SERVICE   = "OTEL_SERVICE_NAME";           // e.g. payments
    private static final String CTX_RES_ATTRS = "OTEL_RESOURCE_ATTRIBUTES";    // e.g. service.namespace=checkout,team=core

    // ---- Minimal configuration knobs ----
    private final int queueSize;
    private final int maxBatchSize;
    private final boolean enableGc;
    private final boolean enableStdStreams;

    // ---- Internal ----
    private volatile LogSink sink;                 // created lazily on first event with endpoint
    private volatile boolean bridgesInstalled;     // ensure bridges only once
    private GcJfrOtelBridge jfrBridge;

    private static final AtomicBoolean STD_BRIDGE_INSTALLED = new AtomicBoolean(false);

    private LogSinkAppender(
            String name,
            Filter filter,
            Layout<? extends Serializable> layout,
            boolean ignoreExceptions,
            int queueSize,
            int maxBatchSize,
            boolean enableGc,
            boolean enableStdStreams
    ) {
        super(name, filter, layout, ignoreExceptions, Property.EMPTY_ARRAY);
        this.queueSize = queueSize > 0 ? queueSize : 1000;
        this.maxBatchSize = maxBatchSize > 0 ? maxBatchSize : 100;
        this.enableGc = enableGc;
        this.enableStdStreams = enableStdStreams;
    }

    @PluginFactory
    public static LogSinkAppender createAppender(
            @PluginAttribute("name") String name,
            @PluginAttribute(value = "queueSize", defaultInt = 1000) int queueSize,
            @PluginAttribute(value = "maxBatchSize", defaultInt = 100) int maxBatchSize,
            @PluginAttribute(value = "enableGC", defaultBoolean = false) boolean enableGc,
            @PluginAttribute(value = "enableStdStreams", defaultBoolean = false) boolean enableStdStreams,
            @PluginElement("Filter") Filter filter,
            @PluginElement("Layout") Layout<? extends Serializable> layout
    ) {
        if (name == null || name.isBlank()) {
            LOGGER.error("LogSinkAppender: 'name' is required");
            return null;
        }
        if (layout == null) {
            layout = PatternLayout.newBuilder().withPattern("%m%n").build();
        }
        return new LogSinkAppender(name, filter, layout, true, queueSize, maxBatchSize, enableGc, enableStdStreams);
    }

    @Override
    public void start() {
        // No sink yet; we’ll build it on the first event that has an endpoint in context
        super.start();
    }

    @Override
    public void append(LogEvent event) {
        if (!isStarted()) return;

        // Once the sink exists, every event is sent unconditionally — no per-event
        // context lookups, so app logs cannot silently stop while bridges keep running.
        LogSink s = sink;
        if (s == null) {
            s = initSink(event);
            if (s == null) {
                // No endpoint configured anywhere yet => LogSink disabled. Quietly skip.
                return;
            }
        }

        // Build and send the record (unchanged)
        long timeUnixNanos = event.getTimeMillis() * 1_000_000L;
        SeverityNumber sev = mapSeverity(event.getLevel());

        List<KeyValue> attrs = new ArrayList<>(8);
        attrs.add(kv("stream", "app"));
        attrs.add(kv("log4j.logger", safe(event.getLoggerName())));
        attrs.add(kv("log4j.thread", safe(event.getThreadName())));
        attrs.add(kv("log4j.level", event.getLevel().name()));


        Throwable thrown = event.getThrown();
        if (thrown != null) {
            attrs.add(kv("exception.type", thrown.getClass().getName()));
            attrs.add(kv("exception.message", thrown.getMessage() == null ? "" : thrown.getMessage()));
            attrs.add(kv("exception.stacktrace", stackToString(thrown)));
        }

        String msg = (event.getMessage() == null) ? "" : event.getMessage().getFormattedMessage();

        LogRecord protoRecord = LogRecord.newBuilder()
                .setTimeUnixNano(timeUnixNanos)
                .setObservedTimeUnixNano(timeUnixNanos)
                .setSeverityNumber(sev)
                .setSeverityText(event.getLevel().name())
                .setBody(AnyValue.newBuilder().setStringValue(msg).build())
                .addAllAttributes(attrs)
                .build();

        s.log(protoRecord);
    }

    /**
     * Creates the sink on the first event for which an endpoint can be resolved.
     * Config is resolved once, preferring event context (ContextDataProvider/MDC)
     * and falling back to system properties and environment variables, so a broken
     * or non-propagating MDC cannot disable the appender.
     */
    private LogSink initSink(LogEvent event) {
        synchronized (this) {
            if (sink != null) return sink;

            ReadOnlyStringMap ctx = event.getContextData();
            final String endpoint = firstNonBlank(
                    getCtx(ctx, CTX_ENDPOINT),
                    System.getProperty("otel.exporter.otlp.endpoint"),
                    System.getenv(CTX_ENDPOINT));
            if (endpoint == null) return null;

            final String serviceName = orDefault(firstNonBlank(
                    getCtx(ctx, CTX_SERVICE),
                    System.getProperty("otel.service.name"),
                    System.getenv(CTX_SERVICE)), "unknown_service:log4j2");
            final String resStr = firstNonBlank(
                    getCtx(ctx, CTX_RES_ATTRS),
                    System.getProperty("otel.resource.attributes"),
                    System.getenv(CTX_RES_ATTRS));
            final Map<String, String> resAttrs = parseOtelResourceAttributes(resStr);
            resAttrs.putIfAbsent("service.name", serviceName);

            LogSinkConfig.Builder b = LogSinkConfig.builder()
                    .setOtlpEndpoint(endpoint)
                    .setAppName(serviceName)
                    .setQueueSize(queueSize)
                    .setMaxBatchSize(maxBatchSize)
                    .addResourceAttributes(resAttrs);

            this.sink = new LogSink(b.build());

            if (!bridgesInstalled) {
                installBridgesOnce();
                bridgesInstalled = true;
            }
            return sink;
        }
    }

    private void installBridgesOnce() {
        if (enableStdStreams && STD_BRIDGE_INSTALLED.compareAndSet(false, true)) {
            try { StdStreamsOtelBridge.install(this.sink); } catch (Throwable ignore) {}
        }
        if (enableGc && jfrBridge == null) {
            try { this.jfrBridge = GcJfrOtelBridge.start(LOGGER, this.sink); } catch (Throwable t) { this.jfrBridge = null; }
        }
    }

    @Override
    public boolean stop(long timeout, TimeUnit timeUnit) {
        try {
            if (jfrBridge != null) { jfrBridge.close(); jfrBridge = null; }
        } catch (Throwable ignore) {}
        boolean res = super.stop(timeout, timeUnit);
        try {
            if (sink != null) {
                sink.flush();
                sink.shutdown();
            }
        } catch (Throwable ignore) {}
        return res;
    }

    // ---------- helpers ----------

    private static String getCtx(ReadOnlyStringMap ctx, String key) {
        if (ctx == null || key == null) return null;
        Object v = ctx.getValue(key);
        return v == null ? null : v.toString();
    }

    private static Map<String, String> parseOtelResourceAttributes(String s) {
        Map<String, String> out = new LinkedHashMap<>();
        if (s == null || s.isBlank()) return out;
        for (String pair : s.split("\\s*,\\s*")) {
            if (pair.isBlank()) continue;
            int eq = pair.indexOf('=');
            if (eq <= 0 || eq == pair.length() - 1) continue;
            String k = pair.substring(0, eq).trim();
            String v = pair.substring(eq + 1).trim();
            if (!k.isEmpty()) out.put(k, v);
        }
        return out;
    }

    private static String trim(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            String t = trim(v);
            if (t != null) return t;
        }
        return null;
    }

    private static String orDefault(String s, String def) {
        return (s == null || s.isEmpty()) ? def : s;
    }

    private static SeverityNumber mapSeverity(Level level) {
        if (level == null) return SeverityNumber.SEVERITY_NUMBER_INFO;
        if (level == Level.TRACE) return SeverityNumber.SEVERITY_NUMBER_TRACE;
        if (level == Level.DEBUG) return SeverityNumber.SEVERITY_NUMBER_DEBUG;
        if (level == Level.INFO) return SeverityNumber.SEVERITY_NUMBER_INFO;
        if (level == Level.WARN) return SeverityNumber.SEVERITY_NUMBER_WARN;
        if (level == Level.ERROR) return SeverityNumber.SEVERITY_NUMBER_ERROR;
        if (level == Level.FATAL) return SeverityNumber.SEVERITY_NUMBER_FATAL;
        return SeverityNumber.SEVERITY_NUMBER_INFO;
    }

    private static KeyValue kv(String k, String v) {
        return KeyValue.newBuilder()
                .setKey(k)
                .setValue(AnyValue.newBuilder().setStringValue(v == null ? "" : v).build())
                .build();
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private static String stackToString(Throwable t) {
        StringBuilder sb = new StringBuilder();
        sb.append(t).append('\n');
        for (StackTraceElement ste : t.getStackTrace()) {
            sb.append("\tat ").append(ste).append('\n');
        }
        Throwable c = t.getCause();
        while (c != null && c != t) {
            sb.append("Caused by: ").append(c).append('\n');
            for (StackTraceElement ste : c.getStackTrace()) {
                sb.append("\tat ").append(ste).append('\n');
            }
            c = c.getCause();
        }
        return sb.toString();
    }
}