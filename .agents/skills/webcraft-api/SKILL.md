---
name: webcraft-api
description: Change or review WebCraft server routes, authorization, live subscriptions, add-on registration, or REST and WebSocket contracts used by clients.
---

# WebCraft API development

- Apply the [WebCraft requirements](../../../modules/webcraft/AGENTS.md) and find the affected route in the [WebCraft index](../../../modules/webcraft/README.md). Check the [bundled OpenAPI schema](../../../modules/webcraft/core/src/main/resources/webcraft-api/openapi.json) for covered REST paths. Check discovery and WebSocket behavior when a route can use both transports.
- Trace registration from `RegisterWebCraftModulesEvent` for add-ons or `WebCraftCoreMod` for built-in feeds. Read the handler and its tests before exploring additional modules.
- Run the relevant Gradle tests from the root, such as `gradle :webcraft-core:test --tests '*WebCraftHttpServerTest'` for listener behavior. For affected reference-client contracts, use `node --test modules/webcraft/frontend/test/*.test.mjs`. For standalone UI work, use the [frontend guide](../../../modules/webcraft/frontend/README.md).
