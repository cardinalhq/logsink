package com.cardinal.logsink;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class TestableLogSinkConsumer extends LogSinkConsumer {
    private final List<PendingLog> processedBatches = new ArrayList<>();
    private int maxRecordsToProcess = Integer.MAX_VALUE;
    private int processedCount = 0;

    public TestableLogSinkConsumer(LogSinkConfig config) throws IOException {
        super(config);
    }

    public void setMaxRecordsToProcess(int max) {
        this.maxRecordsToProcess = max;
    }

    public int getProcessedCount() {
        return processedCount;
    }

    public List<PendingLog> getProcessedBatches() {
        return processedBatches;
    }

    @Override
    protected boolean process(String filePath, List<PendingLog> batch) {
        List<PendingLog> toProcess = new ArrayList<>();
        for (PendingLog p : batch) {
            if (processedCount >= maxRecordsToProcess) {
                // Write partial progress
                if (!toProcess.isEmpty()) {
                    checkpointAll(toProcess);
                }
                return false; // Simulate failure
            }
            toProcess.add(p);
            processedBatches.add(p);
            processedCount++;
        }

        // All succeeded
        return true;
    }

    @Override
    public synchronized void enqueue(String logPathStr) {
        Path logPath = Path.of(logPathStr).toAbsolutePath().normalize();
        processLogFile(logPath);
    }
}