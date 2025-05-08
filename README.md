# 🪵 logsink

**logsink** is a lightweight Java library for exporting OpenTelemetry logs over OTLP/HTTP using protobuf.  
It provides efficient batching, compression, and delivery of structured logs to an OpenTelemetry Collector or compatible backend.

---

## 📖 What Does logsink Do?

`logsink` helps you:

- Emit OTEL-compliant logs from Java apps
- Control export batching by event count
- Automatically gzip and send logs over HTTP
- Set custom `resource-level` and `record-level` attributes (like `service.name`, `env`, etc.)
- Integrate easily with any OTLP-compatible observability pipeline

---

## 📦 Importing logsink

We publish logsink via [JitPack](https://jitpack.io/#cardinalhq/logsink). To use it:

Add JitPack to your `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

Then add the dependency to your `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.cardinalhq:logsink:1.0.4")
}
```

🧱 OTEL Log Data Model

OpenTelemetry organizes log data like this:

```aiignore
ExportLogsServiceRequest
└── ResourceLogs         // Identifies the service or environment
    └── ScopeLogs        // Represents a logical scope or module
        └── LogRecord    // Actual log event with timestamp, message, etc.
```

🧩 Class Overview

🔧 LogSinkConfig

Defines how logsink behaves:

```java
public class LogSinkConfig {
    String otlpEndpoint;     // e.g. http://localhost:4318/v1/logs, this should be the cardinal receiver endpoint
    String apiKey;           // Optional API key sent as HTTP header
    int maxBatchSize;        // Max number of logs per batch
    int maxPayloadBytes;     // Max size of batch before flush (bytes)
}
```

🔧 LogSinkExporter

Handles log delivery:
- Builds an OTLP ExportLogsServiceRequest
- Adds resource-level metadata (like `service.name`, `env`)
- Compresses using GZIP
- Sends via HttpClient

```java
public void sendBatch(String appName, List<LogRecord> records, String... resourceTags)
```

🔧 LogSinkBatcher

Buffers and batches logs for export:
- Uses a `LinkedBlockingQueue<LogRecord>` internally
- Flushes on batch size or payload size threshold
- Periodically flushes every 5 seconds
- Runs flush in a background thread

```java
public void add(LogRecord record)
public void flush()
public void shutdown()
```

🪵 LogSink

The main entrypoint for using logsink in your application.
- Initializes a LogSinkBatcher internally
- Accepts both raw OpenTelemetry LogRecords and convenience method inputs
- Adds custom resource-level metadata like service.name, env, etc.
- Validates that resource tags are passed in key-value format (e.g., "env", "prod")
- Provides built-in batching, flushing, and shutdown support
- Supports custom HTTP headers for API key and other metadata

```java
// Create a LogSink with resource-level tags (must be key-value pairs)
LogSink logSink = new LogSink(config, "my-service", "env", "prod", "region", "us-west");

// Log with convenience method
logSink.log(
        System.currentTimeMillis() * 1_000_000,  // timestamp in nanoseconds
        "User login successful",                // message
Level.INFO,                              // java.util.logging.Level
        "user.id", "12345",                      // log-level attributes (tags)
        "auth.method", "password"
        );

// Log using a raw OpenTelemetry LogRecord
        logSink.log(record);

// Manually flush and shutdown if needed
logSink.flush();
logSink.shutdown(); 
```

✨ Convenience Logging Method
The `LogSink` class provides a convenient method for logging structured data:

```java
public void log(long timestamp, String message, Level level, String... tags)
```
- timestamp is in nanoseconds
- message is the log body
- tags are key-value pairs (must be even number) used as log-level attributes

🛠️ Creating a LogRecord Manually
```java
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.logs.v1.SeverityNumber;

LogRecord record = LogRecord.newBuilder()
    .setTimeUnixNano(System.currentTimeMillis() * 1_000_000)
    .setSeverityNumberValue(SeverityNumber.SEVERITY_NUMBER_INFO.getNumber())
    .setSeverityText("INFO")
    .setBody(AnyValue.newBuilder().setStringValue("User login successful").build())
    .addAttributes(KeyValue.newBuilder()
        .setKey("user.id")
        .setValue(AnyValue.newBuilder().setStringValue("12345").build())
        .build())
    .build();
```