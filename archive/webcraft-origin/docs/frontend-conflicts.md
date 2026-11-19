# Frontend Conflict Handling

When multiple mods or packs target the frontend, WebCraft should avoid silent overwrites.

## Core Rules

- every mod should own its own namespace
- the same template name should not be claimed by two independent sources without an explicit policy
- the runtime should detect collisions during startup
- the runtime should not bounce files back and forth between mods

## Safe Behavior

If two sources want the same output path, the runtime should:

1. detect the collision
2. log it clearly
3. apply a precedence rule or fail fast

## Why This Matters

Silent overwrites make updates hard to debug.

Explicit conflict handling keeps the system deterministic and helps pack authors understand which source owns which files.

