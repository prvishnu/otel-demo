package com.example.demo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class DemoService {
    private static final Logger logger = LoggerFactory.getLogger(DemoService.class);
    private static final Tracer tracer = GlobalOpenTelemetry.getTracer("demo-tracer");
    private static final ObjectMapper mapper = new ObjectMapper();

    public void doWork(Map<String, Object> payload) {
        // current span context available here
        Span current = Span.current();
        SpanContext ctx = current.getSpanContext();
        logger.info("Service (main thread): traceId={} spanId={}", ctx.getTraceId(), ctx.getSpanId());

        // 1) Start a child span for some internal work
        Span child = tracer.spanBuilder("service.doWork.internal").startSpan();
        try (Scope s = child.makeCurrent()) {
            // do some quick work
            logger.info("Service (child span): traceId={} spanId={}", child.getSpanContext().getTraceId(), child.getSpanContext().getSpanId());
        } finally {
            child.end();
        }

        // 2) Start a new thread — propagate the current Context into that thread
        Context currentContext = Context.current();
        Thread t = new Thread(() -> {
            // make context current in this thread
            try (Scope scope = currentContext.makeCurrent()) {
                // In this thread, Span.current() will be same trace (but different active span if started)
                Span threadSpan = tracer.spanBuilder("service.doWork.thread").startSpan();
                try (Scope ws = threadSpan.makeCurrent()) {
                    logger.info("Service (new thread): traceId={} spanId={}",
                            threadSpan.getSpanContext().getTraceId(),
                            threadSpan.getSpanContext().getSpanId());

                    // simulate work
                    try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                } finally {
                    threadSpan.end();
                }
            }
        });
        t.start();

        // 3) Make an outgoing HTTP call and inject trace context into headers
        makeOutgoingCallWithTrace();

        // optionally wait for thread to finish for demo
        try { t.join(500); } catch (InterruptedException ignored) {}
    }

    private void makeOutgoingCallWithTrace() {
        // Build an outgoing request and inject the W3C trace context
        String target = "https://httpbin.org/anything"; // echo endpoint
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpPost post = new HttpPost(target);
            post.setHeader("Content-Type", "application/json");

            // Prepare headers map and use propagator to inject
            Map<String, String> headers = new HashMap<>();
            GlobalOpenTelemetry.getPropagators().getTextMapPropagator()
                    .inject(Context.current(), headers, (carrier, key, value) -> carrier.put(key, value));

            // copy injected headers to actual request
            headers.forEach(post::setHeader);

            // optional body
            String body = mapper.writeValueAsString(Map.of("msg", "hello", "from", "demo-service"));
            post.setEntity(new org.apache.hc.core5.http.io.entity.StringEntity(body));

            logger.info("Outgoing HTTP call: headers injected: {}", headers);
            client.execute(post, response -> {
                logger.info("Outgoing response status: {}", response.getCode());
                return null;
            });
        } catch (Exception ex) {
            logger.error("Error in outgoing call", ex);
        }
    }
}
