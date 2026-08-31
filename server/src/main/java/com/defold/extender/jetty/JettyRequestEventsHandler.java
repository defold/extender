package com.defold.extender.jetty;

import java.util.Set;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Logs requests that fail on the Jetty side. This complements {@link ExtenderJettyErrorHandler}:
 * the error handler only runs when Jetty generates an error response, while this handler also sees
 * failures that never produce one - a client that aborts mid upload, an idle timeout, or a failure
 * after the response was already committed. All of those are client side problems, hence the
 * warnings without com.defold.extender.log.Markers.SERVER_ERROR.
 */
public class JettyRequestEventsHandler extends Handler.Wrapper {

    private static final Logger LOGGER = LoggerFactory.getLogger(JettyRequestEventsHandler.class);

    // Already logged by ExtenderController's @ExceptionHandler methods, with more context than is
    // available here, and for two of them with a SERVER_ERROR alert attached.
    private static final Set<Integer> STATUSES_LOGGED_BY_CONTROLLER = Set.of(
            HttpStatus.UNPROCESSABLE_ENTITY_422,   // handleExtenderException
            HttpStatus.INTERNAL_SERVER_ERROR_500,  // handleException
            HttpStatus.NOT_IMPLEMENTED_501);       // handleUsupportedExceptions

    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception {
        // Not EventsHandler, which offers the same event only by wrapping the request and the
        // response, adding an allocation to every chunk read and written.
        Request.addCompletionListener(request, failure -> onComplete(request, response.getStatus(), failure));
        return super.handle(request, response, callback);
    }

    private static void onComplete(Request request, int status, Throwable failure) {
        if (failure != null) {
            LOGGER.warn("Request {} {} from {} failed with status {}",
                    request.getMethod(), request.getHttpURI(), Request.getRemoteAddr(request), status, failure);
        } else if (status >= 400 && !STATUSES_LOGGED_BY_CONTROLLER.contains(status)) {
            LOGGER.warn("Request {} {} from {} completed with status {}",
                    request.getMethod(), request.getHttpURI(), Request.getRemoteAddr(request), status);
        }
    }
}
