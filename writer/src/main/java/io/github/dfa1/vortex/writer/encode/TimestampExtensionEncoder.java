package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.NullableData;
import io.github.dfa1.vortex.extension.ExtensionEncoder;
import io.github.dfa1.vortex.extension.ExtensionId;
import io.github.dfa1.vortex.extension.ExtensionStorage;
import io.github.dfa1.vortex.extension.TimestampExtension;
import io.github.dfa1.vortex.encoding.TimeUnit;

import java.time.Instant;
import java.util.Collection;

/// Write-side encoder for {@code vortex.timestamp}: converts {@code Collection<Instant>}
/// to the {@code long[]} storage layout the writer accepts.
public final class TimestampExtensionEncoder implements ExtensionEncoder {

    /// Singleton instance.
    public static final TimestampExtensionEncoder INSTANCE = new TimestampExtensionEncoder();

    /// Public no-arg constructor for {@link java.util.ServiceLoader}.
    /// Prefer the {@link #INSTANCE} singleton in application code.
    public TimestampExtensionEncoder() {
    }

    @Override
    public ExtensionId extensionId() {
        return ExtensionId.VORTEX_TIMESTAMP;
    }

    @Override
    public DType.Extension dtype(boolean nullable) {
        return TimestampExtension.INSTANCE.dtype(nullable);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object encodeAll(DType.Extension dtype, Collection<?> values) {
        TimeUnit unit = ExtensionStorage.readUnit(dtype);
        Collection<Instant> typed = (Collection<Instant>) values;
        int n = typed.size();
        long[] out = new long[n];
        boolean[] validity = new boolean[n];
        boolean anyNull = false;
        int i = 0;
        for (Instant v : typed) {
            if (v == null) {
                anyNull = true;
            } else {
                out[i] = TimestampExtension.INSTANCE.encode(v, unit);
                validity[i] = true;
            }
            i++;
        }
        if (!anyNull) {
            return out;
        }
        if (!dtype.nullable()) {
            throw new VortexException("null element in non-nullable vortex.timestamp column");
        }
        return new NullableData(out, validity);
    }
}
