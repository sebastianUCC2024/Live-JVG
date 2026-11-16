package com.live_jgv.worker.entrypoint;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class OperationErrors extends ResponseEntityExceptionHandler {
    @ExceptionHandler({org.springframework.dao.DataAccessException.class,org.springframework.transaction.TransactionException.class,IllegalStateException.class})
    ProblemDetail unavailable(RuntimeException failure){return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,"Operation storage unavailable");}
    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalid(IllegalArgumentException failure){return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,"Invalid operation query");}
}
