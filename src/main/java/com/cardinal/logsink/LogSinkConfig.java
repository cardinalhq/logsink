package com.cardinal.logsink;

public class LogSinkConfig {
    private final String otlpEndpoint;
    private final String apiKey;
    private final int maxBatchSize; // number of logs

    public LogSinkConfig(String otlpEndpoint, String apiKey, int maxBatchSize) {
        this.otlpEndpoint = otlpEndpoint;
        this.apiKey = apiKey;
        this.maxBatchSize = maxBatchSize;
    }

    public String getOTLPEndpoint() { return otlpEndpoint; }
    public String getApiKey() { return apiKey; }
    public int getMaxBatchSize() { return maxBatchSize; }
}
