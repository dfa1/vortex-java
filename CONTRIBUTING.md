# Contributing to vortex-java

Thanks for your interest. This page is the short version for newcomers; the conventions humans and
agents both follow (build commands, module map, code style, adding an encoding, testing, Javadoc,
documentation) live in [AGENTS.md](AGENTS.md).

## Questions, issues, and where to start

- **Questions and ideas:** [GitHub Discussions](https://github.com/dfa1/vortex-java/discussions).
- **Bugs:** open an issue with a minimal reproduction; a Vortex file that fails to read is ideal.
  Security issues go through [SECURITY.md](SECURITY.md), never a public issue.
- **Looking for something to work on:** issues labeled
  [`good first issue`](https://github.com/dfa1/vortex-java/labels/good%20first%20issue) are
  self-contained and a good way to learn the codebase;
  [`help wanted`](https://github.com/dfa1/vortex-java/labels/help%20wanted) marks work the maintainer
  would welcome a contribution for. Comment on the issue before starting so nobody duplicates effort.
  Larger items link their design ADR from the issue body — read it first.

## Build

Java 25+ and the bundled Maven wrapper; nothing else is needed (generated sources are committed).

```bash
./mvnw verify    # build, unit + integration tests, Javadoc; CI runs it on Java 25 and 26
```

## Making a change

1. Fork and create a branch off `main`; keep commits small, `main` must always be green.
2. Include tests, including a cross-check against vortex-jni for fixes and features.
3. Update the docs your change touches (see AGENTS.md).
4. Open a pull request; merges are rebase-only, so no merge commits.

Open an issue first for anything larger than a bug fix or a small feature: it saves time if the
design needs discussion.
