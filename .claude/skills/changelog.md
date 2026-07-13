---
name: changelog
description: Write CHANGELOG.md entries for unreleased commits — terse, one-line bullets that link to the introducing commit(s), not paragraphs. Triggers on "prepare the changelog", "update CHANGELOG", "write changelog entries", or invokes `/changelog`. Standalone version of the `release` skill's "CHANGELOG sync" step — use this when you want entries written without cutting a release yet.
---

## Overview

Write CHANGELOG.md entries for commits that don't have one yet, under `## [Unreleased]`.
Entries are **terse** — a title, not a paragraph — ending in a link to the introducing
commit(s). The link is the receipt; anyone who wants the "why" and the numbers reads the
commit message (`git show <sha>` or the GitHub commit page), not the changelog.

## Steps

1. Find the range of commits missing a changelog entry: compare `## [Unreleased]` in
   `CHANGELOG.md` against `git log --oneline <last-documented-commit>..HEAD`. If unclear
   where the last-documented commit is, ask.
2. For each notable commit, write **one bullet**:
   - A single short sentence — what changed, fewest words that stay precise. No numbers,
     no "why", no before/after prose — those live in the commit message.
   - End with the commit SHA(s), GitHub-auto-linked:
     `([abc1234](https://github.com/dfa1/vortex-java/commit/abc1234))`
   - One logical change spanning multiple commits: comma-separate SHAs, newest first.
3. Categorize under the right `###` heading (create if missing): `Added` / `Changed` /
   `Fixed` / `Performance` / `Removed` / `Security` — Keep-a-Changelog order (Added,
   Changed, Deprecated, Removed, Fixed, Security), plus this project's `Performance`.
4. Skip pure-internal churn: CI/Sonar fixes, source-tree moves, dedup refactors,
   test/tooling-only commits (see [[feedback_changelog_terse]]) — omit rather than write a
   bullet nobody user-facing cares about.
5. Commit as `docs: CHANGELOG entries for <short description>`.

## Example

Good (terse — what to write):
```
- Utf8/Binary now use cost-based encoding selection instead of first-match dispatch. ([1bbc3549](https://github.com/dfa1/vortex-java/commit/1bbc3549))
- FsstEncodingDecoder no longer throws on valid files with empty FSST metadata. ([1bbc3549](https://github.com/dfa1/vortex-java/commit/1bbc3549))
```

Bad (too verbose — do NOT write this):
```
- `CascadingCompressor` now runs `Utf8`/`Binary` columns through the same sample-and-measure
  encoding competition `Primitive` columns already get, instead of first-match dispatch.
  `DictEncodingEncoder` is registered ahead of `FsstEncodingEncoder`, so Dict always won
  regardless of actual output size — confirmed by inspecting a 50k-row, high-cardinality
  string file and finding `vortex.dict` where `vortex.fsst` is ~40% smaller. On
  `highCardinalityUtf8_javaVsJni` (50k distinct 6-byte strings) this drops the Java/JNI
  file-size ratio from 2.26× to 1.36× (801,908 → 483,336 bytes). ([1bbc3549](...))
```
The numbers and rationale belong in the commit message, not duplicated in the changelog.

## Rules

- Never invent entries for commits that don't exist — the link is the receipt.
- Never leave a bullet without a commit link.
- One sentence per bullet. If it needs two sentences, split it into two bullets, or the
  extra detail belongs in the commit message instead.
- Skip internal-only commits (matches the `release` skill's CHANGELOG sync policy).

## When NOT to use

- Finalizing a release's version header/date, or the compare link at the bottom of the
  file — that's the `release` skill's job (its CHANGELOG sync step supersedes this one).
- Writing release notes for work that isn't committed yet.
