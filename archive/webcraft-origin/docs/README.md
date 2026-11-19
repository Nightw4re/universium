# WebCraft Wiki

This folder preserves historical documentation for WebCraft Origin. See the [active WebCraft guide](../../../modules/webcraft/README.md) for current configuration and contracts.

Important layout rule:

- `/api/*` is reserved for the backend REST surface
- `webroot/` is reserved for frontend delivery assets only
- `webroot/` is the runtime copy target for the frontend shell
- the embedded HTTP server is intentionally simple and is best used as an internal service behind a reverse proxy such as nginx or Apache when security hardening is required
- runtime config files belong in the mod config layer, not in `webroot/`
- server or modpack owners can replace or extend the frontend by overlaying files inside `webroot/`
- addons should extend `window.WEBCRAFT_MODULE_CONFIGS` instead of replacing the whole shell
- proxy or pack layers can still override route bindings through `window.WEBCRAFT_PROXY_CONFIG`
- generated runtime caches such as `config/webcraft-cache/` should stay out of version control
- the editable frontend source lives in `modules/webcraft/frontend/` and builds back into `webroot/`
- vanilla missing-texture fallback is referred to as `missingno` throughout the backend, frontend, and Storybook fixtures
- docs are written in English and are ready to be mirrored into the GitHub Wiki for the module

Recommended pages:

- `README.md` - project overview
- `quickstart.md` - local development and testing
- `configuration.md` - TOML configuration reference
- `routes.md` - HTTP route examples and binding model
- `logging.md` - access and error logs
- `frontend-architecture.md` - template, transport, and lifecycle model
- [`frontend-contract.md`](frontend-contract.md) - original frontend item render contract
- `frontend-api.md` - addon API and extension hooks
- `frontend-templates.md` - template ownership and versioning
- `frontend-overrides.md` - default frontend, fallback, and pack override policy
- `addon-modules.md` - addon module registration and shared API model
- `frontend-ssl.md` - HTTPS and certificate handling
- `frontend-conflicts.md` - how collisions are resolved
- `troubleshooting.md` - common runtime issues

These files are written as standalone Markdown pages and can be copied into a GitHub Wiki, mirrored into a wiki repository, or linked from the repository README.
