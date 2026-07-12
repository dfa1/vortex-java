# ADR 0004: Resource caps and `ReadOptions`

- **Status:** Accepted — implementation pending
- **Date:** 2026-06-13
- **Deciders:** project maintainer
- **Related:** [CLAUDE.md §Security contract](../CLAUDE.md), [TODO.md §Resource caps](../TODO.md)

## Context

`VortexReader.open` memory-maps the entire file into one `MemorySegment` and
parses the layout tree from the flatbuffer before any scan starts. There are
currently no limits on:

- **File size** — `channel.map(READ_ONLY, 0, size, arena)` will happily
  try to map a 100 GB file, exhausting virtual address space.
- **Segment count** — a crafted file can declare millions of segment entries
  in the postscript; each is a struct in the footer flatbuffer.
- **Layout depth / children** — the depth-bomb test exists but the child
  count per node is unchecked; a flat tree with 10 M children triggers the
  same allocation explosion.
- **Row count per chunk** — no upper bound; a chunk claiming `Long.MAX_VALUE`
  rows causes a downstream allocation of `rows * byteWidth` bytes.
- **Max layout nodes** — flat tree with enormous sibling count escapes the
  depth guard.
- **Pco page/bin caps** — `PcoEncodingDecoder`/`PcoBin` have no upper bound on
  `bits_per_offset`, `bin_count`, or per-page row count; in scope for the same
  `ResourceLimits` mechanism, not a separate cap type.

The fix is a `ResourceLimits` value that is enforced at open/parse time,
before any byte is decoded.

### Why open-time, not per-scan

The natural instinct is to hang these caps off `ScanOptions`, next to the
existing row `limit`. That is too late. The structural attacks **detonate during
`open()` — before a `ScanOptions` even exists.** `open(path)` already:

1. memory-maps the **entire file** (`channel.map(READ_ONLY, 0, size, arena)`) — a
   100 GB file exhausts virtual address space here;
2. parses the postscript → footer → layout-tree flatbuffers;
3. reads the **segment table** (a crafted file can declare millions of entries);
4. walks the **layout tree** (depth / child-count bomb).

By the time a caller builds `ScanOptions` and calls `scan()`, the file is already
mapped and the layout tree already parsed — the OOM / address-space exhaustion /
depth-bomb has already happened. A scan-time check runs after the damage.

The governing rule: **enforce each cap at the earliest point the resource is
consumed.** For the structural caps that is `open()`/parse, not scan.

There is also a scope mismatch. Caps like `maxFileSizeBytes` and
`maxSegmentCount` are properties of the **file + reader session**, not of an
individual scan: one `open()` feeds many `scan()` calls. Placing them on
`ScanOptions` would force the caller to re-pass the same limit on every scan and
*still* could not guard `open()`.

| Resource | Consumed / detonates at | Configured via |
|----------|-------------------------|----------------|
| file mmap, segment table, layout depth / children / node count | `open()` / parse | `ReadOptions` |
| per-chunk decode allocation (`rows × byteWidth`) | decode (during `scan()`) | `ReadOptions` (`maxRowsPerChunk`, a layout-declared count fixed at open) |
| output row count | `scan()` | `ScanOptions.limit` (already exists) |

So `ScanOptions` keeps the one genuinely per-scan knob (output `limit`); every
structural cap moves to a new open-time `ReadOptions`.

### Where limits live — the decision

Three candidates:

| Placement | Checked when | Drawback |
|-----------|-------------|----------|
| `ScanOptions` (per-scan) | Scan start | Too late for file-size and segment-count checks which happen at `open()` |
| `VortexReader.open` overload param | `open()` / `parse()` | Splits configuration across open and scan; `ScanOptions` already owns scan-time limits |
| New `ReadOptions` record | `open()` | Consistent with `WriteOptions` pattern; clear separation: open-time vs scan-time |

**Decision: new `ReadOptions` record** passed to `VortexReader.open`.
`ScanOptions` retains the existing `limit` field (scan-time row limit) and
gains nothing new.

## Decision

Introduce `ReadOptions` with a `ResourceLimits` sub-record.
`VortexReader.open(Path, ReadRegistry, ReadOptions)` is the new canonical
overload; existing two-arg overload delegates with `ReadOptions.defaults()`.

```java
public record ResourceLimits(
        long maxFileSizeBytes,
        int  maxSegmentCount,
        int  maxLayoutChildren,
        int  maxLayoutDepth,
        long maxRowsPerChunk
) {
    public static ResourceLimits defaults() {
        return new ResourceLimits(
                16L * 1024 * 1024 * 1024,  // 16 GB
                100_000,                    // segment entries
                4_096,                      // children per layout node
                64,                         // layout depth (existing guard)
                (long) Integer.MAX_VALUE    // rows per chunk
        );
    }

    public static ResourceLimits unlimited() {
        return new ResourceLimits(
                Long.MAX_VALUE, Integer.MAX_VALUE,
                Integer.MAX_VALUE, Integer.MAX_VALUE,
                Long.MAX_VALUE);
    }
}

public record ReadOptions(ResourceLimits limits) {
    public static ReadOptions defaults() {
        return new ReadOptions(ResourceLimits.defaults());
    }

    public ReadOptions withLimits(ResourceLimits limits) {
        return new ReadOptions(limits);
    }
}
```

Enforcement points:

| Limit | Enforcement point |
|-------|-------------------|
| `maxFileSizeBytes` | `VortexReader.open`, before `channel.map` |
| `maxSegmentCount` | `PostscriptParser.parse`, after footer flatbuffer parsed |
| `maxLayoutChildren` | `PostscriptParser.parseLayout` (recursive), per node |
| `maxLayoutDepth` | existing `LayoutDepthGuard`; wire to `limits.maxLayoutDepth()` |
| `maxRowsPerChunk` | `ScanIterator.decodeLayout`, before arena allocation |

All violations throw `VortexException` with a message that includes the
limit value and the observed value (both are Java-internal, not
attacker-controlled strings, so no `Sanitize` required).

### HTTP reader

`VortexHttpReader.open` gains the same `ReadOptions` overload. `maxFileSizeBytes`
is checked against the `Content-Length` response header before the first
fetch; the others at the same points as the file reader.

### Integration tests

`ReadOptions.unlimited()` is passed by all integration tests that open
fixtures produced by the Rust reference implementation — some fixtures
intentionally exercise large segment counts or chunk sizes.

### `ScanOptions` unchanged

`ScanOptions.limit` (row cap per scan) stays as-is. It is a different concern:
a user-facing query limit, not a security cap.

## Consequences

### Positive
- Allocation bombs, mmap exhaustion, and layout-node explosions are
  caught before any decode work starts.
- Sane defaults protect production readers without any caller change.
- `ReadOptions.unlimited()` makes integration tests self-documenting
  about which tests are security-relevant vs fixture tests.
- `ResourceLimits` is a plain record — serializable, loggable, diffable.

### Negative
- `VortexReader.open(Path, ReadRegistry)` callers are unaffected
  (delegate overload), but new callers who want custom limits must
  construct `ReadOptions`. Minor friction.
- Default `maxFileSizeBytes = 16 GB` may be too small for some analytical
  workloads. Make it configurable from the start rather than hard-coding.

## References

- [CLAUDE.md §Security contract](../CLAUDE.md)
- [TODO.md §Resource caps](../TODO.md)
- [ADR 0003 — VortexException sanitization](0003-vortex-exception-sanitization.md)
