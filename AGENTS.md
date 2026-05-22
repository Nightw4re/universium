# WebCraft Frontend

This repository contains the standalone WebCraft frontend override.

## What lives here

- `src/app.ts` app shell composition
- `src/config.ts` shared runtime configuration
- `src/components/` reusable UI components
- `src/stories/` Storybook stories for the components
- `.storybook/` Storybook config

## Working rules

- Keep the project Java-free.
- Work only in this repository unless explicitly told to touch another project.
- Develop the project using component-driven development (CDD) and test-driven development (TDD).
- Prefer React Storybook stories and MCP-backed workflows for UI work.
- Prefer small reusable components over page-specific markup.
- Treat `src/config.ts` as the shared source of truth for navigation and runtime defaults.
- Keep stories isolated and representative of the real UI.
- Do not commit build artifacts such as `dist/` or `storybook-static/`.

## Common commands

- `npm run dev`
- `npm run build`
- `npm run preview`
- `npm run storybook`
- `npm run build-storybook`
- `npm run test`

## Notes for contributors

- Storybook is the primary place to validate component behavior.
- Storybook MCP is available at `http://localhost:6006/mcp` when the dev server is running.
- Register the MCP server with `npx mcp-add --type http --url "http://localhost:6006/mcp" --scope project`.
- If you add a new UI surface, create both the component and its story.
- If a component needs runtime defaults, add them to `src/config.ts` instead of hardcoding them in multiple places.

## Active debugging notes

- Shield rendering is being migrated to a model-first WebGL path.
- The backend now serves model payloads for `GET /api/model/:modelId` and includes `renderData`, `meshJson`, `modelJson`, `displayJson`, `texturePaths`, and related metadata.
- `minecraft:shield` is provided through `equipment.offHand` on `GET /api/player/Dev`, not through the hotbar/inventory grid.
- Frontend shield routing currently resolves `modelId === "builtin/entity"` and fetches `GET /api/model/builtin%2Fentity`.
- The frontend should not invent a fallback shield shape; if model geometry is missing, it should stay empty rather than drawing a fake mini-box.
- Current code in `src/components/item-render.ts` parses model payloads and expects geometry from `meshJson` / `modelJson` / `renderData`.
- At the time of writing, `chrome-devtools-mcp` was added to `~/.codex/config.toml` with `npx -y chrome-devtools-mcp@latest --isolated=true`, but this Codex runtime did not expose the MCP server in `list_mcp_resources`.
- `npx chrome-devtools-mcp@latest --help` works locally, so the package itself is installed and runnable.
- If the next session can see the MCP server, use it to inspect `GET /api/player/Dev` and the shield offhand payload directly in Chrome instead of relying on screenshots.
