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
import java.util.zip.GZIPOutputStream;

public class LogSinkExporter {
    private static final Logger logger = LoggerFactory.getLogger(LogSinkExporter.class);

    private final LogSinkConfig config;
    private final HttpClient httpClient;


    public LogSinkExporter(LogSinkConfig config) {
        this(config, HttpClient.newHttpClient());
    }

    public LogSinkExporter(LogSinkConfig config, HttpClient httpClient) {
        this.config = config;
        this.httpClient = httpClient;
    }

    public void sendBatch(String appName, List<LogRecord> records, String... resourceTags) {
        if (resourceTags.length % 2 != 0) {
            throw new IllegalArgumentException("Resource tags must be in key-value pairs");
        }

        List<KeyValue> attributes = new ArrayList<>();
        // Required attribute
        attributes.add(KeyValue.newBuilder()
                .setKey("service.name")
                .setValue(AnyValue.newBuilder().setStringValue(appName).build())
                .build());

        // Additional resource-level attributes
        for (int i = 0; i < resourceTags.length; i += 2) {
            String key = resourceTags[i];
            String value = resourceTags[i + 1];
            attributes.add(KeyValue.newBuilder()
                    .setKey(key)
                    .setValue(AnyValue.newBuilder().setStringValue(value).build())
                    .build());
        }

        Resource resource = Resource.newBuilder()
                .addAllAttributes(attributes)
                .build();

        ScopeLogs scopeLogs = ScopeLogs.newBuilder()
                .addAllLogRecords(records)
                .build();

        ResourceLogs resourceLogs = ResourceLogs.newBuilder()
                .setResource(resource)
                .addScopeLogs(scopeLogs)
                .build();

        ExportLogsServiceRequest request = ExportLogsServiceRequest.newBuilder()
                .addResourceLogs(resourceLogs)
                .build();

        byte[] payload = request.toByteArray();
        sendHttp(payload);
    }

    private void sendHttp(byte[] payload) {


        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(config.getOTLPEndpoint()))
                .header("x-cardinalhq-api-key", config.getApiKey())
                .header("Content-Type", "application/x-protobuf")
                .header("Content-Encoding", "gzip")
                .POST(HttpRequest.BodyPublishers.ofByteArray(gzip(payload)))
                .build();

        this.httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        System.out.println("Logs sent successfully");
                    } else {
                        System.err.println("Failed to send logs: " + response.statusCode());
                    }
                })
                .exceptionally(ex -> {
                    logger.error("Failed to send logs", ex);
                    return null;
                });
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