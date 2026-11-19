# webcraft-marketplace

Historical prototype for WebCraft Origin. The active build omits this module.

It does not start its own HTTP server.

Instead, it depends on the core `webcraft` module and registers additional REST routes into the shared WebCraft runtime.

## Current Route

- `GET /api/marketplace/hello`

The route is registered through the shared addon registry and returns a small JSON hello response.

## Purpose

This module exists as a minimal example of the addon model:

- one core server
- one shared route registry
- many addon modules
- no duplicated HTTP runtime

## Development Notes

- keep the module focused on addon-specific API
- do not create a second HTTP server
- register only your own namespace
- use the core docs for the routing and override model

