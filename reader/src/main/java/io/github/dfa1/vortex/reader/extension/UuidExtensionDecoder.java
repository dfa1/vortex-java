package io.github.dfa1.vortex.reader.extension;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.IoBounds;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.ByteArray;
import io.github.dfa1.vortex.reader.array.FixedSizeListArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.extension.ExtensionId;

import io.github.dfa1.vortex.reader.ExtensionDecoder;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/// `vortex.uuid` — 16-byte UUID stored as `FixedSizeList(Primitive(U8), 16)`.
public final class UuidExtensionDecoder implements ExtensionDecoder {

    /// Singleton instance.
    public static final UuidExtensionDecoder INSTANCE = new UuidExtensionDecoder();

    /// Public no-arg constructor for [java.util.ServiceLoader].
    /// Prefer the [#INSTANCE] singleton in application code.
    public UuidExtensionDecoder() {
    }

    @Override
    public ExtensionId extensionId() {
        return ExtensionId.VORTEX_UUID;
    }

    @Override
    public DType.Extension dtype(boolean nullable) {
        DType.Primitive u8 = new DType.Primitive(PType.U8, false);
        // Rust vortex.uuid metadata: 0 bytes means "no specific version" — but the field
        // must be present in the flatbuffer or the Rust reader's ok_or_else() check fails.
        return new DType.Extension(
                ExtensionId.VORTEX_UUID.id(),
                new DType.FixedSizeList(u8, 16, nullable),
                ByteBuffer.allocate(0),
                nullable);
    }

    /// Decodes the UUID cell at row `i`.
    ///
    /// @param storage UUID storage array
    /// @param i       row index, `0 <= i < storage.length()`
    /// @return decoded [UUID]
    /// @throws VortexException if storage isn't a `FixedSizeListArray<ByteArray>` of size 16
    public UUID decode(Array storage, long i) {
        ExtensionStorage.checkBounds(i, storage.length());
        if (storage instanceof MaskedArray masked) {
            if (!masked.isValid(i)) {
                throw new VortexException("null cell at index " + i);
            }
            return decode(masked.inner(), i);
        }
        if (!(storage instanceof FixedSizeListArray fsl)) {
            throw new VortexException("Uuid.decode: expected FixedSizeListArray, got "
                    + storage.getClass().getSimpleName());
        }
        if (fsl.fixedSize() != 16) {
            throw new VortexException("Uuid.decode: expected fixedSize 16, got " + fsl.fixedSize());
        }
        if (!(fsl.elements() instanceof ByteArray bytes)) {
            throw new VortexException("Uuid.decode: expected ByteArray elements, got "
                    + fsl.elements().getClass().getSimpleName());
        }
        long base = i * 16;
        long msb = 0L;
        long lsb = 0L;
        for (int k = 0; k < 8; k++) {
            msb = (msb << 8) | (bytes.getByte(base + k) & 0xffL);
        }
        for (int k = 0; k < 8; k++) {
            lsb = (lsb << 8) | (bytes.getByte(base + 8 + k) & 0xffL);
        }
        return new UUID(msb, lsb);
    }

    /// Decodes every row of `storage` into a list of UUIDs. [MaskedArray]
    /// storage yields `null` at invalid positions instead of throwing.
    ///
    /// @param storage UUID storage array (optionally wrapped in `MaskedArray`)
    /// @return list of decoded UUIDs in row order; `null` entries mark invalid rows
    public List<UUID> decodeAll(Array storage) {
        int n = IoBounds.toIntSize(storage.length());
        List<UUID> out = new ArrayList<>(n);
        if (storage instanceof MaskedArray masked) {
            for (long i = 0; i < n; i++) {
                out.add(masked.isValid(i) ? decode(masked.inner(), i) : null);
            }
            return out;
        }
        for (long i = 0; i < n; i++) {
            out.add(decode(storage, i));
        }
        return out;
    }
}
