# ADR 0009: Write API ergonomics — DType factories and typed chunk builder

- **Status:** Completed (parts 1-3 shipped 2026-06-14; part 4 split out to ADR 0011)
- **Date:** 2026-06-13
- **Deciders:** project maintainer
- **Supersedes:** —
- **Superseded by:** —

## Implementation status

- ✅ **Part 1** — `DType` static factories (`i8()`/`i16()`/…/`utf8()`/`bool_()`/`decimal()`) plus `asNullable()` shortcut (renamed from `nullable()` to avoid clashing with the existing boolean accessor) — commit `0e9d6703`.
- ✅ **Part 2** — `DType.structBuilder()` + `DType.StructBuilder` — commit `63d66eef`.
- ✅ **Part 3** — `VortexWriter.writeChunk(Consumer<Chunk>)` with the typed `Chunk` builder; per-`put` validation; boxed nullable arrays auto-converted to `NullableData`; old `writeChunk(Map<String, Object>)` deprecated — commit `ddb3e21a`.
- 🔀 **Part 4** — `chunk.put(String, MemorySegment)` zero-copy overload split into [ADR 0011](0011-writer-memorysegment-overload.md). Narrow persona (re-encode pipelines and native integrators), invasive across ~15 encoders; deferred until a concrete downstream use case shows up.

## Context

### User personas

Different callers have fundamentally different data representations and
performance requirements. A single write API cannot serve all three well
without overload stratification.

| Persona | Data they hold | Priority |
|---|---|---|
| **Application developer** | Java arrays (`long[]`, `String[]`, …) produced by application logic or a JDBC cursor | Simplicity, type safety, no FFM knowledge required |
| **Columnar pipeline / re-encoder** | `MemorySegment` buffers from a previous `VortexReader` scan, Arrow vector, or mmap region | Zero-copy — no heap allocation between read and write |
| **Systems / native integrator** | Off-heap buffers from JNI, Panama `MemorySegment`, or a native library | Full control over memory layout, minimal overhead |

These personas need different `.put()` overloads on the same `Chunk` API.
The chunk builder is the common entry point; the overloads differ in what
they accept, what they validate, and what they copy.

### Problems in the current API

The current write API has three independent ergonomic problems that compound
each other when writing even a simple schema.

### Problem 1 — boolean blindness in DType constructors

Every `DType` record takes a trailing `boolean nullable` parameter.
At the call site the meaning is invisible:

```java
new DType.Primitive(PType.I64, false)   // false = nullable? signed? required?
new DType.Utf8(false)                   // false = nullable? ASCII? has BOM?
new DType.Struct(names, types, false)   // false = which of the three booleans?
```

A reader must look up each record definition to decode the boolean.
The existing `withNullable(boolean)` default method exists but reads
even worse (`new DType.Utf8(false).withNullable(true)`).

### Problem 2 — parallel lists in DType.Struct

`DType.Struct` takes two parallel `List<String>` / `List<DType>` parameters:

```java
new DType.Struct(
    List.of("timestamp", "symbol", "price", "volume"),
    List.of(I64_TYPE,    UTF8_TYPE, F64_TYPE, I64_TYPE),
    false
);
```

Names and types are structurally decoupled: the compiler cannot catch
a mismatch in count or order. Inserting a field in one list without
updating the other is a silent runtime bug.

### Problem 3 — writeChunk discards schema knowledge

`VortexWriter.writeChunk` accepts `Map<String, Object>`:

```java
writer.writeChunk(Map.of(
    "timestamp", new long[]  {1_700_000_000_000L, 1_700_000_001_000L},
    "symbol",    new String[] {"AAPL", "AAPL"},
    "price",     new double[] {189.95, 190.10},
    "volume",    new long[]   {100L,   250L}
));
```

The schema that was already provided at construction time is not consulted:

- **Unknown column names** are silently ignored (missing key → `null` →
  `IllegalArgumentException` inside the encoder, not at the call site).
- **Wrong array type** (`int[]` for an `I64` column) compiles and fails with a
  `ClassCastException` deep in the encoder.
- **Length mismatch** between columns is not detected until a reader tries to
  scan the resulting file.
- **Nullable columns** require importing `NullableData` from the internal
  `writer.encode` package — unintended public surface.

## Decision

### 1 — DType static factories and `.nullable()` shortcut

