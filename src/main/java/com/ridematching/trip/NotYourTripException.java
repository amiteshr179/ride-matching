package com.ridematching.trip;

public class NotYourTripException extends RuntimeException {

    public NotYourTripException(String driverId) {
        super("Driver " + driverId + " is not assigned to this trip");
    }
}
