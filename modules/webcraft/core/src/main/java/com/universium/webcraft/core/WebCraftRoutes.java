package com.universium.webcraft.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.universium.webcraft.api.WebCraftChanges;
import com.universium.webcraft.api.WebCraftLiveRoute;
import com.universium.webcraft.api.WebCraftReadRoute;
import com.universium.webcraft.api.WebCraftReadRoute.Response;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

import java.net.InetAddress;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class WebCraftRoutes {
    static final String MODULES_PATH = "/api/v1/modules";
    private static final String INTERNAL_MODULES_PATH = "/api/v1/internal/modules";
    static final String JSON = "application/json; charset=utf-8";
    private static final Logger LOGGER = LogUtils.getLogger();

    enum Scope { HTTP, PUBLIC_SOCKET, RESTRICTED_SOCKET }

    record Request(URI target, String method, InetAddress peer, List<String> authorization, Scope scope) {}

    record Result(Response response, Map<String, String> headers, List<WebCraftChanges> signals) {
        Result(Response response) {
            this(response, Map.of(), List.of());
        }
    }

    private record Route(WebCraftReadRoute handler, WebCraftHttpServer.Feed feed, ApiTransport transport) {
        boolean internal() {
            return handler instanceof WebCraftReadRoute.InternalRoute
                    || feed != null && feed.exposure() == FeedExposure.INTERNAL;
        }

        boolean permitsAddress(InetAddress peer) {
            if (handler instanceof WebCraftReadRoute.InternalRoute) return peer.isLoopbackAddress();
            return feed == null || feed.permitsAddress(peer);
        }

        boolean restricted() {
            return internal() || feed != null && feed.loopbackOnly();
        }
    }

    private final Map<String, Route> routes;
    private final ServerStatusRoute lifecycle;
    private final MinecraftServer server;
    private final WebCraftHttpServer.ViewerAccess access;
    private final WebCraftHttpServer.BearerToken feedToken;

    WebCraftRoutes(MinecraftServer server, Map<ResourceLocation, Map<String, WebCraftReadRoute>> modules,
                   WebCraftHttpServer.ViewerAccess access, List<WebCraftHttpServer.Feed> feeds,
                   WebCraftHttpServer.BearerToken feedToken, ApiTransport.Policy policy) {
        this.server = server;
        this.access = access;
        this.feedToken = feedToken;
        var registered = new HashMap<String, Route>();
        var discovery = new JsonArray();
        var websocketOnly = new JsonArray();
        modules.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            ResourceLocation id = entry.getKey();
            var module = new JsonObject();
            module.addProperty("id", id.toString());
            var links = new JsonObject();
            entry.getValue().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(route -> {
                boolean internal = route.getValue() instanceof WebCraftReadRoute.InternalRoute;
                String prefix = internal ? INTERNAL_MODULES_PATH : MODULES_PATH;
                String path = prefix + "/" + id.getNamespace() + "/" + id.getPath() + "/" + route.getKey();
                ApiTransport transport = policy.resolve(path, ApiTransport.INHERIT);
                registered.put(path, new Route(route.getValue(), null, transport));
                if (!internal) {
                    if (transport == ApiTransport.WEBSOCKET) websocketOnly.add(path);
                    links.addProperty(route.getKey(), path);
                }
            });
            if (links.size() > 0 || entry.getValue().isEmpty()) {
                module.add("routes", links);
                discovery.add(module);
            }
        });
        var document = new JsonObject();
        document.add("modules", discovery);
        if (!websocketOnly.isEmpty()) document.add("websocketOnly", websocketOnly);
        WebCraftReadRoute.PublicRoute discover = query -> query == null
                ? Response.ok(document) : Response.error(400, "unsupported_query");
        registered.put(MODULES_PATH, new Route(discover, null, policy.resolve(MODULES_PATH, ApiTransport.INHERIT)));

        ServerStatusRoute status = null;
        for (var feed : feeds) {
            registered.put(feed.path(), new Route(feed.handler(), feed, policy.resolve(feed.path(), feed.transport())));
            if (feed.handler() instanceof ServerStatusRoute route) status = route;
        }
        lifecycle = status;
        routes = Map.copyOf(registered);
    }

    static URI target(String raw) {
        URI uri = URI.create(raw);
        if (uri.isAbsolute() || uri.getRawAuthority() != null || uri.getRawFragment() != null
                || !raw.startsWith("/") || raw.startsWith("//")) {
            throw new IllegalArgumentException("Expected an origin-form request target");
        }
        return uri;
    }

    boolean supportsSocket(Scope scope) {
        return routes.values().stream().anyMatch(route -> route.transport.websocket()
                && route.restricted() == (scope == Scope.RESTRICTED_SOCKET));
    }

    Map<String, WebCraftReadRoute> documentedRoutes() {
        var documented = new HashMap<String, WebCraftReadRoute>();
        routes.forEach((path, route) -> {
            if (route.transport.http() && !route.internal()) {
                documented.put(path, route.handler);
            }
        });
        return documented;
    }

    Result dispatch(Request request, boolean subscribe) {
        try {
            return read(request, subscribe);
        } catch (RuntimeException exception) {
            LOGGER.error("WebCraft API handler failed", exception);
            return new Result(Response.error(500, "internal_error"));
        }
    }

    private Result read(Request request, boolean subscribe) {
        Route route = routes.get(request.target.getRawPath());
        boolean http = request.scope == Scope.HTTP;
        if (route == null || (!http && route.restricted() != (request.scope == Scope.RESTRICTED_SOCKET))) {
            return new Result(Response.error(404, "not_found"));
        }
        if (!"GET".equals(request.method)) {
            return new Result(Response.error(405, "method_not_allowed"), Map.of("Allow", "GET"), List.of());
        }
        if (!route.permitsAddress(request.peer)) {
            return new Result(Response.error(403, "loopback_required"));
        }
        if (route.internal()) {
            Result denied = authorize(request, feedToken, "feed_access_disabled");
            if (denied != null) return denied;
        }
        if (http ? !route.transport.http() : !route.transport.websocket()) {
            if (http) {
                return new Result(Response.error(405, "transport_disabled"),
                        Map.of("X-WebCraft-Transport", "websocket"), List.of());
            }
            return new Result(Response.error(405, "transport_disabled"));
        }
        if (lifecycle != null && route.handler != lifecycle) {
            Response unavailable = lifecycle.unavailable();
            if (unavailable != null) return new Result(unavailable);
        }
        String query = request.target.getRawQuery();
        if (route.handler instanceof WebCraftReadRoute.InternalRoute internal) {
            return new Result(subscribe ? Response.error(405, "subscriptions_unsupported") : internal.readInternal(server, query));
        }
        boolean isPublic = route.handler instanceof WebCraftReadRoute.PublicRoute;
        if (!isPublic) {
            if (query != null) return new Result(Response.error(400, "unsupported_query"));
            Result denied = authorize(request, access == null ? null : access.token(), "viewer_access_disabled");
            if (denied != null) return denied;
        }
        if (subscribe && !(route.handler instanceof WebCraftLiveRoute)) {
            return new Result(Response.error(405, "subscriptions_unsupported"));
        }
        Response response = isPublic ? ((WebCraftReadRoute.PublicRoute) route.handler).readPublic(query)
                : route.handler.read(server, access.viewer());
        if (subscribe && response.status() >= 200 && response.status() < 300) {
            var signals = ((WebCraftLiveRoute) route.handler).changes();
            return new Result(response, Map.of(), lifecycle == null || route.handler == lifecycle
                    ? List.of(signals) : List.of(signals, lifecycle.changes()));
        }
        return new Result(response);
    }

    private static Result authorize(Request request, WebCraftHttpServer.BearerToken token, String disabledError) {
        if (token == null) {
            return new Result(Response.error(403, disabledError));
        }
        if (!token.permits(request.authorization)) {
            return new Result(Response.error(401, "unauthorized"), Map.of("WWW-Authenticate", "Bearer"), List.of());
        }
        return null;
    }
}
