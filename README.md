# Universium

Universium is a Minecraft mod monorepo focused on shared infrastructure and feature modules.

## Layout

```text
universium/
  AI.md
  build.gradle
  settings.gradle
  docs/
    README_Universium_Web_Bridge.md
    PLAN_Universium_Web_Bridge.md
  modules/
    webcraft/     web bridge integration module
    webcraft-proxy/ route remapping overlay module
  .github/       shared CI workflows
```

## Conventions

- Bridge documentation lives in `docs/`.
- Build and test logic is centralized in Gradle and `.github/workflows/`.
- Module versioning lives in each module's `gradle.properties`.
- The primary mod target is NeoForge 1.21.1 build 224.
- Development uses `gradle :webcraft:runClient` for the client and `gradle :webcraft:runServer` for a dedicated server.
- Commits use conventional commit messages so changelogs stay predictable.

## Status

This repository is scaffolded as a monorepo starter. The individual module implementations are intentionally minimal for now.

