package com.cardinal.logsink;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.SeverityNumber;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LogSinkConsumer {
    private static final Pattern LEVEL_PATTERN = Pattern.compile(
            "\\b(?<lvl>SEVERE|ERROR|WARNING|WARN|INFO|DEBUG|FINE)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final String IN_PROGRESS_FILES = "in_progress_files.json";
    private static final Logger LOGGER = Logger.getLogger(LogSinkConsumer.class.getName());

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<Path, Future<?>> tasks = new ConcurrentHashMap<>();
    private final Path checkpointDir;
    private final Path fileListPath;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LogSinkConsumer(Path checkpointDir) throws IOException {
        this.checkpointDir = checkpointDir;
        this.fileListPath = checkpointDir.resolve(IN_PROGRESS_FILES);
        Files.createDirectories(checkpointDir);
        restoreEnqueuedFiles();
    }

    public synchronized void enqueue(String logPathStr) throws IOException {
        Path logPath = Paths.get(logPathStr).toAbsolutePath().normalize();
        if (!Files.exists(logPath)) {
            throw new DeletedFileException("Log file does not exist: " + logPath);
        }
        if (tasks.containsKey(logPath)) return;

        persistFileList(logPath);

        Future<?> future = executor.submit(() -> {
            Path checkpointPath = checkpointDir.resolve(safeFileName(logPath.toString()) + ".checkpoint");
            long lastOffset = loadCheckpoint(checkpointPath);

            try (RandomAccessFile file = new RandomAccessFile(logPath.toFile(), "r")) {
                file.seek(lastOffset);

                long bufferStart = -1;
                int numBuffered = 0;
                List<PendingLog> pending = new ArrayList<>();

                while (!Thread.currentThread().isInterrupted()) {
                    String line = file.readLine();

                    if (line == null) {
                        if (!pending.isEmpty() && process(pending)) {
                            checkpointAll(pending);
                        }
                        cleanupFile(logPath, checkpointPath);
                        break;
                    }

                    if (bufferStart < 0) {
                        bufferStart = System.currentTimeMillis();
                    }

                    long offset = file.getFilePointer();
                    pending.add(new PendingLog(buildRecord(line), checkpointPath, offset));
                    numBuffered++;

                    long elapsed = System.currentTimeMillis() - bufferStart;
                    if (elapsed >= 5_000 || numBuffered >= 100) {
                        boolean success = process(pending);
                        if (success) {
                            checkpointAll(pending);
                            pending.clear();
                            numBuffered = 0;
                            bufferStart = -1;
                        } else {
                            LOGGER.warning("Batch export failed; will retry");
                            bufferStart = System.currentTimeMillis();
                        }
                    }
                }
            } catch (Exception e) {
                LOGGER.severe("Error reading " + logPath + ": " + e.getMessage());
            }
        });

        tasks.put(logPath, future);
    }

    private LogRecord buildRecord(String line) {
        SeverityNumber sev = extractSeverity(line);
        return LogRecord.newBuilder()
                .setTimeUnixNano(System.currentTimeMillis() * 1_000_000)
                .setSeverityNumberValue(sev.getNumber())
                .setSeverityText(sev.name())
                .setBody(AnyValue.newBuilder().setStringValue(line).build())
                .build();
    }

    private boolean process(List<PendingLog> batch) {
        // TODO: implement export logic; for now always fail
        return false;
    }

    private void checkpointAll(List<PendingLog> batch) {
        long maxOffset = batch.stream()
                .mapToLong(p -> p.offset)
                .max()
                .orElse(0L);
        saveCheckpoint(batch.get(0).checkpointPath, maxOffset);
    }

    private SeverityNumber extractSeverity(String logLine) {
        Matcher m = LEVEL_PATTERN.matcher(logLine);
        if (m.find()) {
            String token = m.group("lvl").toUpperCase();
            return switch (token) {
                case "ERROR", "SEVERE" -> SeverityNumber.SEVERITY_NUMBER_ERROR;
                case "WARN", "WARNING" -> SeverityNumber.SEVERITY_NUMBER_WARN;
                case "DEBUG", "FINE" -> SeverityNumber.SEVERITY_NUMBER_DEBUG;
                case "INFO" -> SeverityNumber.SEVERITY_NUMBER_INFO;
                default -> SeverityNumber.SEVERITY_NUMBER_UNSPECIFIED;
            };
        }
        return SeverityNumber.SEVERITY_NUMBER_UNSPECIFIED;
    }

    private long loadCheckpoint(Path checkpointPath) {
        try {
            if (!Files.exists(checkpointPath)) return 0L;
            try (BufferedReader reader = Files.newBufferedReader(checkpointPath)) {
                String line = reader.readLine();
                return (line != null) ? Long.parseLong(line) : 0L;
            }
        } catch (IOException e) {
            LOGGER.warning("Failed to read checkpoint for " + checkpointPath + ": " + e.getMessage());
            return 0L;
        }
    }

    private void saveCheckpoint(Path checkpointPath, long offset) {
        try (BufferedWriter writer = Files.newBufferedWriter(checkpointPath)) {
            writer.write(Long.toString(offset));
        } catch (IOException e) {
            LOGGER.warning("Failed to write checkpoint for " + checkpointPath + ": " + e.getMessage());
        }
    }

    private String safeFileName(String path) {
        return path.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private synchronized void persistFileList(Path newPath) throws IOException {
        List<String> current = new ArrayList<>();
        if (Files.exists(fileListPath)) {
            current = objectMapper.readValue(fileListPath.toFile(), new TypeReference<>() {
            });
        }
        String p = newPath.toString();
        if (!current.contains(p)) {
            current.add(p);
            objectMapper.writeValue(fileListPath.toFile(), current);
        }
    }

    private void restoreEnqueuedFiles() throws IOException {
        if (!Files.exists(fileListPath)) return;
        List<String> paths = objectMapper.readValue(fileListPath.toFile(), new TypeReference<>() {
        });
        for (String p : paths) {
            try {
                enqueue(p);
            } catch (DeletedFileException dfe) {
                LOGGER.warning("Previously-enqueued file missing; cleaning up: " + p);
                Path logPath = Paths.get(p);
                Path cp = checkpointDir.resolve(safeFileName(p) + ".checkpoint");
                cleanupFile(logPath, cp);
            } catch (IOException ioe) {
                LOGGER.warning("Failed to re-enqueue " + p + ": " + ioe.getMessage());
            }
        }
    }

    private synchronized void cleanupFile(Path logPath, Path checkpointPath) {
        tasks.remove(logPath);
        try {
            if (Files.exists(fileListPath)) {
                List<String> current = objectMapper.readValue(fileListPath.toFile(), new TypeReference<>() {
                });
                current.remove(logPath.toString());
                objectMapper.writeValue(fileListPath.toFile(), current);
            }
            Files.deleteIfExists(checkpointPath);
            LOGGER.info("Cleaned up tracking for: " + logPath);
        } catch (IOException e) {
            LOGGER.warning("Error cleaning up state for " + logPath + ": " + e.getMessage());
        }
    }

    public void shutdown() {
        tasks.values().forEach(f -> f.cancel(true));
        executor.shutdownNow();
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("Usage: java com.cardinal.logsink.LogSinkConsumer <log1> [log2 ...]");
            System.exit(1);
        }
        LogSinkConsumer consumer = new LogSinkConsumer(Paths.get("./checkpoints"));
        for (String log : args) {
            try {
                consumer.enqueue(log);
            } catch (DeletedFileException dfe) {
                System.err.println("Cannot enqueue missing file: " + dfe.getMessage());
            }
        }
        Runtime.getRuntime().addShutdownHook(new Thread(consumer::shutdown));
        Thread.currentThread().join();
    }

    public static class DeletedFileException extends IOException {
        public DeletedFileException(String message) {
            super(message);
        }
    }

    public record PendingLog(LogRecord record, Path checkpointPath, long offset) {}
}