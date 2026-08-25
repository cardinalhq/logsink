# 🪵 logsink

**logsink** is a Java library for sending OpenTelemetry-compatible logs over OTLP/HTTP in protobuf format. It provides a production-ready batching and export mechanism that allows applications to log structured data with custom resource-level attributes, sent efficiently to an OpenTelemetry Collector.

---

## 📖 What does logsink do?

logsink provides a structured way to export logs in the OpenTelemetry Protocol (OTLP) format. It converts raw `LogRecord` entries into `ExportLogsServiceRequest` payloads and sends them over HTTP with gzip compression. It handles batching by count and payload size, and supports periodic background flushing to avoid partial batch loss.

It also provides a standard OpenTelemetry `TracerProvider` that batches and
exports application spans over OTLP/HTTP. Callers can inject that provider (or
a `Tracer`) normally, and code that cannot use injection can obtain the same
provider through `Tracing`.

Logsink is suitable for use cases where you:
- Want fine-grained control over how OTEL logs are exported
- Need to add metadata like `service.name`, `env`, `region` at the resource level
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
logSink.log(logRecord);
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

## Traces and spans

An explicit builder endpoint takes precedence for that signal. If
`setOtlpEndpoint(...)` is omitted, logsink reads the standard OpenTelemetry
endpoint configuration in this order:

1. `OTEL_EXPORTER_OTLP_LOGS_ENDPOINT` for logs and
   `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` for traces.
2. `OTEL_EXPORTER_OTLP_ENDPOINT` as the common collector base URL.

The corresponding Java system properties (`otel.exporter.otlp.logs.endpoint`,
`otel.exporter.otlp.traces.endpoint`, and `otel.exporter.otlp.endpoint`) are
also supported and take precedence over environment variables. Signal-specific
endpoints are used as-is; the common endpoint automatically gets `/v1/logs` or
`/v1/traces` appended. Use `setTracesEndpoint(...)` for an explicit custom
trace route.

Initialize tracing once at application startup and close it at shutdown:

```java
LogSinkConfig config = LogSinkConfig.builder()
    .setApiKey(System.getenv("CARDINAL_API_KEY"))
    .setAppName("checkout-service")
    .addResourceAttribute("deployment.environment", "production")
    .build();

TraceSink traceSink = Tracing.initialize(config);
Runtime.getRuntime().addShutdownHook(new Thread(Tracing::shutdown));
```

Code can ask for the installed tracer from anywhere. OpenTelemetry context
automatically makes a span created inside the scope a child of `requestSpan`:

```java
Tracer tracer = Tracing.getTracer(OrderService.class);
Span requestSpan = tracer.spanBuilder("orders.create").startSpan();
try (Scope ignored = requestSpan.makeCurrent()) {
    requestSpan.setAttribute("order.id", orderId);
    createOrder(orderId);
} catch (Throwable error) {
    requestSpan.recordException(error);
    requestSpan.setStatus(StatusCode.ERROR);
    throw error;
} finally {
    requestSpan.end();
}
```

### Adding attributes (tags) to a span

OpenTelemetry calls span tags **attributes**. Attributes can be supplied when
the span is created or added while the span is active. They must be set before
`end()` is called:

```java
Span span = tracer.spanBuilder("payment.authorize")
    .setAttribute("payment.provider", "stripe")
    .setAttribute("payment.amount", 42.50)
    .setAttribute("payment.test", false)
    .startSpan();

try (Scope ignored = span.makeCurrent()) {
    span.setAttribute("payment.id", paymentId);
    authorizePayment(paymentId);
} catch (RuntimeException error) {
    span.recordException(error);
    span.setStatus(StatusCode.ERROR);
    throw error;
} finally {
    span.end();
}
```

Use span events for timestamped occurrences within a span:

```java
span.addEvent(
    "payment.authorized",
    Attributes.of(
        AttributeKey.stringKey("payment.provider"), "stripe",
        AttributeKey.stringKey("payment.id"), paymentId
    )
);
```

For dependency injection, inject the standard OpenTelemetry type. `TraceSink`
implements `TracerProvider`, so no logsink-specific type has to spread through
application code:

```java
final class OrderService {
    private final Tracer tracer;

    OrderService(TracerProvider tracerProvider) {
        this.tracer = tracerProvider.get(OrderService.class.getName());
    }
}
```

If an OpenTelemetry Java agent or another SDK is already registered globally,
`Tracing.getTracer(...)` uses it automatically until a provider is explicitly
installed or initialized through logsink.
