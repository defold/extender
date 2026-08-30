package com.defold.extender.jetty;

import java.io.IOException;

import org.eclipse.jetty.http.HttpException;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.defold.extender.log.Markers;

/**
 * Error handler installed on the Jetty {@link org.eclipse.jetty.server.Server}, i.e. the one used
 * for requests that fail before they enter the servlet context - malformed HTTP, too large headers,
 * too long URI, unsupported HTTP version. Those never reach Spring, so neither the controller
 * exception handlers nor {@link com.defold.extender.RequestErrorAdvice} can see them; without this
 * handler they are answered with an HTML page and are not logged at all. Errors raised inside the
 * context still go through Spring Boot's own context error handler.
 */
public class ExtenderJettyErrorHandler extends ErrorHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExtenderJettyErrorHandler.class);

    private static final String MIME_TYPE = "text/plain";

    private static final String LOG_MESSAGE = "Jetty rejected request {} {} from {} with status {}: {}";

    public ExtenderJettyErrorHandler() {
        setShowStacks(false);
        setShowCauses(false);
        setShowMessageInTitle(false);
        setDefaultResponseMimeType(MIME_TYPE);
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception {
        final String message = (String)request.getAttribute(ERROR_MESSAGE);
        final Throwable cause = (Throwable)request.getAttribute(ERROR_EXCEPTION);
        final int code = (cause instanceof HttpException httpException) ? httpException.getCode() : response.getStatus();

        final Object[] details = { request.getMethod(), request.getHttpURI(), Request.getRemoteAddr(request), code, message, cause };
        if (HttpStatus.isServerError(code)) {
            LOGGER.error(Markers.SERVER_ERROR, LOG_MESSAGE, details);
        } else {
            LOGGER.warn(LOG_MESSAGE, details);
        }

        return super.handle(request, response, callback);
    }

    @Override
    protected void generateResponse(Request request, Response response, int code, String message, Throwable cause, Callback callback) throws IOException {
        response.getHeaders().put(HttpHeader.CONTENT_TYPE, MIME_TYPE + ";charset=utf-8");
        Content.Sink.write(response, true, describe(code), callback);
    }

    /** The body the user gets to see. What exactly Jetty disliked stays in the log. */
    static String describe(int code) {
        final String explanation = switch (code) {
            case HttpStatus.BAD_REQUEST_400 ->
                "The server could not parse the request, so the build was never started. This usually means the request was truncated or malformed on the way to the server.";
            case HttpStatus.REQUEST_TIMEOUT_408 ->
                "The server timed out while waiting for the request to be sent.";
            case HttpStatus.PAYLOAD_TOO_LARGE_413 ->
                "The build request is too large.";
            case HttpStatus.URI_TOO_LONG_414 ->
                "The request URI is too long.";
            case HttpStatus.REQUEST_HEADER_FIELDS_TOO_LARGE_431 ->
                "The request headers are too large.";
            case HttpStatus.HTTP_VERSION_NOT_SUPPORTED_505 ->
                "The HTTP version used by the client is not supported by the server.";
            default ->
                "The request was rejected by the server before the build was started.";
        };
        return String.format("%d %s%n%n%s%n", code, HttpStatus.getMessage(code), explanation);
    }
}
