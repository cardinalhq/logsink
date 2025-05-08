package com.cardinal.logsink;

import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.resource.v1.Resource;

import java.util.*;

public class LogSinkConfig {
    private final String otlpEndpoint;
    private final String apiKey;
    private final int maxBatchSize;
    private final Resource resource;

    private LogSinkConfig(Builder builder) {
        this.otlpEndpoint = builder.otlpEndpoint;
        this.apiKey = builder.apiKey;
        this.maxBatchSize = builder.maxBatchSize;
        this.resource = builder.resource;
    }

    public String getOTLPEndpoint() {
        return otlpEndpoint;
    }

    public String getApiKey() {
        return apiKey;
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
        private String apiKey;
        private int maxBatchSize = 100; // default
        private String appName;
        private final Map<String, String> resourceAttributes = new LinkedHashMap<>();
        private Resource resource;

        public Builder setOtlpEndpoint(String otlpEndpoint) {
            this.otlpEndpoint = otlpEndpoint;
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
            if (otlpEndpoint == null || otlpEndpoint.isEmpty()) {
                throw new IllegalArgumentException("OTLP endpoint must be provided.");
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

            return new LogSinkConfig(this);
        }
    }
}