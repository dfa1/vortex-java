# Security Policy

`vortex-java` reads and writes the [Vortex columnar file format](https://github.com/vortex-data/vortex).
The reader memory-maps and parses untrusted binary input — trailers, FlatBuffers, proto3
metadata (via the in-tree MemorySegment-native `ProtoReader` — no `protobuf-java` runtime),
and per-segment encoded data. Robustness against malformed input is treated as a correctness
contract, not a best-effort feature.

## Reporting a vulnerability

**Please do not open a public GitHub issue for security problems.**

Use GitHub's private vulnerability reporting:

1. Open <https://github.com/dfa1/vortex-java/security/advisories/new>.
2. Fill in the form. Include a minimal reproduction (a `.vortex` file or the bytes that
   trigger the issue) where possible.
3. You'll receive an acknowledgment within **3 business days**.

If GitHub's reporting flow is unavailable, email the maintainer at the address on the project's
Maven Central metadata.

## What we'll do

- **Triage within 3 business days** — confirm the report and classify severity.
- **Fix or coordinated mitigation within 30 days** for high and critical findings.
- **Coordinated disclosure** — we'll work with you on a public disclosure date once a fix is
  ready or a workaround is documented.
- **Credit** — if you'd like, your name (or a handle) is mentioned in the release notes and
  in any GitHub Security Advisory.

## Scope

In scope:

- Any malformed `.vortex` input that causes the reader to throw an exception other than
  `io.github.dfa1.vortex.core.error.VortexException` (e.g. `IndexOutOfBoundsException`,
  `NegativeArraySizeException`, `OutOfMemoryError`, `StackOverflowError`, raw FlatBuffer
  runtime exceptions, raw `IOException` from the proto3 reader, or a JVM crash via the FFM layer).
- Any malformed `.vortex` input that causes the reader to allocate memory disproportionate
  to its on-disk size (zip-bomb-style amplification).
- Any malformed `.vortex` input that causes silent data corruption — wrong row count,
  wrong values, or a misaligned column with a successful return.
- Any vulnerability in `VortexWriter` that produces files which would later trigger the
  above behaviors when read.

Out of scope:

- Denial of service from legitimately large inputs (multi-gigabyte files). Use the
  resource caps in `ReadOptions` (planned) to bound them.
- Vulnerabilities in third-party dependencies (`vortex-jni`, `zstd-jni`, FlatBuffers runtime).
  Report those upstream; we'll bump the dependency once a fixed version is available.
  Vortex no longer depends on `protobuf-java` — proto3 parsing is handled by the in-tree
  `ProtoReader` (issues there are in scope).
- Performance regressions or correctness bugs unrelated to malformed input — please open
  a regular issue.

## Defensive guarantees in 0.4.0+

The reader contract: **every malformed input throws `VortexException`, never an unchecked JDK
exception**. Concretely:

- Trailer fields (version, magic, postscript length) are validated up front.
- Postscript blob offsets (footer / layout / dtype) are bounds-checked against file size.
- Footer `segmentSpec` offsets and lengths are bounds-checked.
- Layout-tree recursion is capped at depth 64 (rejects deeply nested layouts and
  self-referential FlatBuffer cycles).
- Layout metadata is capped at 4 MiB.
- `Decimal` precision is restricted to `[1, 38]`; `scale` to `[0, precision]`.
- `PType` ordinals from proto3 are bounds-checked.
- `ConstantEncoding` and dict-layout decode allocate `O(1)` memory regardless of the
  declared row count (zip-bomb mitigation).
- `ProtoReader` enforces varint length ≤ 10 bytes, rejects truncated len-delim regions,
  and validates segment bounds on every read. (0.6.0+ — replaces the `protobuf-java`
  parser path; same exception contract.)

The regression suite lives under `reader/src/test/java/.../*SecurityTest`. Run with
`./mvnw test -Dtest='*SecurityTest'`.

Open hardening work is tracked in `TODO.md` under `## Security`, including resource caps,
per-encoding adversarial tests, and a planned Jazzer fuzz harness.
