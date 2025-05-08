package com.cardinal.logsink;

import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import io.opentelemetry.proto.resource.v1.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;import java.util.zip.GZIPOutputStream;

public class LogSinkExporter {
    private static final Logger logger = LoggerFactory.getLogger(LogSinkExporter.class);
    private static final String CARDINAL_API_KEY_HEADER = "x-cardinalhq-api-key";

    private final LogSinkConfig config;
    private final HttpClient httpClient;


    public LogSinkExporter(LogSinkConfig config) {
        this(config, HttpClient.newHttpClient());
    }

    public LogSinkExporter(LogSinkConfig config, HttpClient httpClient) {
        this.config = config;
        this.httpClient = httpClient;
    }

    public void sendBatch(List<LogRecord> records) {
        System.out.println("Sending " + records.size() + " records to LogSink");
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

        System.out.println("Built request: " + request);

        byte[] payload = request.toByteArray();
        sendHttp(payload);
    }

    private void sendHttp(byte[] payload) {
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(config.getOTLPEndpoint()))
                    .header(CARDINAL_API_KEY_HEADER, config.getApiKey())
                    .header("Content-Type", "application/x-protobuf")
                    .header("Content-Encoding", "gzip")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(gzip(payload)))
                    .build();

            this.httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(response -> {
                        System.out.println("Received response from LogSink: " + response.statusCode());
                        if (response.statusCode() >= 200 && response.statusCode() < 300) {
                            logger.debug("Logs sent successfully");
                        } else {
                            logger.error("Failed to send logs: {}", response.statusCode());
                        }
                    })
                    .exceptionally(ex -> {
                        logger.error("Failed to send logs", ex);
                        return null;
                    });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private byte[] gzip(byte[] data) {
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