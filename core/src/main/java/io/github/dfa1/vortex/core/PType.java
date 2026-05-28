package io.github.dfa1.vortex.core;

import java.nio.ByteOrder;
import java.lang.foreign.ValueLayout;

public enum PType {
    U8, U16, U32, U64,
    I8, I16, I32, I64,
    F16, F32, F64;

    public int byteSize() {
        return switch (this) {
            case U8,  I8        -> 1;
            case U16, I16, F16  -> 2;
            case U32, I32, F32  -> 4;
            case U64, I64, F64  -> 8;
        };
    }

    public boolean isFloating() {
        return this == F16 || this == F32 || this == F64;
    }

    public boolean isSigned() {
        return this == I8 || this == I16 || this == I32 || this == I64
            || this == F16 || this == F32 || this == F64;
    }

    public ValueLayout valueLayout() {
        return switch (this) {
            case I8, U8 -> ValueLayout.JAVA_BYTE;
            case I16, U16 -> ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
            case I32, U32, F32 -> ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
            case I64, U64, F64 -> ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
            default -> throw new UnsupportedOperationException("unsupported ptype: " + this);
        };
    }
}
