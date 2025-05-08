# 🪵 logsink

**logsink** is a lightweight Java library for exporting OpenTelemetry logs over OTLP/HTTP using protobuf.  
It provides efficient batching, compression, and delivery of structured logs to an OpenTelemetry Collector or compatible backend.

---

## 📖 What Does `logsink` Do?

`logsink` helps you:

- Emit OTEL-compliant logs from Java apps
- Control export batching by event count
- Automatically gzip and send logs over HTTP
- Set custom `resource-level` and `record-level` attributes (like `service.name`, `env`, etc.)
- Integrate easily with any OTLP-compatible observability pipeline

---

## Main API

The `LogSink` class provides a convenient method for logging structured data:

```java
public void log(long timestamp, String message, Level level, String... tags)
```
- timestamp is in nanoseconds
- message is the log body
- tags are key-value pairs (must be even number) used as log-level attributes

🪵 LogSink

The main entrypoint for using `logsink` in your application.
- Initializes a LogSinkBatcher internally
- Accepts both raw OpenTelemetry LogRecords and convenience method inputs
- Adds custom resource-level metadata like service.name, env, etc.
- Validates that resource tags are passed in key-value format (e.g., "env", "prod")
- Provides built-in batching, flushing, and shutdown support
- Supports custom HTTP headers for API key and other metadata

```java
// Create a LogSink with resource-level tags (must be key-value pairs)
LogSinkConfig config = LogSinkConfig.builder()
        .otlpEndpoint("http://localhost:4318/v1/logs") // OTLP receiver endpoint
        .apiKey("your-api-key")                       // Optional API key
        .maxBatchSize(100)                            // Max logs per batch
        .setAppName("my-app")                         // Set app name 
        .addResourceAttribute("env", "prod")          // Add resource-level attributes or tags. These are optional.
        .addResourceAttribute("stack", "equities")                 
        .build();

LogSink logSink = new LogSink(config);

// Log with convenience method
logSink.log(System.currentTimeMillis() * 1_000_000,  // timestamp in nanoseconds
            "User login successful",                // message
            Level.INFO,                              // java.util.logging.Level
            "user.id", "12345",                      // custom log-level attributes (tags) optional
            "auth.method", "password");


// Manually flush and shutdown if needed
logSink.flush();
logSink.shutdown(); 
```

## 📦 Importing `logsink`

We publish `logsink` via [JitPack](https://jitpack.io/#cardinalhq/logsink). To use it:

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
    implementation("com.github.cardinalhq:logsink:1.0.11")
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


