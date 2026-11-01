package io.github.dfa1.vortex.core.model;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.io.MemorySize;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.HexFormat;

/// Extension metadata kept as the bytes a file stores, for an id nothing parses: Rust's
/// `ForeignExtMetadata`.
///
/// Why bytes and not `null`: a file can carry metadata Java does not interpret, and a dtype read
/// from it must keep that metadata so writing it back loses nothing, as Rust keeps it. That covers
/// the `vortex.uuid` version byte, the `vortex.st.*` geometries' SpatialMetadata protobuf (CRS,
/// edges), any custom id read with `allowUnknown()` or by a decoder that does not parse its
/// metadata, and a writer with no encoder for the id, for which these bytes are the only metadata
/// it can supply. A `null` would drop them: the file would read back in Rust as a different dtype
/// (a geometry without its CRS). [#EMPTY] is the metadata of an extension that has none, written
/// as the empty field Rust requires.
///
/// @param bytes the wire bytes, read-only, at most [#MAX_SIZE]
public record OpaqueMetadata(MemorySegment bytes) implements ExtensionMetadata {

    /// Hard cap on extension metadata size. Spec-defined extensions use 0–3 bytes (e.g. timestamp
    /// = TimeUnit tag + tz length + tz UTF-8); 64 KiB is generous for custom extensions while
    /// blocking attacker-supplied multi-megabyte blobs that would inflate parser allocations.
    public static final MemorySize MAX_SIZE = MemorySize.ofKiB(64);

    /// No metadata.
    public static final OpaqueMetadata EMPTY = new OpaqueMetadata(MemorySegment.ofArray(new byte[0]));

    /// How many bytes [#toString()] shows.
    private static final int TO_STRING_BYTES = 16;

    /// Bounds the bytes, which may arrive from an untrusted file, and keeps them read-only.
    ///
    /// @param bytes the wire bytes
    /// @throws VortexException if `bytes` is longer than [#MAX_SIZE]
    public OpaqueMetadata {
        if (bytes.byteSize() > MAX_SIZE.bytes()) {
            throw new VortexException("extension metadata too large: " + bytes.byteSize() + " > " + MAX_SIZE.bytes());
        }
        // Always a copy: a slice of the file mapping fails once the reader closes, though a dtype
        // outlives it (callers keep reader.dtype()); and a caller's heap array, changed later,
        // would change this value and its hash under a map holding the dtype. Small, as the cap
        // above bounds it.
        bytes = MemorySegment.ofArray(bytes.toArray(ValueLayout.JAVA_BYTE)).asReadOnly();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof OpaqueMetadata(MemorySegment other)
                && bytes.byteSize() == other.byteSize() && bytes.mismatch(other) == -1;
    }

    /// The `Arrays.hashCode` of the bytes, read in place rather than copied out per call.
    @Override
    public int hashCode() {
        int hash = 1;
        for (long i = 0; i < bytes.byteSize(); i++) {
            hash = 31 * hash + bytes.get(ValueLayout.JAVA_BYTE, i);
        }
        return hash;
    }

    /// The size and at most the first [#TO_STRING_BYTES] bytes in hex: the bytes can be 64 KiB and
    /// this string ends up in exception messages.
    @Override
    public String toString() {
        long shown = Math.min(bytes.byteSize(), TO_STRING_BYTES);
        String hex = HexFormat.of().formatHex(bytes.asSlice(0, shown).toArray(ValueLayout.JAVA_BYTE));
        return "OpaqueMetadata[" + bytes.byteSize() + " bytes: " + hex
                + (shown < bytes.byteSize() ? "…" : "") + "]";
    }
}
