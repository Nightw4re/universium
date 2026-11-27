---
name: webcraft-exporter
description: Change or verify WebCraft inventory, catalog, Atlas, or Ponder export, including pack capture, archive compatibility, and the CurseForge launcher.
---

# WebCraft exporter

- The exporter is built into `:webcraft-core` via [exporter.gradle](../../../modules/webcraft/exporter/exporter.gradle). Use the [export guide](../../../modules/webcraft/exports.md) for capture, diagnostics and archive checks. For formats shared with consumers, use [catalog data](../../../modules/webcraft/catalog.md) and the [API guide](../../../modules/webcraft/api.md).
- For launcher changes, run `python -B modules/webcraft/exporter/test-launch-export.py` and use `npm run export:check -- "<instance path>"` when a pack is available. For Java changes, target the affected core exporter tests. When a change affects archive compatibility, verify the reader as well as the writer.
- Use a real game capture when the outcome depends on rendering. Select the smallest representative export or scene, verify it using the guide, and follow [export retention](../../../modules/webcraft/AGENTS.md#exports). For unrelated serve-side routes, use the API skill.
