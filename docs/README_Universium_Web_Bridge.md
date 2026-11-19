# Universium Web Bridge

Historical architecture reference. See the [WebCraft guide](../modules/webcraft/README.md) for the current modules and REST contract.

**Universium Web Bridge** is a universal web integration layer for NeoForge Minecraft servers and modpacks. It provides shared web infrastructure that other mods can use to expose their own scoped APIs, WebSocket events, permissions, and optional frontend modules.

The goal is not to make one web mod that magically understands every other mod. The goal is to provide a common bridge between the Minecraft server and external web interfaces.

```text
Minecraft Server
    ↓
Universium Web Bridge
    ↓
Registered Web Modules
    ↓
REST API / WebSocket / Frontend Assets
    ↓
Web App / Admin Panel / Discord Bot / Launcher Integration
```

## Core Idea

Each mod owns its own scope.

Examples:

```text
webcraft mod  → /api/webcraft/*
stats mod    → /api/stats/*
mail mod     → /api/mail/*
map mod      → /api/map/*
admin mod    → /api/admin/*
```

Universium Web Bridge does not interpret the business logic of these mods. It only provides the shared infrastructure:

- embedded HTTP server
- WebSocket server
- player authentication
- web session handling
- permission and role system
- API route registry
- WebSocket channel registry
- frontend asset hosting
- module discovery
- common API response format
- rate limiting
- audit logging
- proxy/CORS configuration
- shared web configuration and theme metadata

Concrete gameplay logic stays inside the specific mod or its bridge module.

## What This Mod Solves

Universium Web Bridge solves the repeated infrastructure problem for Minecraft mod web integrations.

Without a shared bridge, every mod that wants a web UI must implement its own:

- web server
- authentication
- sessions
- permissions
- route handling
- frontend hosting
- security rules
- event streaming
- API conventions

With Universium Web Bridge, each mod only registers its own API surface.

## What This Mod Does Not Solve

Universium Web Bridge does not automatically know how other mods work.

It does not decide:

- how auctions work
- how bids are validated
- how items are stored
- how currency is transferred
- how mail is delivered
- how research is unlocked
- how quest progress is calculated
- what data is safe to expose
- what actions are safe to perform from the web

Those rules must be implemented by the owning mod.

## Module Scope

Each registered module owns a scoped API namespace, frontend mount point, and permission set.

The bridge only provides the registration and transport layer. Individual modules define their own routes, events, and business rules.

## Architecture

```text
universum-web-bridge
├── api
│   ├── WebBridgeModule
│   ├── WebRouteRegistry
│   ├── WebSocketRegistry
│   ├── PermissionRegistry
│   ├── FrontendAssetRegistry
│   └── WebResponse
│
├── server
│   ├── HttpServer
│   ├── RestRouter
│   ├── WebSocketServer
│   ├── StaticAssetServer
│   └── MiddlewarePipeline
│
├── auth
│   ├── LinkTokenService
│   ├── SessionService
│   ├── WebIdentity
│   └── AuthMiddleware
│
├── permissions
│   ├── Permission
│   ├── Role
│   ├── PermissionService
│   └── PermissionMiddleware
│
├── security
│   ├── RateLimiter
│   ├── AuditLog
│   ├── CsrfProtection
│   ├── IdempotencyService
│   └── RequestValidator
│
├── frontend
│   ├── WebAppConfig
│   ├── ThemeConfig
│   ├── ModuleFrontendManifest
│   └── AssetMount
│
└── config
    ├── WebBridgeConfig
    ├── AuthConfig
    ├── SecurityConfig
    └── ModuleConfig
```

## Public Java API Concept

### Web Module

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

### Route Registry

```java
public interface WebRouteRegistry {
    void get(String path, WebRouteHandler handler);

    void post(String path, WebRouteHandler handler);

    void put(String path, WebRouteHandler handler);

    void delete(String path, WebRouteHandler handler);
}
```

### Route Handler

```java
@FunctionalInterface
public interface WebRouteHandler {
    WebResponse handle(WebRequest request) throws Exception;
}
```

### Web Request

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

### Web Response

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

## System Endpoints

Universium Web Bridge reserves the `/api/webbridge/*` scope.

### Status

```http
GET /api/webbridge/status
```

Example response:

```json
{
  "success": true,
  "data": {
    "serverName": "Universium",
    "online": true,
    "minecraftVersion": "1.21.1",
    "loader": "neoforge",
    "playersOnline": 12,
    "maxPlayers": 100,
    "webBridgeVersion": "1.0.0"
  }
}
```

### Modules

```http
GET /api/webbridge/modules
```

Example response:

```json
{
  "success": true,
  "data": {
    "modules": [
      {
        "id": "webcraft",
        "displayName": "webcraft",
        "version": "1.0.0",
        "basePath": "/api/webcraft",
        "frontendPath": "/modules/webcraft",
        "permissions": [
          "webcraft.view",
          "webcraft.link",
          "webcraft.unlink"
        ]
      }
    ]
  }
}
```

