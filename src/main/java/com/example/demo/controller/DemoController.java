package com.example.demo.controller;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import com.example.demo.service.DemoService;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class DemoController {
    private static final Logger log = LoggerFactory.getLogger(DemoController.class);
    private final DemoService demoService;

    public DemoController(DemoService demoService) {
        this.demoService = demoService;
    }

    @PostMapping("/process")
    public Map<String, Object> process(@RequestBody(required = false) Map<String, Object> body) {
        Span current = Span.current();
        SpanContext ctx = current.getSpanContext();
        log.info("Controller: traceId={} spanId={}, valid={}", ctx.getTraceId(), ctx.getSpanId(), ctx.isValid());

        demoService.doWork(body);

        return Map.of("status", "ok", "trace_id", ctx.isValid() ? ctx.getTraceId() : null);
    }
}
