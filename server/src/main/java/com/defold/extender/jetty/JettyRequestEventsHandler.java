package com.defold.extender.jetty;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.EventsHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.defold.extender.log.Markers;

/**
 * Logs requests that fail on the Jetty side. This complements {@link ExtenderJettyErrorHandler}:
 * the error handler only runs when Jetty generates an error response, while this handler also sees
 * failures that never produce one - a client that aborts mid upload, an idle timeout, or a failure
 * after the response was already committed.
 */
public class JettyRequestEventsHandler extends EventsHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(JettyRequestEventsHandler.class);

    // Compilation errors are reported as 422 by ExtenderController.handleExtenderException, which
    // logs them with the full build output. No need to log them a second time here.
    private static final int COMPILATION_ERROR_STATUS = 422;

    @Override
    protected void onComplete(Request request, int status, HttpFields headers, Throwable failure) {
        if (failure != null) {
            LOGGER.error(Markers.SERVER_ERROR, "Request {} {} from {} failed with status {}",
                    request.getMethod(), request.getHttpURI(), Request.getRemoteAddr(request), status, failure);
        } else if (status >= 500) {
            LOGGER.error(Markers.SERVER_ERROR, "Request {} {} from {} completed with status {}",
                    request.getMethod(), request.getHttpURI(), Request.getRemoteAddr(request), status);
        } else if (status >= 400 && status != COMPILATION_ERROR_STATUS) {
            LOGGER.warn("Request {} {} from {} completed with status {}",
                    request.getMethod(), request.getHttpURI(), Request.getRemoteAddr(request), status);
        }
    }
}
