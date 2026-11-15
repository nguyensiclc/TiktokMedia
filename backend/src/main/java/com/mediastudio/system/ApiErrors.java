package com.mediastudio.system;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.NoSuchElementException;

/** Turns exceptions into {error: "..."} so the UI can show the message. */
@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> notFound(Exception e) {
        return body(HttpStatus.NOT_FOUND, e);
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class, UnsupportedOperationException.class})
    public ResponseEntity<Map<String, String>> badRequest(Exception e) {
        return body(HttpStatus.BAD_REQUEST, e);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> other(Exception e) {
        return body(HttpStatus.INTERNAL_SERVER_ERROR, e);
    }

    private static ResponseEntity<Map<String, String>> body(HttpStatus status, Exception e) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return ResponseEntity.status(status).body(Map.of("error", msg));
    }
}
