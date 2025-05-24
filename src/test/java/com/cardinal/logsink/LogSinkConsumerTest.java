package com.cardinal.logsink;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

public class LogSinkConsumerTest {

    private static final Path TEST_DIR = Paths.get("./logsink-test");
    private Path dir;

    @BeforeEach
    public void setup() throws IOException {
        deleteTestDir();
        Files.createDirectory(TEST_DIR);
        dir = TEST_DIR;
    }

    @AfterEach
    public void cleanup() throws IOException {
        deleteTestDir();
    }

    private void deleteTestDir() throws IOException {
        if (Files.exists(TEST_DIR)) {
            Files.walk(TEST_DIR)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                        }
                    });
        }
    }

    @Test
    public void testIfFileGetsProcessedCorrectly() throws Exception {
        Path logFile = dir.resolve("test.log");
        String[] logLines = {
                "2024-01-01 00:00:01 INFO first line",
                "continued line 1",
                "2024-01-01 00:00:02 ERROR second line"
        };
        Files.write(logFile, String.join("\n", logLines).getBytes());

        LogSinkConfig config = LogSinkConfig.builder()
                .setOtlpEndpoint("http://localhost:4318/v1/logs")
                .setApiKey("dummy")
                .setMaxBatchSize(1)
                .setPublishFrequency(100)
                .setCheckpointPath(dir)
                .setAttributesDeriver(p -> Map.of("service.name", "test"))
                .setRecordStartPattern(Pattern.compile("^\\d{4}-\\d{2}-\\d{2}"))
                .build();

        TestableLogSinkConsumer consumer = new TestableLogSinkConsumer(config);

        consumer.enqueue(logFile.toString());

        Thread.sleep(2000);

        List<LogSinkConsumer.PendingLog> pending =  consumer.getProcessedBatches();
        assertFalse(pending.isEmpty());
        assertEquals(2, pending.size());

        consumer.shutdown();
    }
}