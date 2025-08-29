package io.cardinalhq.logsink;

import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import org.apache.logging.log4j.status.StatusLogger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.zip.GZIPOutputStream;

public final class LogSinkExporter {
    private static final StatusLogger logger = StatusLogger.getLogger();
    private static final String CARDINAL_API_KEY_HEADER = "x-cardinalhq-api-key";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final LogSinkConfig config;
    private final HttpClient httpClient;

    public LogSinkExporter(LogSinkConfig config) {
        this(config, HttpClient.newHttpClient());
    }

    public LogSinkExporter(LogSinkConfig config, HttpClient httpClient) {
        if (config == null) throw new IllegalArgumentException("config is null");
        if (httpClient == null) throw new IllegalArgumentException("httpClient is null");
        this.config = config;
        this.httpClient = httpClient;
    }

    /** Legacy entrypoint; delegates to the blocking implementation. */
    public void sendBatch(List<LogRecord> records) {
        sendBlocking(records);
    }

    /** Blocking send — use with the single-threaded batcher to keep at most one in-flight request. */
    public void sendBlocking(List<LogRecord> records) {
        if (records == null || records.isEmpty()) return;

        // Build OTLP request
        ScopeLogs scopeLogs = ScopeLogs.newBuilder()
                .addAllLogRecords(records)
                .build();

        ResourceLogs resourceLogs = ResourceLogs.newBuilder()
                .setResource(this.config.getResource())
                .addScopeLogs(scopeLogs)
                .build();

        ExportLogsServiceRequest request = ExportLogsServiceRequest.newBuilder()
                .addResourceLogs(resourceLogs)
                .build();

        byte[] payload = request.toByteArray();
        byte[] gz = gzip(payload);

        // Build HTTP request (blocking)
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(config.getOTLPEndpoint()))
                .timeout(REQUEST_TIMEOUT)
                .header(CARDINAL_API_KEY_HEADER, config.getApiKey())
                .header("Content-Type", "application/x-protobuf")
                .header("Content-Encoding", "gzip")
                .POST(HttpRequest.BodyPublishers.ofByteArray(gz))
                .build();

        try {
            HttpResponse<String> resp = this.httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            int code = resp.statusCode();
            if (code >= 200 && code < 300) {
                logger.debug("Logs sent successfully");
            } else {
                logger.error("Failed to send logs: {}", code);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            logger.error("Interrupted while sending logs", ie);
        } catch (Exception e) {
            logger.error("Failed to send logs", e);
        }
    }

    private static byte[] gzip(byte[] data) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             GZIPOutputStream gzipOut = new GZIPOutputStream(bos)) {
            gzipOut.write(data);
            gzipOut.close();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to gzip payload", e);
        }
    }
}