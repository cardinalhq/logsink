🪵 logsink

logsink is a lightweight Java library for exporting OpenTelemetry logs via OTLP/HTTP using protobuf.
It reads from plain log files, groups lines into structured records, batches them efficiently, and sends them to an OTEL-compatible collector.

⸻

✅ What does logsink do?
•	🧠 Detects log record boundaries using regex (e.g., by timestamp)
•	📄 Converts raw log lines into OTEL LogRecord messages
•	🧵 Buffers and batches logs with configurable frequency and batch size
•	📤 Compresses and sends batches over OTLP/HTTP using GZIP
•	🪪 Attaches resource-level attributes like service.name, env, etc.
•	🔁 Supports file checkpointing to resume partial processing after restart


✨ Quick Start

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

// Enqueue a file (this will be tailed, asynchronously processed and sent)
consumer.enqueue("/var/log/app/auth.log");

// Graceful shutdown (flush and stop all threads)
consumer.shutdown();
```

