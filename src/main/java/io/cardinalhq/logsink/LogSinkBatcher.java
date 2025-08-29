package io.cardinalhq.logsink;

import io.opentelemetry.proto.logs.v1.LogRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

public class LogSinkBatcher {
    private final BlockingQueue<LogRecord> queue;
    private final LogSinkExporter exporter;
    private final int maxBatchSize;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService worker;

    public LogSinkBatcher(LogSinkConfig config, LogSinkExporter exporter) {
        this.queue = new LinkedBlockingQueue<>(config.getQueueSize());
        this.exporter = exporter;
        this.maxBatchSize = config.getMaxBatchSize();

        this.scheduler = Executors.newScheduledThreadPool(1);
        this.worker = Executors.newSingleThreadExecutor();

        // flush every 5 seconds if not full
        scheduler.scheduleAtFixedRate(this::flush, 5, 5, TimeUnit.SECONDS);

        // continuously drain the queue in the background
        worker.submit(this::runBatchingLoop);
    }

    public boolean add(LogRecord record) {
        return queue.offer(record); // optionally handle backpressure
    }

    public void flush() {
        List<LogRecord> batch = new ArrayList<>();
        queue.drainTo(batch);
        if (!batch.isEmpty()) {
            exporter.sendBatch(batch);
        }
    }

    private void runBatchingLoop() {
        try {
            while (true) {
                List<LogRecord> batch = new ArrayList<>();
                // Block until at least one record is present
                batch.add(queue.take());
                queue.drainTo(batch, maxBatchSize - 1); // already took one

                if (!batch.isEmpty()) {
                    exporter.sendBatch(batch);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch(Exception e) {
            e.printStackTrace();
        }
    }

    public void shutdown() {
        scheduler.shutdown();
        worker.shutdownNow();
        flush(); // flush any remaining logs
    }
}