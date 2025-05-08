package com.cardinal.logsink;

import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.SeverityNumber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;


public class LogSink {
    private static final Logger logger = LoggerFactory.getLogger(LogSink.class);

    private final LogSinkBatcher batcher;

    public LogSink(LogSinkConfig config) {
        LogSinkExporter exporter = new LogSinkExporter(config);
        this.batcher = new LogSinkBatcher(config, exporter);
    }

    public boolean log(LogRecord record) {
        return batcher.add(record);
    }

    public boolean log(long timestamp, String message, Level level, String... tags) {
        List<KeyValue> attributes = new ArrayList<>();

        // Ensure tags are in key-value pairs
        if (tags.length % 2 != 0) {
            logger.warn("Tags must be key-value pairs. Ignoring incomplete pair.");
        }

        for (int i = 0; i < tags.length - 1; i += 2) {
            attributes.add(KeyValue.newBuilder()
                    .setKey(tags[i])
                    .setValue(AnyValue.newBuilder().setStringValue(tags[i + 1]).build())
                    .build());
        }

        SeverityNumber severityNumber = mapLevelToSeverity(level);

        LogRecord record = LogRecord.newBuilder()
                .setTimeUnixNano(timestamp)
                .setSeverityNumberValue(severityNumber.getNumber())
                .setSeverityText(level.getName())
                .setBody(AnyValue.newBuilder().setStringValue(message).build())
                .addAllAttributes(attributes)
                .build();

        return batcher.add(record);
    }

    private SeverityNumber mapLevelToSeverity(Level level) {
        int val = level.intValue();

        if (val >= Level.SEVERE.intValue()) return SeverityNumber.SEVERITY_NUMBER_ERROR;
        if (val >= Level.WARNING.intValue()) return SeverityNumber.SEVERITY_NUMBER_WARN;
        if (val >= Level.INFO.intValue()) return SeverityNumber.SEVERITY_NUMBER_INFO;
        if (val >= Level.FINE.intValue()) return SeverityNumber.SEVERITY_NUMBER_DEBUG;

        return SeverityNumber.SEVERITY_NUMBER_UNSPECIFIED;
    }

    public void flush() {
        batcher.flush();
    }

    public void shutdown() {
        batcher.shutdown();
    }
}
