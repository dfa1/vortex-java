package io.github.dfa1.vortex.extension;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.array.Array;

import java.util.UUID;

/// {@code vortex.uuid} — 16-byte UUID stored as {@code FixedSizeList(Primitive(U8), 16)}.
public final class UuidExtension implements Extension {

    /// Singleton instance.
    public static final UuidExtension INSTANCE = new UuidExtension();

    private UuidExtension() {
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
    public UUID decode(Array storage, long i) {
        return io.github.dfa1.vortex.core.Extension.UUID.decode(storage, i);
    }
}
