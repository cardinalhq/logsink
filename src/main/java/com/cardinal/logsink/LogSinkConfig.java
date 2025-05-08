package com.cardinal.logsink;

public class LogSinkConfig {
    private final String otlpEndpoint;
    private final String apiKey;
    private final int maxBatchSize;

    private LogSinkConfig(Builder builder) {
        this.otlpEndpoint = builder.otlpEndpoint;
        this.apiKey = builder.apiKey;
        this.maxBatchSize = builder.maxBatchSize;
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

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String otlpEndpoint;
        private String apiKey;
        private int maxBatchSize = 100; // default value

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

        public LogSinkConfig build() {
            if (otlpEndpoint == null || otlpEndpoint.isEmpty()) {
                throw new IllegalArgumentException("OTLP endpoint must be provided.");
            }
            return new LogSinkConfig(this);
        }
    }
}