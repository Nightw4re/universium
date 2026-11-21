# Universium repository guidance

Universium is a Minecraft mod monorepo targeting Java 21, Minecraft 1.21.1 and NeoForge. Read [AI.md](AI.md) for the existing architecture, code and commit conventions. The [repository README](README.md) describes the project layout. The [WebCraft guide](modules/webcraft/README.md) covers active contracts and runtime configuration. The [frontend guide](modules/webcraft/frontend/README.md) covers the independent web client. Historical Origin material lives in [the archive](archive/webcraft-origin/README.md).

## Shared checkout and Git

- Check the current branch, working tree and index before editing. Preserve existing staged and unstaged work. Inspect the branch again before committing.
- Use the primary checkout for routine work and keep related changes together. When authorized parallel work needs isolation, coordinate ownership of shared files, integrate the changes into the intended checkout and verify the combined result before handing it back.
- Review the working diff. If committing, stage explicit task-owned files or hunks and review the staged diff. Leave overlapping changes unstaged when ownership cannot be determined reliably and report the overlap.
- Preserve existing history, branches and release tags. Avoid destructive Git operations and automatic stashing in a shared checkout. Follow the [Git remotes policy](AI.md#git-remotes) for routine pushes, releases, deployments and hosted CI.

## Verification

- Use Gradle from the repository root for Java and NeoForge verification. Start with the affected Gradle projects and targeted regression tests. Broaden coverage for shared APIs, module registration, runtime configuration, resource delivery and changes that span multiple modules. Verify HTTP contract changes on the server and in affected clients.
- Run the affected module build when changes can affect packaging, runtime dependencies, assets or build configuration. For broad changes and release preparation, run the applicable full suites and build the artifacts that will ship. Review the content, links and diff for Markdown-only changes.
- Verify the integrated change once. Reuse passing results while their relevant inputs stay the same. Coordinate heavy jobs that contend for the same ports, files or resources.
- Report which checks ran, what failed and any meaningful gaps. If a failure may predate the task, reproduce it against the starting state when practical. An empty selection, a skipped check or a partial run does not establish full verification. Include the branch and commit if relevant to the handoff.

## Documentation and local artifacts

- Keep new and updated project guidance in English. Update the relevant documentation index when adding, moving or removing indexed documentation. Label historical reports so readers can distinguish them from current guidance.
- Keep generated build output, private saves, raw player data and local diagnostics out of Git. Store ad hoc logs, captures and verification reports in the ignored `.local-artifacts/` directory. Preserve existing saves and diagnostic evidence when tidying files.
- Prefer headless verification and hidden helper processes. Track and close task-owned temporary sessions after use. Preserve user sessions and shared preview servers. Report cleanup failures if they leave a process or resource behind.
