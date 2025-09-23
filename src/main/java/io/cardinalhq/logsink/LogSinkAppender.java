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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Plugin(
        name = "LogSink", // <LogSink .../> in log4j2.xml
        category = Core.CATEGORY_NAME,
        elementType = Appender.ELEMENT_TYPE,
        printObject = true
)
public final class LogSinkAppender extends AbstractAppender {

    // ---- Configurable plugin attributes ----
    private final String otlpEndpoint;      // http://collector:4318/v1/logs
    private final String appName;           // service.name
    private final String envKeysCsv;        // "POD_NAME,NAMESPACE,CLUSTER_NAME"
    private final String envPrefixCsv;      // "OTEL_,CARDINAL_"
    private final String envExcludePattern; // regex to skip secrets
    private final int queueSize;
    private final int maxBatchSize;

    // New: feature toggles
    private final boolean enableGc;
    private final boolean enableStdStreams;

    // ---- Internal ----
    private volatile LogSink sink;
    private GcJfrOtelBridge jfrBridge;

    // Ensure stdout/stderr bridge is installed once per JVM even if Log4j reconfigures.
    private static final AtomicBoolean STD_BRIDGE_INSTALLED = new AtomicBoolean(false);

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
            int maxBatchSize,
            boolean enableGc,
            boolean enableStdStreams
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
        this.enableGc = enableGc;
        this.enableStdStreams = enableStdStreams;
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

            // New flags
            @PluginAttribute(value = "enableGC", defaultBoolean = false) boolean enableGc,
            @PluginAttribute(value = "enableStdStreams", defaultBoolean = false) boolean enableStdStreams,

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

        String finalAppName = resolveAppName(appName);
        if (layout == null) {
            layout = PatternLayout.newBuilder().withPattern("%m%n").build();
        }

        return new LogSinkAppender(
                name, filter, layout, true,
                otlpEndpoint, finalAppName,
                envKeysCsv, envPrefixCsv, envExcludePattern,
                queueSize, maxBatchSize,
                enableGc, enableStdStreams
        );
    }

    // -------- env helpers --------

    private static final Pattern ENV_REF =
            Pattern.compile("^\\$\\{env[:.-]([A-Za-z_][A-Za-z0-9_]*)(?::-(.*))?}$");

    private static String ttn(String s) {
        if (s == null) return null;
        String x = s.trim();
        return x.isEmpty() ? null : x;
    }

    /**
     * appName precedence:
     * 1) explicit (supports ${env:VAR} / ${env.VAR} / ${env-VAR} / ${env:VAR:-default})
     * 2) ENV OTEL_SERVICE_NAME
     * 3) SYS PROP otel.service.name
     * 4) "unknown_service:log4j2"
     */
    private static String resolveAppName(String appNameRaw) {
        String candidate = ttn(appNameRaw);
        if (candidate != null) {
            Matcher m = ENV_REF.matcher(candidate);
            if (m.matches()) {
                String var = m.group(1);
                String def = m.group(2);
                String val = ttn(System.getenv(var));
                candidate = (val != null) ? val : (ttn(def) != null ? def : null);
            }
        }
        if (candidate == null) candidate = ttn(System.getenv("OTEL_SERVICE_NAME"));
        if (candidate == null) candidate = ttn(System.getProperty("otel.service.name"));
        if (candidate == null) candidate = "unknown_service:log4j2";
        return candidate;
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

        // Optional bridges
        if (enableStdStreams && STD_BRIDGE_INSTALLED.compareAndSet(false, true)) {
            try {
                StdStreamsOtelBridge.install(this.sink);
            } catch (Throwable t) {
                // Avoid recursive logging here; StatusLogger is ok but keep it quiet
                // LOGGER.debug("StdStreams bridge installation failed: {}", t.toString()); // optional
            }
        }

        if (enableGc) {
            try {
                this.jfrBridge = GcJfrOtelBridge.start(this.sink);
            } catch (Throwable t) {
                this.jfrBridge = null;
            }
        }
    }

    @Override
    public void append(LogEvent event) {
        if (sink == null || !isStarted()) return;

        long timeUnixNanos = event.getTimeMillis() * 1_000_000L;

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

        // Throwable (flatten)
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

        // Best-effort enqueue; drop if full.
        sink.log(protoRecord);
    }

    @Override
    public boolean stop(long timeout, TimeUnit timeUnit) {
        // Tear down GC bridge (stdout/stderr bridge stays for JVM lifetime)
        try {
            if (jfrBridge != null) {
                jfrBridge.close();
                jfrBridge = null;
            }
        } catch (Throwable ignore) { /* avoid recursion */ }

        boolean res = super.stop(timeout, timeUnit);
        try {
            if (sink != null) {
                sink.flush();
                sink.shutdown();
            }
        } catch (Throwable ignore) { /* avoid recursion */ }
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
                    if (k.startsWith(p)) { include = true; break; }
                }
            }
            if (!include) return;
            if (deny != null && deny.matcher(k).matches()) return;

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