package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;

/// Opaque passthrough array for encodings unknown to this reader.
///
/// Holds raw on-disk bytes: encoding id, metadata, buffers, child arrays (themselves
/// unknown), and stats. Children of an unknown node are always wrapped unknown — matches
/// Rust `decode_foreign` in `vortex-array/src/serde.rs`.
///
/// Constructed by `Registry` when `allowUnknown()` is set and an encoding id is not
/// in the registry. Data access beyond `buffer(i)` and `child(i)` is not supported.
///
/// @param encodingId the unrecognised encoding id string
/// @param dtype      logical type of the array
/// @param length     number of logical rows
/// @param metadata   raw encoding metadata bytes, or `null`
/// @param buffers    raw data buffers owned by this node
/// @param children   decoded child arrays (also wrapped as unknown)
@SuppressWarnings("java:S6218") // internal data carrier; record components are arrays of immutable primitives or refs that flow through pipelines without ever being compared.
public record UnknownArray(
        String encodingId,
        DType dtype,
        long length,
        ByteBuffer metadata,
        MemorySegment[] buffers,
        Array[] children
) implements Array {

    /// Returns the child array at position `i`.
    ///
    /// @param i child index
    /// @return the child {@link Array} at index `i`
    public Array child(int i) {
        return children[i];
    }
}
