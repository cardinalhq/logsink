# 🪵 logsink

**logsink** is a Java library for sending OpenTelemetry-compatible logs over OTLP/HTTP in protobuf format. It provides a production-ready batching and export mechanism that allows applications to log structured data with custom resource-level attributes, sent efficiently to an OpenTelemetry Collector.

---

## 📖 What does logsink do?

logsink provides a structured way to export logs in the OpenTelemetry Protocol (OTLP) format. It converts raw `LogRecord` entries into `ExportLogsServiceRequest` payloads and sends them over HTTP with gzip compression.
It handles batching by count and payload size, and supports periodic background flushing to avoid partial batch loss.

Logsink is suitable for use cases where you:
- Want fine-grained control over how OTEL logs are exported
- Need to add metadata like `service.name`, `env`, `region` at the resource level. You can add whatever other custom resource-level attributes you want.
- Are building telemetry pipelines that send logs downstream to OTEL-compatible backends

---

## 📦 OTEL Data Model 

```aiignore
ExportLogsServiceRequest
└── ResourceLogs         ← One per unique resource (e.g. a service or host)
    └── ScopeLogs        ← One per instrumentation library or logical log scope
        └── LogRecord    ← Individual log entry (timestamp, message, severity, etc.)
```

## 📦 Class Overview

### 🔧 `LogSinkConfig`

> Holds configuration needed to construct and operate the logsink pipeline.

```java
public class LogSinkConfig {
    String otlpEndpoint;     // URL of the OTLP HTTP collector (e.g. http://localhost:4318/v1/logs)
    String apiKey;           // API key sent as an HTTP header
    int maxBatchSize;        // Flush when number of logs reaches this
    int maxPayloadBytes;     // Flush when raw (uncompressed) size exceeds this
}
```

### 🔧 `LogSinkExporter`

Responsible for sending logs over the wire.

	•	Builds a protobuf ExportLogsServiceRequest
	•	Adds resource-level attributes (e.g. service.name, env)
	•	Compresses with GZIP
	•	Sends to the OTLP endpoint via HttpClient


```java
public void sendBatch(String appName, List<LogRecord> records, String... resourceTags)
```

### 🔧 `LogsinkBatcher`

Buffers log records and triggers sendBatch() based on configured thresholds.

	•	Uses LinkedBlockingQueue<LogRecord> internally
	•	Flushes when batch size or payload size limit is hit
	•	Also flushes every 5 seconds (scheduled task)
	•	Drains and exports logs in a background worker thread

```java
public void add(LogRecord record)
public void flush()
public void shutdown()
```

🪵 Logsink

The public-facing class you use to log data.

	•	Owns a LogsinkBatcher and delegates to it
	•	Designed to be the primary entrypoint for developers

```java
LogSink logSink = new LogSink(config, "my-service", "env", "prod");
logSink.log(timestamp, message, level, tags); // or logSink.log(record);
logSink.flush();
logSink.shutdown();
```

### Using the convenience log method on the `LogSink` class which would convert the string message to a `LogRecord` for you.

```java
public void log(long timestamp, String message, Level level, String... tags) // tags here are structured attributes you attach at the logRecord level. 
```

### Instantiating the raw LogRecord

```java
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.logs.v1.SeverityNumber;

LogRecord record = LogRecord.newBuilder()
    .setTimeUnixNano(System.currentTimeMillis() * 1_000_000) // current time in nanoseconds
    .setSeverityNumberValue(SeverityNumber.SEVERITY_NUMBER_INFO.getNumber())
    .setSeverityText("INFO")
    .setBody(AnyValue.newBuilder().setStringValue("User login successful").build())
    .addAttributes(KeyValue.newBuilder()
        .setKey("user.id")
        .setValue(AnyValue.newBuilder().setStringValue("12345").build())
        .build())
    .build();
```