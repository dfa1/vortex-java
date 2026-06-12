package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.NullableData;
import io.github.dfa1.vortex.encoding.FixedSizeListData;
import io.github.dfa1.vortex.extension.ExtensionEncoder;
import io.github.dfa1.vortex.extension.ExtensionId;
import io.github.dfa1.vortex.extension.UuidExtension;

import java.util.Collection;
import java.util.UUID;

/// Write-side encoder for {@code vortex.uuid}: converts {@code Collection<UUID>}
/// to the {@code FixedSizeList(U8, 16)} storage layout the writer accepts.
public final class UuidExtensionEncoder implements ExtensionEncoder {

    /// Singleton instance.
    public static final UuidExtensionEncoder INSTANCE = new UuidExtensionEncoder();

    /// Public no-arg constructor for {@link java.util.ServiceLoader}.
    /// Prefer the {@link #INSTANCE} singleton in application code.
    public UuidExtensionEncoder() {
    }

    @Override
    public ExtensionId extensionId() {
        return ExtensionId.VORTEX_UUID;
    }

    @Override
    public DType.Extension dtype(boolean nullable) {
        return UuidExtension.INSTANCE.dtype(nullable);
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
                byte[] encoded = UuidExtension.INSTANCE.encode(v);
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
}
