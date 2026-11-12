# webcraft-frontend

WebCraft frontend for the Universium Minecraft mod.

## Structure

- [`AGENTS.md`](AGENTS.md) frontend working notes
- `src/app.ts` SPA shell composition
- `src/config.ts` shared runtime configuration
- `src/components/` reusable UI components
- `src/stories/` Storybook stories for the components
- `.storybook/` Storybook configuration

## Development

Run `npm ci` in this directory before using npm scripts. Vite builds into `dist/`. From the Universium root, `gradle :webcraft:syncFrontendWebroot` installs the locked dependencies, builds the frontend, and copies the result to `modules/webcraft/build/webroot/`.

## Scripts

- `npm run dev`
- `npm run build`
- `npm run preview`
- `npm run storybook`
- `npm run build-storybook`
- `npm run test`

## Development notes

- Keep build outputs out of git.
- Use Storybook for UI review before wiring a component into `src/app.ts`.
- Prefer shared data in `src/config.ts` when a value is used by multiple components.
- This workspace uses React Storybook with component-driven development and test-driven development.
- When Storybook is running, the MCP endpoint is available at `http://localhost:6006/mcp`.
- Historical notes about discarded icon rendering approaches live in [docs/icon-render-decisions.md](docs/icon-render-decisions.md).
- Research note: for a future WebGL renderer, first map the vanilla client item render pipeline; there is no drop-in public Java library for a full 1:1 modern vanilla item renderer without a client runtime.
