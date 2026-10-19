package io.github.dfa1.vortex.calcite;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.ExtensionId;
import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.RowFilter;
import io.github.dfa1.vortex.reader.ScanIterator;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.compute.Compute;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.ByteArray;
import io.github.dfa1.vortex.reader.array.DecimalArray;
import io.github.dfa1.vortex.reader.array.DoubleArray;
import io.github.dfa1.vortex.reader.array.Float16Array;
import io.github.dfa1.vortex.reader.array.FloatArray;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.ShortArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import io.github.dfa1.vortex.reader.extension.DateExtensionDecoder;
import io.github.dfa1.vortex.reader.extension.TimeExtensionDecoder;
import io.github.dfa1.vortex.reader.extension.TimestampExtensionDecoder;
import io.github.dfa1.vortex.reader.extension.UuidExtensionDecoder;

import org.apache.calcite.avatica.util.ByteString;
import org.apache.calcite.linq4j.Enumerator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongPredicate;

/// Streaming [Enumerator] over a Vortex scan: advances chunk by chunk, decoding each requested
/// column once per chunk and materializing one `Object[]` row per [#moveNext()]. Rows are not
/// retained, so the working set stays at one chunk rather than the whole result.
final class VortexEnumerator implements Enumerator<Object[]> {

    private final Path file;
    private final AtomicLong chunksScannedLastQuery;
    private final ColumnName[] names;
    private final DType[] types;
    /// Per output column, its temporal conversion or `null`: resolved once, not per row.
    private final Temporal[] temporals;
    private final RowFilter exactFilter;
    private final VortexReader reader;
    private final ScanIterator scan;
    private Chunk chunk;
    private LongPredicate rowMatcher;
    private Object[] columns;
    private long rowInChunk;
    private long currentRow;
    private long chunkRows;
    private Object[] current;

    /// @param file                   the Vortex file to scan
    /// @param chunksScannedLastQuery counter reset to zero here and incremented per decoded chunk,
    ///                               so [VortexTable#chunksScannedLastQuery()] reports this scan
    /// @param options                the scan options (projection plus any pushed [io.github.dfa1.vortex.reader.RowFilter])
    /// @param names                  output column names, in emission order
    /// @param types                  output column dtypes, parallel to `names`
    /// @param exactFilter            a predicate [VortexTable#scan(DataContext, java.util.List, int[])]
    ///                               removed from Calcite's own re-check list, enforced here row-by-row
    ///                               via [Compute#matcher(Chunk, RowFilter)] so the result stays
    ///                               exact — or `null` when every pushed predicate is still Calcite's to
    ///                               re-check
    VortexEnumerator(Path file, AtomicLong chunksScannedLastQuery, ScanOptions options,
                      ColumnName[] names, DType[] types, RowFilter exactFilter) {
        this.file = file;
        this.chunksScannedLastQuery = chunksScannedLastQuery;
        this.names = names;
        this.types = types;
        this.temporals = new Temporal[types.length];
        for (int c = 0; c < types.length; c++) {
            temporals[c] = Temporal.of(types[c]);
        }
        this.exactFilter = exactFilter;
        chunksScannedLastQuery.set(0);
        VortexReader openedReader = null;
        try {
            openedReader = VortexReader.open(file);
            this.reader = openedReader;
            this.scan = openedReader.scan(options);
        } catch (IOException e) {
            closeQuietly(openedReader);
            throw new UncheckedIOException("cannot scan " + file, e);
        } catch (RuntimeException e) {
            closeQuietly(openedReader);
            throw e;
        }
    }

    private void closeQuietly(VortexReader r) {
        if (r != null) {
            r.close();
        }
    }

    @Override
    public Object[] current() {
        return current;
    }

    @Override
    public boolean moveNext() {
        if (!advance()) {
            return false;
        }
        current = row();
        return true;
    }

    /// Moves to the next row the filter keeps without building it, so a caller that needs only a
    /// few of its columns ([#column(int)]) does not box the rest.
    ///
    /// @return `false` when the scan is exhausted
    boolean advance() {
        while (!advanceInChunk()) {
            if (!nextChunk()) {
                return false;
            }
        }
        return true;
    }

    /// Moves to the next row the filter keeps in the current chunk, leaving the chunk open at its end
    /// so a caller can still read rows of it by index ([#column(int, long)]).
    ///
    /// @return `false` at the end of the chunk, or before the first [#nextChunk()]
    boolean advanceInChunk() {
        while (chunk != null && rowInChunk < chunkRows) {
            long row = rowInChunk++;
            if (exactFilter == null || rowMatcher.test(row)) {
                currentRow = row;
                return true;
            }
        }
        return false;
    }

    /// Closes the current chunk and decodes the next one the scan keeps.
    ///
    /// @return `false` when the scan is exhausted
    boolean nextChunk() {
        if (chunk != null) {
            chunk.close();
            chunk = null;
        }
        if (!scan.hasNext()) {
            return false;
        }
        chunk = scan.next();
        chunksScannedLastQuery.incrementAndGet();
        chunkRows = chunk.rowCount();
        rowInChunk = 0;
        rowMatcher = exactFilter == null ? null : Compute.matcher(chunk, exactFilter);
        // read on first use: a top-N reads only its sort keys for most rows, and the chunk then
        // decodes only those columns
        columns = new Object[names.length];
        return true;
    }

