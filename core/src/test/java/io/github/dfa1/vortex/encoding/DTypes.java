package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;

/// Shared DType constants for encoding tests.
///
/// Public so reader/ and writer/ test trees can reuse them via the core test-jar.
@SuppressWarnings("unused")
public final class DTypes {

    public static final DType I8 = DType.I8;
    public static final DType I16 = DType.I16;
    public static final DType I32 = DType.I32;
    public static final DType I64 = DType.I64;
    public static final DType U8 = DType.U8;
    public static final DType U16 = DType.U16;
    public static final DType U32 = DType.U32;
    public static final DType U64 = DType.U64;
    public static final DType F16 = DType.F16;
    public static final DType F32 = DType.F32;
    public static final DType F64 = DType.F64;

    public static final DType I32_N = new DType.Primitive(PType.I32, true);
    public static final DType I64_N = new DType.Primitive(PType.I64, true);
    public static final DType F64_N = new DType.Primitive(PType.F64, true);

    public static final DType BOOL = DType.BOOL;
    public static final DType BOOL_N = new DType.Bool(true);
    public static final DType UTF8 = DType.UTF8;
    public static final DType UTF8_N = new DType.Utf8(true);
    public static final DType BINARY = DType.BINARY;
    public static final DType BINARY_N = new DType.Binary(true);
    public static final DType NULL = new DType.Null(true);

    public static final DType LIST_I32 = new DType.List(I32, false);

    private DTypes() {
    }
}
