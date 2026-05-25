package io.github.dfa1.vortex.core;

import java.nio.ByteBuffer;
import java.util.List;

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

    boolean nullable();

    record Null(boolean nullable) implements DType {}

    record Bool(boolean nullable) implements DType {}

    record Primitive(PType ptype, boolean nullable) implements DType {}

    record Decimal(byte precision, byte scale, boolean nullable) implements DType {}

    record Utf8(boolean nullable) implements DType {}

    record Binary(boolean nullable) implements DType {}

    record Struct(
        java.util.List<String> fieldNames,
        java.util.List<DType>  fieldTypes,
        boolean nullable
    ) implements DType {
        public DType field(String name) {
            int i = fieldNames.indexOf(name);
            if (i < 0) throw new IllegalArgumentException("unknown field: " + name);
            return fieldTypes.get(i);
        }

        public int fieldIndex(String name) {
            int i = fieldNames.indexOf(name);
            if (i < 0) throw new IllegalArgumentException("unknown field: " + name);
            return i;
        }
    }

    record List(DType elementType, boolean nullable) implements DType {}

    record FixedSizeList(DType elementType, int fixedSize, boolean nullable) implements DType {}

    record Extension(
        String     extensionId,
        DType      storageDType,
        ByteBuffer metadata,
        boolean    nullable
    ) implements DType {}
}
