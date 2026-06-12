package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.NullableData;
import io.github.dfa1.vortex.extension.DateExtension;
import io.github.dfa1.vortex.extension.ExtensionEncoder;
import io.github.dfa1.vortex.extension.ExtensionId;

import java.time.LocalDate;
import java.util.Collection;

/// Write-side encoder for {@code vortex.date}: converts {@code Collection<LocalDate>}
/// to the {@code int[]} storage layout the writer accepts.
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
        return DateExtension.INSTANCE.dtype(nullable);
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
                out[i] = DateExtension.INSTANCE.encode(v);
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
