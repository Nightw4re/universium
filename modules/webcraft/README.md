# webcraft

webcraft is the Universium module that provides shared HTTP and realtime web infrastructure for Minecraft servers and connected mods.

It is responsible for exposing a web-facing runtime that can serve static assets, forward API requests, and stream live events between the server and a browser-based frontend.

## Purpose

webcraft exists to make it possible for Minecraft server logic and other mods to integrate with:

- web dashboards
- SPA applications
- PWA applications
- admin panels
- companion tools

The module focuses on transport and delivery. It does not own game rules or gameplay data models.

The embedded HTTP server is intentionally lightweight. For hardened public deployments, it is recommended to keep it internal and place it behind a reverse proxy such as nginx, Apache, or a similar fronting service.

For item and block thumbnails, the web runtime can use a web-specific resource-pack fallback order that is configured in TOML. If `server.resource_packs_web_order` is left empty, the web uses the same pack order it already reads from `resourcepacks.txt`. If it is filled, only the listed packs are used and they are applied in that exact order for the web preview pipeline.

Static web asset delivery under `/resources/*` is cacheable. By default it uses a seven-day max-age and an ETag that changes with each HTTP server start. The TTL can be adjusted in TOML with `server.resources_cache_max_age_seconds`.

The current browser surface is split into two layers:

- `/api/*` for REST and resource delivery
- everything else for the SPA shell

The editable frontend lives in `modules/webcraft/frontend/` and builds back into `webroot/`, so the module still ships as one package while giving the UI a dedicated workspace.

## Development Target

webcraft is primarily developed for NeoForge 1.21.1 build 224.

The runtime config is not development-only. Pack authors and server owners can use the same TOML options in normal installs, including startup port handling and stale-process cleanup.

The frontend is split into a separate workspace under `modules/webcraft/frontend/`, but its build output still lives in `modules/webcraft/webroot/`, so it remains part of the module and ships with the mod.

In development, the mod can be launched with:

- `gradle :webcraft:runClient`
- `gradle :webcraft:runServer`

When the NeoForge dev environment starts, webcraft also starts its HTTP runtime using the current TOML bindings.

Quickstart:

1. Run `gradle :webcraft:runClient`.
2. Open `http://127.0.0.1:8087/server-info`.
3. Open `http://127.0.0.1:8087/player-info/00000000-0000-0000-0000-000000000001/`.
4. Use `gradle :webcraft:runServer` if you want the dedicated server dev target instead.

## Documentation

The wiki-ready docs live in [`docs/`](./docs/). Start with:

- [`docs/README.md`](./docs/README.md)
- [`docs/quickstart.md`](./docs/quickstart.md)
- [`docs/configuration.md`](./docs/configuration.md)
- [`docs/routes.md`](./docs/routes.md)
- [`docs/frontend-architecture.md`](./docs/frontend-architecture.md)
- [`docs/frontend-api.md`](./docs/frontend-api.md)
- [`docs/frontend-templates.md`](./docs/frontend-templates.md)
- [`docs/frontend-ssl.md`](./docs/frontend-ssl.md)
- [`docs/frontend-conflicts.md`](./docs/frontend-conflicts.md)

## Core Capabilities

### HTTP Server

webcraft provides an embedded HTTP server for:

- module-specific API routes
- status endpoints
- authenticated requests
- static asset delivery

### HTTP Delivery

webcraft can serve files required by browser applications, including:

- HTML entry files
- JavaScript bundles
- CSS assets
- icons and manifests
- service worker files

On first run, webcraft also creates a module-specific configuration folder for the user.

That folder contains default HTML delivery files and frontend templates that can be customized by the modpack creator.
The exact file structure and defaults will be defined later, but the intent is that pack authors can adapt the web UI to match their own branding and workflow.

### Webroot Layout

The shipped frontend lives in `webroot/`.

That folder is intended to be a pure browser asset root, so it can be replaced or extended by:

- the core module
- a proxy module
- a mod-specific adapter
- a modpack override

The current front-end stack uses only:

- HTML
- CSS
- modern browser JavaScript

No frontend framework is required.

### Frontend Workspace

The editable frontend source now lives in `frontend/`.

Use it like this:

1. `cd modules/webcraft/frontend`
2. `npm install`
3. `npm run build`
4. The build writes the optimized frontend back into `../webroot`

The runtime server still serves `webroot/` directly, so the built frontend remains part of the module package.

### Frontend Config

The frontend loads a layered config from `webroot/app.config.js`.

The base config is declared in `webroot/app.config.base.js` and may be extended by other modules through:

- `window.WEBCRAFT_MODULE_CONFIGS`
- `window.WEBCRAFT_PROXY_CONFIG`

Merge order:

1. base config
2. module configs
3. proxy config
4. final frozen config exposed as `window.WEBCRAFT_CONFIG`

