package io.github.dfa1.vortex.core;

import java.nio.ByteBuffer;
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

    /// Returns a copy of this type marked nullable. Sugar over
    /// [#withNullable(boolean)] so call sites read as a fluent adjective:
    /// `DType.i64().asNullable()`.
    ///
    /// @return a new {@link DType} identical to this one but with `nullable = true`
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

    // ── Static factories ────────────────────────────────────────────────────
    //
    // Convenience entry points returning non-nullable instances. Combine with
    // [#nullable()] for nullable columns. The underlying records are unchanged
    // and remain usable directly (pattern matching, proto serialization, tests).

    /// @return non-nullable [Bool]
    static Bool bool_() {
        return new Bool(false);
    }

    /// @return non-nullable [Utf8]
    static Utf8 utf8() {
        return new Utf8(false);
    }

    /// @return non-nullable [Binary]
    static Binary binary() {
        return new Binary(false);
    }

    /// @return non-nullable [Null]
    static Null null_() {
        return new Null(false);
    }

    /// @return non-nullable [Variant]
    static Variant variant() {
        return new Variant(false);
    }

    /// @return non-nullable [Primitive] of [PType#I8]
    static Primitive i8() {
        return new Primitive(PType.I8, false);
    }

    /// @return non-nullable [Primitive] of [PType#I16]
    static Primitive i16() {
        return new Primitive(PType.I16, false);
    }

    /// @return non-nullable [Primitive] of [PType#I32]
    static Primitive i32() {
        return new Primitive(PType.I32, false);
    }

    /// @return non-nullable [Primitive] of [PType#I64]
    static Primitive i64() {
        return new Primitive(PType.I64, false);
    }

    /// @return non-nullable [Primitive] of [PType#U8]
    static Primitive u8() {
        return new Primitive(PType.U8, false);
    }

    /// @return non-nullable [Primitive] of [PType#U16]
    static Primitive u16() {
        return new Primitive(PType.U16, false);
    }

    /// @return non-nullable [Primitive] of [PType#U32]
    static Primitive u32() {
        return new Primitive(PType.U32, false);
    }

    /// @return non-nullable [Primitive] of [PType#U64]
    static Primitive u64() {
        return new Primitive(PType.U64, false);
    }

    /// @return non-nullable [Primitive] of [PType#F16]
    static Primitive f16() {
        return new Primitive(PType.F16, false);
    }

    /// @return non-nullable [Primitive] of [PType#F32]
    static Primitive f32() {
        return new Primitive(PType.F32, false);
    }

    /// @return non-nullable [Primitive] of [PType#F64]
    static Primitive f64() {
        return new Primitive(PType.F64, false);
    }

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
    ///     .field("timestamp", DType.i64())
    ///     .field("symbol",    DType.utf8())
    ///     .field("price",     DType.f64())
    ///     .field("volume",    DType.i64().asNullable())
    ///     .build();
    /// ```
    ///
    /// @return a new {@link StructBuilder}
    static StructBuilder structBuilder() {
        return new StructBuilder();
    }

    /// Fluent builder for [Struct] dtypes. Use [#structBuilder()] to obtain one.
    /// Preserves insertion order and rejects duplicate field names at {@link #build()}.
    final class StructBuilder {
        private final LinkedHashMap<String, DType> fields = new LinkedHashMap<>();
        private boolean nullable;

        private StructBuilder() {
        }

        /// Adds a named field to the struct under construction.
        ///
        /// @param name the field name; must be non-`null` and not previously added
        /// @param type the field type
        /// @return this builder
        /// @throws IllegalArgumentException if `name` duplicates a previously added field
        public StructBuilder field(String name, DType type) {
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
        /// @return a new {@link Struct} reflecting every field added so far
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
    /// @param fieldNames ordered list of field names
    /// @param fieldTypes ordered list of field types, parallel to `fieldNames`
    /// @param nullable   whether null values are permitted
    record Struct(
            java.util.List<String> fieldNames,
            java.util.List<DType> fieldTypes,
            boolean nullable
    ) implements DType {
        /// Returns the type of the field with the given name.
        ///
        /// @param name the field name to look up
        /// @return the [DType] of the named field
        /// @throws IllegalArgumentException if no field with that name exists
        public DType field(String name) {
            int i = fieldNames.indexOf(name);
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
            ByteBuffer metadata,
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
            if (metadata != null && metadata.remaining() > MAX_METADATA_SIZE) {
                throw new VortexException("extension metadata too large: "
                        + metadata.remaining() + " > " + MAX_METADATA_SIZE);
            }
        }
    }

    /// Variant logical type for semi-structured data (analogous to Parquet variant / JSON).
    ///
    /// @param nullable whether null values are permitted
    record Variant(boolean nullable) implements DType {
    }
}
