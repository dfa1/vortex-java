---
name: release
description: Cut a vortex-java release. Runs preflight checks, extracts the CHANGELOG section for the target version, drives `mvn release:prepare` to tag, pushes the tag (which triggers Maven Central deploy via GitHub Actions), and creates the matching GitHub release with notes pulled from CHANGELOG.md. Triggers when the user says "release", "cut a release", "tag a release", "release X.Y.Z", or invokes `/release`.
---

## Overview

This skill cuts a release end-to-end. It is **interactive at the gates** — it stops and confirms before any push or any action that touches origin or GitHub. The release process has three irreversible steps; each one needs explicit user OK:

1. `git push` (release commits + tag)
2. `gh release create` (publishes notes on GitHub)
3. Waiting on Maven Central — nothing to do here, but monitor the workflow run.

Never run with `--skip-confirm` or equivalent shortcuts.

## Inputs

Arguments to `/release`:

- `<releaseVersion>` — the version to cut (e.g. `0.4.0`). Required.
- `<developmentVersion>` — the next snapshot (e.g. `0.5.0-SNAPSHOT`). Optional; default is to bump the minor digit of `releaseVersion` and append `-SNAPSHOT`.

If neither is supplied, ask the user.

## Steps

### 1. Preflight

Refuse to proceed unless **all** of the following hold. Report any failure and stop.

- Current branch is `main`.
- Working tree is clean: `git status --short` is empty.
- `main` is up to date with `origin/main`: `git fetch origin && git rev-list --count main..origin/main` is 0 and the reverse direction is 0.
- The full build is green: `./mvnw verify`. **Never `mvn install`** per CLAUDE.md.
- `gh auth status` succeeds (the GitHub release step needs auth).
- The release version is not already a tag: `git tag -l "v<releaseVersion>"` is empty.

### 2. CHANGELOG sync

The `CHANGELOG.md` file must contain a section for the release version. Two cases:

- **Section exists** (e.g. `## [0.4.0] — Unreleased`): rewrite the header to add today's date, e.g. `## [0.4.0] — 2026-06-07`.
- **Section missing**: stop and tell the user to write the section first. Do not invent release notes from `git log`.

Update the compare link at the bottom of the file from `compare/vPREV...main` to `compare/vPREV...v<releaseVersion>`. The previous version is whatever the second-most-recent `## [x.y.z]` heading shows.

**Each bullet must end with the introducing commit SHA(s) in parens.** GitHub auto-links bare 7+ char SHAs in CHANGELOG.md and release bodies. Use `git log --oneline vPREV..HEAD` and grep for the relevant subjects to find each SHA. If a bullet spans multiple commits, list them comma-separated (newest first).

```
- Layout-tree depth capped at 64; metadata capped at 4 MiB. (a1b2c3d)
- ScanResult → renamed Chunk (scan.ScanResult → scan.Chunk). (e4f5a67, b8c9d0e)
```

If a bullet's SHA can't be determined (e.g. cross-cutting work touched in many commits), use the compare-range tag: `(vPREV...v<releaseVersion>)`. Do not leave a bullet unlinked — the link is the receipt that the entry came from real work, not a hallucination.

Commit the changelog edits as `docs(changelog): finalize <releaseVersion> section` before invoking `mvn release:prepare` so the release commit doesn't pick up unrelated drift.

### 3. Tag via `mvn release:prepare`

Use the exact command from CLAUDE.md:

```bash
./mvnw --batch-mode release:clean release:prepare \
    -DreleaseVersion=<releaseVersion> \
    -DdevelopmentVersion=<developmentVersion>
```

This creates two commits (release + next-snapshot) and a tag `v<releaseVersion>` locally. Do **not** add `release:perform` — Maven Central is handled by the GitHub Actions workflow that fires on tag push.

If `release:prepare` fails partway through (e.g. on hook failure), run `./mvnw release:rollback` before retrying.

### 4. Push

Show the user the two new commits and the tag, then confirm:

```bash
git log --oneline @{u}..HEAD
git tag -l "v<releaseVersion>"
```

On explicit OK only:

```bash
git push && git push --tags
```

Once the tag is on origin, the deploy workflow starts. Capture the run URL for the next step.

### 5. GitHub release

Extract the version's section from `CHANGELOG.md` into a temp file. The `awk` block stops at the next `## [` heading so the body contains exactly one version's notes:

