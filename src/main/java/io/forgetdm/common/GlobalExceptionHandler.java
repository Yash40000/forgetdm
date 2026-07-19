package io.forgetdm.common;

import io.forgetdm.audit.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.UUID;

/**
 * Converts exceptions into API responses.
 *
 * <p><b>Never return a raw exception message to the client</b> (DEF-0018). Persistence exceptions in
 * particular carry the failing SQL statement, the table's column list and the offending row values —
 * `POST /api/datasources` and `POST /api/policies` were both returning a 500 whose body disclosed the
 * full INSERT, every column name and the row contents. Details belong in the server log, correlated
 * to the client by a short reference id.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private final AuditService audit;

    public GlobalExceptionHandler(AuditService audit) {
        this.audit = audit;
    }

    /** Deliberate, already-sanitised application errors are safe to echo. */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, String>> api(ApiException e, HttpServletRequest request) {
        if (e.getStatus().value() == 400 || e.getStatus().value() == 409 || e.getStatus().value() == 422) {
            failure("VALIDATION_FAILED", e.getStatus().value(), request, e.getClass(), null);
        } else if (e.getStatus().value() >= 502 && e.getStatus().value() <= 504) {
            failure("DEPENDENCY_FAILED", e.getStatus().value(), request, e.getClass(), null);
        }
        return ResponseEntity.status(e.getStatus()).body(Map.of("error", e.getMessage()));
    }

    /**
     * Constraint violations (duplicate key, NOT NULL, FK) are a client problem, not a server fault:
     * answer 409 with a stable message instead of a 500 carrying the schema.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> integrity(DataIntegrityViolationException e, HttpServletRequest request) {
        String ref = reference();
        System.err.println("[api][" + ref + "] data integrity violation (details suppressed)");
        failure("VALIDATION_FAILED", HttpStatus.CONFLICT.value(), request, e.getClass(), ref);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", "That value conflicts with an existing record, or a required field was missing.",
                "reference", ref));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, String>> optimisticLock(OptimisticLockingFailureException e,
                                                               HttpServletRequest request) {
        failure("VALIDATION_FAILED", HttpStatus.CONFLICT.value(), request, e.getClass(), null);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", "This record changed after you opened it. Refresh and retry your changes."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> any(Exception e, HttpServletRequest request) {
        String ref = reference();
        System.err.println("[api][" + ref + "] unhandled " + e.getClass().getName()
                + " (message and stack trace suppressed; use correlated telemetry in production)");
        String action = isDependencyFailure(e) ? "DEPENDENCY_FAILED" : "API_REQUEST_FAILED";
        failure(action, HttpStatus.INTERNAL_SERVER_ERROR.value(), request, e.getClass(), ref);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "Unexpected server error. Quote reference " + ref + " when reporting this.",
                "reference", ref));
    }

    private static String reference() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private void failure(String action, int status, HttpServletRequest request,
                         Class<?> exceptionType, String reference) {
        String path = request == null ? "unknown" : request.getRequestURI();
        String detail = "status=" + status + " path=" + path
                + " exception=" + exceptionType.getSimpleName()
                + (reference == null ? "" : " reference=" + reference);
        audit.record(null, action, "SECURITY", "api-request", null, path,
                "FAILURE", detail, null);
    }

    private static boolean isDependencyFailure(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String name = current.getClass().getName();
            if (name.contains("SQLException") || name.contains("JDBCConnection")
                    || name.contains("ConnectException") || name.contains("SocketTimeout")
                    || name.contains("ResourceAccessException")) return true;
        }
        return false;
    }
}
