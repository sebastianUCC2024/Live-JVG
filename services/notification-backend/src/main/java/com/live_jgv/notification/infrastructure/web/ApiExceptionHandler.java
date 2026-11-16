package com.live_jgv.notification.infrastructure.web;

import com.live_jgv.notification.application.StorageUnavailableException;
import com.live_jgv.notification.infrastructure.jdbc.AlertCursor;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public final class ApiExceptionHandler {
    private static final Logger LOG=LoggerFactory.getLogger(ApiExceptionHandler.class);
    @ExceptionHandler({IllegalArgumentException.class,MethodArgumentTypeMismatchException.class,org.springframework.web.bind.MissingServletRequestParameterException.class})
    public ResponseEntity<?> invalid(Exception ignored) { return ResponseEntity.badRequest().body(Map.of("error","Consulta de alertas inválida.")); }
    @ExceptionHandler(AlertCursor.StaleCursorException.class)
    public ResponseEntity<?> stale() { return ResponseEntity.status(409).body(Map.of("error","El cursor ya no corresponde al estado disponible. Recarga las alertas.")); }
    @ExceptionHandler({StorageUnavailableException.class,DataAccessException.class})
    public ResponseEntity<?> unavailable(Exception failure) {
        LOG.warn("Notification query unavailable; failureType={}",failure.getClass().getSimpleName());
        return ResponseEntity.status(503).body(Map.of("error","Las notificaciones no están disponibles temporalmente."));
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> unexpected(Exception failure) {
        if (failure instanceof org.springframework.web.ErrorResponse response)
            return ResponseEntity.status(response.getStatusCode()).body(Map.of("error","Solicitud de notificaciones inválida."));
        LOG.error("Notification request failed; failureType={}",failure.getClass().getSimpleName());
        return ResponseEntity.internalServerError().body(Map.of("error","No se pudo consultar las notificaciones."));
    }
}