This makes it possible for modules such as `webcraft-proxy` to:

- change route targets
- rename UI sections
- add new frontend metadata
- inject alternate delivery paths
- override theme values

### SPA Module Model

The frontend is now driven by a generated manifest:

- `webroot/webcraft.manifest.json`
- `index.html` stays the shell entrypoint
- core JS loads first and resolves the manifest
- active modules register navigation and content blocks
- module entries may also expose `path`, `entryScript`, `styles`, and `scripts`
- the core loader will add those assets before rendering the active module

If no module is active, the shell falls back to a hello / 404 view.

For a full authoring contract, see:

- [`modules/webcraft/docs/spa-modules.md`](/c:/Users/lkroc/projects/universium/modules/webcraft/docs/spa-modules.md)

### Addon Contract

Addons should extend the frontend by adding only the parts they need.

The core frontend exposes config slots for:

- `ui.sidebarItems`
- `ui.topbarActions`
- `ui.heroFacts`
- `ui.detailFacts`
- `ui.homeBlocks`
- `ui.detailBlocks`
- some fact slots can be rendered as named components, starting with `server-info` and `uptime`

An addon can layer in just one or two entries instead of replacing the whole shell.

Example:

```js
window.WEBCRAFT_MODULE_CONFIGS = [
  {
    ui: {
      sidebarItems: [
        { id: "overview", label: "Přehled", icon: "overview", active: true },
        { id: "players", label: "Hráči", icon: "players" },
        { id: "auth", label: "Auth", icon: "settings" }
      ],
      topbarActions: [
        { id: "reload", label: "Reload" }
      ],
      detailBlocks: [
        { id: "equipmentGrid", title: "Current loadout", eyebrow: "Equipment", type: "equipment" },
        { id: "inventoryGrid", title: "Main inventory", eyebrow: "Inventory", type: "inventory" },
        { id: "playerStats", title: "REST fields", eyebrow: "Stats", type: "stats" }
      ]
    }
  }
];
```

This keeps core layout stable while letting addons patch only one section.

Example:

```js
window.WEBCRAFT_MODULE_CONFIGS = [
  {
    ui: {
      title: "Custom WebCraft",
      accent: "#66cc88"
    },
    api: {
      playerList: "/player-list"
    }
  }
];

window.WEBCRAFT_PROXY_CONFIG = {
  api: {
    playerListOnline: "/players/online"
  }
};
```

When running from NeoForge dev directories, the default `webroot_dir` is relative to the runtime working directory and usually points to `../webroot`.

### Header Control

The module supports configurable response headers so it can work correctly with SPA and PWA frontends.

Typical uses include:

- cache-control tuning
- content security policy headers
- CORS-related headers
- PWA-related headers
- route-specific header overrides

### API Request Layer

webcraft exposes an API layer for creating and routing HTTP requests toward module handlers.

This allows modules to define:

- request methods
- request metadata
- route matching
- response generation
- error handling

### WebSocket Support

webcraft provides WebSocket interfaces for realtime communication.

Typical uses include:

- live server events
- player-specific updates
- frontend state synchronization
- module notifications

## Built-in Modules

webcraft includes small built-in modules that expose ready-made HTTP APIs and frontend delivery assets.

The first built-in module will expose player state as JSON.

### Player Info Module

This module is named `player-info`.

It returns structured information about a requested player, including:

- total played time on the server
- equipped items
- main hand item
- off hand item
- inventory contents
- ender chest contents
- complete item data, including NBT

The endpoint is addressed by player name in the URL, for example:

`/player-info/nightware6/`

The same data must be consumable through:

- XHR / fetch requests
- WebSocket request and livestream flow

Authentication will be added later.

At that point, the logged-in player's operator level will control whether additional data is visible, such as:

- coordinates
- dimension

The module also provides a dedicated `webroot/*` delivery folder with frontend files for loading the API response and rendering it in the browser.

This is the default frontend for the module. A separate proxy/customization module can later be added so modpack authors can alter the delivered UI more freely.

By default, each module that ships static content should get its own folder inside `webroot/` as a template layout.

That makes it easier to:

- keep module assets separated
- replace only one module's frontend
- copy a module's default frontend into a custom version
- let server owners edit module-specific files without touching unrelated delivery assets

## External Module Registration

webcraft must also provide a registration system for other mods.

This allows a server owner or mod author to:

- reuse an existing webcraft module but replace the HTTP API logic
- keep the original frontend and only swap backend behavior
- keep the backend and replace the frontend with a custom one
- register a module that has no frontend at all
- register a module that exists only as a custom frontend or adapter layer

In practice, this makes webcraft a modular bridge platform rather than a fixed set of hard-coded features.

Examples of supported setups:

- authentication module with custom REST behavior but default frontend
- authentication module with custom REST behavior and custom frontend
- authentication adapter with no frontend
- fully custom module registered into webcraft's module system

