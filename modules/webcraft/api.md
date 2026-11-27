# WebCraft API contracts

[WebCraft index](README.md)

## Account linking

On a dedicated server, set `listenerEnabled = true` and `accountLinksEnabled = true` in `webcraft-server.toml`. Generate a dedicated bearer secret, for example with `openssl rand -hex 32`, and pass it as `WEBCRAFT_ACCOUNT_LINK_TOKEN` to the Minecraft server and the trusted web backend. The accepted format is 43 to 128 URL-safe ASCII characters. Restart the server. Pair only against a server that authenticates Minecraft identities, such as a server using `online-mode=true` or a backend that accepts identity forwarding exclusively from an authenticated Minecraft proxy.

Players use `/webcraft link` without operator permissions. The server sends a private, click-to-copy system chat message to that player. The 16-character, case-sensitive code expires after five minutes. Requesting a new code replaces the player's previous unused code. Players enter the code on the account-linking page after signing in there.

The web backend calls `POST /api/v1/internal/account-links/redeem` with `Authorization: Bearer <WEBCRAFT_ACCOUNT_LINK_TOKEN>` and a JSON body containing exactly two string fields. Generate a fresh random UUID for each new attempt.

```json
{"code":"<code from chat>","attemptId":"29010e3b-2cf2-4aee-a9b7-c12ff5017d6c"}
```

The attempt UUID uses canonical lowercase syntax. Send the code only in the request body. Omit query parameters and keep codes and bearer headers out of request, error and proxy logs. Successful responses use HTTP 200 and the following shape. The UUID is the server-authenticated Minecraft identity. The name is the player's display name captured when the code was issued.

```json
{"uuid":"bb019a8e-de4b-4674-a886-70188c2f4c3b","name":"ExamplePlayer"}
```

| Status | Result |
| --- | --- |
| `400` | Invalid body or unsupported query. |
| `401` | Missing or invalid bearer secret. |
| `403` | Direct TCP peer is outside loopback and remote redemption is disabled. |
| `404` | Route disabled (`not_found`) or code unknown, expired, replaced or already redeemed by another attempt (`invalid_code`). |
| `405` | Send a POST. |
| `409` | The attempt ID already redeemed a different code. |
| `429` | The global limit of 60 well-formed, authenticated redemption attempts per one-minute window was reached. Honor `Retry-After` in seconds. |
| `503` | Server or listener temporarily unavailable or busy. |

Redemption consumes the code atomically. Repeating the same authenticated request with the same code and attempt ID returns the original result for five minutes after redemption, even if the original code lifetime ended or the player issued another code. Successful retries bypass the redemption limit. WebCraft keeps up to 4,096 active codes and recent redemption results in memory. At capacity, a player may replace an unused code, and other issuance waits for expiration. Codes and retry results expire when the API listener is restarted, including during `/webcraft reload` and server restarts.

The route is HTTP-only, independent of API transport settings. The loopback check uses the direct TCP peer regardless of the listener's bind address or forwarding headers. Exclude the whole `/api/v1/internal/` prefix from [public proxy forwarding](server.md#wings-and-https), including when the proxy itself connects over loopback. A backend in the same network namespace can call `http://127.0.0.1:8765`. For a backend on another machine, run a local port forward from the backend host to the Minecraft host, for example `ssh -N -L 127.0.0.1:18765:127.0.0.1:8765 minecraft-host`, then call `http://127.0.0.1:18765` from the backend. The target loopback interface must be in the listener's network namespace. For a direct non-loopback deployment, explicitly set `accountLinksAllowRemote = true`, restrict network access to trusted peers and use a protected connection for HTTP.

### CapyCraft backend integration

CapyCraft owns Google sign-in and the local user database. Complete Google sign-in on CapyCraft, use the verified Google subject to resolve the local account, and accept the code through an authenticated, CSRF-protected form. Rate-limit that form per account and client. Keep the WebCraft bearer secret and request on the backend.

