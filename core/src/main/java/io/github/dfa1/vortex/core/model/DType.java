package io.github.dfa1.vortex.core.model;

import io.github.dfa1.vortex.core.error.VortexException;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/// Vortex logical data type. Strictly logical — defines value domain, not physical storage.
///
/// Usage with pattern matching:
/// ```java
/// switch (dtype) {
///     case DType.Primitive(var pt, var nullable) -> ...
///     case DType.Struct(var names, var types, var nullable) -> ...
///     default -> ...
/// }
/// ```
public sealed interface DType
        permits DType.Null, DType.Bool, DType.Primitive, DType.Decimal,
                        DType.Utf8, DType.Binary, DType.Struct,
                        DType.List, DType.FixedSizeList, DType.Extension, DType.Variant {

    /// Returns whether this type allows null values.
    ///
    /// @return `true` if null values are permitted
    boolean nullable();

    /// Returns whether this is an unsigned integer type (`U8`–`U64`). `false` for every other
    /// type, including signed integers, floats, and the composite/extension types. Useful where
    /// unsigned values are stored in a signed `long` (e.g. zone-map comparisons), so the caller
    /// knows to use unsigned ordering.
    ///
    /// @return `true` if this is an unsigned-integer [Primitive]
    default boolean isUnsigned() {
        return switch (this) {
            case Primitive(var pt, _) -> pt.isUnsigned();
            case Null _, Bool _, Decimal _, Utf8 _, Binary _, Struct _, List _,
                 FixedSizeList _, Extension _, Variant _ -> false;
        };
    }

    /// Returns a copy of this type marked nullable. Sugar over
    /// [#withNullable(boolean)] so call sites read as a fluent adjective:
    /// `DType.I64.asNullable()`.
    ///
    /// @return a new [DType] identical to this one but with `nullable = true`
    default DType asNullable() {
        return withNullable(true);
    }

    /// Returns a copy of this type with the given nullability.
    ///
    /// @param nullable the desired nullability for the returned type
    /// @return a new [DType] identical to this one except for its nullability
    default DType withNullable(boolean nullable) {
        return switch (this) {
            case Null _ -> new Null(nullable);
            case Bool _ -> new Bool(nullable);
            case Primitive(var pt, _) -> new Primitive(pt, nullable);
            case Decimal(var p, var s, _) -> new Decimal(p, s, nullable);
            case Utf8 _ -> new Utf8(nullable);
            case Binary _ -> new Binary(nullable);
            case Struct(var names, var types, _) -> new Struct(names, types, nullable);
            case List(var elem, _) -> new List(elem, nullable);
            case FixedSizeList(var elem, var size, _) -> new FixedSizeList(elem, size, nullable);
            case Extension(var id, var storage, var meta, _) -> new Extension(id, storage, meta, nullable);
            case Variant _ -> new Variant(nullable);
        };
    }

    // ── Canonical non-nullable types ─────────────────────────────────────────
    //
    // Shared immutable instances — prefer these over `new Bool(false)`,
    // `new Utf8(false)`, etc. For a nullable column build from one with
    // [#asNullable()], e.g. `DType.UTF8.asNullable()`.

    /// Non-nullable [Bool].
    Bool BOOL = new Bool(false);

    /// Non-nullable [Utf8].
    Utf8 UTF8 = new Utf8(false);

    /// Non-nullable [Binary].
    Binary BINARY = new Binary(false);

    /// Non-nullable [Null].
    Null NULL = new Null(false);

    /// Non-nullable [Variant].
    Variant VARIANT = new Variant(false);

    // ── Canonical non-nullable primitives ───────────────────────────────────
    //
    // Shared immutable instances — prefer these over `new Primitive(pt, false)`.
    // For a nullable column build from one with [#asNullable()], e.g.
    // `DType.I64.asNullable()`.

    /// Non-nullable [Primitive] of [PType#I8].
    Primitive I8 = new Primitive(PType.I8, false);

    /// Non-nullable [Primitive] of [PType#I16].
    Primitive I16 = new Primitive(PType.I16, false);

    /// Non-nullable [Primitive] of [PType#I32].
    Primitive I32 = new Primitive(PType.I32, false);

    /// Non-nullable [Primitive] of [PType#I64].
    Primitive I64 = new Primitive(PType.I64, false);

    /// Non-nullable [Primitive] of [PType#U8].
    Primitive U8 = new Primitive(PType.U8, false);

    /// Non-nullable [Primitive] of [PType#U16].
    Primitive U16 = new Primitive(PType.U16, false);

    /// Non-nullable [Primitive] of [PType#U32].
    Primitive U32 = new Primitive(PType.U32, false);

    /// Non-nullable [Primitive] of [PType#U64].
    Primitive U64 = new Primitive(PType.U64, false);

    /// Non-nullable [Primitive] of [PType#F16].
    Primitive F16 = new Primitive(PType.F16, false);

    /// Non-nullable [Primitive] of [PType#F32].
    Primitive F32 = new Primitive(PType.F32, false);

    /// Non-nullable [Primitive] of [PType#F64].
    Primitive F64 = new Primitive(PType.F64, false);

    /// @param precision total number of significant decimal digits
    /// @param scale     number of digits to the right of the decimal point
    /// @return non-nullable [Decimal]
    static Decimal decimal(int precision, int scale) {
        return new Decimal((byte) precision, (byte) scale, false);
    }

    /// Returns a fresh [StructBuilder] for assembling a [Struct] dtype with
    /// name+type pairs declared together at the call site.
    ///
    /// ```java
    /// DType.Struct schema = DType.structBuilder()
    ///     .field("timestamp", DType.I64)
    ///     .field("symbol",    DType.UTF8)
    ///     .field("price",     DType.F64)
    ///     .field("volume",    DType.I64.asNullable())
    ///     .build();
    /// ```
    ///
    /// @return a new [StructBuilder]
    static StructBuilder structBuilder() {
        return new StructBuilder();
    }

    /// Fluent builder for [Struct] dtypes. Use [#structBuilder()] to obtain one.
    /// Preserves insertion order and rejects duplicate field names at [#build()].
    final class StructBuilder {
        private final LinkedHashMap<ColumnName, DType> fields = new LinkedHashMap<>();
        private boolean nullable;

        private StructBuilder() {
        }

        /// Adds a named field to the struct under construction.
        ///
        /// Field names follow the [ColumnName] policy, stricter than the wire format on
        /// purpose: blank names and control characters are rejected as footguns, on both the
        /// write and the read side — same spirit as a JSON library refusing a `""` key it
        /// could technically parse.
        ///
        /// @param name the field name; non-`null`, non-blank, no control characters, not
        ///             previously added
        /// @param type the field type
        /// @return this builder
        /// @throws IllegalArgumentException if `name` is blank, contains a control character,
        ///         or duplicates a previously added field
        public StructBuilder field(String name, DType type) {
            return field(ColumnName.of(name), type);
        }

        /// Adds a named field using an already-validated [ColumnName].
        ///
        /// @param name the validated field name; must not have been previously added
        /// @param type the field type
        /// @return this builder
        /// @throws IllegalArgumentException if `name` duplicates a previously added field
        public StructBuilder field(ColumnName name, DType type) {
            if (fields.putIfAbsent(name, type) != null) {
                throw new IllegalArgumentException("duplicate field name: " + name);
            }
            return this;
        }

        /// Marks the resulting struct itself as nullable.
        ///
        /// @return this builder
        public StructBuilder asNullable() {
            this.nullable = true;
            return this;
        }

        /// Builds the [Struct] dtype.
        ///
        /// @return a new [Struct] reflecting every field added so far
        public Struct build() {
            return new Struct(
                    java.util.List.copyOf(fields.keySet()),
                    java.util.List.copyOf(new ArrayList<>(fields.values())),
                    nullable);
        }
    }

    // ── Records ─────────────────────────────────────────────────────────────

    /// The SQL `NULL` type — no values, always nullable.
    ///
    /// @param nullable whether null values are permitted
    record Null(boolean nullable) implements DType {
    }

    /// Boolean logical type.
    ///
    /// @param nullable whether null values are permitted
    record Bool(boolean nullable) implements DType {
    }

    /// Primitive numeric logical type backed by a physical [PType].
    ///
    /// @param ptype    physical primitive type (e.g. I32, F64)
    /// @param nullable whether null values are permitted
    record Primitive(PType ptype, boolean nullable) implements DType {
    }

    /// Fixed-precision decimal logical type.
    ///
    /// @param precision total number of significant decimal digits
    /// @param scale     number of digits to the right of the decimal point
    /// @param nullable  whether null values are permitted
    record Decimal(byte precision, byte scale, boolean nullable) implements DType {
    }

    /// Variable-length UTF-8 string logical type.
    ///
    /// @param nullable whether null values are permitted
    record Utf8(boolean nullable) implements DType {
    }

    /// Variable-length binary (byte string) logical type.
    ///
    /// @param nullable whether null values are permitted
    record Binary(boolean nullable) implements DType {
    }

    /// Struct logical type with named, typed fields.
    ///
    /// @param fieldNames ordered list of validated [ColumnName] field names
    /// @param fieldTypes ordered list of field types, parallel to `fieldNames`
    /// @param nullable   whether null values are permitted
    record Struct(
            java.util.List<ColumnName> fieldNames,
            java.util.List<DType> fieldTypes,
            boolean nullable
    ) implements DType {

        /// Validates that names and types pair 1:1. Duplicate names are legal in memory,
        /// mirroring the Rust reference's `StructFields`; uniqueness is a file-boundary
        /// contract, enforced by the writer and the file parser.
        ///
        /// @param fieldNames ordered list of field names
        /// @param fieldTypes ordered list of field types, parallel to `fieldNames`
        /// @param nullable   whether null values are permitted
        /// @throws NullPointerException     if `fieldNames` or `fieldTypes` is `null`
        /// @throws IllegalArgumentException if the two lists differ in size
        public Struct {
            java.util.Objects.requireNonNull(fieldNames, "fieldNames");
            java.util.Objects.requireNonNull(fieldTypes, "fieldTypes");
            if (fieldNames.size() != fieldTypes.size()) {
                throw new IllegalArgumentException("fieldNames/fieldTypes size mismatch: "
                        + fieldNames.size() + " names, " + fieldTypes.size() + " types");
            }
        }

        /// Returns the type of the field with the given name.
        ///
        /// @param name the field name to look up
        /// @return the [DType] of the named field
        /// @throws IllegalArgumentException if no field with that name exists
        public DType field(String name) {
            int i = fieldNames.indexOf(ColumnName.of(name));
            if (i < 0) {
                throw new IllegalArgumentException("unknown field: " + name);
            }
            return fieldTypes.get(i);
        }
    }

    /// Variable-length list logical type.
    ///
    /// @param elementType logical type of each list element
    /// @param nullable    whether null values are permitted
    record List(DType elementType, boolean nullable) implements DType {
    }

    /// Fixed-size list logical type where every list has the same length.
    ///
    /// @param elementType logical type of each list element
    /// @param fixedSize   number of elements in every list
    /// @param nullable    whether null values are permitted
    record FixedSizeList(DType elementType, int fixedSize, boolean nullable) implements DType {
    }

    /// Extension logical type with user-defined semantics layered over a storage type.
    ///
    /// @param extensionId  unique string identifier for the extension type (e.g. `"vortex.timestamp"`)
    /// @param storageDType underlying storage dtype used for physical encoding
    /// @param metadata     opaque extension-specific metadata bytes, or `null`
    /// @param nullable     whether null values are permitted
    record Extension(
            String extensionId,
            DType storageDType,
            MemorySegment metadata,
            boolean nullable
    ) implements DType {

        /// Hard cap on extension metadata size. Spec-defined extensions use 0–3 bytes
        /// (e.g. timestamp = TimeUnit tag + tz length + tz UTF-8); 64 KiB is generous
        /// for custom extensions while blocking attacker-supplied multi-megabyte blobs
        /// that would inflate parser allocations.
        public static final int MAX_METADATA_SIZE = 64 * 1024;

        /// @throws VortexException if `metadata` carries more than
        ///         [#MAX_METADATA_SIZE] readable bytes
        public Extension {
            if (metadata != null && metadata.byteSize() > MAX_METADATA_SIZE) {
                throw new VortexException("extension metadata too large: "
                        + metadata.byteSize() + " > " + MAX_METADATA_SIZE);
            }
        }

        /// Value equality with content-based metadata comparison.
        ///
        /// [MemorySegment] uses identity equality, so the default record `equals` would
        /// treat a heap-built dtype and the same dtype read back from a mapped file as
        /// unequal. Extension is a value type (compared in schema round-trips and as a
        /// component of [Struct]/[List] dtypes), so metadata is compared by bytes —
        /// matching the content semantics the previous `ByteBuffer` component had.
        ///
        /// @param o other object
        /// @return whether `o` is an Extension with equal id, storage, nullability and metadata bytes
        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Extension other)) {
                return false;
            }
            return nullable == other.nullable
                    && extensionId.equals(other.extensionId)
                    && storageDType.equals(other.storageDType)
                    && metadataEquals(metadata, other.metadata);
        }

        /// @return a hash consistent with [#equals(Object)] (metadata hashed by content)
        @Override
        public int hashCode() {
            int h = java.util.Objects.hash(extensionId, storageDType, nullable);
            if (metadata != null) {
                h = 31 * h + java.util.Arrays.hashCode(metadata.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE));
            }
            return h;
        }

        private static boolean metadataEquals(MemorySegment a, MemorySegment b) {
            if (a == null || b == null) {
                return a == b;
            }
            return a.byteSize() == b.byteSize() && a.mismatch(b) == -1;
        }
    }

    /// Variant logical type for semi-structured data (analogous to Parquet variant / JSON).
    ///
    /// @param nullable whether null values are permitted
    record Variant(boolean nullable) implements DType {
    }
}
