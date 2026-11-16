package com.live_jgv.archiver.entrypoint;

import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class HistoryErrors extends ResponseEntityExceptionHandler {
    @ExceptionHandler({DataAccessException.class, org.springframework.transaction.TransactionException.class})
    ProblemDetail unavailable(RuntimeException failure) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Historical storage is unavailable");
    }
    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalid(IllegalArgumentException failure) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid history query");
    }
}
