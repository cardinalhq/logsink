package io.cardinalhq.logsink.bridge;

import com.sun.management.GarbageCollectionNotificationInfo;
import com.sun.management.GcInfo;
import io.cardinalhq.logsink.LogSink;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.logs.v1.LogRecord;

import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class GcOtelBridge implements AutoCloseable {
    private final List<Registration> regs = new ArrayList<>();
    private final long jvmStartEpochMs;
    private final LogSink sink;

    private static final class Registration {
        final NotificationEmitter emitter;
        final NotificationListener listener;

        Registration(NotificationEmitter emitter, NotificationListener listener) {
            this.emitter = emitter;
            this.listener = listener;
        }
    }

    public GcOtelBridge(LogSink sink) {
        this.sink = sink;
        this.jvmStartEpochMs = ManagementFactory.getRuntimeMXBean().getStartTime();
    }

    public static GcOtelBridge start(LogSink sink) {
        GcOtelBridge b = new GcOtelBridge(sink);
        b.register();
        return b;
    }

    private void register() {
        for (GarbageCollectorMXBean mx : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (mx instanceof NotificationEmitter) {
                NotificationEmitter em = (NotificationEmitter) mx;
                NotificationListener l = (n, hb) -> {
                    if (!GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION.equals(n.getType())) return;
                    GarbageCollectionNotificationInfo info =
                            GarbageCollectionNotificationInfo.from((CompositeData) n.getUserData());
                    onGc(info);
                };
                try {
                    em.addNotificationListener(l, null, null);
                    regs.add(new Registration(em, l));
                } catch (Exception ignored) { /* avoid logging recursion */ }
            }
        }
    }

    private void onGc(GarbageCollectionNotificationInfo info) {
        GcInfo gi = info.getGcInfo();
        if (gi == null) return;

        long beforeUsed = sumUsed(gi.getMemoryUsageBeforeGc());
        long afterUsed = sumUsed(gi.getMemoryUsageAfterGc());
        long committedAfter = sumCommitted(gi.getMemoryUsageAfterGc());
        double uptimeSecs = gi.getStartTime() / 1000.0;
        String gen = info.getGcName() != null && info.getGcName().contains("Young") ? "Young"
                : info.getGcName() != null && info.getGcName().contains("Old") ? "Old"
                : (info.getGcName() != null ? info.getGcName() : "Unknown");
        String cause = (info.getGcCause() == null || info.getGcCause().isBlank()) ? "Unknown" : info.getGcCause();
        String line = String.format("[%.3fs][info][gc] GC(%d) Pause %s (%s) %s->%s(%s) %.3fms",
                uptimeSecs,
                gi.getId(),
                gen, cause,
                toMi(beforeUsed),
                toMi(afterUsed),
                toMi(committedAfter),
                (double) gi.getDuration());

        long timeUnixNanos = (jvmStartEpochMs + gi.getStartTime()) * 1_000_000L;

        List<KeyValue> attrs = new ArrayList<>(10);
        attrs.add(kv("gc.id", String.valueOf(gi.getId())));
        attrs.add(kv("gc.name", info.getGcName() == null ? "" : info.getGcName()));
        attrs.add(kv("gc.cause", cause));
        attrs.add(kv("gc.duration.ms", String.valueOf(gi.getDuration())));
        attrs.add(kv("gc.before.used.bytes", String.valueOf(beforeUsed)));
        attrs.add(kv("gc.after.used.bytes", String.valueOf(afterUsed)));
        attrs.add(kv("gc.committed.after.bytes", String.valueOf(committedAfter)));
        attrs.add(kv("stream", "jvm.gc"));

        LogRecord rec = LogRecord.newBuilder()
                .setTimeUnixNano(timeUnixNanos)
                .setObservedTimeUnixNano(timeUnixNanos)
                .setSeverityNumber(io.opentelemetry.proto.logs.v1.SeverityNumber.SEVERITY_NUMBER_INFO)
                .setSeverityText("INFO")
                .setBody(AnyValue.newBuilder().setStringValue(line).build())
                .addAllAttributes(attrs)
                .build();

        sink.log(rec);
    }

    private static long sumUsed(Map<String, MemoryUsage> m) {
        long s = 0L;
        for (MemoryUsage u : m.values()) if (u != null) s += u.getUsed();
        return s;
    }

    private static long sumCommitted(Map<String, MemoryUsage> m) {
        long s = 0L;
        for (MemoryUsage u : m.values()) if (u != null) s += u.getCommitted();
        return s;
    }

    private static String toMi(long bytes) {
        long mib = (bytes + (1 << 19)) >> 20;
        return mib + "M";
    }

    private static KeyValue kv(String k, String v) {
        return KeyValue.newBuilder()
                .setKey(k)
                .setValue(AnyValue.newBuilder().setStringValue(v == null ? "" : v).build())
                .build();
    }

    @Override
    public void close() {
        for (Registration r : regs) {
            try {
                r.emitter.removeNotificationListener(r.listener);
            } catch (Exception ignored) {}
        }
        regs.clear();
    }
}