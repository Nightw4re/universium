# Routes

WebCraft routes are declared as bindings that point to handlers.

## Built-in Routes

### Server Info

```text
GET /api/server-info
```

Example response:

```json
{
  "success": true,
  "data": {
    "serverName": "WebCraft Dev Server",
    "online": true,
    "minecraftVersion": "1.21.1",
    "neoforgeBuild": 224,
    "host": "127.0.0.1",
    "port": 8087,
    "publicUrl": "http://127.0.0.1:8087",
    "uptimeSeconds": 42
  }
}
```

### Player Info

```text
GET /api/player-info/nightware6/
```

Query form:

```text
GET /api/player-info?player=nightware6
```

Example response:

```json
{
  "success": true,
  "data": {
    "playerName": "nightware6",
    "playedTimeTicks": 1234
  }
}
```

## Route Binding Example

```toml
[route."GET /server-info"]
type = "java"
ref = "webcraft.serverInfo"

[route."GET /player-info/{playerName}"]
type = "java"
ref = "webcraft.playerInfo"

[route."GET /resourcepacks"]
type = "java"
ref = "webcraft.resourcePackManifest"

[[route."GET /player-info/{playerName}".middleware]]
type = "java"
ref = "webcraft.authRequired"

[route."GET /player-info/{playerName}".fallback]
type = "java"
ref = "webcraft.playerInfoFallback"

[route."GET /player-info/{playerName}".errors."401"]
type = "java"
ref = "webcraft.unauthorized"

[route."GET /player-info/{playerName}".errors."403"]
type = "java"
ref = "webcraft.forbidden"
```

## Contract

- `java` routes invoke a Java handler.
- `mcfunction` routes invoke a Minecraft function reference.
- `middleware` runs before the main handler.
- `fallback` is used when the target is missing or unavailable.
- `errors."401"` and `errors."403"` are status-specific handlers.
- Public routes are exposed under `/api/*` in the current runtime. Non-API paths are served by the SPA frontend shell.
