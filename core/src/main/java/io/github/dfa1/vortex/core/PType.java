package io.github.dfa1.vortex.core;

/// Physical primitive type — the wire-level numeric kind for a column.
///
/// Unsigned integers ({@code U8}–{@code U64}) and signed integers ({@code I8}–{@code I64})
/// share the same in-memory bit pattern; signedness only affects interpretation.
/// Floating-point types follow IEEE 754: {@code F16} (half), {@code F32} (single), {@code F64} (double).
public enum PType {
    /// Unsigned 8-bit integer.
    U8,
    /// Unsigned 16-bit integer.
    U16,
    /// Unsigned 32-bit integer.
    U32,
    /// Unsigned 64-bit integer.
    U64,
    /// Signed 8-bit integer.
    I8,
    /// Signed 16-bit integer.
    I16,
    /// Signed 32-bit integer.
    I32,
    /// Signed 64-bit integer.
    I64,
    /// IEEE 754 half-precision float (16-bit). Decoding not yet supported.
    F16,
    /// IEEE 754 single-precision float (32-bit).
    F32,
    /// IEEE 754 double-precision float (64-bit).
    F64;

    /// Number of bytes per element on the wire (1, 2, 4, or 8).
    ///
    /// @return the byte size of this physical type
    public int byteSize() {
        return switch (this) {
            case U8, I8 -> 1;
            case U16, I16, F16 -> 2;
            case U32, I32, F32 -> 4;
            case U64, I64, F64 -> 8;
        };
    }

    /// Returns {@code true} for {@code F16}, {@code F32}, and {@code F64}.
    ///
    /// @return {@code true} if this ptype is a floating-point type
    public boolean isFloating() {
        return this == F16 || this == F32 || this == F64;
    }

    /// Returns {@code true} for signed integers ({@code I8}–{@code I64}) and all floating-point types.
    ///
    /// @return {@code true} if this ptype is signed
    public boolean isSigned() {
        return this == I8 || this == I16 || this == I32 || this == I64
                       || this == F16 || this == F32 || this == F64;
    }
}
