package com.live_jgv.betting.infrastructure.web;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import java.util.Map;

@RestControllerAdvice
public class StatusErrors extends ResponseEntityExceptionHandler {
    @ExceptionHandler({org.springframework.dao.DataAccessException.class,org.springframework.transaction.TransactionException.class,IllegalStateException.class})
    ResponseEntity<Map<String,Object>> unavailable(RuntimeException failure) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("status","UNKNOWN","bets_allowed",false,"reliable",false,"reason","STORAGE_UNAVAILABLE_OR_INCONSISTENT"));
    }
    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalid(IllegalArgumentException failure) { return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,"Invalid status query"); }
}
