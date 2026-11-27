# Documentation maintenance

Use the [repository index](../README.md) for current guidance. Keep repository-wide requirements in [AGENTS.md](../AGENTS.md), directory-specific requirements in the nearest relevant instruction file, and repeatable task workflows in [repository skills](../.agents/skills/). Use a module's index to find its current contracts, operator guides and research.

- Update the existing reference when behavior or an approved requirement changes. Link to source-owned values and schemas instead of copying them across guides. If implementation and an approved requirement disagree, record the discrepancy and its scope.
- Mark proposed designs and experiments with their status. Record consequential decisions and preserve non-reproducible evidence. Keep routine verification output in the local artifact directory. Link historical material through the index below.
- When splitting a guide, update direct links and preserve entrypoints used by integrations, published docs and build tasks. `gradle buildDocs` collects Markdown from `docs/` and each module's `docs/` folder. Module landing pages and adjacent guides are read in the checkout.
- On documentation changes, check touched relative links and anchors, paths used by automation, Markdown diff, and any affected examples against source. When changing agent guidance, check applicable `AGENTS.md` paths, UTF-8 byte totals and skill manifests against the current Codex configuration.

## Repository skills

Codex discovers the [repository skills](../.agents/skills/) from the root of the checkout. Open the one that matches the task.

- [WebCraft API](../.agents/skills/webcraft-api/SKILL.md) covers routes, registration, transport and client contract verification.
- [WebCraft exporter](../.agents/skills/webcraft-exporter/SKILL.md) covers pack capture, archive compatibility and export validation.

## Historical references

- The original bridge [architecture](README_Universium_Web_Bridge.md) and [development plan](PLAN_Universium_Web_Bridge.md) preserve proposals for generic sessions, roles and hosted frontends. Use the [WebCraft index](../modules/webcraft/README.md) for current behavior.
- The [Marketplace prototype](../archive/webcraft-marketplace/README.md) preserves an earlier add-on implementation outside the active Gradle build.

## Measuring context changes

Compare fixes to the documentation against the same source snapshot. Record starting directory, active instruction bytes and model settings. Use a fresh chat per trial with the same prompt, model, reasoning effort and tool access. Keep cold and warm cache trials identifiable.

1. Trace discovery and a live subscription through WebCraft's server and reference client. Identify the authorization and the tests that cover it.
2. Locate the Atlas exporter compatibility rules and the smallest archive or launcher check for a change.

Score each task for correct entrypoints, constraints and verification scope. Count documentation reads and tool calls. If telemetry is available, record input, cached input, output and reasoning tokens separately, and note when cached or reasoning tokens are subsets of reported totals. Report these alongside elapsed time and task correctness. Use the same tokenizer for both revisions when comparing static instruction text. Keep trial data under `.local-artifacts/`.
