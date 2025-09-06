package io.cardinalhq.logsink;

import com.lmax.disruptor.*;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import io.opentelemetry.proto.logs.v1.LogRecord;

import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public class LogSinkBatcher {
    static final class LogEvent {
        LogRecord record;
        boolean flushTick;

        void setRecord(LogRecord r) {
            this.record = r;
            this.flushTick = false;
        }

        void setFlushTick() {
            this.record = null;
            this.flushTick = true;
        }

        void clear() {
            this.record = null;
            this.flushTick = false;
        }
    }

    private static final EventFactory<LogEvent> EVENT_FACTORY = LogEvent::new;

    private final Disruptor<LogEvent> disruptor;
    private final RingBuffer<LogEvent> ring;
    private final ScheduledExecutorService scheduler;

    private volatile boolean running = true;

    public LogSinkBatcher(LogSinkConfig config, LogSinkExporter exporter) {
        LogSinkExporter exporter1 = Objects.requireNonNull(exporter, "exporter");
        int maxBatchSize = Math.max(1, config.getMaxBatchSize());

        int ringSize = pow2AtLeast(config.getQueueSize()); // Disruptor requires power-of-two
        WaitStrategy waitStrategy = new BlockingWaitStrategy();

        ThreadFactory workerFactory = r -> {
            Thread t = new Thread(r, "logsink-disruptor-worker");
            t.setDaemon(true);
            return t;
        };

        this.disruptor = new Disruptor<>(
                EVENT_FACTORY,
                ringSize,
                workerFactory,
                ProducerType.MULTI,       // many logging threads
                waitStrategy
        );

        disruptor.handleEventsWith(new BatchingHandler(exporter1, maxBatchSize));
        disruptor.setDefaultExceptionHandler(new ExceptionHandler<>() {
            @Override
            public void handleEventException(Throwable ex, long seq, LogEvent evt) {
                ex.printStackTrace();
            }

            @Override
            public void handleOnStartException(Throwable ex) {
                ex.printStackTrace();
            }

            @Override
            public void handleOnShutdownException(Throwable ex) {
                ex.printStackTrace();
            }
        });

        this.ring = disruptor.start();

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "log-sink-flush-ticker");
            t.setDaemon(true);
            return t;
        });
        this.scheduler.scheduleAtFixedRate(this::postFlushTick, 1, 1, TimeUnit.SECONDS);
    }

    public boolean add(LogRecord record) {
        if (!running) return false;
        if (record == null) return true; // ignore nulls safely

        EventTranslatorOneArg<LogEvent, LogRecord> tx =
                (evt, seq, rec) -> {
                    evt.clear();
                    evt.setRecord(rec);
                };

        return ring.tryPublishEvent(tx, record); // non-blocking, mirrors LinkedBlockingQueue.offer()
    }

    public void flush() {
        if (!running) return;
        postFlushTick();
    }

    public void shutdown() {
        running = false;
        try {
            postFlushTick();
            scheduler.shutdownNow();
            disruptor.shutdown();
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private void postFlushTick() {
        if (!running) return;
        EventTranslator<LogEvent> tickTx = (evt, seq) -> {
            evt.clear();
            evt.setFlushTick();
        };
        ring.tryPublishEvent(tickTx); // if full, fine—the next record/tick will flush
    }

    private static int pow2AtLeast(int n) {
        int x = 1;
        while (x < n) x <<= 1;
        return Math.max(1024, x); // sensible minimum
    }

    static final class BatchingHandler implements EventHandler<LogEvent> {
        private final LogSinkExporter exporter;
        private final ArrayList<LogRecord> batch;
        private final int maxBatchSize;

        BatchingHandler(LogSinkExporter exporter, int maxBatchSize) {
            this.exporter = exporter;
            this.maxBatchSize = maxBatchSize;
            this.batch = new ArrayList<>(Math.min(Math.max(16, maxBatchSize), 1024));
        }

        @Override
        public void onStart() { /* no-op */ }

        @Override
        public void onShutdown() {
            flushBatch();
        }

        @Override
        public void onEvent(LogEvent event, long sequence, boolean endOfBatch) {
            if (event.flushTick) {
                flushBatch();
                return;
            }

            if (event.record != null) {
                batch.add(event.record);
                if (batch.size() >= maxBatchSize) {
                    flushBatch();
                }
            }

            if (endOfBatch) {
                flushBatch();
            }

            event.clear();
        }

        private void flushBatch() {
            if (!batch.isEmpty()) {
                try {
                    exporter.sendBatch(batch);
                } finally {
                    batch.clear();
                }
            }
        }
    }
}