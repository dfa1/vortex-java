package io.github.dfa1.vortex.reader.extension;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.IoBounds;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.encoding.TimeUnit;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.extension.ExtensionId;

import io.github.dfa1.vortex.reader.ExtensionDecoder;

import java.nio.ByteBuffer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/// `vortex.date` — days since the Unix epoch, signed integer storage.
/// Per Arrow's canonical Date type.
public final class DateExtensionDecoder implements ExtensionDecoder {

    /// Singleton instance.
    public static final DateExtensionDecoder INSTANCE = new DateExtensionDecoder();

    /// Public no-arg constructor for [java.util.ServiceLoader].
    /// Prefer the [#INSTANCE] singleton in application code.
    public DateExtensionDecoder() {
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

    /// Decodes the date cell at row `i`.
    ///
    /// @param storage signed-integer storage (Byte/Short/Int/Long, possibly Masked)
    /// @param i       row index, `0 <= i < storage.length()`
    /// @return decoded date
    /// @throws io.github.dfa1.vortex.core.VortexException if storage isn't an integer primitive
    public LocalDate decode(Array storage, long i) {
        ExtensionStorage.checkBounds(i, storage.length());
        return LocalDate.ofEpochDay(ExtensionStorage.epochInteger(storage, i));
    }

    /// Decodes every row of `storage` into a list of dates. [MaskedArray]
    /// storage yields `null` at invalid positions instead of throwing.
    ///
    /// @param storage signed-integer storage array (optionally wrapped in `MaskedArray`)
    /// @return list of decoded dates in row order; `null` entries mark invalid rows
    public List<LocalDate> decodeAll(Array storage) {
        int n = IoBounds.toIntSize(storage.length());
        List<LocalDate> out = new ArrayList<>(n);
        if (storage instanceof MaskedArray masked) {
            for (int i = 0; i < n; i++) {
                out.add(masked.isValid(i) ? decode(masked.inner(), i) : null);
            }
            return out;
        }
        for (int i = 0; i < n; i++) {
            out.add(decode(storage, i));
        }
        return out;
    }
}
