package io.github.dfa1.vortex.core.model;

/// Shared immutable instances of the non-nullable [DType]s: prefer these over `new Bool(false)`,
/// `new Primitive(pt, false)`, etc. For a nullable column build from one with
/// [DType#asNullable()], e.g. `DTypes.I64.asNullable()`.
///
/// They live here rather than on [DType] because they are records implementing it: as interface
/// constants, initializing `DType` needed its records initialized while each record needed `DType`
/// initialized first (it declares default methods, JLS 12.4.2), so two threads first using them at
/// the same moment deadlocked on each other's class-init lock.
public final class DTypes {

    /// Non-nullable [DType.Bool].
    public static final DType.Bool BOOL = new DType.Bool(false);

    /// Non-nullable [DType.Utf8].
    public static final DType.Utf8 UTF8 = new DType.Utf8(false);

    /// Non-nullable [DType.Binary].
    public static final DType.Binary BINARY = new DType.Binary(false);

    /// Non-nullable [DType.Null].
    public static final DType.Null NULL = new DType.Null(false);

    /// Non-nullable [DType.Variant].
    public static final DType.Variant VARIANT = new DType.Variant(false);

    /// Non-nullable [DType.Primitive] of [PType#I8].
    public static final DType.Primitive I8 = new DType.Primitive(PType.I8, false);

    /// Non-nullable [DType.Primitive] of [PType#I16].
    public static final DType.Primitive I16 = new DType.Primitive(PType.I16, false);

    /// Non-nullable [DType.Primitive] of [PType#I32].
    public static final DType.Primitive I32 = new DType.Primitive(PType.I32, false);

    /// Non-nullable [DType.Primitive] of [PType#I64].
    public static final DType.Primitive I64 = new DType.Primitive(PType.I64, false);

    /// Non-nullable [DType.Primitive] of [PType#U8].
    public static final DType.Primitive U8 = new DType.Primitive(PType.U8, false);

    /// Non-nullable [DType.Primitive] of [PType#U16].
    public static final DType.Primitive U16 = new DType.Primitive(PType.U16, false);

    /// Non-nullable [DType.Primitive] of [PType#U32].
    public static final DType.Primitive U32 = new DType.Primitive(PType.U32, false);

    /// Non-nullable [DType.Primitive] of [PType#U64].
    public static final DType.Primitive U64 = new DType.Primitive(PType.U64, false);

    /// Non-nullable [DType.Primitive] of [PType#F16].
    public static final DType.Primitive F16 = new DType.Primitive(PType.F16, false);

    /// Non-nullable [DType.Primitive] of [PType#F32].
    public static final DType.Primitive F32 = new DType.Primitive(PType.F32, false);

    /// Non-nullable [DType.Primitive] of [PType#F64].
    public static final DType.Primitive F64 = new DType.Primitive(PType.F64, false);

    private DTypes() {
    }
}
