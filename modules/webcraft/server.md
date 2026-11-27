# Server setup and operation

[WebCraft index](README.md)

## Install on a dedicated server

The mods target Minecraft 1.21.1, Java 21 and NeoForge 21.1.230 or later in the 21.1 line. If building from source, run the Gradle build from the Universium root. Use only the project tasks for the mods you need. The command below builds WebCraft and the four add-ons listed in the [module table](README.md).

```sh
gradle :webcraft-core:build :webcraft-equipment:build :webcraft-catalog:build :webcraft-designs:build :webcraft-quests:build
```

1. Put `webcraft-<version>.jar` into the server's `mods/` directory. If building locally, find it in `core/build/libs/`. The same JAR provides the exporter when installed on a Minecraft client.
2. Put each desired add-on JAR in the same server `mods/` directory. Local builds appear in the matching add-on's `build/libs/` directory, for example `equipment/build/libs/webcraft-equipment-<version>.jar`. Follow the [FTB Quests](api.md#ftb-quests) setup when installing that add-on.
3. Start the server once to generate `config/webcraft-server.toml`, then stop it and edit the file. A file at `<level-name>/serverconfig/webcraft-server.toml` takes precedence. Set `listenerEnabled = true` to start the API. Add the desired module IDs from the [module table](README.md) to `enabledModules` to activate routes from the installed add-ons. Leave the list empty for built-in feeds alone and enable those feeds in their own settings.
4. Complete any module-specific setup and restart the server. For inventory previews, install a [client-exported preview pack](exports.md#export-inventory-previews). For a basic vanilla fallback, put the official Minecraft 1.21.1 client JAR at `webcraft-assets/client.jar`, relative to the server working directory. Equipment item details are available without either archive.

The following example enables only the Equipment add-on. Use the UUID the server assigns to a player who has agreed to share their equipment with the token holder. Generate a random 32-byte secret with a command such as `openssl rand -hex 32` and pass it to the server process as `WEBCRAFT_VIEWER_TOKEN`. The accepted format is 43 to 128 URL-safe ASCII characters. Share the token privately with its intended viewer. After restarting, join with the selected player, [start the reference frontend](frontend/README.md), and enter the token.

```toml
listenerEnabled = true
bindAddress = "127.0.0.1"
port = 8765
enabledModules = ["webcraft_equipment:equipment"]
viewerUuid = "00000000-0000-0000-0000-000000000000"
```

Replace the example UUID. The listener defaults to off, with an empty module list and no authorized viewer. Keep the loopback bind for a proxy on the same network namespace. Configure the API transport separately in the [server configuration](#api-transports).

### Reload WebCraft on a running server

Run `webcraft reload` in the server console, or use `/webcraft reload` in-game with permission level 4. WebCraft reapplies the server settings loaded by NeoForge's config watcher, refreshes the built-in feeds and add-on data, and reopens the WebCraft API listener. HTTP requests in flight may be interrupted and WebSocket clients need to reconnect. Minecraft players stay connected.

Install new or updated WebCraft mod JARs and change process environment variables such as `WEBCRAFT_VIEWER_TOKEN` through a server restart. To refresh an export on a running server, replace the complete archive where the filesystem permits replacement and then run the command. If the filesystem blocks replacement, install the archive with the server stopped.

## Enable HTTP in singleplayer

Install the same `webcraft-<version>.jar` in the client's `mods/` directory. Open a singleplayer world once to generate `config/webcraft-server.toml`, then close the world to edit the config. A file at `saves/<world>/serverconfig/webcraft-server.toml` takes precedence for that world.

```toml
singleplayerEnabled = true
apiDocsEnabled = true
```

Reopen the world and visit `http://127.0.0.1:8765/api/v1/docs` for Swagger UI. The port is configurable with `port`. The listener closes when you leave the world. It starts only for worlds hosted by your client.

The singleplayer listener defaults to disabled. Once enabled it binds to `127.0.0.1`. To allow network connections, set `singleplayerAllowNetwork = true` and set `bindAddress` to the interface you want to share. Route authentication, transport rules and per-feed loopback settings also apply in singleplayer. Set `enabled = true` in the [server information config](examples/webcraft-server-info.toml) to include that feed in the API.

## Upgrade a previous preview installation

Replace both the old `webcraft-core-*.jar` and `webcraft-exporter-*.jar` with the single `webcraft-<version>.jar` in each instance. WebCraft keeps the `webcraft_core` mod ID and uses the existing server config files.

1. Stop the server and replace the former `webcraft-next-*.jar` files with the matching `webcraft-*.jar` files. The exporter is part of the main WebCraft JAR.
2. Copy the previous world's `serverconfig/webcraft-next-server.toml` to `serverconfig/webcraft-server.toml` unless the destination already exists. In `enabledModules`, change the prefix `webcraft_next_` to `webcraft_` for WebCraft's built-in modules.
3. Rename `WEBCRAFT_NEXT_VIEWER_TOKEN` to `WEBCRAFT_VIEWER_TOKEN` in the server process environment, keeping the existing token value. Upgrade the frontend together with the server. Integrations can rediscover current routes through `GET /api/v1/modules`. Update any hard-coded module IDs and endpoint paths to the new namespaces.

Existing v1 export archives still work, including design manifests from the former namespace. The optional frontend stays off by default. Use the [development launch command](frontend/README.md) to start it locally.

## Wings and HTTPS

Assign a second TCP allocation to the Minecraft server in Pterodactyl Wings and set `port` to that allocation's port. Bind on the appropriate container interface or use `0.0.0.0` inside the container when the proxy reaches it through the published allocation. Restrict the allocation to the reverse proxy or a private network at the host or firewall. Use a private or otherwise protected connection for the HTTP hop carrying viewer tokens. [Wings allocation documentation](https://docs.pterodactyl.io/v1/wings/installing#node-allocations)

Serve the static frontend from the HTTPS web origin. Forward the REST paths it needs and, when WebSocket is enabled for frontend routes, forward the exact path `/api/v1/ws` with HTTP/1.1 upgrade headers. Add the frontend's HTTPS origin to `websocketOrigins` in `webcraft-server.toml`. Preserve the original request target and the `Origin` and `Authorization` headers, disable proxy and CDN caching of the API, and keep credentials and WebSocket message bodies out of logs. Apply request and connection limits at the proxy.

Exclude `/api/v1/internal/`, including `/api/v1/internal/ws`, from browser-facing forwarding. Also exclude any public REST path configured with `loopbackOnly = true` when the upstream proxy itself connects over loopback. The public WebSocket endpoint rejects all internal and loopback-only feeds, even when a proxy connects from loopback and the caller has a machine token.

For example, add these locations inside an existing TLS-enabled nginx server. Adjust the upstream address for Wings or for a remote game server.

```nginx
location ^~ /api/v1/internal/ { return 404; }

location = /api/v1/ws {
    proxy_pass http://127.0.0.1:8765;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_set_header Host $http_host;
    proxy_set_header Origin $http_origin;
    proxy_set_header Authorization $http_authorization;
    proxy_read_timeout 90s;
    proxy_cache off;
}

location /api/v1/ {
    proxy_pass http://127.0.0.1:8765;
    proxy_set_header Authorization $http_authorization;
    proxy_cache off;
}
```

Equipment uses single-viewer private access. Anyone who holds the token can read the configured player's currently equipped items, including custom item names. Removing the UUID or rotating the token and restarting revokes access. A public player-facing equipment rollout needs account authentication, a verified mapping from accounts to player UUIDs and server-side visibility and consent controls. The enabled-module list and Item Catalog expose public metadata only.

## API transports

In the world's `serverconfig/webcraft-server.toml`, set `apiTransport` to `HTTP` for REST only, `WEBSOCKET` for WebSocket only, or `BOTH`. An existing file without the setting defaults to `HTTP`. The global setting applies to discovery, module routes, previews, textures and enabled feeds. Restart the server or reopen the singleplayer world to apply changes.

The server uses the first applicable setting in this order.

1. An exact path in `routeTransports`, including the `/internal/` segment for internal feeds. Use the actual path and omit the query.
2. The feed's explicit selection. Use `transport` in the status or information config, or `playerHistoryTransport`, `onlinePlayersTransport`, `playerRosterTransport`, or `advancementsTransport` in the main config. `INHERIT` uses the global setting.
3. The global `apiTransport`.

For example, this keeps REST enabled for discovery and the internal online feed while switching the remaining API routes to WebSocket. See the [operator configuration example](examples/webcraft-server.toml).

```toml
apiTransport = "WEBSOCKET"
routeTransports = ["/api/v1/modules=BOTH"]
onlinePlayersTransport = "HTTP"
```

Transport settings leave listener and module enablement, feed exposure, bearer tokens and loopback requirements in force. REST clients receive `405 transport_disabled` with `X-WebCraft-Transport: websocket` when a route requires WebSocket and they pass any feed-level checks. Discovery returns the same `modules` and route links over either transport. If any module routes require WebSocket, its optional `websocketOnly` array lists those exact paths.
