# Universium

Universium is a Minecraft mod monorepo focused on shared infrastructure and feature modules.

## Layout

- [WebCraft](modules/webcraft/README.md) provides an HTTP and WebSocket API for dedicated servers and singleplayer worlds, a client preview exporter, optional data modules including [FTB Quests](modules/webcraft/README.md#ftb-quests) and [Ponder](modules/webcraft/README.md#ponder-scene-api), and an independent frontend. It targets Minecraft 1.21.1, Java 21 and NeoForge 21.1.230 or later.
- The [Marketplace prototype](archive/webcraft-marketplace/README.md) is an archived source reference.
- The older bridge [architecture](docs/README_Universium_Web_Bridge.md) and [plan](docs/PLAN_Universium_Web_Bridge.md) document the original project direction.

## Development

- Run `gradle test` from the repository root for the active modules.
- Run `gradle runClient` for a development client with WebCraft, or `gradle runServer` for a dedicated development server.
- Start the optional frontend using the [frontend development instructions](modules/webcraft/frontend/README.md).
- Run `gradle bundleWebCraftFrontend` to package the static frontend in `build/distributions/frontend-dist.zip`.
- Module versions are defined in their own `gradle.properties` files. Use conventional commit messages.
