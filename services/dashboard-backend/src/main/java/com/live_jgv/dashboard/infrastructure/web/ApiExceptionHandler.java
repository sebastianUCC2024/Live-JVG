package com.live_jgv.dashboard.infrastructure.web;
import com.live_jgv.dashboard.infrastructure.http.ProducerGateway;

import com.live_jgv.dashboard.domain.model.InvalidScoreUpdateException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Conserva los estados HTTP y evita exponer datos internos en los errores. */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    @ExceptionHandler(com.live_jgv.dashboard.infrastructure.http.ProducerGateway.PublicationFailure.class)
    public org.springframework.http.ResponseEntity<java.util.Map<String,Object>> handlePublicationFailure(
            com.live_jgv.dashboard.infrastructure.http.ProducerGateway.PublicationFailure failure) {
        return org.springframework.http.ResponseEntity.status(failure.getStatusCode())
                .cacheControl(org.springframework.http.CacheControl.noStore()).body(failure.body());
    }
    @ExceptionHandler(InvalidScoreUpdateException.class)
    public ProblemDetail handleInvalidScoreUpdate(InvalidScoreUpdateException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid score update");
    }
}
