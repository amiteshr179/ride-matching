package com.ridematching.trip;

import java.util.UUID;

public class TripNotFoundException extends RuntimeException {

    public TripNotFoundException(UUID id) {
        super("Trip not found: " + id);
    }
}
