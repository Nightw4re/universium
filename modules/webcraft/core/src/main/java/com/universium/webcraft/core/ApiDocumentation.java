package com.universium.webcraft.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.universium.webcraft.api.WebCraftReadRoute;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

final class ApiDocumentation {
    static final String UI_PATH = "/api/v1/docs";
    static final String SPEC_PATH = "/api/v1/openapi.json";

    record Asset(String contentType, byte[] body) {}

    private ApiDocumentation() {}

    static Map<String, Asset> load(Map<String, WebCraftReadRoute> routes) throws IOException {
        var spec = JsonParser.parseString(new String(read("openapi.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject paths = spec.getAsJsonObject("paths");
        if (!routes.containsKey(WebCraftRoutes.MODULES_PATH)) {
            paths.remove(WebCraftRoutes.MODULES_PATH);
        }
        if (!routes.containsKey(ServerInfoRoute.PATH)) {
            paths.remove(ServerInfoRoute.PATH);
        }
        if (!routes.containsKey(ServerStatusRoute.PATH)) {
            paths.remove(ServerStatusRoute.PATH);
        }
        for (String route : List.of("/api/v1/modules/webcraft_catalog/recipes/recipes",
                "/api/v1/modules/webcraft_catalog/recipes/tag",
                "/api/v1/modules/webcraft_catalog/recipes/texture")) {
            if (!routes.containsKey(route)) paths.remove(route);
        }
        routes.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            if (!paths.has(entry.getKey())) {
                paths.add(entry.getKey(), modulePath(entry.getKey(), entry.getValue()));
            }
        });
        return Map.of(
                UI_PATH, new Asset("text/html; charset=utf-8", read("index.html")),
                UI_PATH + "/initialize.js", new Asset("text/javascript; charset=utf-8", read("initialize.js")),
                UI_PATH + "/swagger-ui-bundle.js", new Asset("text/javascript; charset=utf-8", read("swagger-ui-bundle.js")),
                UI_PATH + "/swagger-ui-bundle.js.LICENSE.txt", textAsset("swagger-ui-bundle.js.LICENSE.txt"),
                UI_PATH + "/swagger-ui.css", new Asset("text/css; charset=utf-8", read("swagger-ui.css")),
                UI_PATH + "/swagger-ui.LICENSE.txt", textAsset("swagger-ui.LICENSE.txt"),
                UI_PATH + "/swagger-ui.NOTICE.txt", textAsset("swagger-ui.NOTICE.txt"),
                SPEC_PATH, new Asset("application/json; charset=utf-8", spec.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private static Asset textAsset(String name) throws IOException {
        return new Asset("text/plain; charset=utf-8", read(name));
    }

    private static JsonObject modulePath(String path, WebCraftReadRoute route) {
        boolean publicRoute = route instanceof WebCraftReadRoute.PublicRoute;
        JsonObject operation = new JsonObject();
        operation.addProperty("summary", path.substring(path.lastIndexOf('/') + 1));
        JsonObject responses = new JsonObject();
        responses.add("200", description("Module response"));
        if (!publicRoute) {
            JsonObject bearer = new JsonObject();
            bearer.add("viewerToken", new JsonArray());
            JsonArray security = new JsonArray();
            security.add(bearer);
            operation.add("security", security);
            responses.add("401", description("Missing or invalid viewer token"));
            responses.add("403", description("Viewer access disabled"));
        }
        operation.add("responses", responses);
        JsonObject pathItem = new JsonObject();
        pathItem.add("get", operation);
        return pathItem;
    }

    private static JsonObject description(String text) {
        JsonObject object = new JsonObject();
        object.addProperty("description", text);
        return object;
    }

    private static byte[] read(String name) throws IOException {
        try (var input = ApiDocumentation.class.getResourceAsStream("/webcraft-api/" + name)) {
            if (input == null) {
                throw new IOException("Missing WebCraft API documentation resource: " + name);
            }
            return input.readAllBytes();
        }
    }
}
