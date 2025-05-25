## 🪵 logsink

logsink is a lightweight Java library for exporting OpenTelemetry logs via OTLP/HTTP using protobuf.
It reads from plain log files, groups lines into structured records, batches them efficiently, and sends them to an OTEL-compatible collector.

⸻

✅ What does logsink do?
- Tails plain-text log files using RandomAccessFile, resuming from the last checkpoint.
- Groups multi line entries (e.g., stack traces) using a customizable regex to detect record boundaries.
- Extracts log severity (INFO, WARN, ERROR) from content and maps to OTEL SeverityNumber (to filter by log level in Cardinal UI).
- Converts raw text into OTEL LogRecords, including full body and timestamp. 
- Attaches resource-level attributes like service.name or env via a user-defined function. This is to allow for filtering by app in CardinalUI)
- Batched Processing, flushing either on batch size or elapsed time.
- Compresses and sends logs over OTLP/HTTP using GZIP and protobuf.
- Persists checkpoints per file, ensuring no logs are lost or reprocessed on restart.
- Restores in-flight files on startup from a JSON file (in_progress_files.json).
- Handles file deletion gracefully, cleaning up checkpoint and state if needed.
- Runs processing in parallel with a cached thread pool (with num threads = available processors).
- Includes a shutdown hook to stop threads and flush buffers cleanly.


## ✨ Quick Start

`Gradle`:
```java
dependencies {
    implementation 'io.cardinalhq:logsink:1.0.25'
}
```

`Maven`: 
```java
<dependency>
  <groupId>io.cardinalhq</groupId>
  <artifactId>logsink</artifactId>
  <version>1.0.25</version>
</dependency>
```

### Example Usage
```java
LogSinkConfig config = LogSinkConfig.builder()
    .setOtlpEndpoint("http://localhost:4318/v1/logs") // OTLP-compatible collector
    .setApiKey("your-api-key")                        // Optional: x-cardinalhq-api-key
    .setCheckpointPath(Paths.get("./checkpoints"))    // Persistent checkpoint directory
    .setMaxBatchSize(100)                             // Max logs per batch
    .setPublishFrequency(5000)                        // Max wait (ms) before flushing batch
    .setAttributesDeriver(filePath -> Map.of("service.name", "auth-service", "env", "prod")) // Derive attributes from file path
    .setRecordStartPattern(Pattern.compile("^\\d{4}-\\d{2}-\\d{2}")) // Custom record boundary
    .build();

LogSinkConsumer consumer = new LogSinkConsumer(config);

// Enqueue a file (this will be tailed, asynchronously processed and sent). An enqueued file, is written to the checkpoint directory, so that it can be resumed later if the process is restarted.
// In the case of a race condition, where the file at this path is already deleted, we will throw a `DeletedFileException`.
consumer.enqueue("/var/log/app/auth.log");

// Graceful shutdown (flush and stop all threads)
consumer.shutdown();
```

## ⚙️ Configuration Settings

When building a `LogSinkConfig`, the following settings let you customize log parsing, batching, and metadata enrichment:

| Setting              | Type                                  | Default Value                                                 | Description |
|----------------------|---------------------------------------|----------------------------------------------------------------|-------------|
| `recordStartPattern` | `Pattern`                             | `^\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}`               | Regular expression used to detect the **start of a new log record**. Crucial for grouping stack traces and multi-line logs into a **single** structured log. Most Java logs begin with a timestamp, which this default matches. |
| `maxBatchSize`       | `int`                                 | `100`                                                         | Maximum number of log records to batch together before sending to the OTLP endpoint. Helps avoid oversized payloads. |
| `publishFrequency`   | `int` (milliseconds)                  | `5000`                                                        | Maximum time (in milliseconds) to wait before flushing a batch, even if it's not full. |
| `attributesDeriver`  | `Function<String, Map<String, String>>` | `path -> Map.of("service.name", "logsink")`                   | Optional but **recommended**. Function to derive **resource-level OTEL attributes** (e.g. `service.name`, `env`) from the log file path. For example, from `/var/gatekeeper/app.log` → `"service.name" = "gatekeeper"`. These attributes help group logs by service in Cardinal's UI. |

### 💡 Example: attributesDeriver usage

```java
.setAttributesDeriver(path -> {
    String[] parts = path.split("/");
    return Map.of("service.name", parts.length > 2 ? parts[2] : "logsink");
})
```

### 💡 Notes

- The `recordStartPattern` is crucial for correctly grouping log lines into structured records. If your logs don't start with a timestamp, you can adjust this regex accordingly.
- The `attributesDeriver` function is optional but highly recommended to enrich logs with meaningful metadata. It can derive attributes based on the file path, environment variables, or any other logic you need.
- The `maxBatchSize` and `publishFrequency` settings help balance performance and resource usage. Adjust them based on your log volume and processing requirements.


