package com.fist.rmms_backend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Map;

/**
 * Last line of defence for controller exceptions that aren't handled locally.
 *
 *  Without this, an uncaught exception (e.g. a rethrown "upload failed" carrying
 *  a JDBC/PostgreSQL message) can surface driver/schema detail to the client.
 *  Here we log the full stack server-side and return only a generic message.
 *
 *  Spring Security's authentication/authorization failures are handled earlier
 *  in the filter chain and never reach controller advice, so 401/403 behaviour
 *  is unchanged. Validation errors keep their developer-authored message (safe).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Developer-authored validation text is user-facing and safe to return. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badInput(IllegalArgumentException e) {
        String msg = (e.getMessage() == null || e.getMessage().isBlank()) ? "Invalid request." : e.getMessage();
        return ResponseEntity.badRequest().body(Map.of("status", "error", "error", msg, "message", msg));
    }

    /** Friendly message when an upload exceeds the configured multipart limit. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> tooLarge(MaxUploadSizeExceededException e) {
        String msg = "The uploaded file is too large.";
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Map.of("status", "error", "error", msg, "message", msg));
    }

    /** Everything else: log the detail, tell the client nothing sensitive. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception e) {
        log.error("Unhandled exception: {}", e.toString(), e);
        String msg = "Something went wrong while processing the request.";
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("status", "error", "error", msg, "message", msg));
    }
}
