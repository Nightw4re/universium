# Universium Web Bridge - Development Plan

Historical, superseded milestone plan. See the [WebCraft index](../modules/webcraft/README.md) for the current implementation.

This document describes the planned implementation path for **Universium Web Bridge**, a universal web infrastructure mod for NeoForge Minecraft servers.

The plan is intentionally incremental. The goal is to avoid building a large abstract system before proving the architecture with real modules.

## Guiding Principles

1. Web Bridge provides infrastructure, not gameplay logic.
2. Each mod owns its own API scope.
3. Modules register endpoints explicitly.
4. Business logic stays inside the owning mod.
5. Write operations must use the same service layer as in-game actions.
6. Modpack authors configure; mod authors implement modules.
7. Frontend should discover available modules dynamically.
8. Security must be designed from the start.
9. The first real integration should be a webcraft module.
10. Do not try to make the bridge automatically understand every mod.

## Target Architecture

```text
Minecraft Server
    ↓
Universium Web Bridge
    ├── HTTP Server
    ├── WebSocket Server
    ├── Auth / Sessions
    ├── Permissions
    ├── Route Registry
    ├── Module Registry
    ├── Frontend Asset Hosting
    ├── Rate Limiting
    └── Audit Log
        ↓
Registered Modules
    └── webcraft Module
        ↓
Web Frontend / External Clients
```

## Suggested Repository Layout

Single repository, modular code layout:

```text
universum-web-bridge/
├── build.gradle
├── settings.gradle
├── README.md
├── docs/
│   ├── API.md
│   ├── SECURITY.md
│   └── MODULES.md
│
├── src/main/java/com/universum/webbridge/
│   ├── api/
│   ├── server/
│   ├── auth/
│   ├── permissions/
│   ├── security/
│   ├── frontend/
│   ├── config/
│   └── testmodule/
│
└── src/main/resources/
    ├── META-INF/
    ├── assets/
    └── web/
```

Later, if needed, this can become a multi-module Gradle project:

```text
universum-web-bridge/
├── web-api/
├── frontend-default/
├── bridge-webcraft/
```

For the first version, keep it as one mod with clean internal packages.

# Milestones

## Milestone 1 - Embedded HTTP Server

### Goal

Start a web server from the Minecraft server and expose basic status endpoints.

### Features

- NeoForge server-side mod setup
- config for host and port
- embedded HTTP server
- request logging
- graceful startup and shutdown
- JSON response helper
- `GET /api/webbridge/status`
- `GET /api/webbridge/modules`

### Example Endpoints

```http
GET /api/webbridge/status
GET /api/webbridge/modules
```

### Expected Result

The server starts a web endpoint on the configured port and returns basic server status.

### Notes

At this stage, `/api/webbridge/modules` may return an empty list.

## Milestone 2 - Module Registry

### Goal

Allow other mods or internal test modules to register their own scoped API modules.

### Features

- `WebBridgeModule` interface
- `WebModuleRegistry`
- `WebRouteRegistry`
- module metadata
- route registration
- duplicate path detection
- module namespace validation
- test module registration

### Public API Draft

```java
public interface WebBridgeModule {
    String id();

    String displayName();

    String version();

    void registerPermissions(PermissionRegistry permissions);

    void registerRoutes(WebRouteRegistry routes);

    void registerSockets(WebSocketRegistry sockets);

    void registerFrontendAssets(FrontendAssetRegistry assets);
}
```

### webcraft Module

```text
Module ID: webcraft
Endpoint: GET /api/webcraft/status
```

### Expected Result

A `webcraft` module can register an endpoint and appear in `/api/webbridge/modules`.

## Milestone 3 - Request/Response Abstraction

### Goal

Create a stable request and response API for module authors.

### Features

- `WebRequest`
- `WebResponse`
- path params
- query params
- JSON body parsing
- response helpers
- error helpers
- common response envelope

### API Draft

```java
public interface WebRequest {
    String method();

    String path();

    Map<String, String> pathParams();

    Map<String, String> queryParams();

    Optional<WebIdentity> identity();

    JsonObject body();

    boolean hasPermission(String permission);
}
```

```java
public interface WebResponse {
    int status();

    JsonObject body();

    static WebResponse ok(JsonObject data) {
        // implementation
    }

    static WebResponse error(int status, String code, String message) {
        // implementation
    }
}
```

