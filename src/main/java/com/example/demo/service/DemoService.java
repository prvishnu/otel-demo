package com.example.demo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.propagation.Propagator;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class DemoService {
    private static final Logger log = LoggerFactory.getLogger(DemoService.class);

    private final Tracer tracer;
    private final Propagator propagator;
    private final ObjectMapper mapper = new ObjectMapper();

    public DemoService(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    public void doWork(Map<String, Object> payload) {

        // Current Micrometer span
        Span current = tracer.currentSpan();
        log.info("Service (main thread): traceId={} spanId={} valid={}",
                current != null ? current.context().traceId() : "null",
                current != null ? current.context().spanId() : "null",
                current != null);

        // Child span
        Span child = tracer.nextSpan().name("service.doWork.internal").start();
        try (Tracer.SpanInScope scope = tracer.withSpan(child)) {
            log.info("Service (child span): traceId={} spanId={}",
                    child.context().traceId(),
                    child.context().spanId());
            // do some work
        } finally {
            child.end();
        }

        // Propagate context into a new thread
        Span parent = tracer.currentSpan();
        Runnable runnable = () -> {
            Span threadSpan = tracer.nextSpan(parent).name("service.doWork.thread").start();
            try (Tracer.SpanInScope scope = tracer.withSpan(threadSpan)) {
                log.info("Service (new thread): traceId={} spanId={}",
                        threadSpan.context().traceId(),
                        threadSpan.context().spanId());
                try { Thread.sleep(150); } catch (InterruptedException ignored) {}
            } finally {
                threadSpan.end();
            }
        };
        new Thread(runnable).start();

        // Outgoing HTTP call with full trace context injection
        makeOutgoingCallWithTrace();
    }

    private void makeOutgoingCallWithTrace() {
        String target = "https://httpbin.org/anything";

        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpPost post = new HttpPost(target);
            post.setHeader("Content-Type", "application/json");

            // ----------- ⭐ Micrometer Propagator injection ⭐ -----------
            Map<String, String> headers = new HashMap<>();

            Span span = tracer.currentSpan();
            if (span != null) {
                propagator.inject(span.context(), headers, Map::put);
            }

            // Set injected headers
            headers.forEach(post::setHeader);

            // Body
            String body = mapper.writeValueAsString(Map.of("msg", "hello", "from", "demo-service"));
            post.setEntity(new org.apache.hc.core5.http.io.entity.StringEntity(body));

            log.info("Outgoing HTTP call: headers injected: {}", headers);

            client.execute(post, response -> {
                log.info("Outgoing response status: {}", response.getCode());
                return null;
            });

        } catch (Exception ex) {
            log.error("Error in outgoing call", ex);
        }
    }
}
