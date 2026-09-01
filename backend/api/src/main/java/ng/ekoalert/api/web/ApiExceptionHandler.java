package ng.ekoalert.api.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.stream.Collectors;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** The services throw this for anything a caller can fix by sending different input. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Dtos.ApiError> badRequest(IllegalArgumentException e) {
        // Logged because the framework raises this type too, and a wiring fault
        // reported to the caller as a 400 is a fault that hides.
        log.warn("rejected a request as bad input: {}", e.getMessage());
        return ResponseEntity.badRequest()
                .body(new Dtos.ApiError("bad_request", e.getMessage(), Instant.now()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Dtos.ApiError> invalid(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest()
                .body(new Dtos.ApiError("validation_failed", detail, Instant.now()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Dtos.ApiError> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest()
                .body(new Dtos.ApiError("unreadable_body", "the request body could not be parsed", Instant.now()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Dtos.ApiError> conflict(IllegalStateException e) {
        log.warn("illegal state serving a request", e);
        return ResponseEntity.status(409)
                .body(new Dtos.ApiError("conflict", e.getMessage(), Instant.now()));
    }
}