### Expected Result

Module code does not need to know the internal HTTP server implementation.

## Milestone 4 - Auth With `/web link`

### Goal

Allow players to link their Minecraft identity to a web session.

### Features

- `/web link` command
- temporary one-time link codes
- configurable expiration time
- session token generation
- session verification middleware
- `POST /api/webbridge/auth/link`
- `GET /api/webbridge/me`

### Flow

```text
1. Player runs /web link in-game.
2. Server generates code ABCD-1234.
3. Player enters the code on the web UI.
4. Web Bridge creates a session token bound to player UUID.
5. Web requests can identify the player.
```

### Endpoint

```http
POST /api/webbridge/auth/link
```

Request:

```json
{
  "code": "ABCD-1234"
}
```

### Expected Result

The bridge can identify whether a web request belongs to a specific Minecraft player.

## Milestone 5 - Permissions and Roles

### Goal

Provide a shared permission system for all web modules.

### Features

- permission registry
- role config
- role inheritance
- wildcard permission support
- `request.hasPermission(permission)`
- permission middleware
- guest/player/admin default roles

### Example Config

```toml
[roles.guest]
permissions = [
  "webbridge.status.view",
  "webbridge.modules.view"
]

[roles.player]
inherits = ["guest"]
permissions = [
  "market.view",
  "market.buy",
  "market.bid"
]

[roles.admin]
permissions = ["*"]
```

### Expected Result

Modules can safely protect endpoints using registered permissions.

## Milestone 6 - WebSocket Support

### Goal

Provide shared realtime communication for registered modules.

### Features

- shared `/ws` endpoint
- channel registry
- subscribe/unsubscribe messages
- public channels
- private player channels
- permission checks for channels
- broadcast API

### Example Subscribe Message

```json
{
  "type": "subscribe",
  "channel": "market.live"
}
```

### Example Event

```json
{
  "type": "event",
  "channel": "market.live",
  "event": "BID_PLACED",
  "data": {
    "listingId": "abc",
    "amount": 750
  }
}
```

### Expected Result

Modules can broadcast realtime events to the web frontend.

## Milestone 7 - Frontend Asset Hosting

### Goal

Allow Web Bridge and modules to serve static frontend files.

### Features

- static file server
- built-in frontend root
- module frontend mount points
- module frontend manifest
- web theme config
- navigation metadata

### Module Frontend Manifest

```json
{
  "id": "webcraft",
  "displayName": "webcraft",
  "entry": "/modules/webcraft/index.js",
  "styles": [
    "/modules/webcraft/style.css"
  ],
  "navigation": [
    {
      "label": "webcraft",
      "path": "/webcraft",
      "permission": "webcraft.view"
    }
  ]
}
```

### Expected Result

The default frontend can discover registered module pages and show navigation dynamically.

## Milestone 8 - Security Hardening

### Goal

Make the bridge safe enough for real server deployment behind a reverse proxy.

### Features

- rate limiting
- audit log
- CORS config
- proxy headers support
- session expiration
- token invalidation
- idempotency key helper
- safer error handling
- configurable guest access
- optional CSRF protection for cookie sessions

### Important Rules

- All write endpoints should require authentication.
- Sensitive write endpoints should require explicit permissions.
- Modules must validate gameplay logic themselves.
- Web Bridge must not allow direct database/game-state mutation outside module services.

### Expected Result

The bridge has the baseline security needed for public or semi-public use.

## Milestone 9 - First Real Integration: webcraft Read-Only

### Goal

Validate the architecture with a real gameplay module.

### Features

- webcraft mod registers `webcraftModule`
- `GET /api/webcraft/status`
- `GET /api/webcraft/me`
- `webcraft.view` permission
- `webcraft.live` WebSocket channel
- module appears in `/api/webbridge/modules`

### Expected Result

The bridge supports a real mod scope without knowing the internals of the webcraft system.

## Milestone 10 - webcraft Write Operations

### Goal

Validate secure write operations through Web Bridge.

### Features

- `POST /api/webcraft/link`
- `POST /api/webcraft/unlink`
- auth required
- permission required
- rate limit group for link creation
- idempotency key support
- audit log entries
- WebSocket broadcast on successful bid/buy

