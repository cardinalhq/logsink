package com.cardinal.logsink;

import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.common.v1.AnyValue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class LogSinkTest {

    static class TestExporter extends LogSinkExporter {
        public List<List<LogRecord>> batchesSent = new ArrayList<>();

        public TestExporter(LogSinkConfig config) {
            super(config);
        }

        @Override
        public void sendBatch(List<LogRecord> records) {
            batchesSent.add(new ArrayList<>(records));
        }
    }

    @Test
    public void testBatchFlushesOnSize() throws InterruptedException {
        LogSinkConfig config = LogSinkConfig.builder()
                .setApiKey("fake-api-key")
                .setOtlpEndpoint("http://localhost:4318/v1/logs")
                .setMaxBatchSize(2)
                .setAppName("test-app")
                .addResourceAttribute("env", "test")
                .build();

        TestExporter exporter = new TestExporter(config);
        LogSinkBatcher batcher = new LogSinkBatcher(config, exporter);

        LogRecord record = LogRecord.newBuilder()
                .setBody(AnyValue.newBuilder().setStringValue("test").build())
                .setTimeUnixNano(System.currentTimeMillis() * 1_000_000)
                .build();

        batcher.add(record);
        batcher.add(record); // should flush after 2

        // Wait for background thread
        Thread.sleep(500);

        assertEquals(1, exporter.batchesSent.size());
        assertEquals(2, exporter.batchesSent.get(0).size());

        batcher.shutdown();
    }

    @Test
    public void testFlushManual() throws InterruptedException {
        LogSinkConfig config = LogSinkConfig.builder()
                .setApiKey("fake-api-key")
                .setOtlpEndpoint("http://localhost:4318/v1/logs")
                .setMaxBatchSize(100)
                .setAppName("test-app")
                .addResourceAttribute("env", "test")
                .build();

        TestExporter exporter = new TestExporter(config);
        LogSinkBatcher batcher = new LogSinkBatcher(config, exporter);

        LogRecord record = LogRecord.newBuilder()
                .setBody(AnyValue.newBuilder().setStringValue("manual").build())
                .setTimeUnixNano(System.currentTimeMillis() * 1_000_000)
                .build();

        batcher.add(record);
        batcher.flush(); // flush manually

        Thread.sleep(200);
        assertEquals(1, exporter.batchesSent.size());

        batcher.shutdown();
    }
}