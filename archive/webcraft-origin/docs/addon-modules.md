# Addon Modules

WebCraft supports addon modules that extend the core runtime without starting their own HTTP server.

The intended model is:

- one core WebCraft module owns the server
- addon modules register extra REST routes, data providers, or frontend fragments
- the final frontend can still be overridden by a modpack creator

## Example Addon

A marketplace addon can expose a route such as:

- `GET /api/marketplace/hello`

The core server normalizes `/api/*` requests and dispatches them through the shared route registry.

## Registration Model

Addons should not depend on module load order.

Instead, they should register into a shared addon registry during initialization.

The core module can then bind those routes into the active dispatcher when requests arrive.

This makes the system work even when:

- the addon loads before the core mod
- the addon loads after the core mod
- a modpack combines several addon modules

## Minimal Responsibilities

Addon authors should:

- declare a unique `modId`
- depend on the `webcraft` core module
- register only their own REST routes and fragments
- avoid starting a second HTTP server
- keep their namespace isolated

Core responsibilities remain:

- lifecycle
- routing
- auth
- frontend delivery
- fallback behavior

## Recommended Pattern

1. Core mod starts the shared WebCraft runtime.
2. Addon mod registers route definitions or other contributions.
3. Core dispatcher consults the shared registry.
4. The final response is served from one HTTP server.

This keeps the system simple for pack authors and safe for server owners.

