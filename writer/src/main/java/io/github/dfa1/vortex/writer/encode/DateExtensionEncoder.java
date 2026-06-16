package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.encoding.TimeUnit;
import io.github.dfa1.vortex.extension.ExtensionId;
import io.github.dfa1.vortex.writer.ExtensionEncoder;

import java.nio.ByteBuffer;
import java.time.LocalDate;
import java.util.Collection;

/// Write-side encoder for `vortex.date`: converts `Collection<LocalDate>`
/// to the `int[]` storage layout the writer accepts.
public final class DateExtensionEncoder implements ExtensionEncoder {

    /// Singleton instance.
    public static final DateExtensionEncoder INSTANCE = new DateExtensionEncoder();

    /// Public no-arg constructor for {@link java.util.ServiceLoader}.
    /// Prefer the {@link #INSTANCE} singleton in application code.
    public DateExtensionEncoder() {
    }

    @Override
    public ExtensionId extensionId() {
        return ExtensionId.VORTEX_DATE;
    }

    @Override
    public DType.Extension dtype(boolean nullable) {
        // Rust vortex.date metadata: 1 byte = TimeUnit tag (Days = 4), required by Rust reader.
        ByteBuffer meta = ByteBuffer.allocate(1);
        meta.put(0, (byte) TimeUnit.Days.ordinal());
        return new DType.Extension(
                ExtensionId.VORTEX_DATE.id(),
                new DType.Primitive(PType.I32, nullable),
                meta,
                nullable);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object encodeAll(DType.Extension dtype, Collection<?> values) {
        Collection<LocalDate> typed = (Collection<LocalDate>) values;
        int n = typed.size();
        int[] out = new int[n];
        boolean[] validity = new boolean[n];
        boolean anyNull = false;
        int i = 0;
        for (LocalDate v : typed) {
            if (v == null) {
                anyNull = true;
            } else {
                out[i] = Math.toIntExact(v.toEpochDay());
                validity[i] = true;
            }
            i++;
        }
        if (!anyNull) {
            return out;
        }
        if (!dtype.nullable()) {
            throw new VortexException("null element in non-nullable vortex.date column");
        }
        return new NullableData(out, validity);
    }
}
