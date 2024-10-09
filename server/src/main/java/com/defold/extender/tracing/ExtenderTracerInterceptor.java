package com.defold.extender.tracing;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.apache.http.HttpException;
import org.apache.http.HttpMessage;
import org.apache.http.HttpRequest;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.protocol.HttpContext;

import java.io.IOException;


public class ExtenderTracerInterceptor implements HttpRequestInterceptor {

    private final Tracer tracer;
    private final Propagator propagator;

    public ExtenderTracerInterceptor(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    @Override
    public void process(HttpRequest request, HttpContext context) throws HttpException, IOException {
        if (tracer.currentSpan() != null) {
            propagator.inject(tracer.currentSpan().context(), request, HttpMessage::addHeader);
        }
    }
}
