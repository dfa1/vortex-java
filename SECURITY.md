# Security Policy

## Supported versions

vortex-java is pre-1.0: only the latest release gets security fixes. Fixes ship in a new release, not
as backports.

| Version        | Supported |
|:---------------|:----------|
| Latest release | Yes       |
| Older releases | No        |

## What counts as a vulnerability

The reader memory-maps and parses untrusted binary input, and every malformed file must throw
`VortexException` (see the security contract in [AGENTS.md](AGENTS.md)). These are security bugs:

- a malformed or hostile file that makes the reader throw anything else (`ArrayIndexOutOfBoundsException`,
  `NegativeArraySizeException`, `OutOfMemoryError`, `StackOverflowError`, …), hang, or read outside its
  mapped segment
- a file that makes the reader allocate far more memory than its size justifies

A file that is merely rejected with a `VortexException` is not. A flaw in the Vortex format or in the
Rust reference itself belongs upstream at
[vortex-data/vortex](https://github.com/vortex-data/vortex/security).

## Reporting a vulnerability

**Do not open a public issue.** Report it privately through GitHub:
[Security → Report a vulnerability](https://github.com/dfa1/vortex-java/security/advisories/new).

Please include:

- the vortex-java version, JDK version, and OS/arch
- what an attacker can do, and the minimal file or code that triggers it
- the exception or crash you observed

This is a single-maintainer project, so responses are best effort: expect an acknowledgment within a
week. Once a fix is released, the advisory is published with credit to the reporter unless you ask
otherwise.
