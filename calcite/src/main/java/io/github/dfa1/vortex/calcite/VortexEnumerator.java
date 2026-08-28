package io.github.dfa1.vortex.calcite;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.ScanIterator;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.ByteArray;
import io.github.dfa1.vortex.reader.array.DoubleArray;
import io.github.dfa1.vortex.reader.array.FloatArray;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.ShortArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;

import org.apache.calcite.linq4j.Enumerator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

/// Streaming [Enumerator] over a Vortex scan: advances chunk by chunk, decoding each requested
/// column once per chunk and materializing one `Object[]` row per [#moveNext()]. Rows are not
/// retained, so the working set stays at one chunk rather than the whole result.
final class VortexEnumerator implements Enumerator<Object[]> {

    private final Path file;
    private final AtomicLong chunksScannedLastQuery;
    private final String[] names;
    private final DType[] types;
    private final VortexReader reader;
    private final ScanIterator scan;
    private Chunk chunk;
    private Object[] columns;
    private long rowInChunk;
    private long chunkRows;
    private Object[] current;

    /// @param file                   the Vortex file to scan
    /// @param chunksScannedLastQuery counter reset to zero here and incremented per decoded chunk,
    ///                               so [VortexTable#chunksScannedLastQuery()] reports this scan
    /// @param options                the scan options (projection plus any pushed [io.github.dfa1.vortex.reader.RowFilter])
    /// @param names                  output column names, in emission order
    /// @param types                  output column dtypes, parallel to `names`
    VortexEnumerator(Path file, AtomicLong chunksScannedLastQuery, ScanOptions options,
                      String[] names, DType[] types) {
        this.file = file;
        this.chunksScannedLastQuery = chunksScannedLastQuery;
        this.names = names;
        this.types = types;
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
        while (true) {
            if (chunk != null && rowInChunk < chunkRows) {
                Object[] row = new Object[names.length];
                for (int c = 0; c < names.length; c++) {
                    row[c] = value(columns[c], types[c], rowInChunk);
                }
                rowInChunk++;
                current = row;
                return true;
            }
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
            columns = new Object[names.length];
            for (int c = 0; c < names.length; c++) {
                columns[c] = chunk.column(names[c]);
            }
        }
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
                case F32 -> (double) ((FloatArray) array).getFloat(r);
                case I64 -> ((LongArray) array).getLong(r);
                // U64 maps to signed BIGINT (no wider SQL integer exists): values with the high bit
                // set have no signed-long representation, so fail loud rather than surface a
                // negative to SQL — silent wrong results are the worse outcome.
                case U64 -> unsignedBigint(((LongArray) array).getLong(r));
                // U32 exceeds signed INTEGER, so it maps to BIGINT and widens losslessly here.
                case U32 -> Integer.toUnsignedLong(((IntArray) array).getInt(r));
                // Narrow ints decode to their own array width (Byte/Short/Int), not IntArray —
                // each exposes getInt(r) with the correct sign/zero extension. U16/U8 zero-extend
                // into the wider signed SQL type (INTEGER/SMALLINT) they map to.
                case I32 -> ((IntArray) array).getInt(r);
                case I16, U16 -> ((ShortArray) array).getInt(r);
                case I8, U8 -> ((ByteArray) array).getInt(r);
                default -> throw new IllegalStateException("unsupported ptype: " + p.ptype());
            };
            case DType.Utf8 _ -> ((VarBinArray) array).getString(r);
            case DType.Bool _ -> ((BoolArray) array).getBoolean(r);
            default -> throw new IllegalStateException("unsupported column dtype: " + type);
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
