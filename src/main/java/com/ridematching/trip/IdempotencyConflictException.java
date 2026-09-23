package com.ridematching.trip;

public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(String key) {
        super("Idempotency key " + key + " was already used with a different request body");
    }
}
