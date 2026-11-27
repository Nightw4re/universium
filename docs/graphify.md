# Graphify

[Graphify](https://github.com/Graphify-Labs/graphify) builds local code graphs for architecture, dependency and complex caller questions. For a localized symbol or edit, search source directly with `rg`. Run the commands below from the Universium root with Node.js, npm and [uv](https://docs.astral.sh/uv/getting-started/installation/) on `PATH`. The first Graphify invocation uses uv to provision Python 3.13 and the pinned version. Extraction uses local parsers and requires no API key.

```sh
npm run graphify -- list
npm run graphify -- refresh webcraft
npm run graphify -- query webcraft "How do WebCraft add-ons register routes?"
npm run graphify -- explain webcraft-core WebCraftHttpServer.java
npm run graphify -- path webcraft WebCraftCatalogMod.java RegisterWebCraftModulesEvent.java
```

Use the smallest scope that covers both sides of the question. Use `universium` for relationships across projects. Refresh the selected graph if missing or invalid, or if its inputs have changed through edits, pulls or branch switches. `refresh` defaults to `universium`. Reserve `refresh all` for rebuilding all scopes. Refreshes reuse the local cache and write to the same project directory.

Project graphs cover WebCraft as a whole, WebCraft core, equipment, catalog, designs, quests, the independent frontend, the exporter including its native codec, and the archived Marketplace prototype. Update the project list in [tools/graphify.mjs](../tools/graphify.mjs) when adding or moving a project.

Local outputs live in `.local-artifacts/graphify/<project>/graphify-out/` and all scopes share the uv tooling cache under `.local-artifacts/graphify/`. Use the query commands or open `graph.html` interactively. Keep full graph files and reports out of routine agent context. Each scope uses the [Graphify exclusions](../.graphifyignore) and code-only extraction.

If the pinned tool is already cached, uv supports offline operation via `UV_OFFLINE=true` in the command environment. If the tool or cache is unavailable, inspect source directly. Verify findings in source, especially for Gradle, dynamic registration and runtime wiring. If a name matches several nodes, retry with the full node ID shown by Graphify.
