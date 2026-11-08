package app.senyap.shared.error;

import app.senyap.shared.web.RequestIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Semua error HTTP keluar dengan format {@link ErrorResponse}. Tidak ada stack trace ke client. */
@RestControllerAdvice
class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorResponse> api(ApiException e) {
        var builder = ResponseEntity.status(e.status());
        if (e.retryAfter() != null) {
            builder.header(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(1, e.retryAfter().toSeconds())));
        }
        return builder.body(ErrorResponse.of(e.code(), e.getMessage(), RequestIdFilter.current()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HandlerMethodValidationException.class,
            HttpMessageNotReadableException.class, MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class, IllegalArgumentException.class})
    ResponseEntity<ErrorResponse> validation(Exception e) {
        return of(ErrorCode.VALIDATION_FAILED);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ErrorResponse> notFound(NoResourceFoundException e) {
        return of(ErrorCode.NOT_FOUND);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ErrorResponse> method(HttpRequestMethodNotSupportedException e) {
        return of(ErrorCode.METHOD_NOT_ALLOWED);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception e) {
        // Hanya tipe exception yang dicatat beserta stack trace; jangan log body request (bisa berisi pesan DM).
        log.error("Error tak terduga request_id={}", RequestIdFilter.current(), e);
        return of(ErrorCode.INTERNAL);
    }

    private static ResponseEntity<ErrorResponse> of(ErrorCode code) {
        return ResponseEntity.status(code.status())
                .body(ErrorResponse.of(code.name(), code.defaultMessage(), RequestIdFilter.current()));
    }
}