    /// The index in its chunk of the row [#advance()] moved to.
    long rowInChunk() {
        return currentRow;
    }

    /// Column `c` of the row [#advance()] moved to.
    Object column(int c) {
        return column(c, currentRow);
    }

    /// Column `c` of row `r` of the current chunk.
    Object column(int c, long r) {
        Object array = columns[c];
        if (array == null) {
            array = chunk.column(names[c]);
            columns[c] = array;
        }
        Temporal temporal = temporals[c];
        return temporal == null ? value(array, types[c], r) : temporal.read(array, r);
    }

    /// Every column of the row [#advance()] moved to.
    Object[] row() {
        Object[] row = new Object[names.length];
        for (int c = 0; c < names.length; c++) {
            row[c] = column(c);
        }
        return row;
    }

    @Override
    public void reset() {
        throw new UnsupportedOperationException("VortexEnumerator does not support reset");
    }

    @Override
    public void close() {
        try {
            if (chunk != null) {
                chunk.close();
            }
        } finally {
            scan.close();
            reader.close();
        }
    }

    private static Object value(Object array, DType type, long r) {
        // A nullable column decodes to a MaskedArray wrapping the payload plus a validity mask;
        // emit SQL NULL for an invalid row, otherwise read the row from the inner payload array.
        if (array instanceof MaskedArray masked) {
            return masked.isValid(r) ? value(masked.inner(), type, r) : null;
        }
        return switch (type) {
            case DType.Primitive p -> switch (p.ptype()) {
                case F64 -> ((DoubleArray) array).getDouble(r);
                // REAL is a java.lang.Float to Calcite's generated code: a Double here made every SQL
                // query that read an F32 column fail with a ClassCastException
                case F32 -> ((FloatArray) array).getFloat(r);
                case F16 -> ((Float16Array) array).getFloat(r);
                case I64 -> ((LongArray) array).getLong(r);
                // U64 maps to signed BIGINT (no wider SQL integer exists): values with the high bit
                // set have no signed-long representation, so fail loud rather than surface a
                // negative to SQL — silent wrong results are the worse outcome.
                case U64 -> unsignedBigint(((LongArray) array).getLong(r));
                // U32 exceeds signed INTEGER, so it maps to BIGINT and widens losslessly here.
                case U32 -> Integer.toUnsignedLong(((IntArray) array).getInt(r));
                // Narrow ints decode to their own array width (Byte/Short/Int), not IntArray —
                // each exposes getInt(r) with the correct sign/zero extension. U16/U8 zero-extend
                // into the wider signed SQL type (INTEGER/SMALLINT) they map to. Each value is
                // boxed as its SQL type's Java class (SMALLINT a Short, TINYINT a Byte): Calcite's
                // generated code casts to it, so an Integer failed every WHERE on such a column.
                case I32 -> ((IntArray) array).getInt(r);
                case U16 -> ((ShortArray) array).getInt(r);
                case I16 -> (short) ((ShortArray) array).getInt(r);
                case U8 -> (short) ((ByteArray) array).getInt(r);
                case I8 -> (byte) ((ByteArray) array).getInt(r);
            };
            case DType.Utf8 _ -> ((VarBinArray) array).getString(r);
            case DType.Binary _ -> new ByteString(((VarBinArray) array).getBytes(r));
            case DType.Bool _ -> ((BoolArray) array).getBoolean(r);
            case DType.Null _ -> null;
            case DType.Decimal _ -> ((DecimalArray) array).getDecimal(r);
            // Calcite's internal forms: DATE is days since the epoch (int), TIME milliseconds of the
            // day (int), TIMESTAMP milliseconds since the epoch (long)
            case DType.Extension ext -> switch (ExtensionId.parse(ext.extensionId())) {
                case ExtensionId.WellKnown.VORTEX_DATE ->
                        Math.toIntExact(DateExtensionDecoder.INSTANCE.decode(ext, (Array) array, r).toEpochDay());
                case ExtensionId.WellKnown.VORTEX_TIME ->
                        (int) (TimeExtensionDecoder.INSTANCE.decode(ext, (Array) array, r).toNanoOfDay() / 1_000_000);
                case ExtensionId.WellKnown.VORTEX_TIMESTAMP ->
                        TimestampExtensionDecoder.INSTANCE.instant(ext, (Array) array, r).toEpochMilli();
                case ExtensionId.WellKnown.VORTEX_UUID -> UuidExtensionDecoder.INSTANCE.decode((Array) array, r);
                default -> value(array, ext.storageDType(), r);
            };
            case DType.List _, DType.FixedSizeList _, DType.Struct _, DType.Map _, DType.Union _,
                 DType.Variant _ -> throw new VortexException("SQL cannot read a " + type + " column yet");
        };
    }

    /// Guards a U64 value being surfaced to Calcite's signed `BIGINT`: values with the high bit set
    /// (`>= 2^63`) have no lossless signed-long representation, so this throws rather than let a
    /// negative reach SQL. Vortex has no wider unsigned SQL type to widen into, so loud beats wrong.
    ///
    /// @param raw the raw U64 bits read from the column
    /// @return `raw` unchanged when it fits the non-negative signed-long range
    private static long unsignedBigint(long raw) {
        if (raw < 0) {
            throw new VortexException("U64 value " + Long.toUnsignedString(raw)
                    + " exceeds the signed BIGINT range Calcite maps U64 to");
        }
        return raw;
    }
}
