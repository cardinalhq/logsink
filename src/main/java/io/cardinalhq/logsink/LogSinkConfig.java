package io.cardinalhq.logsink;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;

public class LogSinkConfig {
    private final String otlpEndpoint;
    private final String apiKey;
    private final int maxBatchSize;
    private final int publishFrequency;
    private final Pattern recordStartPattern;
    private final Function<String, Map<String, String>> attributesDeriver;
    private final Path checkpointPath;

    private LogSinkConfig(Builder builder) {
        this.otlpEndpoint = builder.otlpEndpoint;
        this.apiKey = builder.apiKey;
        this.maxBatchSize = builder.maxBatchSize;
        this.publishFrequency = builder.publishFrequency;
        this.recordStartPattern = Objects.requireNonNullElseGet(builder.recordStartPattern, () -> Pattern.compile("^\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}"));
        this.attributesDeriver = builder.attributesDeriver;
        this.checkpointPath = builder.checkpointPath;
    }

    public Function<String, Map<String, String>> getAttributesDeriver() {
        return attributesDeriver;
    }



    public Path getCheckpointPath() {
        return checkpointPath;
    }

    public String getOTLPEndpoint() {
        return otlpEndpoint;
    }

    public String getApiKey() {
        return apiKey;
    }

    public int getPublishFrequency() {
        return publishFrequency;
    }

    public int getMaxBatchSize() {
        return maxBatchSize;
    }

    public Pattern getRecordStartPattern() {
        return this.recordStartPattern;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String otlpEndpoint;
        private String apiKey = "";
        private int maxBatchSize = 100; // default
        private Pattern recordStartPattern = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}"); ;
        private int publishFrequency = 5000;
        private Function<String, Map<String, String>> attributesDeriver = s -> Map.of("service.name", "logsink");
        private Path checkpointPath = Paths.get("./checkpoints");

        public Builder setPublishFrequency(int publishFrequency) {
            this.publishFrequency = publishFrequency;
            return this;
        }

        public Builder setRecordStartPattern(Pattern recordStartPattern) {
            this.recordStartPattern = recordStartPattern;
            return this;
        }

        public Builder setAttributesDeriver(Function<String, Map<String, String>> attributesDeriver) {
            this.attributesDeriver = attributesDeriver;
            return this;
        }

        public Builder setOtlpEndpoint(String otlpEndpoint) {
            this.otlpEndpoint = otlpEndpoint;
            return this;
        }

        public Builder setApiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder setCheckpointPath(Path checkpointPath) {
            this.checkpointPath = checkpointPath;
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