# Frontend Addon API

The core module should expose a small Java API to frontend-aware addons.

Addons should not need to parse the internal config structure directly.

## Context Interface

A minimal context interface can expose:

- whether the frontend is enabled
- the active template name
- the frontend owner
- the frontend version tuple
- the active transport mode

Example:

```java
public interface WebCraftFrontendContext {
    boolean isFrontendEnabled();
    String getActiveTemplateName();
    String getActiveTemplateOwner();
    int getFrontendMajor();
    int getFrontendMinor();
    int getFrontendBuild();
    FrontendTransport getTransport();
}
```

## Contributor Interface

Addons can also expose contributions to the active frontend.

Typical contribution points:

- routes
- navigation items
- view fragments
- assets
- extension hooks

The addon should only contribute its own namespace and should not assume that it owns the whole app shell.

## Design Rules

- keep the interface small
- prefer getters over config parsing
- keep all lifecycle decisions in the core module
- make addon behavior idempotent
- never rely on implementation details of another addon

