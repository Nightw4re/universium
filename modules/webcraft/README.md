# WebCraft

WebCraft exposes Minecraft server data over HTTP and WebSocket on the same port. Players can connect with their regular modpack client. Install WebCraft and choose the optional add-ons you need. Each add-on is a separate NeoForge mod JAR that depends on WebCraft and registers one or more API modules.

| Mod | Purpose | Gradle project | NeoForge mod ID | Entries for `enabledModules` |
| --- | --- | --- | --- | --- |
| [WebCraft](core) | HTTP and WebSocket API, built-in server and player feeds including [advancements](#advancements), and the [client preview exporter](#export-inventory-previews) | `:webcraft-core` | `webcraft_core` | Built-in feeds use their own settings |
| [Equipment](equipment) | Authenticated view of a selected online player's equipment | `:webcraft-equipment` | `webcraft_equipment` | `webcraft_equipment:equipment` |
| [Item Catalog](catalog) | Exported item icons, stack details, recipes and tooltips | `:webcraft-catalog` | `webcraft_catalog` | `webcraft_catalog:items`<br>`webcraft_catalog:recipes`<br>`webcraft_catalog:tooltips` |
| [Design Data](designs) | Exported banner patterns and armor trim data for design previews | `:webcraft-designs` | `webcraft_designs` | `webcraft_designs:banners`<br>`webcraft_designs:armor` |
| [FTB Quests](#ftb-quests) | Internal feed for visible quests and a selected player's FTB team progress. Requires FTB Quests on the server. | `:webcraft-quests` | `webcraft_quests` | `webcraft_quests:quests` |
| [Ponder](#ponder-scene-api) | Serves exported scenes from `previews.zip`. Create and Ponder are required on the client that captures scenes. | `:webcraft-ponder` | `webcraft_ponder` | `webcraft_ponder:ponder` |

The [reference frontend](frontend/README.md) is an independent web client.

## Install on a dedicated server

The mods target Minecraft 1.21.1, Java 21 and NeoForge 21.1.230 or later in the 21.1 line. If building from source, run the Gradle build from the Universium root. Use only the project tasks for the mods you need. The command below builds WebCraft and all five add-ons.

```sh
gradle :webcraft-core:build :webcraft-equipment:build :webcraft-catalog:build :webcraft-designs:build :webcraft-quests:build :webcraft-ponder:build
```

1. Put `webcraft-<version>.jar` into the server's `mods/` directory. If building locally, find it in `core/build/libs/`. The same JAR provides the exporter when installed on a Minecraft client.
2. Put each desired add-on JAR in the same server `mods/` directory. Local builds appear in the matching add-on's `build/libs/` directory, for example `equipment/build/libs/webcraft-equipment-<version>.jar`. Follow the [FTB Quests](#ftb-quests) or [Ponder](#ponder-scene-api) setup for those add-ons.
3. Start the server once to generate `config/webcraft-server.toml`, then stop it and edit the file. A file at `<level-name>/serverconfig/webcraft-server.toml` takes precedence. Set `listenerEnabled = true` to start the API. Add the desired module IDs from the table to `enabledModules` to activate routes from the installed add-ons. Leave the list empty for built-in feeds alone and enable those feeds in their own settings.
4. Complete any module-specific setup and restart the server. For inventory previews, install a [client-exported preview pack](#export-inventory-previews). For a basic vanilla fallback, put the official Minecraft 1.21.1 client JAR at `webcraft-assets/client.jar`, relative to the server working directory. Equipment item details are available without either archive.

The following example enables only the Equipment add-on. Use the UUID the server assigns to a player who has agreed to share their equipment with the token holder. Generate a random 32-byte secret with a command such as `openssl rand -hex 32` and pass it to the server process as `WEBCRAFT_VIEWER_TOKEN`. The accepted format is 43 to 128 URL-safe ASCII characters. Share the token privately with its intended viewer. After restarting, join with the selected player, [start the reference frontend](frontend/README.md), and enter the token.

```toml
listenerEnabled = true
bindAddress = "127.0.0.1"
port = 8765
enabledModules = ["webcraft_equipment:equipment"]
viewerUuid = "00000000-0000-0000-0000-000000000000"
```

Replace the example UUID. The listener defaults to off, with an empty module list and no authorized viewer. Keep the loopback bind for a proxy on the same network namespace. Configure the API transport separately in the [server configuration](#api-transports).

## Enable HTTP in singleplayer

Install the same `webcraft-<version>.jar` in the client's `mods/` directory. Open a singleplayer world once to generate `config/webcraft-server.toml`, then close the world to edit the config. A file at `saves/<world>/serverconfig/webcraft-server.toml` takes precedence for that world.

```toml
singleplayerEnabled = true
apiDocsEnabled = true
```

Reopen the world and visit `http://127.0.0.1:8765/api/v1/docs` for Swagger UI. The port is configurable with `port`. The listener closes when you leave the world. It starts only for worlds hosted by your client.

The singleplayer listener defaults to disabled. Once enabled it binds to `127.0.0.1`. To allow network connections, set `singleplayerAllowNetwork = true` and set `bindAddress` to the interface you want to share. Route authentication, transport rules and per-feed loopback settings also apply in singleplayer. Set `enabled = true` in the [server information config](examples/webcraft-server-info.toml) to include that feed in the API.

### Upgrade a previous preview installation

Replace both the old `webcraft-core-*.jar` and `webcraft-exporter-*.jar` with the single `webcraft-<version>.jar` in each instance. WebCraft keeps the `webcraft_core` mod ID and uses the existing server config files.

1. Stop the server and replace the former `webcraft-next-*.jar` files with the matching `webcraft-*.jar` files. The exporter is part of the main WebCraft JAR.
2. Copy the previous world's `serverconfig/webcraft-next-server.toml` to `serverconfig/webcraft-server.toml` unless the destination already exists. In `enabledModules`, change the prefix `webcraft_next_` to `webcraft_` for WebCraft's built-in modules.
3. Rename `WEBCRAFT_NEXT_VIEWER_TOKEN` to `WEBCRAFT_VIEWER_TOKEN` in the server process environment, keeping the existing token value. Upgrade the frontend together with the server. Integrations can rediscover current routes through `GET /api/v1/modules`. Update any hard-coded module IDs and endpoint paths to the new namespaces.

Existing v1 export archives still work, including design manifests from the former namespace. The optional frontend stays off by default. Use the [development launch command](frontend/README.md) to start it locally.

## Export inventory previews

Build WebCraft with `gradle :webcraft-core:build` and put `webcraft-<version>.jar` from `core/build/libs/` in the Minecraft client's `mods/` directory. The exporter uses the installed modpack and active client resource packs. Client-side export works independently of the HTTP listener.

1. Join the target server with the same modpack and select the desired client language and resource packs. A singleplayer export describes that world's recipes and datapacks.
2. Run the client command `/webcraft export`. Check the sections you want and select **Export selected data**. Choose WebP (the default) or PNG under **Preview format**. During export, the screen shows the current phase and the remaining phases. Completion appears once the ZIP has been saved.
3. Take `webcraft-exports/previews.zip` from the client instance and install it as `webcraft-assets/previews.zip` in the server working directory. Replace it while the server is stopped so all indexes and attachments come from the same export. Each successful export replaces the previous client archive. Include all the inventory variants you want in a new export before replacing the server copy.
4. Repeat the catalog export after changing the server's recipes or datapacks, the modpack, or the client language or resource packs. An existing installation serves the previous snapshot until you replace its archive and restart. A separate website can ingest the [catalog data files](#item-data-feeds) and catalog images directly without contacting the game server.

- **Items and previews** captures the visible item catalog, public stack data, and static and animated previews of catalog and inventory stacks.
- **Recipes** captures world recipes and machine recipes available through JEI or EMI. Keep only this section checked for recipe data and its referenced resources. Recipe-only ZIPs contain no `catalog.json` and cannot replace an item catalog in importers that require it.
- **Tooltips** captures item tooltips and their shortcut alternatives. A tooltip export includes a catalog index so consumers can resolve item and variant IDs.
- **Banners and armor** captures design data and its textures.
- **Ponder scenes** records registered interactive scenes and their rendered geometry. This is an opt-in export section that requires Ponder on the client. The Create mod includes Ponder.

The visible catalog skips the operator tab and items, blocks and tabs tagged `c:hidden_from_recipe_viewers`. When a recipe viewer is ready, catalog items must also appear in its visible item list. Under **Advanced options**, **Export inventory only** renders your exact inventory stacks independently of the checked sections. **Export including hidden items** applies the checked sections to every non-empty default stack in the client registry.

WebCraft saves changes to the section checkboxes in `config/webcraft-export.toml` in the Minecraft client instance. The screen restores the selection the next time it opens. You can also edit the file directly. For a recipe-only export, set `sections = ["recipes"]`. A new configuration enables items, recipes, tooltips and designs.

The client calls Minecraft's ordinary GUI item renderer in the loaded world. This includes any renderer that NeoForge selects for the stack. It captures 64 × 64 RGBA at three pixels per GUI unit, with the item's inventory pose intact. In WebP mode, the archive worker encodes those captures as lossless WebP. The [bundled encoder](exporter/codec/README.md) runs on Java 21 across client platforms. If WebP encoding fails, the export stops and the screen offers PNG as a manual retry. Animation detection uses the model quads shared by the standard item renderer and Sodium's optimized item renderer. When the rendered model uses animated atlas sprites, the exporter samples the inventory render on successive client ticks for one combined loop. This includes game-interpolated frames and active resource pack overrides. Identical consecutive samples share one frame whose duration is the sum of their ticks.

During capture, RGB tints from Productive Bees advanced hives, expansion boxes and configurable honeycomb, Ender IO dyed clear glass, Nature's Aura Ancient Leaves and Mystical Agradditions Insanium Farmland are interpreted as opaque. NeoVitae's Spatial Rift uses the Structure Void marker when its inventory render is empty. Unconfigured Domum Ornamentum blocks use their declared default materials for a stable preview. Already configured stacks use the materials stored on the stack.

The exporter samples combined loops of up to 160 ticks (8 seconds), in batches of up to four stacks. Longer combinations, motion driven by custom renderers outside the baked-quad path, and item models that change selection with world or player state may need dedicated adapters. The ordinary static export remains available for supported, visible renders. The shared enchantment glint recipe is independent of atlas animations.

Pack v1 contains `format.txt` with `webcraft-previews-v1/1.21.1` followed by a newline. The key is the SHA-256 of the UTF-8 text produced by Minecraft's sorted SNBT serialization of the exact stack, including count and data components. The dedicated server computes the key from the live Equipment stack and reads the pre-rendered image. Inventory stacks contribute only hashed images. Keep the combined archive out of Git and public static hosting, since those images can reveal private inventory appearances. To serve a standalone site, ingest the public manifests and only images referenced by the catalog or design manifests.

Importers should resolve each `previewKey` to `previews/<key>.webp` first and fall back to `previews/<key>.png` for older packs or PNG exports. For motion, prefer the optional `animations/<key>.webp` and fall back to `animations/<key>.png` (APNG). Both animation formats preserve loop and frame durations. Serve WebP bytes directly as `image/webp`. Deploy support for both extensions in Capycraft before deploying the WebP exporter. Design textures stay at `textures/<hash>.png`.

Each export includes `export-diagnostics.json` at the root of the ZIP. Its `selectedSections` lists the requested data sections. The report lists missing images for attempted catalog items, variants and machine recipe materials with their IDs and reasons. An item whose captured quads use only Minecraft's missing-texture sprite is reported as `missing_texture`. When the exporter resolved a recipe sprite ID, the report includes it. The report also includes whether a recipe viewer was available, how many distinct fluids and chemicals were checked and how many machine recipe captures were skipped. Private inventory failures appear as a count. A completed export with missing images shows a warning and the report filename on the export screen.

To check an existing catalog ZIP without Minecraft, run `python modules/webcraft/exporter/check-previews.py /path/to/previews.zip` with Pillow installed. Append item IDs to check a subset. The checker prints a JSON report and exits with status 1 when it finds missing previews, fully transparent images or Minecraft's black-and-magenta placeholder.

### Run a CurseForge export from the command line

On Windows x64, point the launcher at an installed CurseForge instance with Minecraft 1.21.1 and NeoForge 21.1. The instance path is the only required argument. The launcher runs on Python 3.9 or later, uses Java 21 and builds WebCraft with Gradle.

```powershell
py modules/webcraft/exporter/launch-export.py "C:\Path\To\CurseForge\Instances\My Modpack"
```

The launcher copies the pack into `.local-artifacts/webcraft-instance-exports/`, starts a small hidden OpenGL window with sound muted, and uses a dedicated flat world. It copies that world from the last successful export of the same pack version when available. It creates a fresh flat world for the first run. The client exits after writing the catalog ZIP, and the launcher prints its path after verifying it. The first startup can take several minutes on a large pack. The launcher prints the client log path before starting Minecraft and omits Crash Assistant from the copy to keep crash dialogs out of the foreground.

Use `--dry-run` to check the local Java and CurseForge installation without building or starting the game. Use `--include-hidden` to export all registered items, `--format png` for PNG, or `--heap-gb` to adjust the default 8 GiB heap. Use `--install` for a nonstandard CurseForge installation folder and `--webcraft-jar` to use an already built WebCraft JAR. Press Ctrl+C to cancel. The isolated world and log stay with that run.

The launcher uses the modpack instance's `config/webcraft-export.toml` by default. Use `--sections recipes` to override the selection for a single run. The available selections are `items`, `recipes`, `tooltips`, `designs` and `ponder`.

The launcher prints an update every minute with the elapsed time, current phase and available item counts. The client log records elapsed time and capture time for each export phase, plus time spent processing archive work. Use the render and animation phases to compare exports with the same modpack, selected sections and image format. Capture can overlap archive work, so their measured times do not add up to the total.

### Export Ponder scenes

Select **Ponder scenes** in the exporter or include `ponder` in the configured sections. To export only the Create mechanical pump example, set the following options in the client configuration.

```toml
sections = ["ponder"]
ponderScenes = ["create:mechanical_pump/speed"]
```

An empty `ponderScenes` list selects all schematics registered in the client. The selection uses schematic IDs so scenes can be filtered before they are compiled. This export works independently of catalog and recipe exports. Use a Minecraft 1.21.1 NeoForge client with Ponder. The reference build uses Ponder 1.0.82 as shipped with Create 6.0.10.

The ZIP stores `ponder/index.json` with version `1`, the capture locale, and the ID, title, schematic, item associations and manifest path for each scene. A scene manifest gives the reference viewport, tick rate, frame count, keyframe positions and diffuse lighting. Numbered `frames/<tick>.json` files start at tick zero. Each frame contains ordered scene and overlay draws, the scene-to-GUI matrix, captions, and a reference to block picking data. Picking boxes use block-local coordinates followed by their world section transform. This lets a player seek directly to a tick and identify blocks under the pointer.

Each draw identifies its shader, textures, projection, model-view and texture matrices, and depth, cull and blend state. The mesh paths reference little-endian vertex and optional index buffers. Each draw lists the vertex stride and attribute types and offsets. Draws with implicit quad indices use triangles `0,1,2` and `2,3,0` per quad. Matrices use column-major order. PNGs capture texture samplers. Where present, the texture animation document maps atlas rectangles to source image strips and ordered `[frameIndex, tickDuration]` pairs. Animated block renderers and particles contribute the geometry emitted by the running Minecraft client at each scene tick. Replaying these data in a browser requires equivalents for the captured render states and shaders.

## Ponder scene API

Install WebCraft (`webcraft-<version>.jar`) and `webcraft-ponder-<version>.jar` from `ponder/build/libs/` on the NeoForge server. The add-on's only server-side mod dependency is WebCraft. Create and Ponder are client dependencies for [capturing the scenes](#export-ponder-scenes). Copy the client export to `webcraft-assets/previews.zip`, add `webcraft_ponder:ponder` to `enabledModules` in `webcraft-server.toml`, and restart. Replace the ZIP while stopped. The server reads the index on first access and keeps that archive open until the world closes.

The public discovery API lists these one-shot GET routes under `/api/v1/modules/webcraft_ponder/ponder/`.

| Route | Required query | Response |
| --- | --- | --- |
| `index` | None | Original `ponder/index.json` document, version 1 |
| `asset` | `path=<URL-encoded archive entry>` | Original bytes of an allowed Ponder asset |

Use the index's `manifest` path to fetch `ponder/scenes/<hash>/scene.json`. Within that scene, fetch `frames/<tick>.json` from tick zero up to `frameCount - 1`. The asset route also accepts `ponder/meshes/<sha256>.bin` as `application/octet-stream`, `ponder/textures/<sha256>.png` as `image/png`, and `ponder/textures/<sha256>.json` or `ponder/picking/<sha256>.json` as JSON. Hashes are 64 lowercase hexadecimal characters. Asset bytes at content-addressed paths must match their SHA-256. Private previews and other sections of the ZIP stay outside this route.

The index is limited to 4 MiB and 4,096 scenes. Each manifest is limited to 64 KiB and 12,000 frames. The uncompressed limits for individual frames and picking data are 2 MiB, animation data 1 MiB, mesh buffers 4 MiB, and PNGs 8 MiB with at most 8,192 pixels per side. Missing exports return `404 ponder_export_missing`, and invalid archives or index data return `422 ponder_export_invalid`. Unlisted scenes, out-of-range frames and missing assets return `404 ponder_asset_not_found`. Incorrect asset queries or disallowed paths return `400 invalid_ponder_path`. Invalid, oversized or mismatched assets return `422 ponder_asset_invalid`.

The routes follow `apiTransport` and exact path overrides. A WebSocket one-shot response carries the original bytes in `base64` with their `contentType`. Keep both paths on HTTP for a consumer that proxies GET requests.

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

## WebSocket v1

Connect to `ws://127.0.0.1:8765/api/v1/ws` directly or to `wss://your-frontend.example/api/v1/ws` through an HTTPS proxy. This endpoint serves discovery, enabled module routes and public feeds without a loopback restriction. Browsers can connect directly from the matching HTTP origin. For an HTTPS frontend or another trusted browser origin, list its exact scheme, host and port if non-default in `websocketOrigins`. Forwarded origin headers such as `X-Forwarded-Host` play no role in the check.

Machine integrations use the separate `/api/v1/internal/ws` endpoint for internal and loopback-only feeds. Keep it on the private network or access it directly over loopback as the feed requires. Its upgrade request must omit `Origin`. Send the existing `Authorization: Bearer <feed-token>` header in the handshake for internal feeds. The loopback check uses the listener's TCP peer for each lookup.

Each command is a UTF-8 WebSocket text message with an `id` of 1–64 letters, digits, underscores or hyphens. For `get` and `subscribe`, use the existing GET path and optional query in `path`, with its original escaping. Use distinct IDs for pending requests and active subscriptions.

```json
{"id":"discovery","op":"get","path":"/api/v1/modules"}
{"id":"status","op":"subscribe","path":"/api/v1/server-status"}
{"id":"status","op":"unsubscribe"}
```

A one-shot reply or a rejected command has `type: "response"` and echoes the ID. The response contains the route's status and content type. JSON is embedded in `body`. Binary previews and textures use `base64` with the original bytes and MIME type.

```json
{"id":"discovery","type":"response","response":{"status":200,"contentType":"application/json; charset=utf-8","body":{"modules":[]}}}
{"id":"status","type":"snapshot","response":{"status":200,"contentType":"application/json; charset=utf-8","body":{"status":"ready"}}}
{"id":"status","type":"unsubscribed"}
```

An authorized subscription to an enabled live route sends the initial `snapshot` and then, by default, full `update` messages with the same response shape and ID when its snapshot changes. Slow clients can receive the latest snapshot without every intermediate state. Status, information, online players, player history and roster support subscriptions. Add-on routes participate when they register a live source. One-shot routes reject subscriptions with `405 subscriptions_unsupported`. Use `unsubscribe` with the subscription ID, or close the socket to remove all of its subscriptions.

### Delta updates

The public and internal WebSocket upgrades advertise delta support with the response header `X-WebCraft-Subscription-Updates: delta`. Clients that support this mode can add `"updates":"delta"` to individual `subscribe` commands. Omit the field when the server does not advertise support or when the client needs complete updates. `get` and `unsubscribe` do not accept this field.

```json
{"id":"info","op":"subscribe","path":"/api/v1/server-info","updates":"delta"}
{"id":"info","type":"patch","patch":{"set":{"uptimeSeconds":42},"remove":[]}}
```

The first reply is still a full `snapshot`. Apply each `patch` to the most recent body for its subscription ID. Replace or add the top-level keys in `set`, then delete the keys listed in `remove`. Arrays and nested objects are replaced as whole values. A JSON `null` in `set` keeps the key with a null value. Every patch includes both `set` and `remove`.

Patches apply to successful JSON objects with the same status and content type as their baseline. A full `update` replaces the baseline when the response changes format, the patch would be too large, or the socket has reached its 128 KiB budget for retaining baselines. Each patch accounts for changes since the last body delivered to that subscription, including when the server skips intermediate snapshots. After reconnecting, subscribe again and use the new full `snapshot` as the baseline. Subscribing provides that body without a separate `get`.

Use the existing viewer token for private module routes. A browser sends it as the command's `token` string, and a machine client can pass it in the public socket's handshake using the existing bearer header. Use one credential method per connection or command. The socket API reads no cookie credentials. Keep tokens out of all URLs and use TLS at public origins.

The listener limits open connections, queues and writes. Each socket supports 16 subscriptions, 16 waiting commands, and 8 KiB per command. Encoded responses are limited to 24 MiB. Respond to WebSocket ping frames so the listener can retire disconnected clients and proxies can keep active connections open.

## REST v1

REST routes accept GET on exact paths and send `Cache-Control: no-store`. Discovery is public and follows the configured transport. Viewer routes and discovery reject query parameters.

### Server information

NeoForge generates a separate `config/webcraft-server-info.toml` on first startup. The [example file](examples/webcraft-server-info.toml) shows all generated settings and defaults. Use the world's `serverconfig/webcraft-server-info.toml` to override settings for that world. In `webcraft-server.toml`, enable the HTTP listener with `listenerEnabled` for a dedicated server or `singleplayerEnabled` for singleplayer. In the feed file, set `enabled = true` and select the fields to publish. The feed defaults to disabled. Reopen the world or restart the server to apply changes.

For example, these settings opt in to all available fields.

```toml
enabled = true
exposure = "PUBLIC"
name = "Copycraft"
joinAddress = "play.example.com"
publishPlayersOnline = true
publishMotd = true
publishServerIcon = true
publishUptime = true
publishTotalUptime = true
publishWorldAge = true
publishWorldTime = true

[modpack]
publishName = true
publishVersion = true
source = "AUTO"
```

If upgrading from a configuration with `[serverInfo]` in `webcraft-server.toml`, copy that section's settings to the top level of the new feed file in the same directory before starting the new version. Copy `[serverInfo.modpack]` as `[modpack]`. Keep the listener, viewer access and `apiDocsEnabled` settings in the main file.

Set `apiDocsEnabled = true` in `webcraft-server.toml` to serve Swagger UI at `/api/v1/docs` and its OpenAPI document at `/api/v1/openapi.json`. The UI and document are HTTP resources and list enabled REST operations. Private routes still require the viewer token. Use the [WebSocket v1 protocol](#websocket-v1) for socket commands and subscriptions.

The information feed uses `/api/v1/server-info` in public mode and `/api/v1/internal/server-info` in internal mode. Both paths accept `?iconFormat=webp` to prefer WebP. Place an optional `server-icon.webp` next to `server.properties` and restart the server to make that format available. If it is unavailable, the request uses Minecraft's PNG server-list icon. Requests without a query use the PNG icon. The full data URL is limited to 96,000 characters. Disabled or unavailable fields are omitted. The live values refresh about once per second. The `modpack` object is omitted if neither field is available.

| Field | Setting | Value |
| --- | --- | --- |
| `serverName` | `name` | Public display name. Leave blank to omit. |
| `joinAddress` | `joinAddress` | Minecraft connection address with an optional port. Leave blank to omit. |
| `playersOnline` | `publishPlayersOnline` | Current player count, including zero. |
| `maxPlayers` | `publishPlayersOnline` | Player limit from the running Minecraft server. |
| `motd` | `publishMotd` | Minecraft's current server-list message. A blank message is omitted. |
| `serverIcon` | `publishServerIcon` | PNG data URL, or a WebP data URL when requested and available. |
| `uptimeSeconds` | `publishUptime` | Whole real seconds since this session became ready. |
| `totalUptimeSeconds` | `publishTotalUptime` | Whole real seconds online across tracked sessions for this world, including this session. |
| `worldAgeDays` | `publishWorldAge` | Completed 24-hour periods since the world started, including offline time. Omitted when the start is unknown. |
| `worldAgeMinutes` | `publishWorldAge` | Whole real minutes since the world started, including offline time. Present whenever `worldAgeDays` is present. |
| `worldTimeTicks` | `publishWorldTime` | Current Overworld time of day from 0 to 23,999. Tick 0 is 06:00 and tick 6,000 is noon. Sleeping and time commands can change this value. |
| `modpack.name`, `modpack.version` | `modpack.publishName`, `modpack.publishVersion` | Resolved modpack name and version, independently selectable. |

Cumulative uptime starts when its field is first enabled. It counts time spent running in tracked sessions and excludes offline time and periods when tracking is disabled. WebCraft checkpoints the counter in the world's data directory about every 10 seconds while the server is ticking and records the final elapsed time on a clean stop. An abrupt exit can lose time since the last successful checkpoint, typically about 10 seconds or less during normal ticking.

WebCraft records the first launch when a dedicated server generates a new world or when a new singleplayer world uses the WebCraft listener. For an existing world with an unknown start, put `worldStartTimestamp = "2025-04-07T09:00:00Z"` in that world's `serverconfig/webcraft-server-info.toml`, using the actual timestamp verified from server records. Set `publishWorldAge = true` and reopen the world or restart the server. The timestamp belongs in the world's config. Leave it blank if the date is unknown.

WebCraft stores the start timestamp in the world's data directory during startup, before the first normal world save. For an existing world, a verified `worldStartTimestamp` in that world's config supplies or corrects the saved start. A newly generated replacement gets its own launch timestamp even if it uses the same name. WebCraft remembers and ignores a timestamp inherited at first launch, including on subsequent launches of that replacement.

Modpack metadata is resolved once at server startup when either modpack field is enabled. `AUTO` tries the following sources in order and uses the first complete name and version. Versions are strings, so values such as `8.1` and `0.15.0-beta` retain their original formatting.

| Source | Metadata read |
| --- | --- |
| `BCC_RUNTIME` | The name and version already resolved by the running Better Compatibility Checker, when installed and compatible. |
| `BCC_TOML` | `config/bcc-common.toml`, used by ATM10 and Craftoria. If `general.useMetadata` is true, this source reads `config/metadata.json`. |
| `BCC_JSON` | `config/bcc-common.json` with `modpackName.value` and `modpackVersion.value`. If `useMetadata.value` is true, this source reads `config/metadata.json`. ATM11 is a sample of this format on a newer Minecraft version. |
| `METADATA_JSON` | `config/metadata.json` with `name` and `version.name`, as shipped by FTB Skies 2 and FTB StoneBlock 4. |
| `PACKWIZ` | A local packwiz `pack.toml` with `name` and `version`. The file must be present in the server installation. |

Set `source` to a specific identifier to use only that source. `CURSEFORGE_MANIFEST` reads a local `manifest.json` from a CurseForge export when selected explicitly. Use it if the server installer keeps that file and updates it with the installed version. A manually selected source that cannot be read leaves the modpack fields absent and reports the failure in the server log.

For a different layout, select `CUSTOM` in `[serverInfo.modpack]` and set `file`, `nameKey` and `versionKey`. Use a JSON or TOML file relative to the instance directory, with dot-separated paths to string values. For example, `file = "config/pack-info.json"`, `nameKey = "pack.name"` and `versionKey = "pack.version"` read the two properties from a nested `pack` object. If the installation has no suitable metadata, its build or installer can write this file on each upgrade.

### Server status and polling

The [status configuration](examples/webcraft-server-status.toml) is generated separately as `config/webcraft-server-status.toml`. It is disabled by default. Set `enabled = true` there and enable the main HTTP listener. Like the information feed, it accepts an override in the world's `serverconfig/` directory. Restart after changing feed settings.

| Feed mode | Information path | Status path |
| --- | --- | --- |
| `enabled = false` | Unregistered | Unregistered |
| `exposure = "PUBLIC"` | `/api/v1/server-info` | `/api/v1/server-status` |
| `exposure = "INTERNAL"` | `/api/v1/internal/server-info` | `/api/v1/internal/server-status` |

Configure each feed independently. Information defaults to public exposure when enabled. Status defaults to internal exposure when enabled. Every internal feed requires an `Authorization: Bearer` header with the machine token set in `WEBCRAFT_FEED_TOKEN`. Use a separate 43- to 128-character URL-safe secret for this token. With a missing or invalid server token, internal routes return `403 feed_access_disabled`. A missing or incorrect request token returns `401 unauthorized`. Internal paths have no public alias and are omitted from public OpenAPI documentation.

Set `loopbackOnly = true` independently in the information and status config files to limit that feed to direct loopback connections. For player history, use `playerHistoryLoopbackOnly = true` in `webcraft-server.toml`. Those three settings default to `false` for existing installations. A non-loopback request to a restricted feed receives `403 loopback_required`. The check uses the TCP peer, so a local reverse proxy should exclude private feeds from public forwarding rules.

The status route accepts GET without query parameters and returns HTTP 200 with one of these values in `status`.

| Value | Meaning |
| --- | --- |
| `starting` | Configuration is loaded and the HTTP listener is running while Minecraft loads the world and initializes its first tick. |
| `ready` | Minecraft reports readiness. Individual players still need to meet login requirements. |
| `stopping` | Minecraft has begun its normal shutdown. Polling can observe this state until WebCraft closes the listener at server stopped. |

While the status feed is enabled and the server is starting or stopping, WebCraft returns `503 server_unavailable` for the information feed, enabled gameplay routes and module discovery. The time available to see `stopping` depends on the shutdown duration. A crash or process exit can end polling without a final state. The tool orchestrating restarts can separately report that a restart is planned.

On a reverse proxy, allowlist public feed paths individually and restrict the upstream listener to the proxy or a private network. Keep internal feed paths off the browser-facing proxy and send the machine token only from the polling backend. If the public frontend needs a derived status, expose that sanitized result from the backend.

Poll the status from the main backend with a short timeout. Keep the last successful information response and its observation time independently of the status. If WebCraft stops responding, retain stable information such as the name and icon, and hide or mark dynamic values as stale. Report that the feed is unreachable until a fresh response arrives. If status should distinguish a WebCraft outage from a Minecraft outage, also probe the configured Minecraft server and require repeated failures before marking it unavailable. Keep the bare reverse proxy's responses uncached and store last-known data in the polling backend.

### Player history

Set `playerHistoryEnabled = true` in `webcraft-server.toml` to record successful player logins on the dedicated server. The feature is disabled by default. The Overworld's saved data retains one record per UUID across restarts, updating the username and timestamp on subsequent logins. The roster and advancements features also record logins when enabled.

To serve the history, also enable the HTTP listener and set `WEBCRAFT_FEED_TOKEN`. The token-protected `GET /api/v1/internal/players` endpoint accepts no query parameters and returns a `players` array. Each entry contains the canonical `uuid`, the Minecraft username in `name`, and `lastSeenAt` as a Unix timestamp in milliseconds. Keep the endpoint on the private network and use the existing machine token authorization for internal feeds.

### Online players and player roster

Both feeds are disabled by default. Enable the HTTP listener and set `WEBCRAFT_FEED_TOKEN` before opting in to either setting in `webcraft-server.toml`. Restart to apply configuration changes. Both paths require the machine token, accept GET without query parameters, and return a JSON object with a `players` array.

The independent `onlinePlayersLoopbackOnly` and `playerRosterLoopbackOnly` settings both default to `true`. This limits each feed to direct loopback connections even when the listener binds to other interfaces. Change the corresponding setting to `false` only for a trusted remote consumer on a private network. The machine token is still required.

| Setting | Internal path | Player fields |
| --- | --- | --- |
| `onlinePlayersEnabled` | `/api/v1/internal/players/online` | Minecraft username in `name` and the server's current connection latency in `pingMillis`. |
| `playerRosterEnabled` | `/api/v1/internal/players/all` | Canonical `uuid` and last known Minecraft username in `name`. An unknown name is JSON `null`. |

The online feed refreshes about once a second. It respects the server's `hide-online-players` setting and the player's Minecraft server-list preference. Keep the feed internal. Server-specific visibility rules from vanish mods may differ from Minecraft's list settings.

The roster includes UUIDs from the current world's player saves, statistics, advancements and WebCraft's recorded logins. WebCraft history supplies the most recently recorded username and the local Minecraft profile cache can supply older names. A cache entry alone is insufficient evidence of a visit. Records removed from the world and absent from WebCraft history cannot be recovered by this feed.

### Advancements

Set `advancementsEnabled = true` in `webcraft-server.toml` and restart to expose the internal, one-shot `GET /api/v1/internal/advancements` route. Requests require `Authorization: Bearer <WEBCRAFT_FEED_TOKEN>`. The route defaults to loopback-only. For a trusted consumer on a private network, use `advancementsLoopbackOnly = false` if needed. The route inherits `apiTransport` by default. Set `advancementsTransport = "HTTP"` if the consumer uses HTTP and the global mode is WebSocket-only.

The route returns `total` and an `advancements` array with records such as `{"id":"minecraft:story/mine_stone","title":"Stone Age","description":"Mine Stone with your new Pickaxe","frame":"task"}`. Frames are `task`, `goal`, or `challenge`. The catalog uses the currently loaded advancement tree and plain text from the server's display components. It includes entries with display metadata and excludes all hidden entries and IDs under `recipes/`. The same set and ID order apply to every player.

Add `?player=<Minecraft-username>` to include the canonical known `player` name, the `completed` count, and the matching `completedIds` in catalog order. Lookup uses this world's roster, case-insensitively. Unknown or ambiguous names return `404 player_not_found`. Invalid queries return `400 invalid_query`. For a connected player the route reads current in-memory progress. For an offline player it reads their saved criteria against the currently loaded requirements. A missing save gives zero completions. Unreadable, corrupt, or oversized saves return `503 advancement_progress_unavailable`. The response includes only the selected player's progress and contains no UUIDs, criteria, or timestamps.

The advancements feature maintains the local name roster for lookups even when its separate endpoint is disabled. If a trusted backend such as CapyCraft needs a player picker, separately enable `playerRosterEnabled = true` and read `/api/v1/internal/players/all`. Older visitors may have no saved name after cache expiry or world data cleanup. Keep both endpoints and the machine token server-side. Publishing player names or progress through CapyCraft requires a separate operator opt-in there.

### FTB Quests

Install WebCraft (`webcraft-<version>.jar`), `webcraft-quests-<version>.jar` from `quests/build/libs/`, and FTB Quests 2101.1.30 or later in the Minecraft 1.21.1 line with its required mods. Add `webcraft_quests:quests` to `enabledModules` in `webcraft-server.toml`, set `WEBCRAFT_FEED_TOKEN` in the server environment, and restart. WebCraft and its other add-ons run independently of FTB Quests.

Use `GET /api/v1/internal/modules/webcraft_quests/quests/data` and pass the machine bearer token over a direct loopback connection. This one-shot route follows the normal API transport settings. If the global mode is WebSocket-only, add `"/api/v1/internal/modules/webcraft_quests/quests/data=HTTP"` to `routeTransports` to give an HTTP consumer access.

```json
{"total":1,"chapters":[{"id":"0000000000000010","title":"Getting started","quests":[{"id":"0000000000000011","title":"First steps","description":"Open the quest book."}]}]}
```

Append `?player=<canonical-lowercase-UUID>` to include `completed` and `completedIds` for the player's current FTB team. Fetch a selectable player UUID from the separate [internal roster](#online-players-and-player-roster) in the consuming backend. Players in the same party share completion. Reads use FTB's loaded progress for online and offline members. The response contains the catalog and quest completion only.

The catalog follows FTB Quests chapter and quest visibility for a fresh team with no progress. It omits always-hidden chapters, quests hidden by progress or dependency rules, and quests whose details are hidden until they can be started. If an otherwise visible quest hides its description until completion, the description is empty for every request. Chapters without exported quests are omitted. Stable IDs are FTB's 16-character hexadecimal codes. Titles and descriptions use the server's quest locale, omit rich widgets and link actions, and are limited to 192 and 2,048 characters respectively. Catalogs over 1,024 chapters, 10,000 quests, or one million total text characters return `413 response_too_large`.

Invalid queries return `400 invalid_query`. An unknown FTB player returns `404 player_not_found`. A resolved team with no loaded progress record returns `404 team_progress_unavailable`. An unavailable FTB world or team manager returns `503 quests_unavailable`. Keep the machine token, roster and internal route server-side. CapyCraft should publish team progress only after a separate operator opt-in and label it as team progress.

### Module discovery and routes

```http
GET /api/v1/modules
```

```json
{
  "modules": [
    {
      "id": "webcraft_equipment:equipment",
      "routes": {
        "loadout": "/api/v1/modules/webcraft_equipment/equipment/loadout"
      }
    }
  ]
}
```

```http
GET /api/v1/modules/webcraft_equipment/equipment/loadout
Authorization: Bearer <viewer-token>
```

The response has a `player` name and a `slots` array containing `head`, `chest`, `legs`, `feet`, `mainhand` and `offhand`. Each entry has a `slot` and a nullable `item`. An occupied slot has the item `id`, display `name`, stack `count` and `preview`.

| Preview type | Fields and rendering |
| --- | --- |
| `sprite` | `webp` contains a base64-encoded lossless WebP icon. PNG sprites use `png`, including single-layer vanilla item textures. The optional `animationWebp` or `animation` field contains base64-encoded animated WebP or APNG, respectively, for the exact exported stack. |
| `layered` | `base` and `overlay` contain base64-encoded PNGs, and `tint` is the RGB integer applied only to the base. |
| `cube` | `faces.up`, `faces.north`, `faces.east` contain the visible face PNGs of an unrotated, opaque full cube with the standard inventory pose. |
| `missing` | `reason` reports unavailable assets, an unsupported model or unsupported stack state. Show the item label and an explicit missing preview. |

Exported exact-stack icons take precedence over the built-in vanilla fallback. The fallback supports single-layer icons, standard opaque cubes and untrimmed vanilla leather armor. When drawing leather armor, the web client tints the base with the stack's dye color and draws the static overlay on top. Other special or modded variants can use an exported icon. If an icon is unavailable, the item still appears with its name.

Error responses contain an `error` code. A missing or incorrect token returns `401 unauthorized`, absent viewer configuration returns `403 viewer_access_disabled`, a disabled or absent module returns `404 not_found`, and a disconnected viewer returns `404 player_offline`. A server thread that cannot respond in time returns `503 server_busy`.

With Item Catalog enabled, `GET /api/v1/modules/webcraft_catalog/items/items` lists exported item IDs that also exist in the server registry. Each entry includes a display name and indicates whether an optional default icon is indexed. `GET /api/v1/modules/webcraft_catalog/items/icon?id=minecraft%3Astone` serves the indexed preview as `image/webp` or `image/png`, or returns `404 preview_not_found`. Both routes are public. The server reads `catalog.json` at startup and includes its entries even when their previews are missing. An archive without a catalog yields an empty item listing. Install the archive from the desired export mode before restarting the server. Item data captured from individual inventories is available only through the private Equipment route.

Click an item to open its catalog detail. `GET /api/v1/modules/webcraft_catalog/items/detail?id=<item-id>` exposes the exported variants and selector options. Request an indexed variant with `&variant=<variant-id>` on the icon route. Catalog exports cover the registered Easy Villagers biome types and professions and the vanilla dye palette for stacks tagged as dyeable, including Nano and Quantum Nano armor in Extended Industrialization. A complete exported set of sixteen color-prefixed items is linked as a color family, including any exported undyed default. Applied Energistics 2 cable families use the exported Fluix item as their default. Selecting a family member shows its own item ID and exported icon.

Append `&animated=1` after the item ID and optional variant to request the animation as `image/webp` or `image/png` when available. The route otherwise serves the static preview in its original format. The reference frontend plays animations in catalog tiles, item details and equipment when the browser allows motion. The catalog's optional glint simulation uses the static icon as its mask.

The Easy Villagers breeder detail offers separate **Biome type** and **Profession** selectors for each slot. Either slot can be empty. The exporter populates the same block entity data used by the mod's dropped item and captures each registered combination in each slot with the game's item renderer. The server combines the two slot renders into a single downloadable PNG. A complete breeder stack with additional data can also be rendered directly by exporting it from the player's inventory, for use in Equipment.

Use the breeder's `villager_1/<biome-id>/<profession-id>` or `villager_2/<biome-id>/<profession-id>` variant ID to request one occupied slot. For both, join the two indexed variant IDs in slot order with a URL-encoded plus (`%2B`) in the `variant` parameter. Omit `variant` for the empty breeder. For example, `/api/v1/modules/webcraft_catalog/items/icon?id=easy_villagers%3Abreeder&variant=villager_1%2Fminecraft%3Aplains%2Fminecraft%3Afarmer%2Bvillager_2%2Fminecraft%3Adesert%2Fminecraft%3Alibrarian` serves a complete PNG with both villagers.

The item listing, detail and exported variant rows can include `bar` with an integer `width` from 0 to 13 and an integer RGB `color`. These values come from the item's in-game inventory bar. Width 0 is a visible empty bar. When `bar` is absent there is no bar to draw. The preview contains the item render, and clients can draw a separate track and fill using `width / 13` for the fill fraction.

`GET /api/v1/modules/webcraft_catalog/items/effects` serves shared visual metadata from `effects.json`. The client exports the active item glint texture and the standard glint transform, timing and blend settings once. The web detail animates the glint in screen space over the selected icon, using the icon's opacity as its mask. This reproduces the standard effect as a 2D preview. Exact placement on 3D surfaces and renderer-specific shader replacements depend on the item's original geometry or shader.

### Item data feeds

The Item Catalog JAR registers three public modules. Enable each ID independently through `enabledModules`. Discover enabled route URLs through `GET /api/v1/modules`. The public data routes read the preview archive and do not access the Minecraft game thread.

| Module ID | GET path under `/api/v1/modules/webcraft_catalog/` | Query |
| --- | --- | --- |
| `webcraft_catalog:items` | `items/stack` | `id=<catalog-id>[&variant=<scoped-variant-id>]` |
| `webcraft_catalog:recipes` | `recipes/recipes` | `id=<result-item-id>` |
| `webcraft_catalog:tooltips` | `tooltips/tooltip` | `id=<catalog-id>[&variant=<scoped-variant-id>]` |

The stack and tooltip responses include `version`, `catalogId`, `itemId` and `variantId`. A default stack has a null variant ID. For a color family member with its own registry ID, `itemId` identifies the actual item and can be used to look up recipes. Each payload has version 1. For example, a selected leather helmet variant can return these stack fields alongside its other effective components.

```json
{
  "version": 1,
  "catalogId": "minecraft:leather_helmet",
  "itemId": "minecraft:leather_helmet",
  "variantId": "color/red",
  "count": 1,
  "components": {
    "minecraft:dyed_color": {"rgb": 11546150},
    "minecraft:max_stack_size": 1
  },
  "nbt": {},
  "omittedComponents": []
}
```

The `components` object maps Minecraft 1.21.1 component IDs to their codec JSON for the actual default or synthetic export stack. This includes effective item defaults and serializable modded components, such as fluid contents when a captured stack stores them in a persistent component. The `nbt` object carries exact SNBT strings for NBT-backed `CustomData` components, preserving types such as byte arrays and 64-bit integers for an SNBT parser. Component IDs without a successful JSON encoding appear in `omittedComponents`. Transient capabilities, arbitrary player stacks and variant states the exporter never constructs are outside this contract.

Recipe snapshots use the target world's resolved recipe collection. In Minecraft 1.21.1 the server sends that collection to the client on login and after datapack reload, independently of the player's unlocked recipe book. The catalog export captures the synchronized data when it begins. A shaped entry for an iron pickaxe looks like this. The result object is nested in a response with `version`, `itemId`, `source`, `coverage` and `recipes`.

```json
{
  "id": "minecraft:iron_pickaxe",
  "type": "minecraft:crafting",
  "serializer": "minecraft:crafting_shaped",
  "status": "supported",
  "station": "minecraft:crafting_table",
  "fitsPlayerGrid": false,
  "layout": {"kind": "grid", "width": 3, "height": 3},
  "inputs": [
    {"match": "items", "exampleItems": ["minecraft:iron_ingot"]},
    {"match": "items", "exampleItems": ["minecraft:iron_ingot"]},
    {"match": "items", "exampleItems": ["minecraft:iron_ingot"]},
    null, {"match": "items", "exampleItems": ["minecraft:stick"]}, null,
    null, {"match": "items", "exampleItems": ["minecraft:stick"]}, null
  ],
  "result": {"itemId": "minecraft:iron_pickaxe", "count": 1}
}
```

Supported recipes use the standard shaped and shapeless crafting, smelting, blasting, smoking, campfire cooking, stonecutting, and smithing transform implementations. Pattern slots are row-major. Smithing uses named template, base and addition slots, and may copy base stack components. Cooking exposes ticks and experience. In the default response, ingredients carry up to 32 resolved distinct item IDs with `examplesTruncated` when more exist. A custom ingredient carries `match: "custom"` and its codec definition when available. The example IDs alone cannot describe a custom ingredient predicate.

An unsupported recipe with a reported item appears with `status: "unsupported"` and `reportedResult`. Treat it as a hint, since the actual output can depend on inputs or machine behavior. Mod recipes hidden from the vanilla recipe book can still appear when they report an item. The snapshot counts known dynamic recipes in `coverage.dynamic`. Examples include fireworks, map expansion, smithing trims, and custom crafting recipes without a fixed output. Coverage also counts other recipes with no indexable result and unreadable recipes in `unindexed` and `unreadable`. `source` is `world_recipe_sync`. An indexed result with an oversized export returns `truncated: true`. A successful empty `recipes` array means the snapshot has no matching indexed result.

Either catalog mode can also export machine recipes supplied by active JEI integrations and native EMI categories, including Modern Industrialization. When both viewers supply a category, the exporter uses JEI's version. The existing item recipe response adds an optional `machineRecipes` object with `version`, `itemId`, `source`, `skipped` and `recipes`. Its source is `jei` or `emi` for a single recipe viewer and `recipe_viewers` when both contribute. An oversized result can include `truncated: true`. Query the same route with `?fluid=<result-fluid-id>` or `?chemical=<result-chemical-id>` for the machine recipe object directly, with `fluidId` or `chemicalId` replacing `itemId`. The recipe viewers are optional client mods.

Each machine recipe carries its `id`, category ID, localized title, station item IDs and a `layout` with dimensions and slots. New exports identify the viewer that supplied each recipe with its `source`. A slot has an input, output or catalyst role, an optional name and optional `bounds` in the viewer's layout coordinates. Each alternative has an `id` and a `kind`. Items have a numeric `count`, fluids have a numeric `amountMb`, and Mekanism chemicals have `kind: "chemical"` and an `amount` in millibuckets encoded as a decimal string to preserve full 64-bit precision. Fluid and chemical alternatives may include a localized `name` and a `texture` containing the SHA-256 hash of the canonical tinted PNG or APNG generated from the client's active resource packs. The sprite uses the stationary fluid texture or chemical icon and preserves its frame order, durations and interpolation in a looping APNG or WebP. Loops beyond the capture limits use the first frame. A fluid sprite is stored only as WebP when the selected WebP mode produces an animation within the size limit. The hash remains the same across formats. Use the recipe module's `texture?hash=<texture>` route to retrieve the animated WebP when present or the PNG otherwise. Direct ZIP importers should try the two extensions in the same order.

The Thermopneumatic Processing Plant's yeast-culture layout includes this fluid input. Its bounds place the tank near the left edge of the 166 by 70 recipe area.

```json
{
  "role": "input",
  "bounds": {"x": 8, "y": 3, "width": 16, "height": 64},
  "alternatives": [{"kind": "fluid", "id": "minecraft:water", "amountMb": 1000}]
}
```

Tooltip text comes from Minecraft's normal client item tooltip and NeoForge's component gathering hook in the loaded world, with normal tooltip settings and no player passed to the item tooltip API. The export keeps ordered lines and resolved text spans. For example, the following abbreviated response describes a localized tooltip title.

```json
{
  "version": 1,
  "catalogId": "minecraft:iron_pickaxe",
  "itemId": "minecraft:iron_pickaxe",
  "variantId": null,
  "locale": "cs_cz",
  "hasGraphicElements": false,
  "lines": [
    [{"text": "Železný krumpáč", "color": 16777215, "italic": true}]
  ]
}
```

Spans can also carry `bold`, `underlined`, `strikethrough` and `obfuscated`. The `color` field holds the 24-bit RGB value Minecraft uses for the text. Treat `text` as plain text. The exporter captures text before pixel wrapping, excludes custom drawing and font assets, and flags known graphic components that were skipped. Player context and advanced mode can differ from the captured tooltip.

When the client tooltip changes in response to a supported held-key combination, the optional `alternatives` array includes its Minecraft key identifiers in `keys`, localized shortcut text in `label`, and the complete alternative `lines` and `hasGraphicElements`. Clients can use the label as a selector and display the alternative lines without listening for those keys on the web page. Interactive guide screens and input managed outside the supported keyboard queries remain Minecraft features.

Older packs still serve their previews and catalog details. Missing or invalid stack or tooltip data returns 404 with `stack_data_unavailable` or `tooltip_unavailable`. The same applies to a breeder composite with two occupied slots, since only the combined image is synthesized. An unknown item or single variant returns `item_not_found` or `variant_not_found`. A missing recipe snapshot returns `recipe_export_unavailable`. A missing or invalid indexed recipe file returns `recipes_unavailable`. Disabled modules are omitted from discovery and their routes return `not_found`.

For direct archive ingestion, use `catalog.json` for item and variant identities and their image keys. Either catalog mode can add the versioned JSON documents listed below under `catalog-data/`. The server resolves the same documents from standalone files and bundles, so REST clients use the existing routes and response formats.

When the client can identify the modpack, the archive also contains a root `metadata.json` with this shape.

```json
{"version": 1, "modpack": {"name": "Example Modpack", "version": "2.0"}}
```

The exporter reads the name and version from the CurseForge `manifest.json` in the client game directory. For other installations or an explicit override, put a `webcraft-modpack.json` containing string fields `name` and `version` in that directory. The exporter adds `metadata.json` when it finds both fields in one source. The server accepts up to 16 MiB of uncompressed `catalog.json` data.

| Entry | Contents |
| --- | --- |
| `stacks/<namespace>/<item-path>@<variant>.json` | Effective stack components, count and custom SNBT |
| `tooltips/<namespace>/<item-path>@<variant>.json` | Locale, structured tooltip text and captured shortcut alternatives |
| `recipes.json` | Snapshot source, coverage and `resultItems` index |
| `recipes/<namespace>/<item-path>.json` | Recipes grouped by result item ID, with named tag references in version 2 |
| `recipe-tags/<namespace>/<tag-path>.json` | Resolved item IDs for a named tag referenced by a recipe |
| `recipe-item-previews.json` | Optional object mapping ingredient item IDs outside the catalog to their default preview keys |
| `machine-recipes.json` | Optional recipe viewer snapshot metadata with `resultItems`, `resultFluids` and optional `resultChemicals` |
| `machine-recipes/items/<namespace>/<item-path>.json` | Machine recipe references for an item output |
| `machine-recipes/fluids/<namespace>/<fluid-path>.json` | Machine recipe references for a fluid output |
| `machine-recipes/chemicals/<namespace>/<chemical-path>.json` | Machine recipe references for a chemical output |
| `machine-recipes/textures/<hash>.png` | Static PNG or animated APNG for a fluid or chemical sprite |
| `machine-recipes/textures/<hash>.webp` | Animated sprite in WebP mode. A fluid sprite may have only this entry |
| `machine-recipes/groups/<number>.json` | A shared machine layout and the recipes that use it |

In the two attachment paths, keep the `@` and use an empty suffix for a default stack. Encode variant `:` as `%3A` and `/` as `%2F`. Read separately registered color family members under their own default stack path. A catalog entry with `hasPublicData: true` has at least one default attachment, even if rendering the item produced no image. The REST layer adds the selected catalog identity to stack and tooltip files, and the index coverage to recipe files. The archive does not record private inventory data in any of these JSON entries.

With both **Items and previews** and **Recipes** selected, the exporter attempts to render default icons for world recipe ingredients outside the catalog, including every member of referenced item tags. For an ingredient absent from `catalog.json`, direct archive importers can look up its ID in `catalog-data/recipe-item-previews.json` and resolve the key using the same preview and animation paths. Use those entries in recipe views and keep them out of the catalog listing. REST clients can request the default icon through the existing `items/icon?id=<item-id>` route. Older archives can omit the index.

#### Bundled catalog documents

For a logical path such as `catalog-data/stacks/minecraft/stone@.json`, first look for the standalone ZIP entry. If it is absent, look up that full path in `catalog-data/bundles/v1/index/<shard>.json`. The shard is the low nine bits of Java's string hash code, computed by starting at zero and multiplying by 31 before adding each UTF-16 code unit. For bulk ingestion, enumerate all index entries instead of computing shards separately.

Each index maps logical paths to `[bundle, offset, length]`. Read `catalog-data/bundles/v1/<bundle>.json` and decode the byte range beginning at `offset` for `length` bytes as a standalone UTF-8 document. The bundle itself is a JSON array. Offsets count uncompressed bytes from the start of that array. Small stack, tooltip, recipe, recipe tag and machine recipe documents may be bundled. Larger documents, top-level indexes and images keep their standalone paths. Upgrade direct ZIP importers to resolve both layouts before installing a bundled export.

#### Compact recipe documents

Append `&compact=1` to a recipe request to receive the compact archive representation. Each recipe document has its own `version`, including the nested `machineRecipes` object on item responses. Readers should accept version 1 and version 2. Requests without `compact=1` return version 1 with tag examples and individual machine recipes. An expanded machine result may return `truncated: true` at the response size limit.

In a version 2 crafting document an input such as `{"match":"items","tags":["minecraft:planks"],"items":["minecraft:bamboo_block"]}` accepts any member of the listed tags or any explicit ID in the optional `items` array. The recipe module's discovered `tag?id=<tag-id>` route serves the same tag document as the ZIP. It has `version: 1`, the tag's `id`, and a sorted `items` array of members resolved in the exporting client, including members of nested tags. Use the tag ID as the slot label and fetch its members when needed. Minecraft can expand a tag to item IDs before it synchronizes the recipe to the client. Those inputs and custom predicates use `exampleItems`.

A compact machine **HTTP response** has shared layouts in its `groups` array. Each group has a `template` with the usual recipe fields except `id`, plus a `variants` array. A variant carries its original `id` and can carry a `slots` object. Each key is the zero-based position in `template.layout.slots`, and its value replaces that slot's entire alternatives array. An omitted position inherits the template and an empty array clears the slot. Apply all replacements together so ingredient counts, output counts and byproducts stay attached to the selected variant. Recipe viewer alternatives preserve the viewer's item, fluid and chemical IDs and amounts.

For direct ZIP ingestion, a version 2 **machine output file** uses group references. For example, `{"version":2,"itemId":"minecraft:stone","groups":{"12":[0,4]}}` points to variants 0 and 4 in `catalog-data/machine-recipes/groups/12.json`. The shared file has `version: 1`, the `template` and the `variants` in the same shape as the compact HTTP group. The server resolves the references and only returns variants indexed for the requested output. Multiple outputs can reference the same variant, so an importer can store each shared variant once and index its outputs separately. Group numbers and variant positions are local to the archive.

Deploy support for both document versions in archive importers before replacing their installed ZIPs. The recipe indexes still use version 1 and the same paths.

### Banner and armor design data

The Design Data JAR registers two independently configurable public modules. Each exposes GET `data` and `texture?hash=<sha256>` beneath its discovered route prefix. No player data is needed. The JSON contract has `version: 1`. Texture properties hold content hashes. A module can serve only PNGs listed in its own exported manifest. An enabled module without an applicable export returns `404 design_export_missing` on the data route.

- `webcraft_designs:banners` exposes the banner base and cloth textures, front-face UV rectangle, dye palette, six-layer loom limit, and registered loom patterns with usable sprites. Each pattern's `templates` array lists the item IDs accepted for that layer. An empty array means the loom accepts the pattern without a template.
- `webcraft_designs:armor` exposes supported vanilla armor items, base texture layers with an optional tint, trim patterns and materials, and texture profiles referenced by each piece. Profiles use the actual atlas sprite for each pattern and material, including armor-material overrides and the separate leggings sprites. Its separate GET `geometry` route serves baked classic and slim player models, their skin overlays, and the worn armor meshes for `head`, `chest`, `legs` and `feet` from `armor_geometry.json`. Mesh arrays contain consecutive quads with eight values per vertex in position, UV and normal order. The `data` projection remains available for front views when geometry or WebGL is unavailable. Custom armor models require separate export support.
- GET `skin?name=<Java-player-name>` on the armor module looks up the player's published skin using Minecraft's official profile, session and texture services. It returns a JSON object with `name`, `slim` and a base64 PNG. The server uses HTTPS for all requests. The route accepts Java Edition usernames and caches successful results for two minutes.

A catalog export stores the manifests under `designs/webcraft_designs/` and shares content-addressed PNGs under `textures/`. The client resolves the active resource pack and current world's registered patterns. An older pack remains valid for inventory and catalog previews. Use a catalog export containing these manifests to enable the design views.

The banner manifest can also include `slotPreviews.banner` and `slotPreviews.shield`. Each supplies 64 × 64 base renders keyed by dye ID and neutral pattern overlays keyed by pattern ID. These frames are captured in the inventory pose, with separate shield artwork and geometry. Tint each overlay with its selected dye and composite it in the original layer order. Shader blending and PNG quantization can produce small differences from a complete stack captured in one pass.

## Add-on API

An add-on listens for [`RegisterWebCraftModulesEvent`](core/src/main/java/com/universium/webcraft/api/RegisterWebCraftModulesEvent.java) on its mod event bus. It supplies a unique module ID and a map from route names to read handlers. WebCraft publishes viewer and public routes under `/api/v1/modules/<namespace>/<module>/<route>`. WebCraft resolves the viewer's UUID before running the handler on an API worker. Schedule live Minecraft reads on the game thread and serialize the captured data off that thread. Use `WebCraftReadRoute.PublicRoute` for public data such as the item catalog. Use `WebCraftReadRoute.InternalRoute` for one-shot machine reads under `/api/v1/internal/modules/<namespace>/<module>/<route>`. These reads accept the raw query, require the feed token and a direct loopback peer, and are excluded from public discovery and Swagger.

Implement [`WebCraftLiveRoute`](core/src/main/java/com/universium/webcraft/api/WebCraftLiveRoute.java) for private snapshots or `WebCraftLiveRoute.Public` for public snapshots. Return a stable `WebCraftChanges` instance from `changes()`. Publish an immutable snapshot and then call `changed()` whenever subscribers should read it. WebCraft reads through the existing route handler on a worker and compares the encoded result for each subscription. It manages listener registration and removal.

To check the REST contract and compare returned previews against the original textures, run the build with `-PwebcraftTestAssets=/path/to/1.21.1.jar`.
