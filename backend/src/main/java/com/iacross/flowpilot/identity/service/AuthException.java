package com.iacross.flowpilot.identity.service;

/** Thrown for authentication/authorization failures within the identity module. */
public class AuthException extends RuntimeException {
    public AuthException(String message) {
        super(message);
    }
}
