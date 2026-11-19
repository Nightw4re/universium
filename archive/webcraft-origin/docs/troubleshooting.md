# Troubleshooting

## The Client Crashes During Startup

If NeoForge reports that the mod is invalid or the game crashes during startup, check:

- `config/webcraft-server.toml` exists and contains valid TOML
- the HTTP port is not already in use
- the mod metadata was expanded correctly during `processResources`

## The Server Does Not Start

Common causes:

- the configured port is already bound by another process
- the config file is malformed
- the runtime config path does not exist and cannot be created

## Example Port Conflict

If port `8087` is already occupied, either:

- change `server.port` in `webcraft-server.toml`
- or stop the process using that port

## Player Route Returns 400

The `player-info` route currently expects a player name:

```text
/player-info/nightware6/
```

or:

```text
/player-info?player=nightware6
```

If the name is empty, WebCraft returns `400 invalid_player_name`.

## Logs Are Empty

Check whether logging is enabled:

```toml
[logging]
access_enabled = true
error_enabled = true
```

Also verify that the config directory is writable by the process.