### Expected Result

Web actions can safely call the same webcraft services as in-game actions.

## Milestone 11 - Additional Module Integrations

### Goal

Prove that multiple mods can operate in separate scopes at the same time.

### Candidate Modules

```text
webcraft Module
```

### Expected Result

Multiple modules can register routes, permissions, WebSocket channels, and frontend manifests without conflict.

# Development Priorities

## Phase 1 - Foundation

Build the minimal usable bridge.

```text
1. HTTP server
2. status endpoint
3. module registry
4. route registry
5. test module
```

Do not build the frontend yet.

## Phase 2 - Identity and Security

Make the bridge player-aware.

```text
1. /web link
2. session token
3. /api/webbridge/me
4. permissions
5. basic audit log
```

## Phase 3 - Realtime and Frontend

Add the web-facing experience.

```text
1. WebSocket server
2. static frontend hosting
3. module manifests
4. default frontend shell
5. theme config
```

## Phase 4 - Real Module Validation

Integrate the first real module.

```text
1. webcraft read-only endpoints
2. webcraft live events
3. webcraft frontend page
4. webcraft write endpoints
```

## Phase 5 - Ecosystem

Prepare for more mods.

```text
1. documentation
2. webcraft module
3. bridge module template
4. API versioning
5. public release packaging
```

# API Versioning Plan

Use versioned API routes for stable public endpoints.

Recommended:

```text
/api/webbridge/*      current bridge system endpoints
/api/v1/{moduleId}/*  stable public module API
```

Alternative:

```text
/api/{moduleId}/*     simple module routes for internal use
/api/v1/{moduleId}/*  stable external routes
```

Recommended final rule:

```text
System endpoints: /api/webbridge/*
Module endpoints: /api/{moduleId}/*
Public versioning should be added before public release.
```

# Threading Rules

HTTP requests should not block or corrupt the Minecraft server thread.

Rules:

1. HTTP server runs outside the main server thread.
2. Pure cached reads can run off-thread.
3. Minecraft world/player operations must be scheduled on the server thread.
4. Write operations must be serialized or locked where needed.
5. Expensive operations should be rate-limited and cached.
6. Modules must clearly mark whether an endpoint needs main-thread execution.

Possible route metadata:

```java
routes.post("/market/listings/{id}/bid", this::placeBid)
    .requiresAuth()
    .requiresPermission("market.bid")
    .requiresServerThread();
```

# Security Checklist

Before public deployment:

```text
[ ] Web server binds to 127.0.0.1 by default.
[ ] Public deployments recommend reverse proxy.
[ ] Auth link codes expire.
[ ] Session tokens are signed.
[ ] Session expiration is configurable.
[ ] Permissions are enforced before write actions.
[ ] Rate limiting is enabled.
[ ] Audit logging is enabled.
[ ] CORS is disabled by default.
[ ] Error responses do not leak sensitive internals.
[ ] Write operations support idempotency where relevant.
[ ] Private WebSocket channels validate identity.
[ ] Module route conflicts are rejected.
[ ] Module IDs are validated.
[ ] Guest access is configurable.
```

# Config Files

## webbridge-server.toml

```toml
[server]
enabled = true
host = "127.0.0.1"
port = 8087
public_url = "https://universum.example.com"

[server.proxy]
enabled = true
trusted_proxies = ["127.0.0.1"]

[frontend]
mode = "built_in"
theme = "universum"

[auth]
link_code_expiration_minutes = 10
session_expiration_days = 30
allow_guest_access = true

[security]
enable_rate_limit = true
enable_audit_log = true
require_csrf_for_cookie_sessions = true

[cors]
enabled = false
allowed_origins = []

[modules]
auto_discover = true
disabled = []
```

## webbridge-roles.toml

```toml
[roles.guest]
permissions = [
  "webbridge.status.view",
  "webbridge.modules.view"
]

[roles.player]
inherits = ["guest"]
permissions = [
  "market.view",
  "market.buy",
  "market.bid",
  "market.sell",
  "research.view",
  "mail.view"
]

[roles.moderator]
inherits = ["player"]
permissions = [
  "market.admin",
  "mail.admin"
]

[roles.admin]
permissions = ["*"]
```

## webbridge-theme.json

