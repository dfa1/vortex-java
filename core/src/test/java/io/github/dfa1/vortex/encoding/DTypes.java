package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;

/// Shared DType constants for encoding tests.
@SuppressWarnings("unused")
final class DTypes {

	static final DType I8    = new DType.Primitive(PType.I8,  false);
	static final DType I16   = new DType.Primitive(PType.I16, false);
	static final DType I32   = new DType.Primitive(PType.I32, false);
	static final DType I64   = new DType.Primitive(PType.I64, false);
	static final DType U8    = new DType.Primitive(PType.U8,  false);
	static final DType U16   = new DType.Primitive(PType.U16, false);
	static final DType U32   = new DType.Primitive(PType.U32, false);
	static final DType U64   = new DType.Primitive(PType.U64, false);
	static final DType F32   = new DType.Primitive(PType.F32, false);
	static final DType F64   = new DType.Primitive(PType.F64, false);

	static final DType I32_N = new DType.Primitive(PType.I32, true);
	static final DType I64_N = new DType.Primitive(PType.I64, true);
	static final DType F64_N = new DType.Primitive(PType.F64, true);

	static final DType BOOL     = new DType.Bool(false);
	static final DType BOOL_N   = new DType.Bool(true);
	static final DType UTF8     = new DType.Utf8(false);
	static final DType UTF8_N   = new DType.Utf8(true);
	static final DType BINARY   = new DType.Binary(false);
	static final DType BINARY_N = new DType.Binary(true);
	static final DType NULL     = new DType.Null(true);

	static final DType LIST_I32 = new DType.List(I32, false);

	private DTypes() {}
}
