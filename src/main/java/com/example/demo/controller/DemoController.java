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
    private static final Logger logger = LoggerFactory.getLogger(DemoController.class);
    private final DemoService demoService;

    public DemoController(DemoService demoService) {
        this.demoService = demoService;
    }

    @PostMapping("/process")
    public Map<String, Object> process(@RequestBody(required = false) Map<String, Object> body) {
        Span currentSpan = Span.current();
        SpanContext ctx = currentSpan.getSpanContext();
        logger.info("Controller: current traceId={} spanId={}", ctx.getTraceId(), ctx.getSpanId());

        // call service
        demoService.doWork(body);

        return Map.of(
                "status", "ok",
                "trace_id", ctx.getTraceId()
        );
    }
}
