package io.github.dfa1.vortex.core;

import java.nio.ByteBuffer;

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
                        DType.List, DType.FixedSizeList, DType.Extension {

    /// Returns whether this type allows null values.
    ///
    /// @return {@code true} if null values are permitted
    boolean nullable();

    /// Returns a copy of this type with the given nullability.
    ///
    /// @param nullable the desired nullability for the returned type
    /// @return a new {@link DType} identical to this one except for its nullability
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
        };
    }

    /// The SQL {@code NULL} type — no values, always nullable.
    ///
    /// @param nullable whether null values are permitted
    record Null(boolean nullable) implements DType {
    }

    /// Boolean logical type.
    ///
    /// @param nullable whether null values are permitted
    record Bool(boolean nullable) implements DType {
    }

    /// Primitive numeric logical type backed by a physical {@link PType}.
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
    /// @param fieldTypes ordered list of field types, parallel to {@code fieldNames}
    /// @param nullable   whether null values are permitted
    record Struct(
            java.util.List<String> fieldNames,
            java.util.List<DType> fieldTypes,
            boolean nullable
    ) implements DType {
        /// Returns the type of the field with the given name.
        ///
        /// @param name the field name to look up
        /// @return the {@link DType} of the named field
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
    /// @param extensionId  unique string identifier for the extension type (e.g. {@code "vortex.timestamp"})
    /// @param storageDType underlying storage dtype used for physical encoding
    /// @param metadata     opaque extension-specific metadata bytes, or {@code null}
    /// @param nullable     whether null values are permitted
    record Extension(
            String extensionId,
            DType storageDType,
            ByteBuffer metadata,
            boolean nullable
    ) implements DType {
    }
}
