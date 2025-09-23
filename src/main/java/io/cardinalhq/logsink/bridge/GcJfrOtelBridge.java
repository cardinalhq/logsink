package io.cardinalhq.logsink.bridge;

import io.cardinalhq.logsink.LogSink;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.logs.v1.LogRecord;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingStream;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class GcJfrOtelBridge implements AutoCloseable {
    private final LogSink sink;
    private final long jvmStartMs = ManagementFactory.getRuntimeMXBean().getStartTime();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private RecordingStream rs;
    private Thread thread;

    public GcJfrOtelBridge(LogSink sink) {
        this.sink = sink;
    }

    public static GcJfrOtelBridge start(LogSink sink) {
        GcJfrOtelBridge b = new GcJfrOtelBridge(sink);
        b.start();
        return b;
    }

    public void start() {
        if (!started.compareAndSet(false, true)) return;

        rs = new RecordingStream();

        rs.enable("jdk.GarbageCollection");
        rs.enable("jdk.GCPhasePause");
        rs.enable("jdk.GCPhaseConcurrent");
        rs.enable("jdk.GCConfiguration");

        rs.onEvent("jdk.GCConfiguration", e -> {
            String collector = s(e, "youngCollector");
            String old = s(e, "oldCollector");

            emit(e, tag("gc,init"),
                    "Using " + (collector != null ? collector : "unknown") +
                            (old != null ? " + " + old : ""));
        });

        rs.onEvent("jdk.GarbageCollection", e -> {
            long id = l(e, "gcId", -1);
            String name = s(e, "name");     // e.g., "Young" / "Old"
            String cause = s(e, "cause");   // e.g., "G1 Humongous Allocation"
            double durMs = durMs(e);
            emit(e, tag("gc"),
                    String.format("GC(%d) Pause %s (%s) %.3fms",
                            id, nz(name, "Unknown"), nz(cause, "Unknown"), durMs));
        });

        rs.onEvent("jdk.GCPhasePause", e -> {
            long id = l(e, "gcId", -1);
            String phase = s(e, "name"); // e.g., "Evacuate Collection Set", "Remark"
            double durMs = durMs(e);
            emit(e, tag("gc,phases"),
                    String.format("GC(%d)   %s: %.3fms", id, nz(phase, "Pause"), durMs));
        });

        rs.onEvent("jdk.GCPhaseConcurrent", e -> {
            long id = l(e, "gcId", -1);
            String phase = s(e, "name"); // e.g., "Concurrent Mark", "Concurrent Rebuild Remembered Sets"
            double durMs = durMs(e);
            emit(e, tag("gc"),
                    String.format("GC(%d) %s %.3fms", id, nz(phase, "Concurrent Phase"), durMs));
        });

        thread = new Thread(() -> {
            try {
                rs.start();
            } catch (Throwable ignore) { /* don't recurse into logging */ }
        }, "gc-jfr-bridge");
        thread.setDaemon(true);
        thread.start();
    }

    private void emit(RecordedEvent e, String tags, String body) {
        // uptime seconds like -Xlog:gc prefix
        double upSec = (e.getEndTime().toEpochMilli() - jvmStartMs) / 1000.0;
        String line = String.format("[%.3fs][info][%s] %s", upSec, tags, body);

        List<KeyValue> attrs = new ArrayList<>(10);
        attrs.add(kv("stream", "jvm.gc"));

        long tsNanos = e.getEndTime().toEpochMilli() * 1_000_000L;
        LogRecord rec = LogRecord.newBuilder()
                .setTimeUnixNano(tsNanos)
                .setObservedTimeUnixNano(tsNanos)
                .setSeverityText("INFO")
                .addAllAttributes(attrs)
                .setBody(AnyValue.newBuilder().setStringValue(line).build())
                .build();
        sink.log(rec);
    }

    private static KeyValue kv(String k, String v) {
        return KeyValue.newBuilder()
                .setKey(k)
                .setValue(AnyValue.newBuilder().setStringValue(v == null ? "" : v).build())
                .build();
    }

    private static String tag(String t) {
        return t;
    }

    private static String s(RecordedEvent e, String f) {
        try {
            return e.getString(f);
        } catch (Throwable x) {
            return null;
        }
    }

    private static long l(RecordedEvent e, String f, long d) {
        try {
            return e.getLong(f);
        } catch (Throwable x) {
            return d;
        }
    }

    private static double durMs(RecordedEvent e) {
        try {
            return e.getDuration("duration").toNanos() / 1_000_000.0;
        } catch (Throwable x) {
            return Double.NaN;
        }
    }

    private static String nz(String v, String d) {
        return (v == null || v.isBlank()) ? d : v;
    }

    @Override
    public void close() {
        try {
            if (rs != null) rs.close();
        } catch (Throwable ignored) {
        }
        try {
            if (thread != null) thread.interrupt();
        } catch (Throwable ignored) {
        }
    }
}