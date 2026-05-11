# SPA Module Contract

WebCraft uses a single-shell SPA model.

The shell is always:

- `index.html` as the browser entry point
- core JavaScript as the first bootstrap layer
- a generated manifest that lists active modules and their routes
- API requests use `/api/*`; the same shell can be served in production or through the Vite dev proxy

The shell may be overwritten by a module or modpack, but the contract stays the same:

1. The browser loads `index.html`.
2. `index.html` loads the core bootstrap script first.
3. The bootstrap script loads `webcraft.manifest.json`.
4. The manifest describes which modules are active.
5. Each active module contributes routes, navigation items, and optional UI blocks.

## Core Behavior

If no active module exists, the core shell shows a hello / 404 state.

That fallback must:

- render without crashing
- not assume any addon data
- not require any module-specific assets

## Manifest Contract

The manifest is the authoritative list of module entrypoints.

It should contain:

- module metadata
- active state
- optional `path` prefix for assets
- optional `entryScript`
- optional `styles`
- optional `scripts`
- routes
- navigation items
- optional UI blocks
- optional bootstrap references

Core only reads the manifest and builds the runtime from it.

The frontend workspace in `modules/webcraft/frontend/` is the editable source version of this shell. Its production build is written back to `webroot/`, so the runtime always serves the compiled output while authors can still develop in a separate workspace.

## Builtin Modules

WebCraft includes a builtin `player-info` module.

This module provides:

- player list
- player detail
- player profile data from REST

It is the first active module in the default manifest.

## Extension Rules

Addon authors should not overwrite shared shell files unless they intentionally want to replace the entire UI.

Preferred extension points:

- add a module entry to the manifest
- provide a module-local bootstrap script
- add navigation entries
- add topbar actions
- add hero facts
- add detail facts
- add detail blocks

### Component Slots

Some facts can be rendered as reusable components instead of simple bound text.

The current core exposes these component slots:

- `server-info`
- `uptime`

`server-info` is a composite component that can show multiple server fields at once.
`uptime` is a smaller reusable block that focuses on runtime uptime only.

`server-info` is meant for grouped server metadata such as:

- public URL
- Minecraft version
- uptime
- online player count

The manifest can also provide custom rows for this component, so addon authors can add
or reorder server facts without replacing the whole layout.

Future components should follow the same pattern:

- declarative name in the manifest
- render function in the core component registry
- optional reuse in addon layouts

Do not rely on ad-hoc HTML page replacement as a primary extension model.

## Navigation

Navigation can be implemented with:

- plain links with full reloads
- hash routing
- event-driven SPA navigation

The core bootstrap always runs first, even if the shell is replaced.

## Safety

Addons should use their own namespace in the manifest and asset paths so they do not collide with each other.

The core should treat manifest entries as declarative metadata, not as application logic.
