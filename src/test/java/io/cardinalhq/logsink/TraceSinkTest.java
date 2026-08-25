package io.cardinalhq.logsink;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceSinkTest {
    @AfterEach
    void clearGlobalProvider() {
        Tracing.shutdown();
    }

    @Test
    void exportsParentAndChildSpansFromInjectedAndStaticTracers() {
        LogSinkConfig config = config("http://localhost:4318/v1/logs");
        InMemorySpanExporter exporter = InMemorySpanExporter.create();

        try (TraceSink traceSink = new TraceSink(config, exporter)) {
            Tracing.install(traceSink);
            assertSame(traceSink, Tracing.getTracerProvider());

            Tracer injectedTracer = traceSink.getTracer(TraceSinkTest.class);
            Span parent = injectedTracer.spanBuilder("request").startSpan();
            try (Scope ignored = parent.makeCurrent()) {
                Span child = Tracing.getTracer("test.worker")
                        .spanBuilder("database.query")
                        .startSpan();
                child.setAttribute("db.system", "postgresql");
                child.end();
            } finally {
                parent.end();
            }

            traceSink.flush();
            List<SpanData> spans = exporter.getFinishedSpanItems();
            assertEquals(2, spans.size());

            SpanData parentData = spans.stream()
                    .filter(span -> span.getName().equals("request"))
                    .findFirst()
                    .orElseThrow();
            SpanData childData = spans.stream()
                    .filter(span -> span.getName().equals("database.query"))
                    .findFirst()
                    .orElseThrow();

            assertEquals(parentData.getTraceId(), childData.getTraceId());
            assertEquals(parentData.getSpanId(), childData.getParentSpanId());
            assertNotEquals(parentData.getSpanId(), childData.getSpanId());
            assertEquals("test-service", parentData.getResource().getAttribute(
                    AttributeKey.stringKey("service.name")));
        }
    }

    @Test
    void derivesAndOverridesTraceEndpoint() {
        assertEquals(
                "http://localhost:4318/v1/traces",
                config("http://localhost:4318/v1/logs").getTracesEndpoint());
        assertEquals(
                "http://localhost:4318/v1/traces",
                config("http://localhost:4318").getTracesEndpoint());

        LogSinkConfig config = LogSinkConfig.builder()
                .setOtlpEndpoint("http://localhost:4318/v1/logs")
                .setTracesEndpoint("https://collector.example/custom/traces")
                .setAppName("test-service")
                .build();
        assertEquals("https://collector.example/custom/traces", config.getTracesEndpoint());
    }

    @Test
    void defaultExporterSendsGzippedOtlpProtobufWithApiKey() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<HttpExchangeData> request = new AtomicReference<>();
        CountDownLatch received = new CountDownLatch(1);
        server.createContext("/v1/traces", exchange -> handle(exchange, request, received));
        server.start();

        try {
            LogSinkConfig config = LogSinkConfig.builder()
                    .setOtlpEndpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/logs")
                    .setApiKey("test-api-key")
                    .setAppName("test-service")
                    .build();

            try (TraceSink traceSink = new TraceSink(config)) {
                traceSink.getTracer("integration-test")
                        .spanBuilder("exported-span")
                        .startSpan()
                        .end();
                traceSink.flush();
                assertTrue(received.await(5, TimeUnit.SECONDS));
            }

            HttpExchangeData sent = request.get();
            assertEquals("test-api-key", sent.apiKey);
            assertEquals("gzip", sent.contentEncoding);

            ExportTraceServiceRequest payload = ExportTraceServiceRequest.parseFrom(gunzip(sent.body));
            assertEquals("exported-span", payload.getResourceSpans(0)
                    .getScopeSpans(0)
                    .getSpans(0)
                    .getName());
        } finally {
            server.stop(0);
        }
    }

    private static LogSinkConfig config(String endpoint) {
        return LogSinkConfig.builder()
                .setOtlpEndpoint(endpoint)
                .setAppName("test-service")
                .build();
    }

    private static void handle(
            HttpExchange exchange,
            AtomicReference<HttpExchangeData> request,
            CountDownLatch received) throws IOException {
        request.set(new HttpExchangeData(
                exchange.getRequestHeaders().getFirst("x-cardinalhq-api-key"),
                exchange.getRequestHeaders().getFirst("Content-Encoding"),
                exchange.getRequestBody().readAllBytes()));
        exchange.sendResponseHeaders(200, -1);
        exchange.close();
        received.countDown();
    }

    private static byte[] gunzip(byte[] body) throws IOException {
        try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(body))) {
            return input.readAllBytes();
        }
    }

    private static final class HttpExchangeData {
        private final String apiKey;
        private final String contentEncoding;
        private final byte[] body;

        private HttpExchangeData(String apiKey, String contentEncoding, byte[] body) {
            this.apiKey = apiKey;
            this.contentEncoding = contentEncoding;
            this.body = body;
        }
    }
}
