package com.live_jgv.dashboard.infrastructure.web;

import com.live_jgv.dashboard.domain.model.InvalidScoreUpdateException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Preserves Spring MVC status codes and avoids exposing invalid input values. */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    @ExceptionHandler(InvalidScoreUpdateException.class)
    public ProblemDetail handleInvalidScoreUpdate(InvalidScoreUpdateException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid score update");
    }
}
