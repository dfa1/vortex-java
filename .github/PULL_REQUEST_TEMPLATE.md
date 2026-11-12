## What and why

<!-- What this changes and why. "Closes #NNN" if it fixes an issue. -->

## Checklist

See [CONTRIBUTING.md](https://github.com/dfa1/vortex-java/blob/main/CONTRIBUTING.md) and [AGENTS.md](https://github.com/dfa1/vortex-java/blob/main/AGENTS.md) for details.

- [ ] Behavior matches the Rust reference (what is written, what is accepted, defaults, errors); any deliberate divergence is recorded where it applies
- [ ] Tests use JUnit 5 + AssertJ + BDDMockito, class under test named `sut`, and every test follows `// Given` / `// When` / `// Then`
- [ ] Fix or feature has a cross-check test against vortex-jni (vortex-jni writes and Java reads, or the reverse), written first so it fails without the change
- [ ] `./mvnw verify` passes (includes failsafe integration tests and Javadoc, which fails the build on any error or warning)
- [ ] Documentation updated in the same PR when public API, module structure, wire behavior or policy changes (`docs/reference.md`, `docs/compatibility.md`, `AGENTS.md`)
