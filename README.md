# Universium

Universium is a Minecraft mod monorepo focused on shared infrastructure and feature modules.

## Layout

- [WebCraft](modules/webcraft/README.md) provides a dedicated-server HTTP API, optional data modules, an independent frontend and a client preview exporter. It targets Minecraft 1.21.1, Java 21 and NeoForge 21.1.230 or later.
- [WebCraft Origin](archive/webcraft-origin/README.md) and its [Marketplace prototype](archive/webcraft-marketplace/README.md) are archived source references.
- The older bridge [architecture](docs/README_Universium_Web_Bridge.md) and [plan](docs/PLAN_Universium_Web_Bridge.md) document the original project direction.

## Development

- Run `gradle test` from the repository root for the active modules.
- Run `gradle runClient` for a development client with the exporter, or `gradle runServer` for a development server with WebCraft Core.
- Start the optional frontend using the [frontend development instructions](modules/webcraft/frontend/README.md).
- Run `gradle bundleWebCraftFrontend` to package the static frontend in `build/distributions/frontend-dist.zip`.
- Module versions are defined in their own `gradle.properties` files. Use conventional commit messages.
