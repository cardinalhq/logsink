package io.cardinalhq.logsink.bridge;

import com.sun.management.GarbageCollectionNotificationInfo;
import com.sun.management.GcInfo;
import io.cardinalhq.logsink.LogSink;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.logs.v1.LogRecord;

import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Registers GC listeners and forwards each event as an OTLP LogRecord via LogSink. */
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

    /** Start listening; returns this AutoCloseable so you can close() on shutdown. */
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
                } catch (Exception ignored) { /* avoid logging via Log4j here (recursion risk) */ }
            }
        }
    }

    private void onGc(GarbageCollectionNotificationInfo info) {
        GcInfo gi = info.getGcInfo();
        long startEpochMs = jvmStartEpochMs + gi.getStartTime();   // ms since JVM start → epoch
        long tsNanos      = startEpochMs * 1_000_000L;

        long beforeUsed = sumUsed(gi.getMemoryUsageBeforeGc());
        long afterUsed  = sumUsed(gi.getMemoryUsageAfterGc());
        long reclaimed  = Math.max(0L, beforeUsed - afterUsed);

        List<KeyValue> attrs = new ArrayList<KeyValue>(8);
        attrs.add(kv("gc.name",   info.getGcName()));
        attrs.add(kv("gc.action", info.getGcAction()));  // e.g. "end of minor GC"
        attrs.add(kv("gc.cause",  info.getGcCause()));
        attrs.add(kv("gc.duration.ms", String.valueOf(gi.getDuration())));
        attrs.add(kv("gc.before.used.bytes", String.valueOf(beforeUsed)));
        attrs.add(kv("gc.after.used.bytes",  String.valueOf(afterUsed)));
        attrs.add(kv("gc.reclaimed.bytes",   String.valueOf(reclaimed)));

        // Optional: include a few common pools (names vary by GC)
        includePool("heap.young", gi.getMemoryUsageBeforeGc(), gi.getMemoryUsageAfterGc(), "G1 Eden Space", attrs);
        includePool("heap.survivor", gi.getMemoryUsageBeforeGc(), gi.getMemoryUsageAfterGc(), "G1 Survivor Space", attrs);
        includePool("heap.old", gi.getMemoryUsageBeforeGc(), gi.getMemoryUsageAfterGc(), "G1 Old Gen", attrs);

        String msg = "GC " + info.getGcName() + " (" + info.getGcAction() + ") cause=" + info.getGcCause()
                + " dur=" + gi.getDuration() + "ms reclaimed=" + reclaimed + "B";

        LogRecord rec = LogRecord.newBuilder()
                .setTimeUnixNano(tsNanos)
                .setObservedTimeUnixNano(tsNanos)
                .setSeverityNumber(io.opentelemetry.proto.logs.v1.SeverityNumber.SEVERITY_NUMBER_INFO)
                .setSeverityText("INFO")
                .setBody(AnyValue.newBuilder().setStringValue(msg).build())
                .addAllAttributes(attrs)
                .build();

        // Send in-band through your existing pipeline
        sink.log(rec);
    }

    private static long sumUsed(Map<String, MemoryUsage> m) {
        long s = 0L;
        for (MemoryUsage u : m.values()) if (u != null) s += u.getUsed();
        return s;
    }

    private static void includePool(String keyPrefix,
                                    Map<String, MemoryUsage> before,
                                    Map<String, MemoryUsage> after,
                                    String poolName,
                                    List<KeyValue> out) {
        MemoryUsage b = before.get(poolName);
        MemoryUsage a = after.get(poolName);
        if (b != null && a != null) {
            out.add(kv(keyPrefix + ".before.bytes", String.valueOf(b.getUsed())));
            out.add(kv(keyPrefix + ".after.bytes",  String.valueOf(a.getUsed())));
        }
    }

    private static KeyValue kv(String k, String v) {
        return KeyValue.newBuilder().setKey(k)
                .setValue(AnyValue.newBuilder().setStringValue(v == null ? "" : v).build())
                .build();
    }

    @Override public void close() {
        for (Registration r : regs) {
            try { r.emitter.removeNotificationListener(r.listener); } catch (Exception ignored) {}
        }
        regs.clear();
    }
}