package io.cardinalhq.logsink;

import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.resource.v1.Resource;

import java.util.*;
import java.util.function.Function;

public class LogSinkConfig {
    private static final String OTEL_ENDPOINT = "OTEL_EXPORTER_OTLP_ENDPOINT";
    private static final String OTEL_LOGS_ENDPOINT = "OTEL_EXPORTER_OTLP_LOGS_ENDPOINT";
    private static final String OTEL_TRACES_ENDPOINT = "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT";
    private static final String OTEL_ENDPOINT_PROPERTY = "otel.exporter.otlp.endpoint";
    private static final String OTEL_LOGS_ENDPOINT_PROPERTY = "otel.exporter.otlp.logs.endpoint";
    private static final String OTEL_TRACES_ENDPOINT_PROPERTY = "otel.exporter.otlp.traces.endpoint";

    private final String otlpEndpoint;
    private final String tracesEndpoint;
    private final String apiKey;
    private final int maxBatchSize;
    private final Resource resource;
    private final int queueSize; // default

    private LogSinkConfig(Builder builder, ResolvedEndpoints endpoints) {
        this.otlpEndpoint = endpoints.logs;
        this.tracesEndpoint = endpoints.traces;
        this.apiKey = builder.apiKey;
        this.maxBatchSize = builder.maxBatchSize;
        this.resource = builder.resource;
        this.queueSize = builder.queueSize;
    }

    public String getOTLPEndpoint() {
        return otlpEndpoint;
    }

    /**
     * Returns the OTLP/HTTP traces endpoint. By default it is derived from the log
     * endpoint (for example, {@code /v1/logs} becomes {@code /v1/traces}).
     */
    public String getTracesEndpoint() {
        return tracesEndpoint;
    }

    public String getApiKey() {
        return apiKey;
    }

    public int getQueueSize() {
        return queueSize;
    }

    public int getMaxBatchSize() {
        return maxBatchSize;
    }

    public Resource getResource() {
        return this.resource;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String otlpEndpoint;
        private String tracesEndpoint;
        private String apiKey = "";
        private int maxBatchSize = 100; // default
        private String appName;
        private final Map<String, String> resourceAttributes = new LinkedHashMap<>();
        private Resource resource;
        private int queueSize = 1000;

        public Builder setQueueSize(int queueSize) {
            this.queueSize = queueSize;
            return this;
        }


        public Builder setOtlpEndpoint(String otlpEndpoint) {
            this.otlpEndpoint = otlpEndpoint;
            return this;
        }

        /** Sets a traces endpoint when it cannot be derived from the log endpoint. */
        public Builder setTracesEndpoint(String tracesEndpoint) {
            this.tracesEndpoint = tracesEndpoint;
            return this;
        }

        public Builder setApiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder setMaxBatchSize(int maxBatchSize) {
            this.maxBatchSize = maxBatchSize;
            return this;
        }

        public Builder setAppName(String appName) {
            this.appName = appName;
            return this;
        }

        public Builder addResourceAttribute(String key, String value) {
            this.resourceAttributes.put(key, value);
            return this;
        }

        public Builder addResourceAttributes(Map<String, String> attributes) {
            this.resourceAttributes.putAll(attributes);
            return this;
        }

        public LogSinkConfig build() {
            if (otlpEndpoint != null && otlpEndpoint.isBlank()) {
                throw new IllegalArgumentException("OTLP endpoint must not be blank.");
            }
            if (tracesEndpoint != null && tracesEndpoint.isBlank()) {
                throw new IllegalArgumentException("Traces endpoint must not be blank.");
            }
            if (appName == null || appName.isEmpty()) {
                throw new IllegalArgumentException("App name must be provided.");
            }
            List<KeyValue> attributes = new ArrayList<>();
            for (Map.Entry<String, String> entry : resourceAttributes.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    throw new IllegalArgumentException("Resource attributes must not contain null keys or values");
                }
                attributes.add(KeyValue.newBuilder()
                        .setKey(entry.getKey())
                        .setValue(AnyValue.newBuilder().setStringValue(entry.getValue()).build())
                        .build());
            }
            attributes.add(KeyValue.newBuilder()
                    .setKey("service.name")
                    .setValue(AnyValue.newBuilder().setStringValue(appName).build()).build());
            this.resource = Resource.newBuilder()
                    .addAllAttributes(attributes)
                    .build();

            ResolvedEndpoints endpoints = resolveEndpoints(
                    otlpEndpoint,
                    tracesEndpoint,
                    System::getProperty,
                    System::getenv);
            return new LogSinkConfig(this, endpoints);
        }
    }

    static ResolvedEndpoints resolveEndpoints(
            String configuredLogs,
            String configuredTraces,
            Function<String, String> property,
            Function<String, String> environment) {
        String generic = firstNonBlank(
                property.apply(OTEL_ENDPOINT_PROPERTY),
                property.apply(OTEL_ENDPOINT),
                environment.apply(OTEL_ENDPOINT));

        String logs = firstNonBlank(
                configuredLogs,
                property.apply(OTEL_LOGS_ENDPOINT_PROPERTY),
                property.apply(OTEL_LOGS_ENDPOINT),
                environment.apply(OTEL_LOGS_ENDPOINT));
        if (logs == null && generic != null) {
            logs = signalEndpoint(generic, "logs");
        }
        if (logs == null) {
            throw new IllegalArgumentException(
                    "OTLP endpoint must be provided with setOtlpEndpoint(), "
                            + OTEL_LOGS_ENDPOINT + ", or " + OTEL_ENDPOINT + ".");
        }

        String traces = firstNonBlank(
                configuredTraces,
                property.apply(OTEL_TRACES_ENDPOINT_PROPERTY),
                property.apply(OTEL_TRACES_ENDPOINT),
                environment.apply(OTEL_TRACES_ENDPOINT));
        if (traces == null) {
            traces = configuredLogs != null || generic == null
                    ? signalEndpoint(logs, "traces")
                    : signalEndpoint(generic, "traces");
        }

        return new ResolvedEndpoints(logs, traces);
    }

    private static String signalEndpoint(String endpoint, String signal) {
        String normalized = endpoint.endsWith("/")
                ? endpoint.substring(0, endpoint.length() - 1)
                : endpoint;
        if (normalized.endsWith("/v1/logs")) {
            normalized = normalized.substring(0, normalized.length() - "/v1/logs".length());
        } else if (normalized.endsWith("/v1/traces")) {
            normalized = normalized.substring(0, normalized.length() - "/v1/traces".length());
        }
        return normalized + "/v1/" + signal;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    static final class ResolvedEndpoints {
        final String logs;
        final String traces;

        private ResolvedEndpoints(String logs, String traces) {
            this.logs = logs;
            this.traces = traces;
        }
    }
}
