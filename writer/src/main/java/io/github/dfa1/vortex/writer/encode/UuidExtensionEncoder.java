package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.extension.ExtensionId;
import io.github.dfa1.vortex.writer.ExtensionEncoder;

import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.UUID;

/// Write-side encoder for `vortex.uuid`: converts `Collection<UUID>`
/// to the `FixedSizeList(U8, 16)` storage layout the writer accepts.
public final class UuidExtensionEncoder implements ExtensionEncoder {

    /// Singleton instance.
    public static final UuidExtensionEncoder INSTANCE = new UuidExtensionEncoder();

    /// Public no-arg constructor for [java.util.ServiceLoader].
    /// Prefer the [#INSTANCE] singleton in application code.
    public UuidExtensionEncoder() {
    }

    @Override
    public ExtensionId extensionId() {
        return ExtensionId.VORTEX_UUID;
    }

    @Override
    public DType.Extension dtype(boolean nullable) {
        DType.Primitive u8 = DType.U8;
        // Rust vortex.uuid metadata: 0 bytes means "no specific version" — but the field
        // must be present in the flatbuffer or the Rust reader's ok_or_else() check fails.
        return new DType.Extension(
                ExtensionId.VORTEX_UUID.id(),
                new DType.FixedSizeList(u8, 16, nullable),
                ByteBuffer.allocate(0),
                nullable);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object encodeAll(DType.Extension dtype, Collection<?> values) {
        Collection<UUID> typed = (Collection<UUID>) values;
        int n = typed.size();
        byte[] flat = new byte[16 * n];
        boolean[] validity = new boolean[n];
        boolean anyNull = false;
        int row = 0;
        for (UUID v : typed) {
            if (v == null) {
                anyNull = true;
            } else {
                byte[] encoded = encode(v);
                System.arraycopy(encoded, 0, flat, row * 16, 16);
                validity[row] = true;
            }
            row++;
        }
        FixedSizeListData storage = new FixedSizeListData(flat, n);
        if (!anyNull) {
            return storage;
        }
        if (!dtype.nullable()) {
            throw new VortexException("null element in non-nullable vortex.uuid column");
        }
        return new NullableData(storage, validity);
    }

    private static byte[] encode(UUID value) {
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