### Current User

```http
GET /api/webbridge/me
```

Example response:

```json
{
  "success": true,
  "data": {
    "authenticated": true,
    "player": {
      "uuid": "00000000-0000-0000-0000-000000000000",
      "name": "Nightware"
    },
    "roles": [
      "player"
    ],
    "permissions": [
      "webcraft.view",
      "webcraft.link",
      "webcraft.unlink"
    ]
  }
}
```

## Authentication

The default authentication method is an in-game link code.

Flow:

```text
1. Player joins the Minecraft server.
2. Player runs /web link.
3. Server generates a temporary one-time code.
4. Player enters the code on the web frontend.
5. Web Bridge creates a signed session token bound to the player's UUID.
6. Web requests are handled as that player.
```

Command:

```text
/web link
```

Example in-game output:

```text
Your web link code: ABCD-1234
Expires in: 10 minutes
Enter this code on the server web page.
```

Endpoint:

```http
POST /api/webbridge/auth/link
```

Request:

```json
{
  "code": "ABCD-1234"
}
```

Response:

```json
{
  "success": true,
  "data": {
    "sessionToken": "signed-session-token",
    "player": {
      "uuid": "00000000-0000-0000-0000-000000000000",
      "name": "Nightware"
    }
  }
}
```

## Permissions

The bridge provides a shared permission system.

Default roles:

```text
guest
player
moderator
admin
```

Modules register permissions, but the server config assigns them to roles.

Example:

```toml
[roles.guest]
permissions = [
  "webbridge.status.view",
  "webbridge.modules.view",
  "market.view"
]

[roles.player]
inherits = ["guest"]
permissions = [
  "market.buy",
  "market.bid",
  "market.sell",
  "research.view",
  "mail.view",
  "mail.send"
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

## WebSocket

Shared WebSocket endpoint:

```text
/ws
```

Subscribe message:

```json
{
  "type": "subscribe",
  "channel": "webcraft.live"
}
```

Event message:

```json
{
  "type": "event",
  "channel": "webcraft.live",
  "event": "LINK_CREATED",
  "data": {
    "linkId": "abc",
    "bidderName": "Nightware"
  }
}
```

Recommended channel naming:

```text
webcraft.live
webcraft.player.{uuid}
status.live
status.player.{uuid}
account.player.{uuid}
server.status
```

Private channels must validate the current session.

## Frontend Support

Universium Web Bridge may support two frontend modes.

### Built-in Frontend

The mod serves a default web app directly.

Useful for:

- small servers
- quick setup
- bundled modpack web UI
- local testing

### External Frontend

The web frontend is hosted separately and calls the bridge API.

Useful for:

- production servers
- custom domains
- reverse proxy setups
- custom React/Vue/Svelte apps
- launcher integration

Example config:

```toml
[frontend]
mode = "external"
public_url = "https://universum.example.com"
```

## Module Frontend Manifest

A module can expose frontend assets and navigation metadata.

Example:

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

The default frontend can use this manifest to build dynamic navigation.

## API Response Format

### Success

```json
{
  "success": true,
  "data": {}
}
```

### Error

```json
{
  "success": false,
  "error": {
    "code": "permission_denied",
    "message": "You do not have permission to perform this action."
  }
}
```

Recommended error codes:

```text
unauthenticated
permission_denied
not_found
invalid_request
rate_limited
conflict
module_unavailable
internal_error
```

## Security Rules

Important rules:

1. Web Bridge must not automatically expose mod data.
2. Each mod must explicitly register its endpoints.
3. Write operations must require authentication and permissions.
4. Modules must perform their own business validation.
5. Web endpoints must call the same service layer as in-game actions.
6. Web endpoints must not directly bypass game logic or storage safety.
7. Public deployments should run behind a reverse proxy.
8. Rate limits should apply to auth, write operations, and expensive reads.
9. Audit logging should record important write operations.
10. Idempotency keys should be supported for sensitive operations.

## Example Config

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

## Integration Types

### Native Integration

A mod directly supports Universium Web Bridge and registers its own module.

This is the preferred integration type.

### Bridge Mod

A separate compatibility mod connects a third-party mod to Web Bridge.

The bridge mod understands the third-party mod API and exposes safe web endpoints.

### Command Endpoint

A configurable endpoint that executes or reads command output.

This should only be used for simple status/admin tools, not for primary gameplay systems like auctions, item transfers, economy, or research logic.

## Recommended Project Names

For Universium-specific use:

```text
Universium Web Bridge
Universium webcraft
Universium Server Bridge
```

For public/general use:

```text
Modpack Web Bridge
NeoForge Web Bridge
Universal Mod Web Bridge
```

## One-Sentence Definition

**Universium Web Bridge is a headless web bridge platform for NeoForge modpacks that lets mods expose scoped APIs, live events, permissions, and optional frontend modules through a shared authenticated web runtime.**

