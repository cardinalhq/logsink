package io.cardinalhq.logsink;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LogSinkConfigTest {
    private static final Function<String, String> NO_PROPERTIES = key -> null;

    @Test
    void derivesSignalEndpointsFromGenericEnvironmentVariable() {
        Map<String, String> environment = Map.of(
                "OTEL_EXPORTER_OTLP_ENDPOINT", "http://collector:4318/");

        LogSinkConfig.ResolvedEndpoints endpoints = LogSinkConfig.resolveEndpoints(
                null,
                null,
                NO_PROPERTIES,
                environment::get);

        assertEquals("http://collector:4318/v1/logs", endpoints.logs);
        assertEquals("http://collector:4318/v1/traces", endpoints.traces);
    }

    @Test
    void signalSpecificEnvironmentVariablesOverrideGenericEndpoint() {
        Map<String, String> environment = Map.of(
                "OTEL_EXPORTER_OTLP_ENDPOINT", "http://generic:4318",
                "OTEL_EXPORTER_OTLP_LOGS_ENDPOINT", "http://logs:4318/custom/logs",
                "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT", "http://traces:4318/custom/traces");

        LogSinkConfig.ResolvedEndpoints endpoints = LogSinkConfig.resolveEndpoints(
                null,
                null,
                NO_PROPERTIES,
                environment::get);

        assertEquals("http://logs:4318/custom/logs", endpoints.logs);
        assertEquals("http://traces:4318/custom/traces", endpoints.traces);
    }

    @Test
    void configuredLogEndpointWinsAndSuppliesDefaultTraceEndpoint() {
        Map<String, String> environment = Map.of(
                "OTEL_EXPORTER_OTLP_ENDPOINT", "http://environment:4318");

        LogSinkConfig.ResolvedEndpoints endpoints = LogSinkConfig.resolveEndpoints(
                "http://configured:4318/v1/logs",
                null,
                NO_PROPERTIES,
                environment::get);

        assertEquals("http://configured:4318/v1/logs", endpoints.logs);
        assertEquals("http://configured:4318/v1/traces", endpoints.traces);
    }

    @Test
    void builderFallsBackToOpenTelemetrySystemProperty() {
        String key = "otel.exporter.otlp.endpoint";
        String previous = System.getProperty(key);
        System.setProperty(key, "http://property-collector:4318");
        try {
            LogSinkConfig config = LogSinkConfig.builder()
                    .setAppName("test-service")
                    .build();

            assertEquals("http://property-collector:4318/v1/logs", config.getOTLPEndpoint());
            assertEquals("http://property-collector:4318/v1/traces", config.getTracesEndpoint());
        } finally {
            if (previous == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, previous);
            }
        }
    }
}
