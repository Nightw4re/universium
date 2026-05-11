package com.universium.webcraft.runtime;

public final class webcraftRouteException extends RuntimeException {
    private final int status;

    public webcraftRouteException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}

