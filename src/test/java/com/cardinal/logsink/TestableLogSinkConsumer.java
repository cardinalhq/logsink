package com.cardinal.logsink;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class TestableLogSinkConsumer extends LogSinkConsumer {
    private final List<PendingLog> processedBatches = new ArrayList<>();

    public TestableLogSinkConsumer(LogSinkConfig config) throws IOException {
        super(config);
    }

    @Override
    protected boolean process(String filePath, List<PendingLog> batch) {
        processedBatches.addAll(batch);
        return true;
    }

    public List<PendingLog> getProcessedBatches() {
        return processedBatches;
    }
}
