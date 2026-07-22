package com.fist.rmms_backend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Converts a caught exception into a message that is safe to hand back to the
 * client.
 *
 *  Validation errors ({@link IllegalArgumentException}) carry developer-authored,
 *  user-facing text ("CSV must have columns …", "Select the survey period …")
 *  and are passed through unchanged. Anything else — JDBC/PostgreSQL driver
 *  errors, I/O failures, NPEs — can expose SQL, schema, file-system paths or
 *  stack detail in its message, so it is logged server-side and replaced with a
 *  single generic sentence. This keeps information disclosure out of API
 *  responses without losing the genuinely helpful validation messages.
 */
final class ApiErrors {

    private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);

    private static final String GENERIC =
            "The operation could not be completed. Please check your input and try again, "
          + "or contact the administrator if the problem persists.";

    private ApiErrors() {}

    /** Safe client message for {@code e}; {@code context} is only used in the server log. */
    static String safe(String context, Throwable e) {
        if (e instanceof IllegalArgumentException && e.getMessage() != null && !e.getMessage().isBlank())
            return e.getMessage();
        log.error("{}: {}", context, e.toString(), e);
        return GENERIC;
    }
}
