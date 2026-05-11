# Frontend SSL

WebCraft frontend delivery can run over plain HTTP or HTTPS.

## SSL Modes

The recommended configuration supports these modes:

- `off` - no TLS
- `bundled` - use bundled certificate files
- `custom` - use owner-provided certificate files

## Common Options

Useful SSL-related settings include:

- enable or disable SSL
- certificate path
- key path
- HTTP to HTTPS redirect
- whether the HTTPS server should stop together with Minecraft

## Deployment Notes

For local development, HTTP is usually enough.

For public servers or shared instances, HTTPS should be enabled when the environment can provide valid certificate material.

When a reverse proxy already terminates TLS, the internal WebCraft HTTP service can remain plain HTTP.