Before calling WebCraft, create an operation tied to the authenticated local user and generate its attempt ID server-side. Keep the original code available securely for the short retry window or have the same signed-in user resubmit it for that operation. After a timeout, a transient failure or an interrupted database write, resend the same body and authorization. Use a new attempt ID for a new code. If a retry result expires before CapyCraft records it, ask the player to generate a fresh code.

On HTTP 200, store the Minecraft UUID against the operation's local user in a transaction. Enforce unique constraints for both the local user ID and the Minecraft UUID for a one-to-one mapping. An existing identical mapping is an idempotent success. Return a conflict if either side belongs to a different mapping, preserving the existing link. Treat the returned display name as optional presentation data. The durable database write is what completes linking on CapyCraft.

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

Read routes accept GET on exact paths and send `Cache-Control: no-store`. The internal [account-link](#account-linking) and [player privacy](#online-players-and-player-roster) endpoints also have HTTP write methods. Discovery is public and follows the configured transport. Viewer routes and discovery reject query parameters.

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
| `worldGameTimeTicks` | `publishWorldAge` | Accumulated Minecraft game ticks saved with the Overworld. Available even when the calendar start is unknown. Divide by 24,000 for elapsed Minecraft days. |
| `worldTimeTicks` | `publishWorldTime` | Current Overworld time of day from 0 to 23,999. Tick 0 is 06:00 and tick 6,000 is noon. Sleeping and time commands can change this value. |
| `performance` | `publishSparkMetrics` | One-minute Spark measurements: `tps`, mean milliseconds per tick (`mspt`), and 95th-percentile milliseconds per tick (`msptP95`). |
| `modpack.name`, `modpack.version` | `modpack.publishName`, `modpack.publishVersion` | Resolved modpack name and version, independently selectable. |

Cumulative uptime starts when its field is first enabled. It counts time spent running in tracked sessions and excludes offline time and periods when tracking is disabled. WebCraft checkpoints the counter in the world's data directory about every 10 seconds while the server is ticking and records the final elapsed time on a clean stop. An abrupt exit can lose time since the last successful checkpoint, typically about 10 seconds or less during normal ticking.

Set `publishWorldAge = true` to publish the Minecraft game-time counter. It continues from the saved world, advances while the world ticks even without players, and excludes offline time. Sleeping and daylight commands affect `worldTimeTicks` independently.

Set `publishSparkMetrics = true` to include tick performance when the Spark mod is installed. WebCraft samples Spark every 15 seconds by default. Set `sparkMetricsIntervalSeconds` between 5 and 300 to change the interval. The field follows the information feed's existing exposure and is omitted until Spark supplies measurements.

For calendar age, WebCraft records the first launch when a dedicated server generates a new world or when a new singleplayer world uses the WebCraft listener. For an existing world with an unknown start, put `worldStartTimestamp = "2025-04-07T09:00:00Z"` in that world's `serverconfig/webcraft-server-info.toml`, using the actual timestamp verified from server records. Reopen the world or restart the server to apply the settings. Leave the timestamp blank if the date is unknown.

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
| `onlinePlayersEnabled` | `/api/v1/internal/players/online` | Minecraft `name` and `uuid`, current `pingMillis`, effective `maxHealth` in health points, `playTimeMinutes`, current `gameMode`, and whether the player is a server `operator`. |
| `playerRosterEnabled` | `/api/v1/internal/players/all` | Canonical `uuid` and last known Minecraft username in `name`. An unknown name is JSON `null`. Includes `playTimeMinutes` when available from world statistics at startup or from a logout. |
| `playerPrivacyEnabled` | `/api/v1/internal/players/privacy?player=<uuid>` | HTTP GET reads the saved preferences. HTTP PUT replaces them using a JSON document such as `{"hidden":["equipment","inventory"]}`. Loopback and the machine feed token are required for both methods. |

Player privacy preferences are stored per UUID in the current world. Accepted fields are `online`, `playTime`, `gameMode`, `operator`, `equipment`, `inventory`, `achievements`, and `quests`. An empty list clears the preferences. The trusted caller must authorize the change for that player. Policy enforcement in data feeds and downstream caches is planned.

The online feed refreshes about once a second. It respects the server's `hide-online-players` setting and the player's Minecraft server-list preference. Keep the feed internal. Server-specific visibility rules from vanish mods may differ from Minecraft's list settings.

The roster includes UUIDs from the current world's player saves, statistics, advancements and WebCraft's recorded logins. WebCraft history supplies the most recently recorded username and the local Minecraft profile cache can supply older names. A cache entry alone is insufficient evidence of a visit. Records removed from the world and absent from WebCraft history cannot be recovered by this feed.

### Advancements

Set `advancementsEnabled = true` in `webcraft-server.toml` and restart to expose the internal, one-shot `GET /api/v1/internal/advancements` route. Requests require `Authorization: Bearer <WEBCRAFT_FEED_TOKEN>`. The route defaults to loopback-only. For a trusted consumer on a private network, use `advancementsLoopbackOnly = false` if needed. The route inherits `apiTransport` by default. Set `advancementsTransport = "HTTP"` if the consumer uses HTTP and the global mode is WebSocket-only.

The route returns `total` and an `advancements` array with records such as `{"id":"minecraft:story/mine_stone","title":"Stone Age","description":"Mine Stone with your new Pickaxe","frame":"task","x":1,"y":0,"parentId":"minecraft:story/root"}`. Frames are `task`, `goal`, or `challenge`. The catalog uses the currently loaded advancement tree, its layout coordinates and plain text from the server's display components. A `parentId`, when present, identifies the nearest ancestor included in the catalog. The catalog excludes hidden entries and IDs under `recipes/`. The same set and ID order apply to every player.

Add `?player=<Minecraft-username>` to include the canonical known `player` name, the `completed` count, and the matching `completedIds` in catalog order. Lookup uses this world's roster, case-insensitively. Unknown or ambiguous names return `404 player_not_found`. Invalid queries return `400 invalid_query`. For a connected player the route reads current in-memory progress. For an offline player it reads their saved criteria against the currently loaded requirements. A missing save gives zero completions. Unreadable, corrupt, or oversized saves return `503 advancement_progress_unavailable`. The response includes only the selected player's progress and contains no UUIDs, criteria, or timestamps.

The advancements feature maintains the local name roster for lookups even when its separate endpoint is disabled. If a trusted backend such as CapyCraft needs a player picker, separately enable `playerRosterEnabled = true` and read `/api/v1/internal/players/all`. Older visitors may have no saved name after cache expiry or world data cleanup. Keep both endpoints and the machine token server-side. Publishing player names or progress through CapyCraft requires a separate operator opt-in there.

### FTB Quests

Install WebCraft (`webcraft-<version>.jar`), `webcraft-quests-<version>.jar` from `quests/build/libs/`, and FTB Quests 2101.1.30 or later in the Minecraft 1.21.1 line with its required mods. Add `webcraft_quests:quests` to `enabledModules` in `webcraft-server.toml`, set `WEBCRAFT_FEED_TOKEN` in the server environment, and restart. WebCraft and its other add-ons run independently of FTB Quests.

Use `GET /api/v1/internal/modules/webcraft_quests/quests/data` and pass the machine bearer token over a direct loopback connection. This one-shot route follows the normal API transport settings. If the global mode is WebSocket-only, add `"/api/v1/internal/modules/webcraft_quests/quests/data=HTTP"` to `routeTransports` to give an HTTP consumer access.

```json
{"total":1,"chapters":[{"id":"0000000000000010","title":"Getting started","quests":[{"id":"0000000000000011","title":"First steps","description":"Open the quest book.","x":0,"y":0,"size":1,"shape":"rsquare","dependencyIds":[]}]}]}
```

Append `?player=<canonical-lowercase-UUID>` to include `completed` and `completedIds` for the player's current FTB team. Fetch a selectable player UUID from the separate [internal roster](#online-players-and-player-roster) in the consuming backend. Players in the same party share completion. Reads use FTB's loaded progress for online and offline members. The response contains the catalog and quest completion only.

The catalog follows FTB Quests chapter and quest visibility for a fresh team with no progress. It omits always-hidden chapters, quests hidden by progress or dependency rules, and quests whose details are hidden until they can be started. If an otherwise visible quest hides its description until completion, the description is empty for every request. Chapters without exported quests are omitted. Stable IDs are FTB's 16-character hexadecimal codes. Titles and descriptions use the server's quest locale, omit rich widgets and link actions, and are limited to 192 and 2,048 characters respectively. Catalogs over 1,024 chapters, 10,000 quests, or one million total text characters return `413 response_too_large`.

The catalog includes chapter coordinates, size, shape and visible quest or chapter prerequisite IDs for each quest. Some prerequisites belong to other chapters. Hidden prerequisites contribute only to `hiddenDependencyCount`. The optional `minimumRequired` describes the configured minimum across visible and hidden prerequisites. The optional `hideDependencyLines` and `hideDependentLines` flags control when incoming and outgoing lines appear on the board. Compact progress responses omit the catalog and its layout.

The full response includes chapter groups, chapter and quest icons, and individual tasks and rewards where available. Item artwork is referenced by registry ID. Quest icons with no configured art can use an item task's image or the display icon of a referenced advancement. Command rewards expose their configured name or display type without the command. Visible chapter decorations include coordinates, dimensions, and optional rotation and opacity. They appear behind the quest nodes in their configured order and carry no click actions.

Catalog responses since `itemDetailVersion: 4` identify icon slots with `itemEntry`. An optional `itemId` identifies a concrete item for recipe links. Its displayed artwork can be set separately. Filter stacks use a representative item that passes the installed FTB Quests filter adapter's matcher. The server checks explicit item, component, and tag hints before checking a bounded selection of registered items. A filter with no verified representative keeps its configured artwork and can show an item or tag hint where available. Kill tasks can use their target's spawn egg as fallback artwork. In version 5, the Ender Dragon task uses the portrait or Dragon Head, and random rewards use the table's display title and artwork or the FTB dice icon. Experience rewards use the Bottle o' Enchanting icon. These display icons carry no recipe target. Ordinary item reward counts include the base amount (`count`) and, for a random bonus, the inclusive upper bound (`countMax`).

The add-on resolves PNG texture references inside installed mods and `kubejs/assets`. For client-only textures that may be published, copy them to `webcraft-assets/quest-art/assets/<namespace>/textures/` under the game directory, preserving the original filenames and nested directories. For example, an image referenced as `example:textures/quests/banner.png` can be installed at `webcraft-assets/quest-art/assets/example/textures/quests/banner.png`. These images are inlined with their transparency and used for chapter icons, quest icons, and board decorations. Each PNG may be up to 128 KiB and 2,048 pixels in either dimension. Remote images, missing textures, and editor-only or progress-gated decorations are omitted from the public catalog.

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
        "loadout": "/api/v1/modules/webcraft_equipment/equipment/loadout",
        "inventory": "/api/v1/modules/webcraft_equipment/equipment/inventory"
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

The loadout also returns hotbar slots `"0"` through `"8"` and the integer `selectedSlot` from 0 through 8, captured alongside the equipped items. Equipped armor can include `dye`, a 24-bit RGB integer for leather armor, and `trim`, an object containing the registered `material` and `pattern` IDs. A client can use these fields and the item's ID to select layers from the Armor Design export for a worn preview.

GET `/api/v1/modules/webcraft_equipment/equipment/inventory` uses the same viewer token and configured viewer UUID. It takes no player selector. The response contains `player`, 36 ordered `slots` with string IDs `"0"` through `"35"`, and the integer `selectedSlot` from 0 through 8. Slots 0 through 8 are the hotbar. Each empty position has `item: null`. Occupied positions include `id`, `name` and `count`.

Occupied slots in both routes may also include `tooltip`, the equipped stack's plain tooltip lines after its name, `tooltipLines`, the same lines with styled text spans, `modName`, the registered display name for the item's mod, and `bar`, its in-game item bar with a `width` from 0 through 13 and a 24-bit RGB `color`. Each styled span contains `text` and can include a 24-bit RGB `color` and boolean `bold`, `italic`, `underlined`, `strikethrough`, and `obfuscated` fields. Tooltip text and bars come from the actual stack. Tooltips use server-side item details and standard stack components such as lore and enchantments. Client tooltip event listeners run only in the client export.

Both routes require the configured player to be online by default. To enable offline access on a server such as CapyCraft, set `allowOfflineEquipment = true` in `config/webcraft-equipment.toml` and restart the server. Edit `<level-name>/serverconfig/webcraft-equipment.toml` instead when the world has that override. Online requests still return live data. Offline requests return the latest readable inventory saved in this world, using the previous save if needed. The response uses the cached player name when available and the configured UUID otherwise. Both routes retain the same viewer token and UUID restrictions.

| Preview type | Fields and rendering |
| --- | --- |
| `sprite` | `webp` contains a base64-encoded lossless WebP icon. PNG sprites use `png`, including single-layer vanilla item textures. The optional `animationWebp` or `animation` field contains base64-encoded animated WebP or APNG, respectively, for the exact exported stack. |
| `layered` | `base` and `overlay` contain base64-encoded PNGs, and `tint` is the RGB integer applied only to the base. |
| `cube` | `faces.up`, `faces.north`, `faces.east` contain the visible face PNGs of an unrotated, opaque full cube with the standard inventory pose. |
| `missing` | `reason` reports unavailable assets, an unsupported model or unsupported stack state. Show the item label and an explicit missing preview. |

Exported exact-stack icons take precedence over the built-in vanilla fallback. The fallback supports single-layer icons, standard opaque cubes and untrimmed vanilla leather armor. When drawing leather armor, the web client tints the base with the stack's dye color and draws the static overlay on top. Other special or modded variants can use an exported icon. If an icon is unavailable, the item still appears with its name.

Error responses contain an `error` code. A missing or incorrect token returns `401 unauthorized`, absent viewer configuration returns `403 viewer_access_disabled`, and a disabled or absent module returns `404 not_found`. An offline viewer gets `404 player_offline` if offline access is disabled or no save exists. Unreadable saved data returns `503 inventory_unavailable`. A server thread that cannot respond in time returns `503 server_busy`.

With Item Catalog enabled, `GET /api/v1/modules/webcraft_catalog/items/items` lists exported item IDs that also exist in the server registry. Each entry includes a display name and indicates whether an optional default icon is indexed. `GET /api/v1/modules/webcraft_catalog/items/icon?id=minecraft%3Astone` serves the indexed preview as `image/webp` or `image/png`, or returns `404 preview_not_found`. Both routes are public. The server reads `catalog.json` at startup and includes its entries even when their previews are missing. An archive without a catalog yields an empty item listing. Install the archive from the desired export mode before restarting the server. Item data captured from individual inventories is available only through the private Equipment route.

Click an item to open its catalog detail. `GET /api/v1/modules/webcraft_catalog/items/detail?id=<item-id>` exposes the exported variants and selector options. Request an indexed variant with `&variant=<variant-id>` on the icon route. Catalog exports cover the registered Easy Villagers biome types and professions and the vanilla dye palette for stacks tagged as dyeable, including Nano and Quantum Nano armor in Extended Industrialization. A complete exported set of sixteen color-prefixed items is linked as a color family, including any exported undyed default. Applied Energistics 2 cable families use the exported Fluix item as their default. Selecting a family member shows its own item ID and exported icon.

Append `&animated=1` after the item ID and optional variant to request the animation as `image/webp` or `image/png` when available. The route otherwise serves the static preview in its original format. The reference frontend plays animations in catalog tiles, item details and equipment when the browser allows motion. The catalog's optional glint simulation uses the static icon as its mask.

The Easy Villagers breeder detail offers separate **Biome type** and **Profession** selectors for each slot. Either slot can be empty. The exporter populates the same block entity data used by the mod's dropped item and captures each registered combination in each slot with the game's item renderer. The server combines the two slot renders into a single downloadable PNG. A complete breeder stack with additional data can also be rendered directly by exporting it from the player's inventory, for use in Equipment.

Use the breeder's `villager_1/<biome-id>/<profession-id>` or `villager_2/<biome-id>/<profession-id>` variant ID to request one occupied slot. For both, join the two indexed variant IDs in slot order with a URL-encoded plus (`%2B`) in the `variant` parameter. Omit `variant` for the empty breeder. For example, `/api/v1/modules/webcraft_catalog/items/icon?id=easy_villagers%3Abreeder&variant=villager_1%2Fminecraft%3Aplains%2Fminecraft%3Afarmer%2Bvillager_2%2Fminecraft%3Adesert%2Fminecraft%3Alibrarian` serves a complete PNG with both villagers.

The item listing, detail and exported variant rows can include `bar` with an integer `width` from 0 to 13 and an integer RGB `color`. These values come from the item's in-game inventory bar. Width 0 is a visible empty bar. When `bar` is absent there is no bar to draw. The preview contains the item render, and clients can draw a separate track and fill using `width / 13` for the fill fraction.

`GET /api/v1/modules/webcraft_catalog/items/effects` serves shared visual metadata from `effects.json`. The client exports the active item glint texture and the standard glint transform, timing and blend settings once. The web detail animates the glint in screen space over the selected icon, using the icon's opacity as its mask. This reproduces the standard effect as a 2D preview. Exact placement on 3D surfaces and renderer-specific shader replacements depend on the item's original geometry or shader.

## Add-on API

An add-on listens for [`RegisterWebCraftModulesEvent`](core/src/main/java/com/universium/webcraft/api/RegisterWebCraftModulesEvent.java) on its mod event bus. It supplies a unique module ID and a map from route names to read handlers. WebCraft publishes viewer and public routes under `/api/v1/modules/<namespace>/<module>/<route>`. WebCraft resolves the viewer's UUID before running the handler on an API worker. Schedule live Minecraft reads on the game thread and serialize the captured data off that thread. Use `WebCraftReadRoute.PublicRoute` for public data such as the item catalog. Use `WebCraftReadRoute.InternalRoute` for one-shot machine reads under `/api/v1/internal/modules/<namespace>/<module>/<route>`. These reads accept the raw query, require the feed token and a direct loopback peer, and are excluded from public discovery and Swagger.

Implement [`WebCraftLiveRoute`](core/src/main/java/com/universium/webcraft/api/WebCraftLiveRoute.java) for private snapshots or `WebCraftLiveRoute.Public` for public snapshots. Return a stable `WebCraftChanges` instance from `changes()`. Publish an immutable snapshot and then call `changed()` whenever subscribers should read it. WebCraft reads through the existing route handler on a worker and compares the encoded result for each subscription. It manages listener registration and removal.

The [bundled OpenAPI schema](core/src/main/resources/webcraft-api/openapi.json) covers discovery, server information and status, and the public recipe routes. Use the relevant guide section for other route contracts. To verify equipment previews against the original textures, run `gradle :webcraft-equipment:test -PwebcraftTestAssets=/path/to/1.21.1.jar` from the repository root.
