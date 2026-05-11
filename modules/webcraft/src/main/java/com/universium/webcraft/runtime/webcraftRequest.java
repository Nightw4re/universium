package com.universium.webcraft.runtime;

import java.util.Map;
import java.util.Objects;

public record webcraftRequest(
        String method,
        String path,
        Map<String, String> queryParams,
        Map<String, String> pathParams
) {
    public webcraftRequest {
        method = Objects.requireNonNull(method, "method");
        path = Objects.requireNonNull(path, "path");
        queryParams = Map.copyOf(Objects.requireNonNull(queryParams, "queryParams"));
        pathParams = Map.copyOf(Objects.requireNonNull(pathParams, "pathParams"));
    }
}

