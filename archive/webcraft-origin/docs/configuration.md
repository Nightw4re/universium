# Configuration

WebCraft uses TOML for runtime binding and presentation settings.

The active config is loaded from:

```text
config/webcraft-server.toml
```

If the file does not exist, WebCraft copies its bundled default config into place.

## Example

```toml
[server]
enabled = true
host = "127.0.0.1"
port = 8087
public_url = "http://127.0.0.1:8087"
minecraft_version = "1.21.1"
neoforge_build = 224
server_name = "WebCraft Dev Server"
minecraft_server_name = "WebCraft Dev Server"
minecraft_server_address = "127.0.0.1:25565"
resource_packs_web_order = []
resources_cache_max_age_seconds = 604800

[frontend]
mode = "built_in"
theme = "default"
enabled = true
transport = "static"
template = "webcraft-core"
shutdown_with_minecraft = true

[frontend.ssl]
enabled = false
mode = "off"
cert_path = ""
key_path = ""
redirect_http_to_https = false

[logging]
access_enabled = true
error_enabled = true
access_file = "logs/webcraft-access.log"
error_file = "logs/webcraft-error.log"
```

## Server Block

- `enabled` controls whether the web server is allowed to start.
- `host` is the bind host for the embedded HTTP server.
- `port` is the bind port.
- `public_url` is the externally visible URL.
- `minecraft_version` and `neoforge_build` are exposed in `server-info`.
- `server_name` is the human-facing WebCraft name.
- `minecraft_server_name` and `minecraft_server_address` are used by the dev client server entry model.
- `resource_packs_web_order` defines the WebCraft-specific fallback order for textures and previews. Leave it empty to keep the order from `resourcepacks.txt`, or list pack names/folders to explicitly reorder or filter the web pipeline.
- `resources_cache_max_age_seconds` controls the public cache TTL for `/resources/*` responses. The default is 604800 seconds, which is 7 days.

## Frontend Block

- `enabled` controls whether the frontend shell is active at all.
- `mode` is the legacy frontend mode selector. The default can remain `built_in` for compatibility.
- `transport` selects how the frontend is delivered:
  - `static` serves the generated `webroot/` assets
  - `proxy` forwards requests to a live development server
  - `none` disables the frontend shell
- `template` selects the active named frontend template.
- `shutdown_with_minecraft` controls whether the frontend HTTP runtime stops together with the Minecraft server.

## Frontend SSL Block

- `enabled` toggles HTTPS support for the frontend transport.
- `mode` selects the SSL source:
  - `off`
  - `bundled`
  - `custom`
- `cert_path` is the certificate file path relative to the runtime config root unless a custom loader overrides it.
- `key_path` is the private key file path relative to the runtime config root unless a custom loader overrides it.
- `redirect_http_to_https` enables an HTTP-to-HTTPS redirect when the HTTP listener is still available.

## Missing Texture Fallback

WebCraft uses the vanilla-style `missingno` fallback name for unknown or unresolved items.

- backend fallback item id: `minecraft:missingno`
- public fallback icon path: `/resources/textures/item/missingno.png`
- Storybook fallback icon path: `/storybook-assets/previews/item/missingno.png`

## Logging Block

- `access_enabled` toggles access log writes.
- `error_enabled` toggles error log writes.
- `access_file` is the access log path relative to the config root.
- `error_file` is the error log path relative to the config root.
