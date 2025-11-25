package com.example.demo.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class RequestResponseLoggingFilter extends OncePerRequestFilter {
    private static final Logger logger = LoggerFactory.getLogger(RequestResponseLoggingFilter.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService asyncLogger = Executors.newFixedThreadPool(2);
    private final int MAX_BODY_BYTES = 64 * 1024; // 64 KB

    private static final Tracer tracer = GlobalOpenTelemetry.getTracer("demo-tracer");

    private static final io.opentelemetry.context.propagation.TextMapPropagator propagator =
            GlobalOpenTelemetry.getPropagators().getTextMapPropagator();

    // Getter for extracting headers into propagator
    private static final TextMapGetter<HttpServletRequest> getter = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(HttpServletRequest carrier) {
            return Collections.list(carrier.getHeaderNames());
        }

        @Override
        public String get(HttpServletRequest carrier, String key) {
            return carrier.getHeader(key);
        }
    };

    // Setter for injecting into outgoing header maps (used by service)
    public static final TextMapSetter<Map<String, String>> mapSetter = (carrier, key, value) -> carrier.put(key, value);

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {



        ContentCachingRequestWrapper wrappedRequest = new ContentCachingRequestWrapper(request);
        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);

        String messageId = getOrCreateMessageId(wrappedRequest);
        wrappedResponse.setHeader("X-Message-Id", messageId);

        // Extract incoming context (if any) using the configured propagator (W3C by default)
        Context extractedContext = propagator.extract(Context.current(), wrappedRequest, getter);

        // Start a SERVER span using the extracted context as parent (so the trace continues)
        SpanKind kind = SpanKind.SERVER;
        SpanBuilder spanBuilder = tracer.spanBuilder(wrappedRequest.getMethod() + " " + wrappedRequest.getRequestURI())
                .setSpanKind(kind)
                .setParent(extractedContext);

        Span serverSpan = spanBuilder.startSpan();

        // Put the span into the current Context
        try (Scope scope = serverSpan.makeCurrent()) {
            Instant requestTime = Instant.now();
            long startNs = System.nanoTime();

            try {
                filterChain.doFilter(wrappedRequest, wrappedResponse);
            } finally {
                long latencyMs = (System.nanoTime() - startNs) / 1_000_000;
                int status = wrappedResponse.getStatus();
                String path = wrappedRequest.getRequestURI();
                String method = wrappedRequest.getMethod();

                // read request/response body safely (may be empty)
                String requestBody = safeGetBody(wrappedRequest.getContentAsByteArray());
                String responseBody = safeGetBody(wrappedResponse.getContentAsByteArray());

                // capture trace id from current span context
                SpanContext spanContext = serverSpan.getSpanContext();
                String traceId = spanContext.getTraceId();
                String spanId = spanContext.getSpanId();

                Map<String, Object> event = new LinkedHashMap<>();
                event.put("message_tracking_id", messageId);
                event.put("trace_id", traceId);
                event.put("span_id", spanId);
                event.put("endpoint", path);
                event.put("method", method);
                event.put("request_timestamp", requestTime.toString());
                event.put("response_timestamp", Instant.now().toString());
                event.put("latency_ms", latencyMs);
                event.put("response_http_code", status);
                event.put("client_ip", getClientIp(wrappedRequest));
                event.put("request_body_summary", summarize(requestBody));
                event.put("response_body_summary", summarize(responseBody));

                asyncLogger.submit(() -> {
                    try {
                        String json = mapper.writeValueAsString(event);
                        // For demo we output to logger; in prod publish to Kinesis/Firehose/etc.
                        logger.info("EVENT: {}", json);
                    } catch (Exception e) {
                        logger.error("Failed to serialize event", e);
                    }
                });

                // add trace/span attributes to span for richer detail (optional)
                serverSpan.setAttribute("http.method", method);
                serverSpan.setAttribute("http.path", path);
                serverSpan.setAttribute("http.status_code", status);
                serverSpan.setAttribute("message.id", messageId);

                // end span
                serverSpan.end();

                // copy response body back to response stream
                wrappedResponse.copyBodyToResponse();
            }
        } // scope closed
    }

    private String getOrCreateMessageId(HttpServletRequest req) {
        String id = req.getHeader("X-Message-Id");
        if (id != null && !id.isBlank()) return id;
        String xrid = req.getHeader("X-Request-Id");
        if (xrid != null && !xrid.isBlank()) return xrid;
        return UUID.randomUUID().toString();
    }

    private String safeGetBody(byte[] buf) {
        if (buf == null || buf.length == 0) return "";
        int len = Math.min(buf.length, MAX_BODY_BYTES);
        return new String(buf, 0, len, StandardCharsets.UTF_8);
    }

    private Object summarize(String body) {
        if (body == null || body.isEmpty()) return "";
        if (body.length() > 1000) return body.substring(0, 1000) + "...(truncated)";
        return body;
    }

    private String getClientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }
}
