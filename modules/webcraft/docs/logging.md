# Logging

WebCraft writes its own runtime logs in addition to the NeoForge and Minecraft logs.

## Default Files

When enabled, WebCraft writes to:

- `config/logs/webcraft-access.log`
- `config/logs/webcraft-error.log`

The exact location is controlled by the `logging` block in `webcraft-server.toml`.

## Access Log Example

```text
[2026-05-04T16:53:39Z] [ACCESS] GET /server-info -> 200
[2026-05-04T16:53:40Z] [ACCESS] GET /player-info/nightware6/ -> 200
```

## Error Log Example

```text
[2026-05-04T16:53:41Z] [ERROR] GET /player-info/bad-user :: IllegalArgumentException: Invalid player name
```

## Disable Logging

```toml
[logging]
access_enabled = false
error_enabled = false
```

## Notes

- Access logging tracks request path and response status.
- Error logging captures runtime exceptions and startup failures.
- If a log file cannot be created, WebCraft falls back to standard error output for that write attempt.
