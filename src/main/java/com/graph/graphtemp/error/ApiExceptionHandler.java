package com.graph.graphtemp.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import tools.jackson.core.JacksonException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every error leaves the API as {"detail": ...}. Anything the editor form could
 * plausibly fix also carries an "errors" map keyed by the wire field name, so the
 * client can highlight individual inputs.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private static final String VALIDATION_FAILED = "spec validation failed";

    /** @AssertTrue methods report under their property name; map them back to the real field. */
    private static final Map<String, String> FIELD_ALIASES = Map.of("stepsConsistent", "steps");

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, Object>> handleApi(ApiException ex) {
        return ResponseEntity.status(ex.status()).body(Map.of("detail", ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            String field = FIELD_ALIASES.getOrDefault(fe.getField(), fe.getField());
            errors.putIfAbsent(toSnakeCase(field), fe.getDefaultMessage());
        }
        return ResponseEntity.badRequest().body(body(VALIDATION_FAILED, errors));
    }

    /**
     * Jackson rejects a value before bean validation ever runs — an unknown enum
     * constant, a string where a number belongs. Recover the property path so these
     * land on the same field-keyed shape as bean-validation failures.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException ex) {
        if (ex.getCause() instanceof JacksonException jackson) {
            String field = pathOf(jackson);
            if (field != null) {
                String message = rootCauseMessage(jackson);
                return ResponseEntity.badRequest()
                        .body(body(VALIDATION_FAILED, Map.of(field, message)));
            }
        }
        return ResponseEntity.badRequest()
                .body(Map.of("detail", "malformed request body: " + ex.getMostSpecificCause().getMessage()));
    }

    /**
     * A path variable that will not convert (a malformed UUID, say) is the caller's
     * mistake, not a server fault, so it must not fall through to 500.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        Class<?> required = ex.getRequiredType();
        String expected = required == null ? "the expected type" : required.getSimpleName();
        return ResponseEntity.badRequest().body(Map.of("detail",
                "'" + ex.getName() + "' is not a valid " + expected + ": " + ex.getValue()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex) {
        log.error("unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("detail", ex.getClass().getSimpleName() + ": " + ex.getMessage()));
    }

    private static Map<String, Object> body(String detail, Map<String, String> errors) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("detail", detail);
        body.put("errors", errors);
        return body;
    }

    /** Renders Jackson's reference chain as "steps[0].name"; null when there is no path. */
    private static String pathOf(JacksonException ex) {
        StringBuilder sb = new StringBuilder();
        for (JacksonException.Reference ref : ex.getPath()) {
            String property = ref.getPropertyName();
            if (property != null) {
                if (!sb.isEmpty()) {
                    sb.append('.');
                }
                sb.append(property);
            } else if (ref.getIndex() >= 0) {
                sb.append('[').append(ref.getIndex()).append(']');
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    /**
     * Prefer the message we threw ourselves (e.g. from a @JsonCreator) over Jackson's
     * wrapper, and drop the source-location suffix Jackson appends.
     */
    private static String rootCauseMessage(JacksonException ex) {
        Throwable cause = ex;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage() == null ? ex.getMessage() : cause.getMessage();
        int at = message.indexOf("\n at [");
        return at >= 0 ? message.substring(0, at).trim() : message.trim();
    }

    /** "systemPrompt" -> "system_prompt". */
    private static String toSnakeCase(String name) {
        StringBuilder sb = new StringBuilder(name.length() + 4);
        for (char c : name.toCharArray()) {
            if (Character.isUpperCase(c)) {
                sb.append('_').append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
