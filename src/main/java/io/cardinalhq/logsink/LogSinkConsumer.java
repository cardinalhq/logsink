package io.cardinalhq.logsink;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import io.opentelemetry.proto.logs.v1.SeverityNumber;
import io.opentelemetry.proto.resource.v1.Resource;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

public class LogSinkConsumer {
    private static final org.slf4j.Logger logger = LoggerFactory.getLogger(LogSinkConsumer.class);

    private static final String CARDINAL_API_KEY_HEADER = "x-cardinalhq-api-key";
    private static final Pattern LEVEL_PATTERN = Pattern.compile("\\b(?<lvl>SEVERE|ERROR|WARNING|WARN|INFO|DEBUG|FINE)\\b",
            Pattern.CASE_INSENSITIVE);
    // Pattern: start of line matches ISO-like timestamp: YYYY-MM-DD[ T]HH:MM:SS
    private static final String IN_PROGRESS_FILES = "in_progress_files.json";

    private final ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    private final LogSinkConfig config;
    private final Map<Path, Future<?>> tasks = new ConcurrentHashMap<>();
    private final Path checkpointDir;
    private final Path fileListPath;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Pattern recordStartPattern;
    private final HttpClient httpClient;

    public LogSinkConsumer(LogSinkConfig config) throws IOException {
        this.config = config;
        this.checkpointDir = config.getCheckpointPath();
        this.httpClient = HttpClient.newHttpClient();
        this.fileListPath = checkpointDir.resolve(IN_PROGRESS_FILES);
        this.recordStartPattern = config.getRecordStartPattern();
        Files.createDirectories(checkpointDir);
        restoreEnqueuedFiles();
    }

