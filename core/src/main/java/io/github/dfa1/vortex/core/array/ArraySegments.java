package io.github.dfa1.vortex.core.array;

import io.github.dfa1.vortex.core.VortexException;

import java.lang.foreign.MemorySegment;

/// Utility for extracting the primary {@link MemorySegment} from any {@link Array}.
///
/// <p>If {@code arr} is a {@link MaskedArray}, the inner (data) segment is returned;
/// the validity mask is not surfaced here — callers that need validity must unwrap manually.
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
            case IntArray a -> a.segment();
            case LongArray a -> a.segment();
            case DoubleArray a -> a.segment();
            case FloatArray a -> a.segment();
            case ShortArray a -> a.segment();
            case ByteArray a -> a.segment();
            case BoolArray a -> a.segment();
            case Float16Array a -> a.segment();
            case VarBinArray a -> a.segment();
            case GenericArray a -> a.segment();
            default -> throw new VortexException(data.getClass().getSimpleName() + " has no primary segment");
        };
    }
}