Add static factory methods to `DType` for each concrete type, returning
non-nullable instances by default. Add a `nullable()` default method
(sugar over the existing `withNullable(true)`) so nullability reads as
a fluent adjective:

```java
DType.i64()             // new DType.Primitive(PType.I64,  false)
DType.i64().nullable()  // new DType.Primitive(PType.I64,  true)
DType.f64()             // new DType.Primitive(PType.F64,  false)
DType.utf8()            // new DType.Utf8(false)
DType.utf8().nullable() // new DType.Utf8(true)
DType.bool_()           // new DType.Bool(false)  (bool is a keyword, use bool_)
// etc. for all primitive ptypes, Bool, Binary, Decimal, Variant
```

The underlying records are unchanged — pattern matching with deconstruction
continues to work. Factories are convenience entry points, not replacements.

### 2 — DType.Struct builder

Add `DType.structBuilder()` returning a `DType.StructBuilder` that pairs
names and types at the call site, eliminating parallel lists:

```java
DType.Struct schema = DType.structBuilder()
    .field("timestamp", DType.i64())
    .field("symbol",    DType.utf8())
    .field("price",     DType.f64())
    .field("volume",    DType.i64().nullable())  // absent for index instruments
    .build();

// nullable struct itself (rare):
DType.structBuilder()
    .field(...)
    .nullable()
    .build();
```

`StructBuilder` is a public nested class of `DType`. It holds a
`LinkedHashMap<String, DType>` to preserve insertion order and reject
duplicate field names at build time.

The existing `DType.Struct(List, List, boolean)` record constructor remains —
it is used in pattern matching, proto serialization, and test fixtures.
`DType.structBuilder()` is an additional entry point, not a replacement.

### 3 — Typed chunk builder replacing Map\<String, Object\>

Replace `writeChunk(Map<String, Object>)` with a consumer-based API:

```java
writer.writeChunk(chunk -> chunk
    .put("timestamp", new long[]  {1_700_000_000_000L, 1_700_000_001_000L})
    .put("symbol",    new String[] {"AAPL", "AAPL"})
    .put("price",     new double[] {189.95, 190.10})
    .put("volume",    new Long[]   {100L, null})   // boxed = nullable column
);
```

`Chunk` (package-private implementation, public interface) validates at
each `.put()` call:

| Check | When | Error |
|---|---|---|
| Column name exists in schema | `.put()` | `IllegalArgumentException` |
| Array type matches column DType | `.put()` | `IllegalArgumentException` |
| All schema columns provided | `writeChunk` closes the lambda | `IllegalStateException` |
| All column arrays same length | `writeChunk` closes the lambda | `IllegalArgumentException` |

#### Type mapping

The `Chunk` builder validates the Java array type against the column's `DType`:

| DType | Non-nullable array | Nullable array |
|---|---|---|
| `Primitive(I8/U8)` | `byte[]` | `Byte[]` |
| `Primitive(I16/U16)` | `short[]` | `Short[]` |
| `Primitive(I32/U32)` | `int[]` | `Integer[]` |
| `Primitive(I64/U64)` | `long[]` | `Long[]` |
| `Primitive(F32)` | `float[]` | `Float[]` |
| `Primitive(F64)` | `double[]` | `Double[]` |
| `Utf8` | `String[]` | `String[]` (nulls allowed) |
| `Bool` | `boolean[]` | `Boolean[]` |

Boxed arrays (`Long[]`, `Double[]`, etc.) are accepted for nullable columns.
Passing a boxed array for a non-nullable column is an `IllegalArgumentException`
(nulls are not permitted by the schema).
Passing a primitive array for a nullable column is accepted (no nulls present).

`NullableData` is removed from the public API; boxing handles the
nullable-column case without an internal type leaking outward.

The old `writeChunk(Map<String, Object>)` is retained as a deprecated
method delegating to the new path, giving callers one release to migrate.

### 4 — MemorySegment overload for zero-copy callers

Add a second `put` overload on `Chunk` accepting a `MemorySegment` directly.
Targets the columnar pipeline and native integrator personas — callers who
already hold off-heap data and cannot afford a copy:

