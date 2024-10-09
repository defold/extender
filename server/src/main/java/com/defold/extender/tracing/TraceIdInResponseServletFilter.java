package com.defold.extender.tracing;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class TraceIdInResponseServletFilter implements Filter {

    public static final String TRACE_ID_HEADER_NAME = "X-TraceId";

    private final Tracer tracer;

    public TraceIdInResponseServletFilter(@Autowired Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        final Span currentSpan = tracer.currentSpan();
        if (currentSpan != null) {
            HttpServletResponse resp = (HttpServletResponse) response;
            resp.addHeader(TRACE_ID_HEADER_NAME, currentSpan.context().traceId());
        }
        chain.doFilter(request, response);
    }
}