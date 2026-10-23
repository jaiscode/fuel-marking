package org.jai.fuelmarking.sync;

/** An incoming record refers to something not synced here yet (e.g. a user whose site hasn't arrived). Retry later. */
public class MissingDependencyException extends RuntimeException {
    public MissingDependencyException(String message) { super(message); }
}
