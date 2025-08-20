package com.cardinal.logsink;

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
import java.util.regex.Pattern;

@Plugin(
        name = "LogSink",                 // use <LogSink .../> in log4j2.xml
        category = Core.CATEGORY_NAME,
        elementType = Appender.ELEMENT_TYPE,
        printObject = true
)
public final class LogSinkAppender extends AbstractAppender {

    // --- Configurable plugin attributes ---
    private final String otlpEndpoint;            // e.g. http://collector:4318/v1/logs
    private final String appName;                 // service.name
    private final String envKeysCsv;              // "POD_NAME,NAMESPACE,CLUSTER_NAME"
    private final String envPrefixCsv;            // "OTEL_,CARDINAL_"
    private final String envExcludePattern;       // regex to skip secrets
    private final int queueSize;
    private final int maxBatchSize;

    // --- Internal ---
    private volatile LogSink sink;

    private LogSinkAppender(
            String name,
            Filter filter,
            Layout<? extends Serializable> layout,
            boolean ignoreExceptions,
            String otlpEndpoint,
            String appName,
            String envKeysCsv,
            String envPrefixCsv,
            String envExcludePattern,
            int queueSize,
            int maxBatchSize
    ) {
        super(name, filter, layout, ignoreExceptions, Property.EMPTY_ARRAY);
        this.otlpEndpoint = otlpEndpoint;
        this.appName = appName;
        this.envKeysCsv = envKeysCsv;
        this.envPrefixCsv = envPrefixCsv;
        this.envExcludePattern = (envExcludePattern == null || envExcludePattern.isBlank())
                ? "(?i).*(SECRET|TOKEN|PASSWORD|PWD|PRIVATE|CREDENTIAL|ACCESS_KEY|API_KEY).*"
                : envExcludePattern;
        this.queueSize = queueSize > 0 ? queueSize : 1000;
        this.maxBatchSize = maxBatchSize > 0 ? maxBatchSize : 100;
    }

    @PluginFactory
    public static LogSinkAppender createAppender(
            @PluginAttribute("name") String name,
            @PluginAttribute("otlpEndpoint") String otlpEndpoint,
            @PluginAttribute("appName") String appName,
            @PluginAttribute("envKeys") String envKeysCsv,
            @PluginAttribute("envPrefix") String envPrefixCsv,
            @PluginAttribute("envExcludePattern") String envExcludePattern,
            @PluginAttribute(value = "queueSize", defaultInt = 1000) int queueSize,
            @PluginAttribute(value = "maxBatchSize", defaultInt = 100) int maxBatchSize,
            @PluginElement("Filter") Filter filter,
            @PluginElement("Layout") Layout<? extends Serializable> layout
    ) {
        if (name == null || name.isBlank()) {
            LOGGER.error("LogSinkAppender: 'name' is required");
            return null;
        }
        if (otlpEndpoint == null || otlpEndpoint.isBlank()) {
            LOGGER.error("LogSinkAppender: 'otlpEndpoint' is required");
            return null;
        }
        // Fall back to OTEL_SERVICE_NAME or a sane default
        String finalAppName = (appName != null && !appName.isBlank())
                ? appName
                : System.getenv().getOrDefault("OTEL_SERVICE_NAME", "unknown_service:log4j2");

        if (layout == null) {
            layout = PatternLayout.newBuilder().withPattern("%m%n").build();
        }

        return new LogSinkAppender(
                name, filter, layout, true,
                otlpEndpoint, finalAppName,
                envKeysCsv, envPrefixCsv, envExcludePattern,
                queueSize, maxBatchSize
        );
    }

    @Override
    public void start() {
        super.start();

        // Build Resource once at initialization
        Map<String, String> resMap = new LinkedHashMap<>(collectEnvAttributes(envKeysCsv, envPrefixCsv, envExcludePattern));


        LogSinkConfig config = LogSinkConfig.builder()
                .setOtlpEndpoint(otlpEndpoint)
                .setAppName(appName)
                .setQueueSize(queueSize)
                .setMaxBatchSize(maxBatchSize)
                .addResourceAttributes(resMap)
                .build();

        this.sink = new LogSink(config);
    }

    @Override
    public void append(LogEvent event) {
        if (sink == null || !isStarted()) return;

        long timeUnixNanos = event.getTimeMillis() * 1_000_000L; // convert ms → ns

        SeverityNumber sev = mapSeverity(event.getLevel());

        List<KeyValue> attrs = new ArrayList<>(8);
        attrs.add(kv("log4j.logger", safe(event.getLoggerName())));
        attrs.add(kv("log4j.thread", safe(event.getThreadName())));
        attrs.add(kv("log4j.level", event.getLevel().name()));

        // MDC
        ReadOnlyStringMap mdc = event.getContextData();
        if (mdc != null) {
            mdc.forEach((k, v) -> {
                if (k != null && v != null) {
                    attrs.add(kv("mdc." + k, String.valueOf(v)));
                }
            });
        }

        // Throwable
        // Exception details (no ThrowableProxy)
        Throwable t = event.getThrown();
        if (t != null) {
            attrs.add(kv("exception.type", t.getClass().getName()));
            attrs.add(kv("exception.message", t.getMessage() == null ? "" : t.getMessage()));
            attrs.add(kv("exception.stacktrace", stackToString(t)));
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

        // Best-effort enqueue; drop if queue is full.
        sink.log(protoRecord);
    }

    @Override
    public boolean stop(long timeout, TimeUnit timeUnit) {
        boolean res = super.stop(timeout, timeUnit);
        try {
            if (sink != null) {
                sink.flush();
                sink.shutdown();
            }
        } catch (Throwable ignore) {
            // avoid recursive logging
        }
        return res;
    }

    // ---------- helpers ----------

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

    private static String safe(String s) {
        return s == null ? "" : s;
    }

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

    private static Map<String, String> collectEnvAttributes(String keysCsv, String prefixesCsv, String excludeRegex) {
        Map<String, String> out = new LinkedHashMap<>();
        Set<String> keys = csvToSet(keysCsv);
        Set<String> prefixes = csvToSet(prefixesCsv);
        Pattern deny = (excludeRegex == null || excludeRegex.isBlank()) ? null : Pattern.compile(excludeRegex);

        System.getenv().forEach((k, v) -> {
            if (v == null) return;
            boolean include = (!keys.isEmpty() && keys.contains(k));
            if (!include && !prefixes.isEmpty()) {
                for (String p : prefixes) {
                    if (k.startsWith(p)) {
                        include = true;
                        break;
                    }
                }
            }
            if (!include) return;
            if (deny != null && deny.matcher(k).matches()) return;

            // put as resource attr under "env.<lowercased_key>"
            String attrKey = "env." + sanitizeKey(k);
            out.put(attrKey, v);
        });
        return out;
    }

    private static Set<String> csvToSet(String csv) {
        if (csv == null || csv.isBlank()) return Collections.emptySet();
        Set<String> s = new LinkedHashSet<>();
        for (String t : csv.split(",")) {
            String x = t.trim();
            if (!x.isEmpty()) s.add(x);
        }
        return s;
    }

    private static String sanitizeKey(String raw) {
        String lower = raw.toLowerCase(Locale.ROOT);
        return lower.replaceAll("[^a-z0-9_.-]", "_");
    }
}