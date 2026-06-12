package io.github.dfa1.vortex.extension;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.ByteArray;
import io.github.dfa1.vortex.core.array.FixedSizeListArray;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/// {@code vortex.uuid} — 16-byte UUID stored as {@code FixedSizeList(Primitive(U8), 16)}.
public final class UuidExtension implements Extension {

    /// Singleton instance.
    public static final UuidExtension INSTANCE = new UuidExtension();

    /// Public no-arg constructor for {@link java.util.ServiceLoader}.
    /// Prefer the {@link #INSTANCE} singleton in application code.
    public UuidExtension() {
    }

    @Override
    public ExtensionId extensionId() {
        return ExtensionId.VORTEX_UUID;
    }

    @Override
    public DType.Extension dtype(boolean nullable) {
        DType.Primitive u8 = new DType.Primitive(PType.U8, false);
        return new DType.Extension(
                ExtensionId.VORTEX_UUID.id(),
                new DType.FixedSizeList(u8, 16, nullable),
                null,
                nullable);
    }

    /// Decodes the UUID cell at row {@code i}.
    ///
    /// @param storage UUID storage array
    /// @param i       row index, {@code 0 <= i < storage.length()}
    /// @return decoded {@link UUID}
    /// @throws VortexException if storage isn't a {@code FixedSizeListArray<ByteArray>} of size 16
    public UUID decode(Array storage, long i) {
        ExtensionStorage.checkBounds(i, storage.length());
        if (storage instanceof io.github.dfa1.vortex.core.array.MaskedArray masked) {
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

    /// Decodes every row of {@code storage} into a list of UUIDs. {@link io.github.dfa1.vortex.core.array.MaskedArray}
    /// storage yields {@code null} at invalid positions instead of throwing.
    ///
    /// @param storage UUID storage array (optionally wrapped in {@code MaskedArray})
    /// @return list of decoded UUIDs in row order; {@code null} entries mark invalid rows
    public List<UUID> decodeAll(Array storage) {
        int n = Math.toIntExact(storage.length());
        List<UUID> out = new ArrayList<>(n);
        if (storage instanceof io.github.dfa1.vortex.core.array.MaskedArray masked) {
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

    /// Encodes a UUID as 16 big-endian bytes.
    ///
    /// @param value UUID to encode
    /// @return new {@code byte[16]} carrying the canonical big-endian layout
    public byte[] encode(UUID value) {
        byte[] out = new byte[16];
        long msb = value.getMostSignificantBits();
        long lsb = value.getLeastSignificantBits();
        for (int k = 0; k < 8; k++) {
            out[k] = (byte) ((msb >> (56 - 8 * k)) & 0xff);
            out[8 + k] = (byte) ((lsb >> (56 - 8 * k)) & 0xff);
        }
        return out;
    }

}
