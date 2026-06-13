# ADR 0009: Write API ergonomics — DType factories and typed chunk builder

- **Status:** Proposed
- **Date:** 2026-06-13
- **Deciders:** project maintainer
- **Supersedes:** —
- **Superseded by:** —

## Context

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

## Consequences

### Positive

- Boolean blindness eliminated: `DType.i64().nullable()` is self-documenting.
- Parallel-list mismatch is now a compile error (`field(name, dtype)` pairs).
- Wrong column name is caught at `.put()`, not inside the encoder.
- Wrong array type is caught at `.put()`, not via `ClassCastException` in encode.
- Length mismatch caught before any bytes are written to disk.
- `NullableData` no longer leaks from `writer.encode` into user code.
- Nullable columns expressed naturally via boxed arrays (`Long[]`, `Double[]`).

### Negative

- `writeChunk(Map<String, Object>)` callers must migrate (one-release window).
- `Chunk.put()` type validation adds a small per-column cost (one `instanceof`
  per column per chunk). Negligible versus encode time.
- Boxed arrays for nullable columns introduce GC pressure if the caller
  constructs them per-chunk from primitive data. A future `putNullable(name,
  long[], boolean[])` overload can address this without changing the design.

### Risks to manage

- `DType.structBuilder()` duplicate field name check: must be at `field()` time,
  not at `build()` time — silent shadowing is worse than early failure.
- `Chunk` validation must match encoder expectations exactly. A type accepted by
  the validator but rejected by the encoder (or vice versa) is a latent bug.
  Integration-test the full path for every (ptype, nullable) combination.

## Alternatives considered

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
