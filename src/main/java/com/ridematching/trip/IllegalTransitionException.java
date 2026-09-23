package com.ridematching.trip;

public class IllegalTransitionException extends RuntimeException {

    public IllegalTransitionException(TripStatus from, TripStatus to) {
        super("Cannot move trip from " + from + " to " + to);
    }
}