```bash
awk -v ver="<releaseVersion>" '
  $0 ~ "^## \\[" ver "\\]" { in_sec=1; next }
  in_sec && /^## \[/ { exit }
  in_sec { print }
' CHANGELOG.md > /tmp/release-notes-<releaseVersion>.md
```

**Condense before publishing.** CHANGELOG entries carry full context (attack details, rationale, file refs); the GitHub release body must stay scannable — aim ~30 lines, ~one line per bullet. Rewrite the extracted file in place, applying these rules:

- **First line is the headline.** One sentence stating the technical themes. No `The headline themes for this release are…`. Replace the opening paragraph with a single technical sentence.
  - Good: `Security-hardening sweep of the parser, Array interface slimmed, cascading writer features.`
  - Bad: `The headline themes for this release are a security-hardening sweep of the file-format parser…`
- **Sections in this order, omit empty ones:** `Security`, `Added`, `Breaking`, `Removed`, `Performance`, `Fixed`, `Build`. Use `Breaking` (not `Changed`) for source/binary-breaking changes — readers scan for it.
- **One line per bullet.** No sub-bullets. If a bullet needs two sentences, the detail belongs in CHANGELOG, not GH release.
  - Good: `Layout-tree depth capped at 64; metadata capped at 4 MiB.`
  - Bad: `Layout-tree depth cap — PostscriptParser.convertLayout is capped at depth 64, preventing both unbounded nesting and self-referential FlatBuffer cycles (a ~120-byte cycle attack previously triggered StackOverflowError).`
- **Drop `see docs/X.md` / `documented in …` references.** Readers click compare diffs, not doc cross-refs.
- **Drop benchmark prose.** Keep the number, drop the surrounding sentence.
  - Good: `ALP + Dict broadcast modulo gated by cap == n check (~5–10× recovery).`
- **Migration hints stay terse.** `old → new`, one line. e.g. `ScanResult → renamed Chunk (scan.ScanResult → scan.Chunk).`
- **Preserve trailing commit SHAs.** Each bullet inherits `(sha)` / `(sha, sha)` from CHANGELOG; do not strip them. GitHub auto-links bare 7+ char SHAs in release bodies.
- **Footer:** keep the compare link only: `[<version>]: https://github.com/<owner>/<repo>/compare/v<prev>...v<version>`.

CHANGELOG stays long-form; do not force the two to match.

Then create the release:

```bash
gh release create v<releaseVersion> \
    --title "v<releaseVersion>" \
    --notes-file /tmp/release-notes-<releaseVersion>.md
```

Do **not** pass `--draft` unless the user asked for one.

### 6. Monitor the deploy

Tail the workflow run kicked off by the tag push. Report success or surface failures:

```bash
gh run list --workflow=release.yml --limit 1
gh run watch <run-id>
```

If the Maven Central step fails, do **not** delete the tag. Stop and report the failure — re-publishing the same version requires hand intervention via Sonatype.

### 7. Post-release cleanup

After the workflow completes successfully:

- Verify the artifact is on Maven Central:
  `curl -fsSI https://repo1.maven.org/maven2/io/github/dfa1/vortex/vortex-bom/<releaseVersion>/vortex-bom-<releaseVersion>.pom | head -1`
  (may take 10–30 minutes after the workflow completes; not a hard gate).
- Open `CHANGELOG.md` and add a fresh `## [<developmentVersion-without-snapshot>] — Unreleased` section at the top with empty `### Added` / `### Changed` / `### Fixed` placeholders for the next cycle.
- Commit as `docs(changelog): open <next-version> section` and push.

## Rules

- **Never** force-push or delete tags. If a release is broken, cut a new patch version.
- **Never** run `mvn install`. Always `./mvnw verify` for builds, `./mvnw --batch-mode release:prepare` for tagging.
- **Never** skip Maven hooks (`--no-verify`, `--no-gpg-sign`). Signing is required for Maven Central.
- **Never** invent release notes — the CHANGELOG entry is the contract with users.
- **Always** confirm with the user before each of: `git push`, `gh release create`, and any retry of `release:prepare` after a failure.
- **Stop on first failure** in steps 1–4. Steps 5–7 may continue with manual recovery instructions if something goes sideways.

## When NOT to use

- Hot-patching an already-released artifact — cut a new patch version instead.
- Releasing from a branch other than `main`.
- Releasing without a CHANGELOG entry.
