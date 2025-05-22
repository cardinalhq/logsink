package com.cardinal.logsink;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

public class LogSinkConsumer {
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

        Future<?> future = this.executor.submit(() -> {
            Path checkpointPath = this.checkpointDir.resolve(safeFileName(logPath.toString()) + ".checkpoint");
            long lastOffset = loadCheckpoint(checkpointPath);

            try (RandomAccessFile file = new RandomAccessFile(logPath.toFile(), "r")) {
                file.seek(lastOffset);

                while (!Thread.currentThread().isInterrupted()) {
                    String line = file.readLine();
                    if (line != null) {
                        process(logPath.toString(), line);
                        lastOffset = file.getFilePointer();
                        saveCheckpoint(checkpointPath, lastOffset);
                    } else {
                        LOGGER.info("Finished reading " + logPath);
                        cleanupFile(logPath, checkpointPath);
                        break;
                    }
                }
            } catch (Exception e) {
                LOGGER.severe("Error reading " + logPath + ": " + e.getMessage());
            }
        });

        tasks.put(logPath, future);
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

    private void process(String file, String line) {
        // TODO: replace with your real processing logic
        System.out.printf("[%s] %s%n", file, line);
    }

    private String safeFileName(String path) {
        return path.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private synchronized void persistFileList(Path newPath) throws IOException {
        List<String> current = new ArrayList<>();
        if (Files.exists(fileListPath)) {
            current = objectMapper.readValue(fileListPath.toFile(), new TypeReference<List<String>>() {});
        }
        String p = newPath.toString();
        if (!current.contains(p)) {
            current.add(p);
            objectMapper.writeValue(fileListPath.toFile(), current);
        }
    }

    private void restoreEnqueuedFiles() throws IOException {
        if (!Files.exists(fileListPath)) return;

        List<String> paths = objectMapper.readValue(fileListPath.toFile(), new TypeReference<List<String>>() {});
        for (String p : paths) {
            try {
                enqueue(p);
            } catch (DeletedFileException dfe) {
                LOGGER.warning("Previously-enqueued file no longer exists; cleaning up: " + p);
                Path logPath = Paths.get(p);
                Path checkpointPath = checkpointDir.resolve(safeFileName(p) + ".checkpoint");
                cleanupFile(logPath, checkpointPath);
            } catch (IOException ioe) {
                LOGGER.warning("Failed to re-enqueue " + p + ": " + ioe.getMessage());
            }
        }
    }

    private synchronized void cleanupFile(Path logPath, Path checkpointPath) {
        tasks.remove(logPath);

        try {
            if (Files.exists(fileListPath)) {
                List<String> current = objectMapper.readValue(fileListPath.toFile(), new TypeReference<List<String>>() {});
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
            System.err.println("Usage: java com.cardinal.logsink.LogFileConsumer <log1> [log2 ...]");
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
}