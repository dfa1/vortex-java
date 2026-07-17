package io.github.dfa1.vortex.core.model;

import io.github.dfa1.vortex.core.error.VortexException;

/// Physical primitive type — the wire-level numeric kind for a column.
///
/// Unsigned integers (`U8`–`U64`) and signed integers (`I8`–`I64`)
/// share the same in-memory bit pattern; signedness only affects interpretation.
/// Floating-point types follow IEEE 754: `F16` (half), `F32` (single), `F64` (double).
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
    /// IEEE 754 half-precision float (16-bit).
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

    /// Number of bits per element — [#byteSize()] times eight (8, 16, 32, or 64).
    ///
    /// @return the bit width of this physical type
    public int bits() {
        return byteSize() * 8;
    }

    /// Returns `true` for `F16`, `F32`, and `F64`.
    ///
    /// @return `true` if this ptype is a floating-point type
    public boolean isFloating() {
        return this == F16 || this == F32 || this == F64;
    }

    /// Returns `true` for signed integers (`I8`–`I64`) and all floating-point types.
    ///
    /// @return `true` if this ptype is signed
    public boolean isSigned() {
        return this == I8 || this == I16 || this == I32 || this == I64
                       || this == F16 || this == F32 || this == F64;
    }

    /// Returns `true` for the unsigned integer types (`U8`–`U64`) — the complement of
    /// [#isSigned()], since every non-unsigned ptype is either a signed integer or floating-point.
    ///
    /// @return `true` if this ptype is an unsigned integer
    public boolean isUnsigned() {
        return !isSigned();
    }

    /// Returns the [PType] for the given enum ordinal — the integer value the wire format
    /// uses to identify a physical type.
    ///
    /// Unlike `PType.values()[ordinal]`, this method validates the ordinal against the
    /// declared range and throws [VortexException] for crafted out-of-range values rather
    /// than the JDK's [ArrayIndexOutOfBoundsException]. Use this at every decode site that
    /// reads a ptype from untrusted metadata.
    ///
    /// @param ordinal the enum ordinal, typically taken from a Protobuf `ptype` field
    /// @return the [PType] for the given ordinal
    /// @throws VortexException if `ordinal` is negative or greater than the largest defined
    ///                         [PType] ordinal
    public static PType fromOrdinal(int ordinal) {
        PType[] all = values();
        if (ordinal < 0 || ordinal >= all.length) {
            throw new VortexException(
                    "invalid PType ordinal=" + ordinal
                            + " (expected 0.." + (all.length - 1) + ")");
        }
        return all[ordinal];
    }

    /// Returns the narrowest unsigned integer ptype that can hold every value in
    /// `0..maxValue` — `U8` up to `0xFF`, `U16` up to `0xFFFF`, else `U32`.
    ///
    /// Used writer-side to size index/length/offset buffers (patch indices, FSST row
    /// lengths and code offsets) to the smallest width that still fits, instead of
    /// always paying for a 4-byte `U32`.
    ///
    /// @param maxValue the largest value the buffer must represent, `>= 0`
    /// @return `U8`, `U16`, or `U32`, whichever is smallest and still fits
    public static PType narrowestUnsigned(long maxValue) {
        if (maxValue <= 0xFFL) {
            return U8;
        }
        if (maxValue <= 0xFFFFL) {
            return U16;
        }
        return U32;
    }
}