    protected void processLogFile(Path logPath) {
        Path checkpointPath = checkpointDir.resolve(logPath.getFileName() + ".checkpoint");
        long lastOffset = loadCheckpoint(checkpointPath);

        try (RandomAccessFile file = new RandomAccessFile(logPath.toFile(), "r")) {
            file.seek(lastOffset);

            long bufferStart = -1;
            int numBuffered = 0;
            List<PendingLog> pending = new ArrayList<>();

            StringBuilder current = new StringBuilder();
            long recordStartOffset = lastOffset;

            while (true) {
                long lineStart = file.getFilePointer();
                String line = file.readLine();

                if (line == null) {
                    if (!current.isEmpty()) {
                        pending.add(makePending(current.toString(), recordStartOffset, checkpointPath));
                    }
                    boolean success = pending.isEmpty() || process(logPath.toString(), pending);

                    if (success) {
                        checkpointAll(pending);
                        cleanupFile(logPath, checkpointPath);
                    }
                    break;
                }

                boolean isStart = this.recordStartPattern.matcher(line).find();
                if (isStart) {
                    if (!current.isEmpty()) {
                        long recordEndOffset = file.getFilePointer();
                        pending.add(makePending(current.toString(), recordEndOffset, checkpointPath));
                    }
                    current.setLength(0);
                    recordStartOffset = lineStart;
                }
                current.append(line).append("\n");

                if (bufferStart < 0) {
                    bufferStart = System.currentTimeMillis();
                }
                numBuffered++;

                long elapsed = System.currentTimeMillis() - bufferStart;
                if (!pending.isEmpty() && (elapsed >= config.getPublishFrequency() || numBuffered >= config.getMaxBatchSize())) {
                    boolean success = process(logPath.toString(), pending);
                    if (success) {
                        checkpointAll(pending);
                        pending.clear();
                        numBuffered = 0;
                        bufferStart = -1;
                    } else {
                        logger.warn("Batch export failed; will retry");
                        bufferStart = System.currentTimeMillis();
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error reading {}: {}", logPath, e.getMessage());
        }
    }

    public synchronized void enqueue(String logPathStr) throws IOException {
        Path logPath = Paths.get(logPathStr).toAbsolutePath().normalize();
        if (!Files.exists(logPath)) {
            throw new DeletedFileException("Log file does not exist: " + logPath);
        }
        if (tasks.containsKey(logPath)) return;

        persistFileList(logPath);

        Future<?> future = executor.submit(() -> processLogFile(logPath));
        tasks.put(logPath, future);
    }

    private PendingLog makePending(String message, long offset, Path checkpointPath) {
        SeverityNumber sev = extractSeverity(message);
        LogRecord record = LogRecord.newBuilder()
                .setTimeUnixNano(System.currentTimeMillis() * 1_000_000)
                .setSeverityNumberValue(sev.getNumber())
                .setSeverityText(sev.name())
                .setBody(AnyValue.newBuilder().setStringValue(message).build())
                .build();
        return new PendingLog(record, checkpointPath, offset);
    }

    protected boolean process(String filePath, List<PendingLog> batch) {
        List<LogRecord> records = new ArrayList<>();
        for (PendingLog pending : batch) {
            LogRecord record = pending.record;
            records.add(record);
        }
        ScopeLogs scopeLogs = ScopeLogs.newBuilder()
                .addAllLogRecords(records)
                .build();

        Map<String, String> attributes = this.config.getAttributesDeriver().apply(filePath);
        List<KeyValue> keyValues = new ArrayList<>();
        for (Map.Entry<String, String> entry : attributes.entrySet()) {
            keyValues.add(KeyValue.newBuilder()
                    .setKey(entry.getKey())
                    .setValue(AnyValue.newBuilder().setStringValue(entry.getValue()).build())
                    .build());
        }
        Resource resource = Resource.newBuilder()
                .addAllAttributes(keyValues)
                .build();
        ResourceLogs resourceLogs = ResourceLogs.newBuilder()
                .setResource(resource)
                .addScopeLogs(scopeLogs)
                .build();

        ExportLogsServiceRequest request = ExportLogsServiceRequest.newBuilder()
                .addResourceLogs(resourceLogs)
                .build();

        byte[] payload = request.toByteArray();
        return sendHttp(payload);
    }

    private byte[] gzip(byte[] data) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             GZIPOutputStream gzipOut = new GZIPOutputStream(bos)) {
            gzipOut.write(data);
            gzipOut.close();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to gzip payload", e);
        }
    }

    private boolean sendHttp(byte[] payload) {
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(config.getOTLPEndpoint()))
                    .header(CARDINAL_API_KEY_HEADER, config.getApiKey())
                    .header("Content-Type", "application/x-protobuf")
                    .header("Content-Encoding", "gzip")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(gzip(payload)))
                    .build();

            HttpResponse<String> response = this.httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception e) {
            logger.error("Failed to send logs", e);
            return false;
        }
    }


    protected void checkpointAll(List<PendingLog> batch) {
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
            logger.warn("Failed to read checkpoint for {}: {}", checkpointPath, e.getMessage());
            return 0L;
        }
    }

    private void saveCheckpoint(Path checkpointPath, long offset) {
        try (BufferedWriter writer = Files.newBufferedWriter(checkpointPath)) {
            writer.write(Long.toString(offset));
        } catch (IOException e) {
            logger.warn("Failed to write checkpoint for {}: {}", checkpointPath, e.getMessage());
        }
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
                logger.warn("Previously-enqueued file missing; cleaning up: {}", p);
                Path logPath = Paths.get(p);
                Path cp = checkpointDir.resolve(p + ".checkpoint");
                cleanupFile(logPath, cp);
            } catch (IOException ioe) {
                logger.warn("Failed to re-enqueue {}: {}", p, ioe.getMessage());
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
            logger.info("Cleaned up tracking for: {}", logPath);
        } catch (IOException e) {
            logger.warn("Error cleaning up state for {}: {}", logPath, e.getMessage());
        }
    }

    public void shutdown() {
        tasks.values().forEach(f -> f.cancel(true));
        executor.shutdownNow();
    }


    public static class DeletedFileException extends IOException {
        public DeletedFileException(String message) {
            super(message);
        }
    }

    public record PendingLog(LogRecord record, Path checkpointPath, long offset) {
    }
}