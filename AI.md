# Universium development guide

[Repository guidance](AGENTS.md) defines the required scope, Git and verification policies. Use the sections here for coding, local operations and commits.

## Code conventions

- Prefer small, testable pure Java logic, immutable data models, and constructor injection or explicit wiring. Avoid oversized classes and hidden global state.
- Keep namespaces, mappings and APIs deterministic. Document public Java APIs with JavaDoc. Separate API, server, auth, permissions, security, frontend, config, module and test-support responsibilities where relevant. Follow the existing package layout.
- Prefer unit tests, then integration-style tests with temporary directories. Report checks that were skipped or selected no tests.

## Build and verification

- Use Gradle from the repository root. [settings.gradle](settings.gradle) lists the active projects, and module-local `gradle.properties` files own versioning. Keep module versions there.
- Use `gradle :<project>:test` for an affected module and `gradle :<project>:build` when its artifact is affected. Use `gradle test` for broad Java verification.
- Use `gradle runClient` or `gradle runServer` for WebCraft development. See [WebCraft](modules/webcraft/README.md) for settings. The [frontend guide](modules/webcraft/frontend/README.md) owns the preview and static bundle commands.
- Use existing repository entrypoints for the [preview exporter](modules/webcraft/exports.md#run-a-curseforge-export-from-the-command-line) and [Graphify](docs/graphify.md).

## Local workflows and artifacts

- Prefer headless or non-interactive checks. Run test apps in the background and launch helpers hidden. If Minecraft rendering or export needs OpenGL and a window, use a small isolated instance at the lowest practical resolution and keep it in the background if supported. Warn the user before taking foreground focus or taking over the desktop.
- Close task-owned temporary sessions. Preserve user sessions and shared preview servers. Report failures that leave a process or resource behind.
- Use the ignored `.local-artifacts/` directory for local logs, captures and verification reports. Keep one stable location for each recurring workflow's latest successful build, export or dataset. Temporary staging is fine during a run. Verify the replacement before removing the previous output and clean up staging and disposable logs or captures.
- Keep the last good output and at most one compact failure bundle. Remove the bundle after a successful rerun. Preserve active runs, user data, diagnostic evidence, and release or rollback material. The [WebCraft requirements](modules/webcraft/AGENTS.md#exports) cover export retention per pack configuration.

## Commit format

```text
<type>: <short description>

<explanation of what changed and why>
```

Use `feat`, `fix`, `refactor`, `chore`, `docs` or `test`. Keep the subject concise and specific. Omit `Co-Authored-By` trailers.

## Git remotes

See the [Git policy](AGENTS.md#git-and-scope). Review the workflow triggers for the branch or tag before a push.

## Hosted workflows

- [Test](.github/workflows/test.yml) checks Gradle projects, the frontend bundle and frontend tests for pull requests. [PR changelog](.github/workflows/pr-changelog.yml) posts a changelog preview.
- [Deploy](.github/workflows/deploy.yml) watches WebCraft module version changes on `main` and supports manual dispatch. The workflow defines the deployment targets and project selection.
- [Frontend release](.github/workflows/release.yml) bundles and publishes static frontend assets on version tags.
