package io.github.dfa1.vortex.core.io;

import io.github.dfa1.vortex.core.model.PType;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;

import static io.github.dfa1.vortex.core.io.VortexFormat.LE_DOUBLE;
import static io.github.dfa1.vortex.core.io.VortexFormat.LE_FLOAT;
import static io.github.dfa1.vortex.core.io.VortexFormat.LE_INT;
import static io.github.dfa1.vortex.core.io.VortexFormat.LE_LONG;
import static io.github.dfa1.vortex.core.io.VortexFormat.LE_SHORT;

/// Bulk I/O helpers for primitive ptypes, backed by `MemorySegment`/`ValueLayout`/`MethodHandle`.
///
/// `SETTERS[ordinal]` is a MethodHandle `(MemorySegment, long offset, long bits) -> void`
/// that narrows / bit-reinterprets `bits` to the carrier type of the ptype and writes via
/// the unaligned little-endian VarHandle. This lets hot loops avoid per-element `switch`
/// dispatch on `PType`.
///
/// The shared little-endian layouts live in [VortexFormat] — endianness is a property of the
/// wire format, not of ptypes; this class only maps ptypes onto those layouts.
public final class PTypeIO {

    private static final MethodHandle[] SETTERS = buildSetters();

    private PTypeIO() {
    }

    /// Little-endian, unaligned `ValueLayout` for the given ptype.
    static ValueLayout valueLayout(PType ptype) {
        return switch (ptype) {
            case I8, U8 -> ValueLayout.JAVA_BYTE;
            case I16, U16 -> LE_SHORT;
            case I32, U32 -> LE_INT;
            case I64, U64 -> LE_LONG;
            case F32 -> LE_FLOAT;
            case F64 -> LE_DOUBLE;
            case F16 -> LE_SHORT;
        };
    }

    private static MethodHandle[] buildSetters() {
        MethodHandle[] mhs = new MethodHandle[PType.values().length];
        MethodHandles.Lookup lookup = MethodHandles.lookup();

        // (long) -> int narrowing — used for raw-bits to int for F32.
        MethodHandle longToInt = MethodHandles.explicitCastArguments(
                MethodHandles.identity(int.class),
                MethodType.methodType(int.class, long.class));

        MethodHandle intBitsToFloat;
        MethodHandle longBitsToDouble;
        try {
            intBitsToFloat = lookup.findStatic(Float.class, "intBitsToFloat",
                    MethodType.methodType(float.class, int.class));
            longBitsToDouble = lookup.findStatic(Double.class, "longBitsToDouble",
                    MethodType.methodType(double.class, long.class));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        // (long) -> float (raw-bits reinterpret) for F32.
        MethodHandle longBitsToFloat = MethodHandles.filterArguments(intBitsToFloat, 0, longToInt);

        for (PType p : PType.values()) {
            VarHandle vh = valueLayout(p).varHandle();
            // VarHandle SET access: (MemorySegment, long offset, T) -> void
            MethodHandle setter = vh.toMethodHandle(VarHandle.AccessMode.SET);

            MethodHandle narrowed;
            switch (p) {
                case F32 -> narrowed = MethodHandles.filterArguments(setter, 2, longBitsToFloat);
                case F64 -> narrowed = MethodHandles.filterArguments(setter, 2, longBitsToDouble);
                default -> narrowed = MethodHandles.explicitCastArguments(setter,
                        MethodType.methodType(void.class, MemorySegment.class, long.class, long.class));
            }
            mhs[p.ordinal()] = narrowed;
        }
        return mhs;
    }

    /// Write `bits` at `offset` in `seg`, narrowed or bit-reinterpreted to the ptype's carrier.
    /// Float bits use `Float.intBitsToFloat` / `Double.longBitsToDouble` semantics.
    ///
    /// @param seg    destination segment
    /// @param offset byte offset within `seg` to write at
    /// @param ptype  physical type deciding the carrier width and interpretation
    /// @param bits   the value, as raw bits widened to a `long`
    public static void set(MemorySegment seg, long offset, PType ptype, long bits) {
        try {
            SETTERS[ptype.ordinal()].invokeExact(seg, offset, bits);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    /// Bulk-copy a primitive Java array into a freshly allocated little-endian segment.
    /// Element layout conversion (host order → LE) is delegated to `MemorySegment.copy`.
    ///
    /// @param ptype      physical type deciding the element width
    /// @param typedArray the source array; its runtime type must match `ptype`
    ///                   (`byte[]` for I8/U8, `short[]` for I16/U16, and so on)
    /// @param count      number of leading elements to copy
    /// @return a freshly allocated little-endian segment holding `count` elements
    public static MemorySegment copyArray(PType ptype, Object typedArray, int count) {
        MemorySegment src;
        ValueLayout srcLayout;
        switch (typedArray) {
            case byte[] a -> {
                src = MemorySegment.ofArray(a);
                srcLayout = ValueLayout.JAVA_BYTE;
            }
            case short[] a -> {
                src = MemorySegment.ofArray(a);
                srcLayout = ValueLayout.JAVA_SHORT;
            }
            case int[] a -> {
                src = MemorySegment.ofArray(a);
                srcLayout = ValueLayout.JAVA_INT;
            }
            case long[] a -> {
                src = MemorySegment.ofArray(a);
                srcLayout = ValueLayout.JAVA_LONG;
            }
            case float[] a -> {
                src = MemorySegment.ofArray(a);
                srcLayout = ValueLayout.JAVA_FLOAT;
            }
            case double[] a -> {
                src = MemorySegment.ofArray(a);
                srcLayout = ValueLayout.JAVA_DOUBLE;
            }
            default -> throw new UnsupportedOperationException(
                    "unsupported array type: " + typedArray.getClass());
        }
        MemorySegment dst = Arena.ofAuto().allocate((long) count * ptype.byteSize());
        MemorySegment.copy(src, srcLayout, 0L, dst, valueLayout(ptype), 0L, count);
        return dst;
    }
}
