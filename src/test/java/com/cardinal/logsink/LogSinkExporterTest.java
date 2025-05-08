package com.cardinal.logsink;

import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.logs.v1.LogRecord;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
public class LogSinkExporterTest {

    @Test
    public void testSendBatchDoesNotThrowWithMockHttp() throws Exception {
        // Mock HttpClient and response
        HttpClient mockHttpClient = mock(HttpClient.class);
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockHttpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(mockResponse));

        // Create exporter with mock client
        LogSinkConfig config = LogSinkConfig.builder()
                .setApiKey("fake-api-key")
                .setOtlpEndpoint("http://localhost:4318/v1/logs")
                .setMaxBatchSize(100)
                .build();
        LogSinkExporter exporter = new LogSinkExporter(config, mockHttpClient);

        LogRecord record = LogRecord.newBuilder()
                .setBody(AnyValue.newBuilder().setStringValue("test").build())
                .setTimeUnixNano(System.currentTimeMillis() * 1_000_000)
                .build();

        // Verify no exception thrown on mock request
        assertDoesNotThrow(() ->
                exporter.sendBatch(List.of(record))
        );

        // Verify that the request was sent once on the mock client
        verify(mockHttpClient, times(1))
                .sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }
}