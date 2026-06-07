package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.BoolArray;
import io.github.dfa1.vortex.core.array.ByteArray;
import io.github.dfa1.vortex.core.array.DoubleArray;
import io.github.dfa1.vortex.core.array.Float16Array;
import io.github.dfa1.vortex.core.array.FloatArray;
import io.github.dfa1.vortex.core.array.GenericArray;
import io.github.dfa1.vortex.core.array.IntArray;
import io.github.dfa1.vortex.core.array.LongArray;
import io.github.dfa1.vortex.core.array.MaskedArray;
import io.github.dfa1.vortex.core.array.ShortArray;
import io.github.dfa1.vortex.core.array.VarBinArray;

import java.lang.foreign.MemorySegment;

/// Package-private helper: extracts the primary {@link MemorySegment} from any {@link Array}
/// without going through the deprecated {@link Array#segment()} interface method.
///
/// <p>If {@code arr} is a {@link MaskedArray}, the inner (data) segment is returned;
/// the validity mask is not surfaced here — callers that need validity must unwrap manually.
final class ArraySegments {

    private ArraySegments() {
    }

    /// Returns the primary backing segment of {@code arr}.
    ///
    /// @param arr the array whose segment is needed
    /// @return the primary {@link MemorySegment}
    /// @throws VortexException if the array type has no primary segment
    static MemorySegment of(Array arr) {
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
