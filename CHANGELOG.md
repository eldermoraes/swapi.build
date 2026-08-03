# Changelog

All notable changes to swapi.build are documented here.

The format is based on [Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Versions up to and including 2.1.0 were reconstructed retroactively from git history
in August 2026 — the project had no changelog and no tags before then. Each version's
entry covers the commits between its tag and the previous one, so a change appears
under the version whose line of development contains it. Where a version bump
*declared* something implemented in the previous line, the entry says so.

The version served in [`/openapi.json`](https://swapi.build/openapi.json) (`info.version`)
is inherited from `swapi-app/pom.xml`, so it always matches the latest released version.

## [Unreleased]

### Added

- `CHANGELOG.md`, reconstructed retroactively down to 1.1.
- `docs/RELEASE.md`: the release process — version bump, changelog entry, tag,
  GitHub Release, deploy.
- Retroactive git tags `v1.1` … `v2.1.0` and matching GitHub Releases.
- Tests keeping the pom version, the changelog and the published OpenAPI version in sync.

## [2.1.0] - 2026-08-03

### Changed

- MCP serves stateful and stateless clients on the same `/mcp` endpoint. An unknown
  or missing `Mcp-Session-Id` is now served with a throwaway session instead of
  `404` (`quarkus.mcp.server.http.streamable.auto-init=true`), which is what makes
  stateful clients reliable on a platform without session affinity. `GET /mcp`
  answers `405`, `DELETE /mcp` answers `204`, and the legacy HTTP+SSE transport at
  `/mcp/sse` is rejected on purpose.
- The site presents swapi.build as both a REST API and an MCP server.

[Unreleased]: https://github.com/eldermoraes/swapi.build/compare/v2.1.0...HEAD
[2.1.0]: https://github.com/eldermoraes/swapi.build/compare/v2.0.2...v2.1.0
