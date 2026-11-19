package com.universium.webcraft.runtime;

public record webcraftResponse(int status, String contentType, String body) {
    public static webcraftResponse json(String body) {
        return new webcraftResponse(200, "application/json", body);
    }
}

