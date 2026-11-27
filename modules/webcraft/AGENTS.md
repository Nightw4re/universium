# WebCraft requirements

## API and data

- Use the shared listener and register add-on routes under the add-on's module namespace. WebCraft read routes are private by default. Access live Minecraft state on the game thread and serialize captured data off that thread. See the [add-on API](api.md#add-on-api).
- Preserve separate viewer, machine and account-link access policies. Keep credentials out of URLs and logs and keep internal and loopback-only feeds off browser-facing proxy paths. Check the [relevant contract](README.md) when changing exposure, transport or player data. Keep cross-repository integration work within the authorized scope.

## Exports

- Follow the [artifact lifecycle](../../AI.md#local-workflows-and-artifacts). For each pack configuration, keep only the latest successful WebCraft export and its working world, subject to the shared preservation rules. Use the [export launcher and checks](exports.md) for pack exports.
