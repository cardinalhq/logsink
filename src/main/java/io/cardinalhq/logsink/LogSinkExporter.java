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
import java.util.concurrent.ThreadLocalRandom;
import java.util.zip.GZIPOutputStream;

public final class LogSinkExporter {
    private static final StatusLogger logger = StatusLogger.getLogger();
    private static final String CARDINAL_API_KEY_HEADER = "x-cardinalhq-api-key";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    // Retry policy for transient failures (connection reset, "header parser received no bytes",
    // request timeouts, 429 and 5xx). Only the last failure of an exhausted retry sequence
    // is logged at ERROR; intermediate attempts log at DEBUG to avoid noise.
    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_BASE_BACKOFF_MS = 250L;
    private static final long RETRY_MAX_BACKOFF_MS = 2_000L;

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

        Exception lastException = null;
        int lastStatus = -1;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpResponse<String> resp = this.httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
                int code = resp.statusCode();
                if (code >= 200 && code < 300) {
                    logger.debug("Logs sent successfully");
                    return;
                }
                lastException = null;
                lastStatus = code;
                if (!isRetryableStatus(code) || attempt == MAX_ATTEMPTS) {
                    logger.error("Failed to send logs: {}", code);
                    return;
                }
                logger.debug("Transient send failure (status {}), attempt {}/{}", code, attempt, MAX_ATTEMPTS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                logger.error("Interrupted while sending logs", ie);
                return;
            } catch (IOException e) {
                // Any IOException from the JDK HttpClient here (HttpTimeoutException,
                // "connection closed locally", "HTTP/1.1 header parser received no bytes",
                // connection reset, etc.) is a transient network error worth retrying.
                lastException = e;
                if (attempt == MAX_ATTEMPTS) {
                    logger.error("Failed to send logs after {} attempt(s)", attempt, e);
                    return;
                }
                logger.debug("Transient send failure ({}), attempt {}/{}", e.getClass().getSimpleName(), attempt, MAX_ATTEMPTS);
            } catch (Exception e) {
                logger.error("Failed to send logs", e);
                return;
            }

            if (!sleepBackoff(attempt)) {
                if (lastException != null) {
                    logger.error("Failed to send logs after {} attempt(s)", attempt, lastException);
                } else if (lastStatus != -1) {
                    logger.error("Failed to send logs: {}", lastStatus);
                }
                return;
            }
        }
    }

    private static boolean isRetryableStatus(int code) {
        return code == 429 || (code >= 500 && code < 600);
    }

    /** Returns true if the thread slept for the backoff, false if it was interrupted. */
    private static boolean sleepBackoff(int attempt) {
        long exp = Math.min(RETRY_MAX_BACKOFF_MS, RETRY_BASE_BACKOFF_MS * (1L << (attempt - 1)));
        long jitter = ThreadLocalRandom.current().nextLong(exp / 2 + 1);
        try {
            Thread.sleep(exp + jitter);
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
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