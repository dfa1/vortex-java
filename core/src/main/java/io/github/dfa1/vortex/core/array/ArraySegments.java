package io.github.dfa1.vortex.core.array;

import io.github.dfa1.vortex.core.VortexException;

import java.lang.foreign.MemorySegment;

/// Utility for extracting the primary {@link MemorySegment} from any {@link Array}.
///
/// <p>If {@code arr} is a {@link MaskedArray}, the inner (data) segment is returned;
/// the validity mask is not surfaced here — callers that need validity must unwrap manually.
///
/// @apiNote <strong>Internal utility.</strong> This class is {@code public} only because the
/// vortex-java reader, writer, and encoding implementations live in separate Maven modules and
/// need cross-package access to the raw backing segment of typed arrays. It is not part of the
/// supported public API: signatures may change without a deprecation cycle. Application code
/// should prefer the typed accessors on concrete subtypes — {@link LongArray#getLong(long)},
/// {@link IntArray#getInt(long)}, {@link DoubleArray#getDouble(long)}, and friends — and treat
/// {@code ArraySegments} as a Vortex-internal escape hatch.
public final class ArraySegments {

    private ArraySegments() {
    }

    /// Returns the primary backing segment of {@code arr}.
    ///
    /// @param arr the array whose segment is needed
    /// @return the primary {@link MemorySegment}
    /// @throws VortexException if the array type has no primary segment
    public static MemorySegment of(Array arr) {
        Array data = arr instanceof MaskedArray m ? m.inner() : arr;
        return switch (data) {
            case IntArray a -> a.buffer();
            case LongArray a -> a.buffer();
            case DoubleArray a -> a.buffer();
            case FloatArray a -> a.buffer();
            case ShortArray a -> a.buffer();
            case ByteArray a -> a.buffer();
            case BoolArray a -> a.buffer();
            case Float16Array a -> a.buffer();
            case VarBinArray a -> a.buffer();
            case GenericArray a -> a.buffer(0);
            default -> throw new VortexException(data.getClass().getSimpleName() + " has no primary segment");
        };
    }
}
