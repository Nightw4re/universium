# Frontend Architecture

WebCraft frontend delivery is built around a small set of stable rules:

- the runtime entry point is `webroot/index.html`
- the frontend shell may be served as static files or proxied to a live dev server
- the active template is selected by config
- each module or pack can provide its own named frontend template
- the core module controls the runtime lifecycle and transport policy
- `webroot/` is a generated runtime output directory and should not be treated as source control frontend input

## Core Goals

The frontend system is designed to support:

- a default built-in frontend
- mod-specific frontend extensions
- modpack-owned overrides
- a live development mode
- HTTPS deployment
- optional shutdown coupling with the Minecraft server

The goal is not to force every mod into one rigid JSON schema. Instead, WebCraft keeps a small set of core controls and lets the active template define the actual UI.

## Runtime Layers

WebCraft frontend delivery has three layers:

1. **Template layer**
   - chooses which frontend template is active
   - template identity is derived from the template folder or package namespace

2. **Transport layer**
   - decides whether the frontend is served from static files, a proxy, or not at all

3. **Lifecycle layer**
   - decides whether the HTTP service stops together with the Minecraft server

## Recommended Model

The recommended model is:

- a mod or pack chooses an active template name
- the core module publishes the active template state through a small Java interface
- addons can react to that state without reading internal config files directly
- the runtime copies or mounts the chosen template into the instance webroot when needed
- the editable frontend source lives in `modules/webcraft/frontend/` and builds into the generated `webroot/` output

This keeps the system stable while still allowing packs to fully own the final frontend composition.

## Why This Works

This approach supports several common scenarios:

- one core template
- one core template plus addon extensions
- a pack-owned frontend bundle
- a dev-only frontend proxy
- a production static frontend

It also avoids a common failure mode where every mod tries to rewrite the same shared app entrypoint.
