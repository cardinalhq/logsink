package com.cardinal.logsink;

import io.opentelemetry.proto.logs.v1.LogRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;



public class LogSink {
    private static final Logger logger = LoggerFactory.getLogger(LogSink.class);

    private final LogSinkBatcher batcher;

    public LogSink(LogSinkConfig config, String appName, String... resourceTags) {
        LogSinkExporter exporter = new LogSinkExporter(config);
        this.batcher = new LogSinkBatcher(config, exporter, appName, resourceTags);
        logger.info("LogSink initialized with appName: {}, resourceTags: {}", appName, String.join(", ", resourceTags));
    }

    public void log(LogRecord record) {
        batcher.add(record);
    }

    public void flush() {
        batcher.flush();
    }

    public void shutdown() {
        batcher.shutdown();
    }
}
