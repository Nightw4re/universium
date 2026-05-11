# Quickstart

WebCraft is the Universium module that exposes HTTP infrastructure for Minecraft worlds and connected web UIs.

## Development

Run the NeoForge dev environment:

```bash
gradle :webcraft:runClient
```

Or start the dedicated server target:

```bash
gradle :webcraft:runServer
```

## First Check

After startup, open the API or the SPA shell:

```text
http://127.0.0.1:8087/api/server-info
```

Then test player information with a player name:

```text
http://127.0.0.1:8087/api/player-info/nightware6/
```

Or with the query form:

```text
http://127.0.0.1:8087/api/player-info?player=nightware6
```

Open the frontend shell directly:

```text
http://127.0.0.1:8087/
```

## Notes

- The built-in HTTP server listens on the port configured in `config/webcraft-server.toml`.
- If the configured port is already in use, WebCraft will skip startup instead of crashing the client.
- Logs are written to separate access and error files when enabled in config.
- When developing the frontend workspace, use `modules/webcraft/frontend/` with Vite, Vitest, and Storybook 10.
- API requests from the frontend should go through `/api/*` so development proxying stays CORS-free.
