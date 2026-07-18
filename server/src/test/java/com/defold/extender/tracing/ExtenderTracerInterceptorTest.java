package com.defold.extender.tracing;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.apache.http.HttpRequest;
import org.apache.http.message.BasicHttpRequest;
import org.junit.jupiter.api.Test;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;

public class ExtenderTracerInterceptorTest {

    private static final String TRACE_HEADER = "traceparent";
    private static final String TRACE_VALUE = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";

    @Test
    public void noCurrentSpanInjectsNothing() throws Exception {
        Tracer tracer = mock(Tracer.class);
        Propagator propagator = mock(Propagator.class);
        when(tracer.currentSpan()).thenReturn(null);

        BasicHttpRequest request = new BasicHttpRequest("GET", "/job_status?jobId=job-abc");
        new ExtenderTracerInterceptor(tracer, propagator).process(request, null);

        verifyNoInteractions(propagator);
        assertEquals(0, request.getAllHeaders().length);
    }

    @Test
    public void currentSpanContextIsHandedToThePropagator() throws Exception {
        Tracer tracer = mock(Tracer.class);
        Propagator propagator = mock(Propagator.class);
        Span span = mock(Span.class);
        TraceContext traceContext = mock(TraceContext.class);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(traceContext);

        BasicHttpRequest request = new BasicHttpRequest("POST", "/build_async/x86_64-osx/sdk123");
        new ExtenderTracerInterceptor(tracer, propagator).process(request, null);

        verify(propagator).inject(eq(traceContext), eq(request), any());
    }

    @Test
    public void theSetterHandedToThePropagatorAddsHeadersToTheRequest() throws Exception {
        Tracer tracer = mock(Tracer.class);
        Propagator propagator = mock(Propagator.class);
        Span span = mock(Span.class);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(mock(TraceContext.class));

        // The interceptor passes HttpMessage::addHeader as the carrier setter. Nothing else in the
        // build pipeline proves that method reference actually reaches the outgoing request, so drive
        // it here: a broken carrier means the remote builder silently loses trace correlation.
        doAnswer(invocation -> {
            HttpRequest carrier = invocation.getArgument(1);
            Propagator.Setter<HttpRequest> setter = invocation.getArgument(2);
            setter.set(carrier, TRACE_HEADER, TRACE_VALUE);
            return null;
        }).when(propagator).inject(any(), any(), any());

        BasicHttpRequest request = new BasicHttpRequest("GET", "/job_result?jobId=job-abc");
        new ExtenderTracerInterceptor(tracer, propagator).process(request, null);

        assertEquals(1, request.getAllHeaders().length);
        assertEquals(TRACE_VALUE, request.getFirstHeader(TRACE_HEADER).getValue());
    }

    @Test
    public void noopPropagatorLeavesTheRequestUntouched() throws Exception {
        Tracer tracer = mock(Tracer.class);
        Span span = mock(Span.class);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(mock(TraceContext.class));

        BasicHttpRequest request = new BasicHttpRequest("GET", "/health_report");
        new ExtenderTracerInterceptor(tracer, Propagator.NOOP).process(request, null);

        assertEquals(0, request.getAllHeaders().length);
        assertNull(request.getFirstHeader(TRACE_HEADER));
    }

    @Test
    public void aNullPropagatorIsSafeWhenThereIsNoCurrentSpan() {
        Tracer tracer = mock(Tracer.class);
        when(tracer.currentSpan()).thenReturn(null);

        ExtenderTracerInterceptor interceptor = new ExtenderTracerInterceptor(tracer, null);
        BasicHttpRequest request = new BasicHttpRequest("GET", "/health_report");

        assertDoesNotThrow(() -> interceptor.process(request, null));
    }
}
