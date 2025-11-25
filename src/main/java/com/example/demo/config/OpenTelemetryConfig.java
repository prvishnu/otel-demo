package com.example.demo.config;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.exporter.logging.LoggingSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenTelemetryConfig {
    private static final Logger log = LoggerFactory.getLogger(OpenTelemetryConfig.class);

    @PostConstruct
    public void init() {

        {
            System.out.println("GlobalOpenTelemetry implementation: " + GlobalOpenTelemetry.get().getClass().getName());
            System.out.println("GlobalOpenTelemetry is noop()? " + (GlobalOpenTelemetry.get() == OpenTelemetry.noop()));
            Span current = Span.current();
            System.out.println("Span.current() class: " + current.getClass().getName());
            System.out.println("Span.current() valid? " + current.getSpanContext().isValid());
        }

        // If some other library or the OTel agent already set a GlobalOpenTelemetry instance,
        // GlobalOpenTelemetry.get() will return a non-noop instance. We should not override it.
        OpenTelemetry current = GlobalOpenTelemetry.get();
        if (current != OpenTelemetry.noop()) {
            log.info("Global OpenTelemetry already configured by another component. Skipping SDK registration.");
            return;
        }

        log.info("Global OpenTelemetry is noop — registering SDK programmatically.");

        // Build a simple SDK with logging exporter (demo). In production use OTLP exporter, etc.
        LoggingSpanExporter exporter = new LoggingSpanExporter();
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .setResource(Resource.getDefault())
                .build();

        // Use buildAndRegisterGlobal to atomically set the global SDK.
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .buildAndRegisterGlobal();

        // DO NOT call GlobalOpenTelemetry.set(...) here — buildAndRegisterGlobal already set the global.
        log.info("OpenTelemetry SDK registered programmatically: tracerProvider={}, global={}", tracerProvider, GlobalOpenTelemetry.get());
    }
}
