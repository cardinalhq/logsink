## 🪵 logsink

logsink is a tiny, production-ready Java library for shipping OpenTelemetry logs (OTLP/HTTP + protobuf + gzip). It includes:
- A Log4j2 Appender (LogSinkAppender) so you can drop it into existing apps.
- A batched exporter (LogSinkExporter) that builds OTLP requests and sends over HTTP.
- A high-throughput batcher (LogSinkBatcher) built on the LMAX Disruptor ring buffer to minimize allocations and GC.

```java
dependencies {
    implementation("io.cardinalhq:logsink:1.0.51")
}
```

## What does it do?
- Converts LogRecord → ExportLogsServiceRequest (OTLP) with your resource attributes (e.g., service.name, environment, cluster).
- Batches logs by count and flushes periodically to keep latency low even when traffic is sparse.
- Compresses payloads with gzip and sends them with Java 11+ HttpClient.
- Plugs into Log4j2 via <LogSink .../> appender config, or you can use the LogSink class directly.

🧱 Key Components

`LogSinkConfig`

Builds the runtime configuration: OTLP endpoint, API key, queue size, batch size, and resource attributes.

```java
LogSinkConfig config = LogSinkConfig.builder()
    .setOtlpEndpoint("http://localhost:4318/v1/logs")
    .setApiKey("YOUR_API_KEY")              // optional header x-cardinalhq-api-key
    .setQueueSize(1024)                     // desired capacity (rounded up to power-of-two for the ring)
    .setMaxBatchSize(100)                   // send when we have this many logs
    .addResourceAttribute("service.name", "orders-api")
    .addResourceAttribute("env", "prod")
    .build();
```

`LogSinkExporter`

Responsible for turning a list of LogRecord into an OTLP ExportLogsServiceRequest, gzipping the protobuf bytes, and sending it via HTTP.
- Headers:
  - Content-Type: application/x-protobuf 
  - Content-Encoding: gzip 
  - x-cardinalhq-api-key: <apiKey> (if provided)
  - Synchronous by design (used by a single batching thread), which keeps in-flight requests bounded.

```java
exporter.sendBatch(records);  // blocking; ok because only the batcher thread calls it
```

`LogSink`

A tiny facade you can use from application code or from the Log4j appender. It owns a LogSinkBatcher and exposes:

```java
boolean log(LogRecord record);

boolean log(long tsNanos, String message, java.util.logging.Level level, String... kvTags);

void flush();
void shutdown();
```
It also maps java.util.logging.Level → OTLP SeverityNumber and builds attributes from your tag pairs.

⚙️ The Log4j2 Appender

LogSinkAppender is a Log4j2 plugin (name = LogSink). It converts Log4j LogEvent → OTLP LogRecord, attaches MDC as attributes, includes exception details, and enqueues to the batcher.

```java
<?xml version="1.0" encoding="UTF-8"?>
<Configuration status="WARN">
  <Appenders>
    <LogSink name="LogSink"
             otlpEndpoint="http://localhost:4318/v1/logs"
             appName="log4j-example"
             envKeys="POD_NAME,NAMESPACE,CLUSTER_NAME"
             envPrefix="OTEL_,CARDINAL_"
             queueSize="2048"
             maxBatchSize="200"/>
    <Console name="Console" target="SYSTEM_OUT">
      <PatternLayout pattern="%d{HH:mm:ss.SSS} %-5p %c - %m%n"/>
    </Console>
  </Appenders>

  <Loggers>
    <Root level="info">
      <AppenderRef ref="LogSink"/>
      <AppenderRef ref="Console"/>
    </Root>
  </Loggers>
</Configuration>
```
The appender will also collect environment variables into resource attributes based on the envKeys and envPrefix filters, while excluding anything that matches a configurable “secrets” regex.

🚀 The Batcher (Disruptor) — How it saves GC & boosts throughput

LogSinkBatcher is where performance work happens. It replaces queues with the LMAX Disruptor:

```java
disruptor = new Disruptor<>(
    EVENT_FACTORY,                   // pre-allocates N event slots
    ringSize,                        // power-of-two
    workerThreadFactory,
    ProducerType.MULTI,              // many logging threads
    new BlockingWaitStrategy()       // low-CPU default
);

disruptor.handleEventsWith(new BatchingHandler(exporter, maxBatchSize));
ring = disruptor.start();

// periodic flush: post a "flush tick" into the ring every 5s
scheduler.scheduleAtFixedRate(this::postFlushTick, 5, 5, TimeUnit.SECONDS);
```

## Why Disruptor?
- Pre-allocated ring: Disruptor creates N event objects once. Producers do not allocate per log; they populate an existing slot.
- Fewer allocations → less GC pressure → higher, steadier throughput.
- Cache locality: contiguous ring buffer slots are cache-friendly vs. linked structures.
- One consumer thread: only the handler builds batches and calls exporter.sendBatch(...), so you have at most one in-flight HTTP call, and no extra coordination around the exporter.

### Event model

We publish one of two things to the ring:
- A log record (normal case)
- A “flush tick” (control signal) posted by a scheduled task or by flush()

A tiny mutable LogEvent struct carries either a LogRecord or a flushTick flag. The consumer (BatchingHandler) looks like this (simplified):

```java
public void onEvent(LogEvent e, long seq, boolean endOfBatch) {
    if (e.flushTick) { flushBatch(); return; }
    if (e.record != null) {
        batch.add(e.record);
        if (batch.size() >= maxBatchSize) flushBatch();
    }
    if (endOfBatch) flushBatch();   // reduce latency when producers pause
    e.clear();                       // release refs in the slot (help GC)
}
```

Non-blocking add(...) like offer(...)

Your LogSink.add(LogRecord) calls:

```java
ring.tryPublishEvent(translator, record);  // returns false if full
```
	
- This doesn’t block producer threads (good for logging paths).
- If you want lossless behavior instead, switch to ring.publishEvent(...) (blocks when ring is full).

Disruptor does not support “drop oldest”; the realistic choices are block or drop new.

Periodic flush & shutdown
- Every `1s`, the batcher posts a flush tick into the ring to push out small batches during idle periods.
- `flush()` just posts a tick; it never races the consumer thread.
- `shutdown()` posts a final tick, stops the scheduler, then calls disruptor.shutdown(), which drains all published events before returning.

Why this is GC-friendly
- No per-enqueue Node objects (as with `LinkedBlockingQueue`) → major reduction in small, short-lived allocations.
- The batch list is a single ArrayList owned by the consumer thread and reused: batch.clear() resets the size without reallocating the backing array.
- Clearing the ring slot (`event.clear()`) drops strong refs quickly, letting the GC skip scanning big object graphs.


