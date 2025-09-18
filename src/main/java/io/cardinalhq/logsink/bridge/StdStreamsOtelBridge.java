package io.cardinalhq.logsink.bridge;

import io.cardinalhq.logsink.LogSink;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.SeverityNumber;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class StdStreamsOtelBridge {
    private StdStreamsOtelBridge() {}

    /** Call once, very early in main(). Keeps existing shell redirections intact. */
    public static void install(LogSink sink) {
        install(sink, StandardCharsets.UTF_8);
    }

    public static void install(LogSink sink, Charset charset) {
        // Keep originals (may be redirected by the shell to files)
        PrintStream origOut = System.out;
        PrintStream origErr = System.err;

        // Build line->OTLP record callbacks
        LineSink outLineSink = (line, tsNanos) -> sink.log(buildRecord(line, tsNanos,
                SeverityNumber.SEVERITY_NUMBER_INFO, "proc.stdout"));
        LineSink errLineSink = (line, tsNanos) -> sink.log(buildRecord(line, tsNanos,
                SeverityNumber.SEVERITY_NUMBER_ERROR, "proc.stderr"));

        // Create tees that write to original streams AND to OTLP via sink.log(...)
        OutputStream teeOut = new TeeOutputStream(
                new PrintStreamOutput(origOut), new LineToSinkOutputStream(outLineSink, charset));
        OutputStream teeErr = new TeeOutputStream(
                new PrintStreamOutput(origErr), new LineToSinkOutputStream(errLineSink, charset));

        // Replace system streams (autoFlush on newline)
        System.setOut(new PrintStream(teeOut, true, charset));
        System.setErr(new PrintStream(teeErr, true, charset));
    }

    // ---- plumbing ----

    /** Minimal callback for completed lines. */
    @FunctionalInterface
    interface LineSink { void accept(String line, long epochNanos); }

    /** Converts bytes to lines, calls LineSink per line. */
    static final class LineToSinkOutputStream extends OutputStream {
        private final LineSink sink;
        private final Charset cs;
        private final StringBuilder sb = new StringBuilder(256);
        LineToSinkOutputStream(LineSink sink, Charset cs) { this.sink = sink; this.cs = cs; }

        @Override public void write(int b) {
            if (b == '\n') flushLine();
            else if (b != '\r') sb.append((char)(b & 0xFF)); // quick UTF-8 ASCII fast-path
        }
        @Override public void write(byte[] b, int off, int len) {
            for (int i = 0; i < len; i++) write(b[off + i]);
        }
        @Override public void flush() { if (sb.length() > 0) flushLine(); }
        private void flushLine() {
            String line = sb.toString();
            sb.setLength(0);
            if (!line.isEmpty()) {
                long nowNanos = System.currentTimeMillis() * 1_000_000L;
                sink.accept(line, nowNanos);
            }
        }
    }

    /** Writes to two outputs; exceptions from one don’t stop the other. */
    static final class TeeOutputStream extends OutputStream {
        private final OutputStream a, b;
        TeeOutputStream(OutputStream a, OutputStream b) { this.a = a; this.b = b; }
        @Override public void write(int x) {
            try { a.write(x); } catch (Exception ignored) {}
            try { b.write(x); } catch (Exception ignored) {}
        }
        @Override public void write(byte[] buf, int off, int len) {
            try { a.write(buf, off, len); } catch (Exception ignored) {}
            try { b.write(buf, off, len); } catch (Exception ignored) {}
        }
        @Override public void flush() {
            try { a.flush(); } catch (Exception ignored) {}
            try { b.flush(); } catch (Exception ignored) {}
        }
        @Override public void close() {
            try { a.close(); } catch (Exception ignored) {}
            try { b.close(); } catch (Exception ignored) {}
        }
    }

    /** Lets us treat a PrintStream as an OutputStream target for teeing. */
    static final class PrintStreamOutput extends OutputStream {
        private final PrintStream ps;
        PrintStreamOutput(PrintStream ps) { this.ps = ps; }
        @Override public void write(int b) { ps.write(b); }
        @Override public void write(byte[] b, int off, int len) { ps.write(b, off, len); }
        @Override public void flush() { ps.flush(); }
        @Override public void close() { /* do not close the original System streams */ }
    }

    private static LogRecord buildRecord(String line, long tsNanos, SeverityNumber sev, String logType) {
        List<KeyValue> attrs = new ArrayList<>(2);
        attrs.add(kv("log_type", logType));
        attrs.add(kv("stream", logType.endsWith("stderr") ? "stderr" : "stdout"));

        return LogRecord.newBuilder()
                .setTimeUnixNano(tsNanos)
                .setObservedTimeUnixNano(tsNanos)
                .setSeverityNumber(sev)
                .setSeverityText(sev.name().replace("SEVERITY_NUMBER_", ""))
                .setBody(AnyValue.newBuilder().setStringValue(line).build())
                .addAllAttributes(attrs)
                .build();
    }

    private static KeyValue kv(String k, String v) {
        return KeyValue.newBuilder()
                .setKey(k)
                .setValue(AnyValue.newBuilder().setStringValue(v == null ? "" : v).build())
                .build();
    }
}