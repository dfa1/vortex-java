# ADR 0003: `VortexException` contract — message sanitization and bounds typing

- **Status:** Accepted — implementation pending (see Phases below)
- **Date:** 2026-06-13 (bounds-typing scope added 2026-06-20)
- **Deciders:** project maintainer
- **Related:** [ADR 0001 — Split read and write runtimes](0001-split-read-and-write-runtimes.md),
  [ADR 0004 — Resource caps and `ReadOptions`](0004-resource-caps-read-options.md),
  [SECURITY.md](../../SECURITY.md)

## Context

`VortexException` is thrown at ~233 sites across the reader, writer, and
extension modules. ~10–15 of those sites interpolate **attacker-controlled
strings** derived directly from parsed file bytes into the exception message:

| Site | Attacker-controlled field |
|------|--------------------------|
| `ScanIterator.decodeLayout` | `layout.encodingId()` — string from `layoutSpecs` flatbuffer |
| `ScanIterator.decodeDictLayout` | same |
| `Chunk.column` | struct field name from `Footer` |
| `ExtensionStorage` | `ext.extensionId()` — string from `DType.Extension` flatbuffer |
| `ReadRegistry.Builder.register` | `decoder.encodingId()` |

A crafted Vortex file can embed ANSI escape sequences, newline characters,
null bytes, right-to-left override codepoints, or kilobyte-scale blobs in
these fields. When the exception message is:

- **logged** (Log4j, Logback, java.util.logging) — newline injection splits
  one log entry into two, allowing a crafted second "line" to look like a
  legitimate log event
- **printed to a terminal** — ANSI escapes produce visual spoofing or
  terminal state corruption
- **captured in a metrics label** (Micrometer, Prometheus) — a 1 MB blob in
  a metric tag causes allocation pressure and potential OOM in the metrics
  pipeline
- **sent to an error-aggregation service** (Sentry, Datadog) — raw binary
  corrupts the event payload

The existing `VortexException(EncodingId, String)` constructor requires a
typed `EncodingId` for the attribution field but accepts a free-form `String`
for the message body, which callers build via `+` or `.formatted()`. There is
no sanitization contract.

### Second axis — exception *type*, not just message

Message sanitization governs *what a `VortexException` says*. A separate,
orthogonal gap governs *whether a `VortexException` is thrown at all*. The
reader's contract (SECURITY.md) is: any malformed input throws
`VortexException`, never a raw JDK exception. But ~21 `MemorySegment.asSlice`
call sites take offsets/lengths straight from untrusted layout/footer
metadata and pass them to the JDK unguarded:

```java
// ScanIterator — fbStart/fbLen decoded from an attacker FlatBuffer
ByteBuffer fbBuf = seg.asSlice(fbStart, fbLen).asByteBuffer()...   // raw IndexOutOfBoundsException on overflow
```

Only `PostscriptParser` guards its slices (a private `slice()` +
`checkBlobBounds()`); its own comment warns that every *other* scan-time
`asSlice` would throw `IndexOutOfBoundsException` and break the contract. The
same leak appears in three more shapes:

- `Math.toIntExact(storage.length())` (4 extension decoders) → raw
  `ArithmeticException` on a > 2 GB declared length.
- `new byte[(int)(end - start)]` / `new long[(int) rowCount]` (VarBin, AlpRd,
  Delta) → `NegativeArraySizeException` / `OutOfMemoryError` on crafted
  non-monotonic offsets or huge counts.
- `ByteBuffer` is `int`-indexed (2 GB cap); a slice fed to `asByteBuffer()`
  past that throws raw too.

These are the same `VortexException`-contract violation as a leaked ANSI
escape — just on the type axis instead of the content axis — so they belong
in the same ADR.

## Decision

**Pick Option A (enum error catalog) as the structural shape.** Add a
`Sanitize` helper as the shared sanitization primitive. Deprecate the
raw-`String` constructors and migrate attacker-controlled sites first, then
the safe sites mechanically.

### Why Option A over Option B

Option B (sealed exception hierarchy, ~600 LOC across many leaf classes) has
compile-time arg checking and pattern-destructuring in `catch`. But:

- The project already uses enums as stable identifiers for all domain
  primitives: `EncodingId`, `ExtensionId`, `PType`, `TimeUnit`. An enum
  catalog is idiomatic here.
- ~233 migration sites want a mechanical 1-line pattern:
  `throw new VortexException(VortexError.FOO, arg)`. A new file per error
  class raises the per-error contribution cost during the mass migration.
