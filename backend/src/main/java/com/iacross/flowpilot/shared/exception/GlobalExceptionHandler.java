package com.iacross.flowpilot.shared.exception;

import com.iacross.flowpilot.shared.lock.ConversationLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    record ErrorBody(String error) {}

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorBody> handleBadArg(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(new ErrorBody(ex.getMessage()));
    }

    @ExceptionHandler(ConversationLock.LockAcquisitionException.class)
    ResponseEntity<ErrorBody> handleLock(ConversationLock.LockAcquisitionException ex) {
        log.warn("Lock contention: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(new ErrorBody("Server busy, please retry"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorBody> handleGeneric(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.internalServerError()
            .body(new ErrorBody("Internal server error"));
    }
}
