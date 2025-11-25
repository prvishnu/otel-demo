package com.example.demo.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RequestResponseLoggingFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RequestResponseLoggingFilter.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService exec = Executors.newFixedThreadPool(2);
    private final int MAX_BODY_BYTES = 64 * 1024;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        ContentCachingRequestWrapper wrappedRequest = new ContentCachingRequestWrapper(request);
        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);

        // correlation id (propagate or create)
        String messageId = getOrCreateMessageId(wrappedRequest);
        wrappedResponse.setHeader("X-Message-Id", messageId);

        Instant requestTime = Instant.now();
        long startNs = System.nanoTime();

        try {
            filterChain.doFilter(wrappedRequest, wrappedResponse);
        } finally {
            long latencyMs = (System.nanoTime() - startNs) / 1_000_000;
            int status = wrappedResponse.getStatus();

            // read cached bodies after controller has consumed the request
            String requestBody = safeGetBody(wrappedRequest.getContentAsByteArray());
            String responseBody = safeGetBody(wrappedResponse.getContentAsByteArray());

            // extract trace info from current span
            Span current = Span.current();
            SpanContext ctx = current.getSpanContext();
            String traceId = ctx.isValid() ? ctx.getTraceId() : null;
            String spanId = ctx.isValid() ? ctx.getSpanId() : null;

            Map<String, Object> event = new LinkedHashMap<>();
            event.put("message_tracking_id", messageId);
            event.put("endpoint", wrappedRequest.getRequestURI());
            event.put("method", wrappedRequest.getMethod());
            event.put("request_timestamp", requestTime.toString());
            event.put("response_timestamp", Instant.now().toString());
            event.put("latency_ms", latencyMs);
            event.put("response_http_code", status);
            event.put("trace_id", traceId);
            event.put("span_id", spanId);
            event.put("client_ip", getClientIp(wrappedRequest));
            event.put("request_body_summary", summarize(requestBody));
            event.put("response_body_summary", summarize(responseBody));

            exec.submit(() -> {
                try {
                    String json = mapper.writeValueAsString(event);
                    // In your deployment replace this with an async push to Kinesis/Firehose/CloudWatch
                    log.info("EVENT: {}", json);
                } catch (Exception ex) {
                    log.error("Failed to serialize event", ex);
                }
            });

            // copy response back to client
            wrappedResponse.copyBodyToResponse();
        }
    }

    private String getOrCreateMessageId(HttpServletRequest req) {
        String id = req.getHeader("X-Message-Id");
        if (id != null && !id.isBlank()) return id;
        String r = req.getHeader("X-Request-Id");
        if (r != null && !r.isBlank()) return r;
        return UUID.randomUUID().toString();
    }

    private String getClientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }

    private String safeGetBody(byte[] buf) {
        if (buf == null || buf.length == 0) return "";
        int len = Math.min(buf.length, MAX_BODY_BYTES);
        return new String(buf, 0, len, StandardCharsets.UTF_8);
    }

    private Object summarize(String body) {
        if (body == null || body.isEmpty()) return "";
        if (body.length() > 2000) return body.substring(0, 2000) + "...(truncated)";
        return body;
    }
}