```json
{
  "name": "universum",
  "displayName": "Universium",
  "logo": "/assets/logo.png",
  "accentColor": "#55ccff",
  "background": "dark",
  "navigation": {
    "showServerStatus": true,
    "showPlayerCount": true
  }
}
```

# Risks and Solutions

## Risk 1 - The bridge becomes too generic

Problem:

```text
Trying to make the bridge automatically understand every mod.
```

Solution:

```text
Keep the bridge focused on infrastructure only.
Business meaning belongs to registered modules.
```

## Risk 2 - Web actions bypass game validation

Problem:

```text
A web endpoint directly edits data or storage without using the mod's service layer.
```

Solution:

```text
Write endpoints must call the same services used by in-game GUI/actions.
```

## Risk 3 - Modpack authors do not want to code backend integrations

Problem:

```text
The system is too technical for pack authors.
```

Solution:

```text
Pack authors configure theme, port, permissions, and enabled modules.
Mod authors or bridge mods provide the backend integrations.
```

## Risk 4 - Route conflicts

Problem:

```text
Two modules register the same endpoint.
```

Solution:

```text
Each module receives its own namespace: /api/{moduleId}.
Duplicate route registration fails during startup.
```

## Risk 5 - Server thread performance issues

Problem:

```text
Web requests overload the Minecraft server thread.
```

Solution:

```text
Run HTTP off-thread, cache read-only data, schedule Minecraft operations safely on the main server thread, and rate-limit expensive endpoints.
```

## Risk 6 - Public exposure of the Minecraft server

Problem:

```text
The embedded web server is exposed directly to the internet.
```

Solution:

```text
Bind to 127.0.0.1 by default and recommend Nginx/Caddy/Cloudflare reverse proxy for production.
```

# Definition of Done for First Public Alpha

The first public alpha should include:

```text
[ ] Embedded HTTP server
[ ] Configurable host/port
[ ] Status endpoint
[ ] Module registry
[ ] Route registry
[ ] Test module
[ ] /web link auth
[ ] Session token support
[ ] /api/webbridge/me
[ ] Permission system
[ ] Basic rate limiting
[ ] Basic audit log
[ ] WebSocket support
[ ] Static frontend hosting
[ ] Example frontend shell
[ ] Example module documentation
[ ] Security notes
```

# Definition of Done for Beta

The beta should include:

```text
[ ] Stable Java API for modules
[ ] Stable JSON response format
[ ] API documentation
[ ] Example webcraft module integration
[ ] Private WebSocket channels
[ ] Frontend module manifests
[ ] Theme config
[ ] Reverse proxy documentation
[ ] Idempotency support helper
[ ] Better error handling
[ ] Role inheritance
[ ] Route conflict validation
[ ] Module disable config
```

# Definition of Done for 1.0

The 1.0 release should include:

```text
[ ] Stable module API
[ ] Stable auth/session API
[ ] Stable permission API
[ ] Stable WebSocket API
[ ] Production security checklist completed
[ ] Full documentation for mod authors
[ ] Full documentation for modpack authors
[ ] Example bridge module
[ ] Example default frontend
[ ] Public changelog
[ ] Migration/versioning policy
```

# Recommended First Real Use Case

The first real integration should be **Universium webcraft** because it validates most important parts of the bridge:

```text
- public data
- private player data
- write operations
- permissions
- audit log
- WebSocket events
- frontend navigation
- module scope isolation
```

Start with a minimal status endpoint first:

```http
GET /api/webcraft/status
```

# Short Roadmap

```text
v0.1  HTTP server + status endpoint
v0.2  Module registry + route registry
v0.3  /web link authentication
v0.4  Permissions and roles
v0.5  WebSocket channels
v0.6  Frontend asset hosting
v0.7  Security hardening
v0.8  webcraft read-only integration
v0.9  webcraft write integration
v1.0  Stable API and documentation
```

# Final Product Summary

Universium Web Bridge should become a reusable web infrastructure layer for NeoForge modpacks.

It should allow multiple mods to run web APIs and live web features at the same time, each within its own scope, while sharing authentication, permissions, routing, WebSocket handling, frontend hosting, rate limiting, and audit logging.

The bridge should stay small, strict, and infrastructure-focused. The actual meaning of data and actions must remain inside the owning mod.

