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

	boolean nullable();

	record Null(boolean nullable) implements DType {
	}

	record Bool(boolean nullable) implements DType {
	}

	record Primitive(PType ptype, boolean nullable) implements DType {
	}

	record Decimal(byte precision, byte scale, boolean nullable) implements DType {
	}

	record Utf8(boolean nullable) implements DType {
	}

	record Binary(boolean nullable) implements DType {
	}

	record Struct(
			java.util.List<String> fieldNames,
			java.util.List<DType> fieldTypes,
			boolean nullable
	) implements DType {
		public DType field(String name) {
			int i = fieldNames.indexOf(name);
			if (i < 0) {
				throw new IllegalArgumentException("unknown field: " + name);
			}
			return fieldTypes.get(i);
		}
	}

	record List(DType elementType, boolean nullable) implements DType {
	}

	record FixedSizeList(DType elementType, int fixedSize, boolean nullable) implements DType {
	}

	record Extension(
			String extensionId,
			DType storageDType,
			ByteBuffer metadata,
			boolean nullable
	) implements DType {
	}

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
}
