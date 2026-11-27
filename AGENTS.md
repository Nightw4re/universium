# Universium repository guidance

Universium targets Java 21, Minecraft 1.21.1 and NeoForge. Start with the [repository index](README.md). Apply the [WebCraft requirements](modules/webcraft/AGENTS.md) to WebCraft work from any directory.

## Git and scope

- Check the branch, tree and index first. Preserve staged and unstaged work. Use the primary checkout for routine work. For authorized parallel work, coordinate shared files, integrate in the target checkout and verify together.
- Review the working diff. Before committing, recheck the branch, stage task-owned paths or hunks, and review the staged diff. Report ambiguous overlap and leave it unstaged. Use the [commit format](AI.md#commit-format).
- Preserve history, branches and tags. Avoid destructive Git and automatic stashing. Routine fast-forward pushes to a verified Universium remote are allowed after branch and commit review. Get explicit authorization for releases, deployment or manual hosted CI.
- Get explicit authorization in the current conversation to edit other repositories. Patch third-party mods only when a bridge explicitly requires it. Preserve their assets and respect mod and server rules. Keep approved model and reasoning settings.

## Implementation and verification

- Keep NeoForge integration thin and delegate logic to module services. Preserve existing names and boundaries. Use the [coding conventions](AI.md#code-conventions) for implementation work.
- For Java or NeoForge, start with targeted Gradle tests from the root. Add deterministic tests for non-trivial logic before or with behavior changes. Broaden coverage for shared APIs, registration, configuration, resource delivery and cross-module work. Verify HTTP changes on the server and affected clients. Update affected contracts and examples.
- Build affected modules for packaging, runtime dependency, asset or build changes. For broad changes and releases, run applicable full suites and build shipping artifacts. For Markdown-only changes, check content, links and diff.
- Verify the integrated change once. Reuse valid results and coordinate jobs sharing resources. Report checks, failures and gaps, plus branch and commit when relevant. Where practical, reproduce suspected pre-existing failures on the starting state.

## Context, documentation and artifacts

- Search paths, headings and symbols first. Read relevant sections and reuse valid context. Limit output, poll at meaningful intervals and open detailed logs for diagnosis. Use existing automation and delegate only when authorized and useful.
- Inspect local changes directly. For architecture, dependencies or complex callers, use the smallest [Graphify scope](docs/graphify.md). Refresh when inputs changed or the index is missing or invalid. Confirm in source or inspect it directly if Graphify is unavailable.
- Use English for comments, docs and commits. Update authoritative references and their indexes. Label proposals and history. Keep private saves, raw player data, generated output and diagnostics out of Git. Apply [local workflow and artifact rules](AI.md#local-workflows-and-artifacts) for apps, exports and captures.