### Proposed Module Model

The registration system should be transport-agnostic.

A module can expose one or more of these transports:

- HTTP REST API
- WebSocket channels
- static frontend delivery

For the first version, a module should preferably have one primary transport and optionally a second one.

That keeps the system simple while still allowing the later combination of:

- REST only
- WebSocket only
- REST plus WebSocket
- REST plus frontend
- WebSocket plus frontend
- fully custom module layouts

### Authenticated Access

webcraft should attach session state to requests and use it to decide what data is visible.

The intended baseline is:

- unauthenticated visitors see only public information
- authenticated users see their own linked account data
- admins see extended data such as advanced player details

The session should be sent with requests and reflected in the web frontend as the server account currently linked to the browser session.

### Player Info Access

The built-in `player-info` module should use the same session model.

Its access model should be:

- public viewers get basic player information
- authenticated players get their own linked data
- admins get advanced information such as coordinates and dimension

This access split should be driven by session identity and server-side role or operator level checks.

## Lifecycle Model

webcraft should not hard-code its runtime state too early.

The startup flow should be staged:

1. Bootstrap
2. Overlay and override collection
3. Finalize
4. Start runtime services

### Bootstrap

webcraft loads its default config, default built-in modules, and default webroot delivery assets.

At this point, nothing should be permanently locked.

### Overlay and Override Collection

Other mods and modpack layers can then:

- register new modules
- remap routes before finalization
- replace REST routes
- replace WebSocket listeners
- override headers
- override webroot delivery
- override frontend manifests
- merge or replace config values

### Handler Model

webcraft should treat TOML as a binding layer, not as request logic itself.

Every route should point to a declared handler reference.

The first supported handler types should be:

- `java`
- `mcfunction`

Routes should also support:

- `middleware`
- `fallback`
- `error handlers`

Examples:

```toml
[route."GET /server-info"]
type = "java"
ref = "webcraft.serverInfo"

[route."GET /player-info/{uuid}"]
type = "mcfunction"
ref = "webcraft/player_info"

[[route."GET /player-info/{uuid}".middleware]]
type = "java"
ref = "webcraft.authRequired"

[route."GET /player-info/{uuid}".fallback]
type = "java"
ref = "webcraft.playerInfoFallback"

[route."GET /player-info/{uuid}".errors."401"]
type = "java"
ref = "webcraft.unauthorized"

[route."GET /player-info/{uuid}".errors."403"]
type = "java"
ref = "webcraft.forbidden"
```

In this model:

- `java` means the route is handled by a Java implementation
- `mcfunction` means the route is handled by a Minecraft function reference
- `ref` identifies the concrete target to invoke
- `middleware` runs before the main handler and can transform the request or stop processing
- `fallback` is used when the primary handler target is unavailable or fails validation
- `errors."401"` and `errors."403"` are status-specific error handlers

Middleware can be chained when a route needs multiple processing steps.

Fallbacks are especially useful when a referenced mod or function is missing, or when a proxy layer needs to replace a broken target with a safe alternative.

Status-specific error handlers are useful for cases like:

- `401 Unauthorized`, when a session is missing or invalid
- `403 Forbidden`, when the session exists but does not have permission

If a status-specific error handler is not configured, webcraft should fall back to the default route error response.

This keeps the request model extensible while still allowing modpack creators to override routing without rewriting core logic.

### Finalize

webcraft composes the final runtime view from all layers and then freezes the active registry state.

### Runtime Controls

webcraft should expose lifecycle controls for administration:

- `start`
- `stop`
- `restart`
- `reload`

These controls should:

- be permission-gated
- be audit logged
- reapply the staged registry model
- rebind HTTP routes and WebSocket listeners when needed

`reload` should re-read config and overlay state.

`restart` should stop the runtime and start it again from the current staged configuration.

`stop` should shut down HTTP and WebSocket services cleanly.

`start` should bring the runtime up from the finalized configuration.

### Proxy Layers

A proxy module, such as `webcraft-proxy`, can sit in the overlay stage and remap routes before finalization.

That means it can:

- move a route to a different path
- replace one module's handler with another
- disable a route entirely
- preserve the original frontend while pointing it at new backend targets

This is the main mechanism for compatibility and server-owner customization.

## Intended Interfaces

webcraft is expected to provide interfaces and services for:

- HTTP request routing
- HTTP response generation
- header customization
- static file delivery
- websocket channel registration
- websocket message handling
- auth-aware request processing
- module registration
- frontend manifest delivery

## Scope

webcraft is infrastructure, not gameplay logic.

Modules that use webcraft are responsible for:

- validating game actions
- applying permissions
- deciding which data is safe to expose
- calling their own service layer

webcraft only provides the bridge between the Minecraft side and the web side.

## Versioning

The module version is defined in `gradle.properties`.