- Compile-time arg-count checking (Option B's main win) is marginal for a
  `RuntimeException` hierarchy used exclusively inside decoder logic — callers
  do not catch and destructure these exceptions; they propagate to the user.
- Stable `enum.name()` codes are superior to `getClass().getSimpleName()` for
  log-based alerting and metric cardinality control. The monitoring team can
  write a rule against `UNKNOWN_LAYOUT_ENCODING` without worrying about
  package renames.

Option B is recorded in §"Alternative considered" for future reference.

### The `Sanitize` helper

A package-private class in `io.github.dfa1.vortex.core` (shared by both
reader and writer via the `core` dependency):

```java
// package-private — not part of the public API
final class Sanitize {
    static final int MAX_LEN = 128;

    /** Length-cap + hex-escape non-printable ASCII. Null-safe. */
    static String safe(String raw) {
        if (raw == null) {
            return "<null>";
        }
        var sb = new StringBuilder(Math.min(raw.length(), MAX_LEN + 8));
        int written = 0;
        for (int i = 0; i < raw.length() && written < MAX_LEN; i++, written++) {
            char c = raw.charAt(i);
            if (c >= 0x20 && c <= 0x7e) {
                sb.append(c);
            } else {
                sb.append(String.format("\\x%02x", (int) c));
                written += 3; // \xHH counts as 4 chars
            }
        }
        if (raw.length() > written) {
            sb.append("[…]");
        }
        return sb.toString();
    }
}
```

Contract:
- Output is always printable ASCII (no ANSI escapes, no newlines, no null bytes).
- Output is bounded at ~128 characters + `[…]` marker.
- Pure ASCII input in the printable range is returned unchanged (zero overhead
  for the common case of internal non-attacker-controlled strings).

### The `VortexError` enum catalog

```java
public enum VortexError {
    // File structure
    FILE_TOO_SMALL,
    MISSING_MAGIC,
    BAD_MAGIC,
    POSTSCRIPT_TOO_LARGE,
    MISSING_LAYOUT_SEGMENT,
    MALFORMED_POSTSCRIPT,
    MALFORMED_FOOTER,

    // Layout traversal
    UNKNOWN_LAYOUT_ENCODING,   // attacker-controlled: sanitize arg
    UNSUPPORTED_PTYPE_FOR_LAYOUT,
    NO_FLAT_CHILDREN,
    LAYOUT_DEPTH_BOMB,
    SEGMENT_INDEX_OUT_OF_RANGE,

    // Encoding / decoding
    UNKNOWN_ENCODING,          // attacker-controlled: sanitize arg
    UNSUPPORTED_DTYPE,
    MISSING_BUFFER,
    WRONG_BUFFER_SIZE,
    MISSING_METADATA,
    MALFORMED_METADATA,

    // Extension types
    UNKNOWN_EXTENSION,         // attacker-controlled: sanitize arg
    MISSING_EXTENSION_METADATA,
    MALFORMED_EXTENSION_METADATA,

    // Schema / field access
    UNKNOWN_COLUMN,            // attacker-controlled: sanitize arg
    COLUMN_TYPE_MISMATCH,      // attacker-controlled: sanitize field name arg
    DUPLICATE_DECODER,
    DUPLICATE_ENCODER,

    // Write side
    MISSING_COLUMN_DATA,
    NULL_IN_NON_NULLABLE_COLUMN,
    UNSUPPORTED_ARRAY_TYPE,

    // Internal / assertion
    INTERNAL;

    /**
     * Stable string code suitable for metric labels and structured logs.
     * Equal to {@code this.name()} — recorded explicitly so callers do not
     * rely on {@code name()} staying lowercase/camelCase in future.
     *
     * @return the error code string
     */
    public String code() {
        return name();
    }
}
```

The new `VortexException` constructor:

```java
public VortexException(VortexError error, Object... args) {
    super(render(error, args));
    this.error = error;
}

public VortexError error() { return error; }

private static String render(VortexError error, Object[] args) {
    // All String args are sanitized; non-String args use toString() directly
    // (EncodingId, PType etc. are trusted internal types).
    var sb = new StringBuilder("[").append(error.code()).append("] ");
    for (Object arg : args) {
        sb.append(arg instanceof String s ? Sanitize.safe(s) : arg);
        sb.append(' ');
    }
    return sb.toString().stripTrailing();
}
```

Call-site pattern:

```java
// before
throw new VortexException("cannot decode layout " + layout.encodingId());

// after
throw new VortexException(VortexError.UNKNOWN_LAYOUT_ENCODING, layout.encodingId());
```

The message format `[UNKNOWN_LAYOUT_ENCODING] vortex.flat\x0a<injected>` is
machine-parseable, log-friendly, and injection-safe.

### Bounds typing: the `IoBounds` helper

A public static utility in `io.github.dfa1.vortex.core` (must be reachable by
core itself — `ProtoReader` has a site — plus reader, `reader.array`, and
`reader.decode`; `reader → core`, so core is the only home that covers all
layers). It wraps the untrusted-offset operations and throws `VortexException`
(via the `VortexError` catalog above) instead of the raw JDK exception:

```java
public final class IoBounds {
    private IoBounds() {}

    /// off/len must lie within [0, size]. Throws VortexException otherwise.
    public static void checkRange(long off, long len, long size) {
        if (off < 0 || len < 0 || len > size - off) {
            throw new VortexException(VortexError.SEGMENT_INDEX_OUT_OF_RANGE, off, len, size);
        }
    }

    /// Bounds-checked asSlice — the canonical replacement for raw seg.asSlice.
    public static MemorySegment slice(MemorySegment seg, long off, long len) {
        checkRange(off, len, seg.byteSize());
        return seg.asSlice(off, len);
    }

    /// long → int for sizes/counts that index a ByteBuffer or back a Java array.
    /// Replaces Math.toIntExact (ArithmeticException) and guards the 2 GB cap.
    public static int toIntSize(long n) {
        if (n < 0 || n > Integer.MAX_VALUE) {
            throw new VortexException(VortexError.SEGMENT_INDEX_OUT_OF_RANGE, n);
        }
        return (int) n;
    }

    /// Element count for a `new T[n]` decode buffer; same guard as toIntSize,
    /// named for the alloc-count call sites (the per-encoding cap from ADR 0004
    /// plugs in here later).
    public static int checkCount(long n) {
        return toIntSize(n);
    }
}
```

Why a static helper, not a `BoundedSegment` wrapper (the approach explored in
[PR #27](https://github.com/dfa1/vortex-java/pull/27)):

- The hot path is `MemorySegment` zero-copy slices; a wrapper type would have
  to be unwrapped at every typed accessor or it taxes per-element reads. A
  static call slices once, off the per-element path, and returns a plain
  `MemorySegment` — no new type crosses module boundaries.
- It mirrors the `Sanitize` decision: one small pure primitive in `core`, not
  a new abstraction. `Sanitize` cleans the message; `IoBounds` types the
  throw. Symmetric.

#### The consumer-access carve-out

The ~14 per-element guards in `Lazy*` / `Materialized*` / `Generic` accessors
(`getInt(i)` etc.) throw `IndexOutOfBoundsException` and **stay that way** —
they are *consumer* random-access (`array.getInt(5)`), where IOOBE is the
correct JDK-idiomatic signal (cf. `List.get`), not a malformed-file event.
These must **not** be routed through `IoBounds`. They are instead collapsed
onto the JDK built-in `Objects.checkIndex(i, length)` (Java 16+) — stdlib, no
custom helper. The dividing line:

- offset/length/count from **parsed file bytes** → `IoBounds` → `VortexException`
- index from a **caller's accessor argument** → `Objects.checkIndex` → `IndexOutOfBoundsException`

## Migration phases

### Phase A — Foundation (~1.5 h)
- Add `Sanitize` with unit tests covering: ANSI escapes, embedded newlines,
  null bytes, RTL-override (`‮`), 200-char oversize input, pure-ASCII
  passthrough, null input.
- Add `VortexError` enum (~30 constants covering errors used by
  security-sensitive sites and recent security commits).
- Add `VortexException(VortexError, Object...)` constructor; keep the
  existing `String` constructors but mark `@Deprecated(forRemoval = true)`.
- Add `VortexException.error()` accessor; `Optional.empty()` when
  constructed via the deprecated path.

### Phase B — Migrate attacker-controlled sites (~1 h)
Migrate the ~10–15 sites that interpolate parsed-file strings. These are the
security-critical sites:

| File | Old message | New call |
|------|-------------|----------|
| `ScanIterator` | `"cannot decode layout " + layout.encodingId()` | `UNKNOWN_LAYOUT_ENCODING, layout.encodingId()` |
| `ScanIterator` | `"layout: unsupported ptype " + ptype` | `UNSUPPORTED_PTYPE_FOR_LAYOUT, ptype` |
| `Chunk` | `"column '" + name + "' decodes to …"` | `COLUMN_TYPE_MISMATCH, name, …` |
| `ExtensionStorage` | `"missing TimeUnit metadata byte for " + ext.extensionId()` | `MISSING_EXTENSION_METADATA, ext.extensionId()` |
| `ReadRegistry` | `"decoder %s already registered"…` | `DUPLICATE_DECODER, decoder.encodingId()` |

Add `MessageSanitizationSecurityTest` asserting that a crafted file with
ANSI escapes / newlines in `encodingId` produces a clean exception message.

### Phase C — Mass-migrate safe sites (~3–4 h, mechanical)
Migrate remaining ~220 sites. These carry no attacker-controlled strings, so
the migration is for consistency and to enable the Phase D checkstyle rule.
Expand `VortexError` as needed; add constants rather than reusing
approximate-fit existing ones.

### Phase D — Remove deprecated constructors + checkstyle guard (0.5.0)
- Delete `VortexException(String)` and `VortexException(String, Throwable)`.
- Add a Checkstyle `RegexpCheck` rule forbidding
  `throw new VortexException\(\"` (raw string literal in constructor) to
  prevent regression. Also flag `+` inside the VortexException args to
  catch interpolation-before-sanitization.

### Phase E — Bounds typing via `IoBounds` (0.8.0)

Independent of A–D. The `VortexError` catalog (Phase A) is not built yet, so
`IoBounds` ships using the current `VortexException(String)` constructor with a
fixed, non-interpolated message (no attacker strings in the bounds messages —
only numeric offsets/lengths, which need no sanitization). When Phase A lands,
`IoBounds` migrates to `VortexError.SEGMENT_INDEX_OUT_OF_RANGE` mechanically
with every other site. Lands in 0.8.0 before the release, since variant decode
widens the parse surface.

1. Add `IoBounds` (`slice` / `checkRange` / `toIntSize` / `checkCount`) in
   `core` with unit tests: negative offset, length overflow, off+len past end,
   > 2 GB size, exact-boundary pass.
2. Route the ~21 raw `asSlice` sites through `IoBounds.slice`; fold
   `PostscriptParser`'s private `slice()`/`checkBlobBounds` and `ProtoReader`'s
   hand-rolled guard into it.
3. Replace `Math.toIntExact(...length())` (4 extension decoders) with
   `IoBounds.toIntSize`; guard the `new T[(int) n]` alloc sites with
   `IoBounds.checkCount`.
4. Collapse the ~14 consumer-access `getX(i)` guards onto
   `Objects.checkIndex(i, length)` (separate commit — different error class,
   no `IoBounds`).
5. Checkstyle `RegexpSingleline` rejecting raw `.asSlice(` in
   `reader` / `reader.array` / `reader.decode` / `core.proto` packages
   (mirrors the existing `<p>`-blocking rule), so new raw slices can't regress.
6. `BoundsTypingSecurityTest`: crafted file with out-of-range slice offset,
   oversize declared length, and non-monotonic VarBin offsets each produce a
   `VortexException`, never a raw JDK exception.

## Alternative considered

**Option B — Sealed `VortexException` hierarchy:** Make `VortexException`
`sealed` over ~4 category abstracts (`Malformed`, `Unsupported`, `Resource`,
`Internal`), each `sealed` over ~25 leaf record classes. Each leaf carries
typed final fields; compact constructors sanitize attacker-controlled fields.

Pros: compile-time arg-count + arg-type checking; typed `catch` destructuring
(`case UnknownLayoutEncoding(String id) ->`); category-level catch for
coarse-grained handling.

Rejected because: ~600 LOC across many files raises per-error contribution
cost during the ~220-site mass migration; `getClass().getSimpleName()` metric
labels are less stable than `enum.name()`; compile-time arg checking is
marginal for a `RuntimeException` hierarchy that callers never destructure
in `catch`. If the project later adds a query/filter layer that needs to
programmatically inspect exception types, this decision can be revisited.

## Consequences

### Positive
- All exception messages from attacker-controlled input are injection-safe
  after Phase B.
- Structured `[ERROR_CODE] sanitized args` format is grep-friendly and
  parseable by log-aggregation pipelines without regex gymnastics.
- `VortexError.code()` provides stable metric labels across refactors.
- Checkstyle rule (Phase D) makes regression impossible without a deliberate
  override.

### Negative
- `VortexError` enum grows as new errors are added; reviewers must check
  for duplicate or approximate-fit reuse.
- The `render(VortexError, Object...)` varargs call loses compile-time
  arg-count safety. Mitigation: a short convention doc in `VortexError`
  Javadoc stating expected arg count per constant.
- Phases C and D touch nearly every file in the reader and writer; diff noise
  is high. Bundle Phase C in a single mechanical commit with a clear message.

## References

- [SECURITY.md — injection threat model](../../SECURITY.md)
- [PR #27 — `BoundedSegment` + audit trail for untrusted `asSlice`](https://github.com/dfa1/vortex-java/pull/27)
- [ADR 0001 — Split read and write runtimes](0001-split-read-and-write-runtimes.md)
- [TODO.md §"Error messages — structural sanitization"](../../TODO.md)
