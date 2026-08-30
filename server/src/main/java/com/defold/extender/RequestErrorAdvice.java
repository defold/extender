package com.defold.extender;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

/**
 * Handles uploads that fail while Jetty parses the multipart body. Parsing happens in
 * DispatcherServlet.checkMultipart(), i.e. before the request is mapped to a controller method, so
 * the @ExceptionHandler methods inside {@link ExtenderController} are never consulted for these
 * failures - the request would otherwise end up in Spring's default /error handling, which
 * answers with a bare "400 Bad Request" and logs nothing (see defold/extender#590).
 */
@RestControllerAdvice
public class RequestErrorAdvice {

    private static final Logger LOGGER = LoggerFactory.getLogger(RequestErrorAdvice.class);

    // Matches Jetty's message for the part limit: Form with too many keys [1043 > 1000]
    private static final Pattern TOO_MANY_PARTS_RE = Pattern.compile("too many keys \\[(\\d+) > (\\d+)]");

    private final String maxFileSize;
    private final String maxRequestSize;

    public RequestErrorAdvice(@Value("${spring.servlet.multipart.max-file-size}") String maxFileSize,
                              @Value("${spring.servlet.multipart.max-request-size}") String maxRequestSize) {
        this.maxFileSize = maxFileSize;
        this.maxRequestSize = maxRequestSize;
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<String> handleMultipartException(MultipartException ex) {
        final Matcher tooManyParts = findTooManyParts(ex);
        if (tooManyParts != null) {
            final String message = String.format(
                    "The build request contains too many files (%s, the limit is %s). Reduce the number of files in the project extensions, or update the editor to a version that uploads the sources as a single archive.",
                    tooManyParts.group(1), tooManyParts.group(2));
            LOGGER.warn(message);
            return textResponse(HttpStatus.PAYLOAD_TOO_LARGE, message);
        }

        if (ex instanceof MaxUploadSizeExceededException) {
            // Spring raises this for both limits without saying which one was hit.
            final String message = String.format("The build request is too large. Max allowed size is %s per file and %s per request.",
                    maxFileSize, maxRequestSize);
            LOGGER.warn(message);
            return textResponse(HttpStatus.PAYLOAD_TOO_LARGE, message);
        }

        final String message = "The build request is not a valid multipart request and could not be read by the server.";
        LOGGER.warn(message, ex);
        return textResponse(HttpStatus.BAD_REQUEST, message);
    }

    private static Matcher findTooManyParts(Throwable ex) {
        final Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = ex; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause.getMessage() != null) {
                final Matcher matcher = TOO_MANY_PARTS_RE.matcher(cause.getMessage());
                if (matcher.find()) {
                    return matcher;
                }
            }
        }
        return null;
    }

    private static ResponseEntity<String> textResponse(HttpStatus status, String message) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        return new ResponseEntity<>(message, headers, status);
    }
}
