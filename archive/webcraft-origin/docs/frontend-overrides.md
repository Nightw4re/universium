# Frontend Overrides Guide

This page describes the recommended frontend policy for WebCraft modules and modpacks.

The goal is to keep the default frontend stable, while still allowing pack authors and addon authors to override it when they explicitly want to.

## Recommended Model

WebCraft should ship with a built-in default frontend.

That default frontend is the baseline delivery target for:

- the core WebCraft module
- standalone addon modules that only need to add features
- modpacks that do not want to customize the UI

If another module or modpack wants to change the frontend, it should do so explicitly.

## Ownership Rules

Frontend files should be treated as owned by one of these layers:

- `core-owned`
- `addon-owned`
- `pack-owned`
- `immutable`

These ownership categories are important because they make conflict resolution predictable.

### Core-owned

Core-owned files are the default WebCraft frontend files.

They may be updated by WebCraft itself when a newer version is installed.

### Addon-owned

Addon-owned files belong to a feature module that extends the default frontend.

Addon authors should only replace the parts they actually need.

### Pack-owned

Pack-owned files belong to the modpack creator.

If a modpack wants to combine multiple frontend mods or replace the default shell, the modpack creator is responsible for that decision.

### Immutable

Immutable files are not expected to change automatically.

They are useful for:

- pinned UI shells
- hand-authored pack layouts
- release artifacts that should not be rewritten by later installs

## Fallback Behavior

The preferred behavior is:

1. use the default core frontend
2. apply addon overlays where available
3. apply pack-specific overrides last
4. keep the final result deterministic

If a requested override is missing, the system should fall back to the next available layer.

If a required part is missing in every layer, the build or bootstrap step should fail loudly instead of silently producing a broken UI.

## Conflict Responsibility

WebCraft should be able to detect that two frontend modules want to affect the same area.

When that happens, the framework should expose the conflict clearly, but the final decision belongs to the modpack creator.

This means:

- WebCraft provides the base contract and the fallback model
- addon authors can extend the frontend without replacing everything
- modpack creators decide how aggressive overrides should be

This is the most practical division of responsibility for shared server setups.

## Why This Model Is Recommended

This approach keeps the platform universal without forcing every server owner to become a frontend integrator.

It also keeps the runtime simpler because:

- the server can serve a ready-made frontend artifact
- the frontend does not need Node.js at runtime
- overrides stay explicit instead of accidental
- the default UI remains safe to update

## Short Version

- ship a built-in default frontend
- allow explicit overrides
- keep ownership visible
- make fallback deterministic
- let modpack creators own the final combination