```java
// Re-encoding path: VortexReader → VortexWriter, zero heap allocation
try (var reader = VortexReader.open(path)) {
    for (Chunk chunk : reader.scan()) {
        writer.writeChunk(wb -> wb
            .put("timestamp", ((LongArray) chunk.column("timestamp")).buffer())
            .put("symbol",    ((VarBinArray) chunk.column("symbol")).bytesSegment(), chunk.rowCount())
            .put("price",     ((DoubleArray) chunk.column("price")).buffer())
            .put("volume",    ((LongArray) chunk.column("volume")).buffer())
        );
    }
}

// Native / Arrow path:
writer.writeChunk(wb -> wb
    .put("timestamp", nativeTimestampSegment)   // byteSize == n * 8, LE layout
    .put("price",     arrowDoubleBuffer)
);
```

Validation for the `MemorySegment` overload:

| Check | When | Error |
|---|---|---|
| Column name exists in schema | `.put()` | `IllegalArgumentException` |
| `seg.byteSize() % elemBytes == 0` | `.put()` | `IllegalArgumentException` |
| All columns produce same row count | `writeChunk` closes the lambda | `IllegalArgumentException` |

The segment is consumed as-is — no copy, no type inference beyond size check.
Callers are responsible for correct byte order (little-endian) and layout.
No validity bitmap is inferred; nullable columns need an explicit mask segment:

```java
.put("volume", valueSegment, validitySegment)  // validity: 1 bit per row, LSB-first
```

The `MemorySegment` overload coexists with the Java array overload — callers
can mix both within a single `writeChunk` call (e.g. one column from a native
buffer, another from a freshly computed `long[]`).

## Consequences

### Positive

- Boolean blindness eliminated: `DType.i64().nullable()` is self-documenting.
- Parallel-list mismatch is now a compile error (`field(name, dtype)` pairs).
- Wrong column name is caught at `.put()`, not inside the encoder.
- Wrong array type is caught at `.put()`, not via `ClassCastException` in encode.
- Length mismatch caught before any bytes are written to disk.
- `NullableData` no longer leaks from `writer.encode` into user code.
- Nullable columns expressed naturally via boxed arrays (`Long[]`, `Double[]`).
- All three personas served by one `Chunk` API: Java arrays, boxed arrays,
  and `MemorySegment` overloads coexist on the same builder.
- Re-encoding path (VortexReader → VortexWriter) becomes zero heap allocation.

### Negative

- `writeChunk(Map<String, Object>)` callers must migrate (one-release window).
- `Chunk.put()` type validation adds a small per-column cost (one `instanceof`
  per column per chunk). Negligible versus encode time.
- Boxed arrays for nullable columns introduce GC pressure if the caller
  constructs them per-chunk from primitive data. A future `putNullable(name,
  long[], boolean[])` overload can address this without changing the design.
- `MemorySegment` overload cannot validate byte order or element layout —
  caller is trusted. A wrong-endian segment produces a valid file that decodes
  to garbage. Document this explicitly in the overload's Javadoc.

### Risks to manage

- `DType.structBuilder()` duplicate field name check: must be at `field()` time,
  not at `build()` time — silent shadowing is worse than early failure.
- `Chunk` validation must match encoder expectations exactly. A type accepted by
  the validator but rejected by the encoder (or vice versa) is a latent bug.
  Integration-test the full path for every (ptype, nullable) combination.

## Alternatives considered

**Single unified `MemorySegment`-only API (no Java array overload).** Forces
all callers to allocate off-heap and manage layouts manually. Removes the
application-developer persona entirely. Rejected: most callers start with
Java arrays; forcing FFM knowledge on them is unnecessary friction.

**Row-oriented `addRow(Object...)` API.** Ergonomic for event-by-event streams
(e.g. a JDBC cursor). Requires an internal column buffer, transpose step before
encode, and memory proportional to chunk size. Columnar bulk-load (the primary
use case) gets no benefit. Not chosen for this ADR; could be added as a
separate path without conflicting with the chunk builder.

**Generic schema `VortexWriter<S extends Record>`** with code generation.
Compile-time type safety via generated typed writers. Requires an annotation
processor or build-plugin, substantial complexity, and ties the API to a
specific Java version. Overkill for the current user base; deferred.

**Keep `Map<String, Object>`, add explicit `validate()` call.** Validation
at write time rather than at `.put()` time. Still requires `NullableData` for
nullable columns; error messages are further from the mistake. Rejected.

**Sealed `Column<T>` wrapper type** (`LongColumn`, `DoubleColumn`, etc.).
Forces callers to construct wrapper objects; more ceremony than boxed arrays
for the nullable case. Ruled out: boxed arrays are idiomatic Java and already
supported by pattern matching / streams.
