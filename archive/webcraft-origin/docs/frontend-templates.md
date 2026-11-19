# Frontend Templates

WebCraft can host more than one frontend template.

The selected template name is configured explicitly, and the runtime resolves it from the available template sources.

## Template Identity

A template should be identified by its folder or namespace.

Examples:

- `webcraft-core`
- `webcraft-shop`
- `webcraft-players`
- `webcraft-asdf`

This keeps ownership clear and avoids guessing based on generated file names.

## Template Versioning

Templates should use a simple numeric version tuple:

- `major`
- `minor`
- `build`

The version is the author’s responsibility. When the template changes in a way that matters, the version should be bumped.

Example marker:

```ini
major=0
minor=1
build=3
```

## Ownership

Each template should have a clear owner:

- core module
- addon module
- modpack

The owner is useful when the runtime decides whether a template can be replaced, copied, or kept as-is.

## Update Rules

The runtime should compare:

- the active template name
- the current version tuple
- the owner

If the active template is already current, it should not be regenerated.

If the version changes, the runtime can recreate the frontend layer for that template.

