package io.pilots.processservice.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class GlobalExceptionHandler {

    /**
     * The spec requires HTTP 428 (Precondition Required) when the If-Match header is absent on
     * mutating operations. Spring MVC raises MissingRequestHeaderException (→ 400) for required
     * headers, so we remap it here.
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<Void> handleMissingHeader(MissingRequestHeaderException ex) {
        if ("If-Match".equalsIgnoreCase(ex.getHeaderName())) {
            return ResponseEntity.status(HttpStatus.PRECONDITION_REQUIRED).build();
        }
        return ResponseEntity.badRequest().build();
    }
}
