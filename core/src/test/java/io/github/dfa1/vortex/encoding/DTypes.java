package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;

/// Shared DType constants for encoding tests.
///
/// Public so reader/ and writer/ test trees can reuse them via the core test-jar.
@SuppressWarnings("unused")
public final class DTypes {

    public static final DType I8 = new DType.Primitive(PType.I8, false);
    public static final DType I16 = new DType.Primitive(PType.I16, false);
    public static final DType I32 = new DType.Primitive(PType.I32, false);
    public static final DType I64 = new DType.Primitive(PType.I64, false);
    public static final DType U8 = new DType.Primitive(PType.U8, false);
    public static final DType U16 = new DType.Primitive(PType.U16, false);
    public static final DType U32 = new DType.Primitive(PType.U32, false);
    public static final DType U64 = new DType.Primitive(PType.U64, false);
    public static final DType F16 = new DType.Primitive(PType.F16, false);
    public static final DType F32 = new DType.Primitive(PType.F32, false);
    public static final DType F64 = new DType.Primitive(PType.F64, false);

    public static final DType I32_N = new DType.Primitive(PType.I32, true);
    public static final DType I64_N = new DType.Primitive(PType.I64, true);
    public static final DType F64_N = new DType.Primitive(PType.F64, true);

    public static final DType BOOL = new DType.Bool(false);
    public static final DType BOOL_N = new DType.Bool(true);
    public static final DType UTF8 = new DType.Utf8(false);
    public static final DType UTF8_N = new DType.Utf8(true);
    public static final DType BINARY = new DType.Binary(false);
    public static final DType BINARY_N = new DType.Binary(true);
    public static final DType NULL = new DType.Null(true);

    public static final DType LIST_I32 = new DType.List(I32, false);

    private DTypes() {
    }
}
