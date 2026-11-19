package com.universium.webcraft.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.universium.webcraft.api.WebCraftReadRoute;
import com.universium.webcraft.api.WebCraftReadRoute.Response;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

final class WebCraftHttpServer implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String MODULES_PATH = "/api/v1/modules";

    private final HttpServer http;
    private final ExecutorService workers;

    WebCraftHttpServer(InetSocketAddress address, MinecraftServer server,
                       Map<ResourceLocation, Map<String, WebCraftReadRoute>> modules, ViewerAccess access,
                       ServerInfoRoute serverInfo, boolean docsEnabled)
            throws IOException {
        Map<String, WebCraftReadRoute> routes = new HashMap<>();
        JsonArray discovery = new JsonArray();
        modules.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            ResourceLocation id = entry.getKey();
            JsonObject module = new JsonObject();
            module.addProperty("id", id.toString());
            JsonObject links = new JsonObject();
            entry.getValue().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(route -> {
                String path = MODULES_PATH + "/" + id.getNamespace() + "/" + id.getPath() + "/" + route.getKey();
                routes.put(path, route.getValue());
                links.addProperty(route.getKey(), path);
            });
            module.add("routes", links);
            discovery.add(module);
        });
        JsonObject document = new JsonObject();
        document.add("modules", discovery);
        Response catalog = Response.ok(document);
        if (serverInfo != null) {
            routes.put(ServerInfoRoute.PATH, serverInfo);
        }
        var docs = docsEnabled ? ApiDocumentation.load(routes) : Map.<String, ApiDocumentation.Asset>of();

        http = HttpServer.create(address, 16);
        workers = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16),
                Thread.ofVirtual().name("webcraft-http-", 0).factory());
        http.setExecutor(workers);
        http.createContext("/", exchange -> {
            try (exchange) {
                ApiDocumentation.Asset asset = docs.get(exchange.getRequestURI().getRawPath());
                if (asset != null && "GET".equals(exchange.getRequestMethod())
                        && exchange.getRequestURI().getRawQuery() == null) {
                    var headers = exchange.getResponseHeaders();
                    headers.set("Content-Security-Policy", "default-src 'none'; script-src 'self'; "
                            + "style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; "
                            + "font-src 'self' data:; frame-ancestors 'none'; base-uri 'none'");
                    write(exchange, 200, asset.contentType(), asset.body());
                    return;
                }
                Response response;
                try {
                    response = dispatch(exchange, server, routes, catalog, access, asset != null);
                } catch (RuntimeException exception) {
                    LOGGER.error("WebCraft REST handler failed", exception);
                    response = Response.error(500, "internal_error");
                }
                byte[] image = response.image();
                byte[] body = image == null ? response.body().toString().getBytes(StandardCharsets.UTF_8) : image;
                write(exchange, response.status(), image == null ? "application/json; charset=utf-8" : "image/png", body);
            }
        });
        http.start();
    }

    private static Response dispatch(HttpExchange exchange, MinecraftServer server, Map<String, WebCraftReadRoute> routes,
                                     Response catalog, ViewerAccess access, boolean documentationPath) {
        String path = exchange.getRequestURI().getRawPath();
        WebCraftReadRoute route = routes.get(path);
        if (route == null && !MODULES_PATH.equals(path) && !documentationPath) {
            return Response.error(404, "not_found");
        }
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "GET");
            return Response.error(405, "method_not_allowed");
        }
        String rawQuery = exchange.getRequestURI().getRawQuery();
        if (documentationPath) {
            return Response.error(400, "unsupported_query");
        }
        if (route instanceof WebCraftReadRoute.PublicRoute publicRoute) {
            return publicRoute.readPublic(rawQuery);
        }
        if (rawQuery != null) {
            return Response.error(400, "unsupported_query");
        }
        if (route == null) {
            return catalog;
        }
        if (access == null) {
            return Response.error(403, "viewer_access_disabled");
        }
        if (!access.permits(exchange.getRequestHeaders().get("Authorization"))) {
            exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
            return Response.error(401, "unauthorized");
        }
        return route.read(server, access.viewer);
    }

    private static void write(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        var headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType);
        headers.set("Cache-Control", "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        if ("HEAD".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(status, -1);
        } else {
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
        }
    }

    InetSocketAddress address() {
        return http.getAddress();
    }

    @Override
    public void close() {
        http.stop(0);
        workers.shutdownNow();
    }

    static final class ViewerAccess {
        private final UUID viewer;
        private final byte[] expectedAuthorization;

        ViewerAccess(UUID viewer, String token) {
            if (token == null || !token.matches("[A-Za-z0-9_-]{43,128}")) {
                throw new IllegalArgumentException("WEBCRAFT_VIEWER_TOKEN requires 43–128 URL-safe characters.");
            }
            this.viewer = viewer;
            expectedAuthorization = ("Bearer " + token).getBytes(StandardCharsets.US_ASCII);
        }

        boolean permits(List<String> authorization) {
            return authorization != null && authorization.size() == 1
                    && MessageDigest.isEqual(expectedAuthorization, authorization.getFirst().getBytes(StandardCharsets.UTF_8));
        }
    }
}
