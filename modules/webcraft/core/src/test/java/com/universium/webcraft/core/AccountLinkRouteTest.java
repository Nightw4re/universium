package com.universium.webcraft.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.universium.webcraft.core.WebCraftRoutes.Scope;
import static org.junit.jupiter.api.Assertions.*;

final class AccountLinkRouteTest {
    private static final String PATH = AccountLinkRoute.PATH;
    private static final String LINK_TOKEN = "a".repeat(43);
    private static final String FEED_TOKEN = "b".repeat(43);
    private static final ApiTransport.Policy POLICY = ApiTransport.Policy.from(ApiTransport.WEBSOCKET,
            List.of(PATH + "=WEBSOCKET"));
    private final AccountLinks links = new AccountLinks();
    private final UUID player = UUID.randomUUID();
    private final UUID attempt = UUID.randomUUID();

    @Test
    void redeemsOverRealHttpWithSeparateTokenAndNoPublicDiscovery() throws Exception {
        var auth = linkRoute(false);
        var feed = new WebCraftHttpServer.BearerToken(FEED_TOKEN, "TEST_FEED_TOKEN");
        try (var server = new WebCraftHttpServer(new InetSocketAddress("0.0.0.0", 0), null,
                Map.of(), null, List.of(), feed, true, POLICY, List.of(), auth);
             var client = HttpClient.newHttpClient()) {
            String code = links.issue(player, "In-game Name");
            String body = body(code, attempt);
            String origin = "http://127.0.0.1:" + server.address().getPort();
            assertEquals(401, send(client, origin + PATH, FEED_TOKEN, body).statusCode());
            assertEquals(400, send(client, origin + PATH + "?unexpected=1", LINK_TOKEN, body).statusCode());
            var invalid = send(client, origin + PATH, LINK_TOKEN, body.replace("\"code\":", "\"code\":\"duplicate\",\"code\":"));
            assertEquals(400, invalid.statusCode());

            var redeemed = send(client, origin + PATH, LINK_TOKEN, body);
            assertEquals(200, redeemed.statusCode());
            assertEquals("no-store", redeemed.headers().firstValue("Cache-Control").orElseThrow());
            var json = JsonParser.parseString(redeemed.body()).getAsJsonObject();
            assertEquals(player.toString(), json.get("uuid").getAsString());
            assertEquals("In-game Name", json.get("name").getAsString());
            assertEquals(redeemed.body(), send(client, origin + PATH, LINK_TOKEN, body).body());
            assertEquals(404, send(client, origin + PATH, LINK_TOKEN, body(code, UUID.randomUUID())).statusCode());

            var get = HttpRequest.newBuilder(URI.create(origin + PATH)).build();
            var denied = client.send(get, HttpResponse.BodyHandlers.ofString());
            assertEquals(405, denied.statusCode());
            assertEquals("POST", denied.headers().firstValue("Allow").orElseThrow());
        }
        var routes = routes(auth);
        assertFalse(routes.documentedRoutes().containsKey(PATH));
        assertFalse(routes.supportsSocket(Scope.RESTRICTED_SOCKET));
    }

    @Test
    void restrictsTcpPeerAndBothSocketScopesAndDefaultsToDisabled() throws Exception {
        String body = body(links.issue(player, "Player"), attempt);
        InetAddress remote = InetAddress.getByName("192.0.2.5");
        var request = new WebCraftRoutes.Request(URI.create(PATH), "POST", remote,
                List.of("Bearer " + LINK_TOKEN), Scope.HTTP, body);
        assertEquals(404, routes(null).dispatch(request, false).response().status());
        assertEquals(403, routes(linkRoute(false)).dispatch(request, false).response().status());
        assertEquals(200, routes(linkRoute(true)).dispatch(request, false).response().status());

        for (var scope : List.of(Scope.PUBLIC_SOCKET, Scope.RESTRICTED_SOCKET)) {
            var socket = new WebCraftRoutes.Request(URI.create(PATH), "POST", InetAddress.getLoopbackAddress(),
                    request.authorization(), scope, body);
            assertEquals(404, routes(linkRoute(true)).dispatch(socket, false).response().status());
        }
    }

    private AccountLinkRoute linkRoute(boolean allowRemote) {
        return new AccountLinkRoute(links, new WebCraftHttpServer.BearerToken(LINK_TOKEN, "TEST_LINK_TOKEN"), allowRemote);
    }

    private static WebCraftRoutes routes(AccountLinkRoute route) {
        return new WebCraftRoutes(null, Map.of(), null, List.of(), null, POLICY, route);
    }

    private static String body(String code, UUID attempt) {
        var body = new JsonObject();
        body.addProperty("code", code);
        body.addProperty("attemptId", attempt.toString());
        return body.toString();
    }

    private static HttpResponse<String> send(HttpClient client, String url, String token, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
