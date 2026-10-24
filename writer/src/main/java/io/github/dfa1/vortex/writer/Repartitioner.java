package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.writer.encode.ComparableValues;
import io.github.dfa1.vortex.writer.encode.DateTimePartsData;
import io.github.dfa1.vortex.writer.encode.NullableData;

import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/// Coalesces one column's `writeChunk` batches into the chunks Rust's default write strategy
/// emits (`vortex-file/src/strategy.rs`). Its outer `RepartitionStrategy` first re-cuts the stream
/// into [#BLOCK_ROWS]-row blocks aligned to the column's first row, whatever the batches; the inner
/// one (`vortex-layout/src/layouts/repartition.rs`) emits every pending block as one chunk the
/// moment they reach [#BLOCK_BYTES], each block measured as a whole by Rust's exact uncompressed
/// size. The partial block at the end becomes the last chunk, after the pending whole blocks when
/// it tops them up to [#BLOCK_BYTES]. So a fixed-width column gets chunks of the fewest whole blocks
/// reaching 1 MB: 131 072 rows of 8-byte values, 524 288 of U16 dict codes.
///
/// Chunk size drives both segment granularity and compression: a ~1 MB chunk gives the cascade's
/// sample enough rows to pick what Rust picks (#458, #470).
final class Repartitioner {

    /// Rust's `row_block_size`.
    static final int BLOCK_ROWS = 8192;

    /// Rust's `data_block_target_bytes` (`ONE_MEG`), the coalescing `block_size_minimum`.
    static final long BLOCK_BYTES = 1L << 20;

    // A row range of one batch inside one block, with the bytes its valid out-of-line strings add.
    private record Piece(Object data, int offset, int length, long outOfLineBytes) {
    }

    private final DType dtype;
    private final List<Piece> block = new ArrayList<>();
    private int blockRows;
    private final List<Piece> pending = new ArrayList<>();
    private long pendingBytes;
    private long rows;

    Repartitioner(DType dtype) {
        this.dtype = dtype;
    }

    /// Whether this column's carriers can be coalesced. Nested carriers (structs, lists, maps,
    /// variants) are written one chunk per batch.
    static boolean supports(DType dtype) {
        DType storage = dtype instanceof DType.Extension ext ? ext.storageDType() : dtype;
        return storage instanceof DType.Primitive || storage instanceof DType.Bool
                || storage instanceof DType.Decimal || storage instanceof DType.Utf8
                || storage instanceof DType.Binary;
    }

    /// The rows added so far: where the next batch starts.
    long rows() {
        return rows;
    }

    /// Measures the pieces of a batch ahead of [#add(Object,long,long[])], so a caller can measure
    /// the columns of a batch in parallel: columns are independent, and measuring a string column
    /// reads every one of its strings.
    ///
    /// @param data     the batch, as it will be handed to [#add(Object,long,long[])]
    /// @param rows     the batch's row count
    /// @param firstRow the column's [#rows()] before the batch
    /// @return the out-of-line string bytes of each piece, in order
    static long[] sizePieces(Object data, long rows, long firstRow) {
        int len = (int) rows;
        int first = pieceRows(len, firstRow);
        long[] sizes = new long[len == 0 ? 0 : 1 + (len - first + BLOCK_ROWS - 1) / BLOCK_ROWS];
        int piece = 0;
        int off = 0;
        while (off < len) {
            int length = pieceRows(len - off, firstRow + off);
            sizes[piece] = outOfLineBytes(data, off, length);
            piece++;
            off += length;
        }
        return sizes;
    }

    /// Whether sizing a batch of `data` reads its values, as for strings and binaries -- the
    /// only batches worth [#sizePieces(Object,long,long)] ahead of time.
    ///
    /// @param data the batch
    /// @return `true` for a variable-width batch
    static boolean sizeReadsValues(Object data) {
        return values(data) instanceof Object[];
    }

    /// Adds one batch and returns the chunks now ready, in order.
    ///
    /// @param data the batch, as handed to the column's encoder
    /// @param rows the batch's row count
    /// @return the chunks to write, possibly none
    List<Object> add(Object data, long rows) {
        return add(data, rows, null);
    }

    /// Adds one batch whose pieces were already measured by [#sizePieces(Object,long,long)].
    ///
    /// @param data       the batch, as handed to the column's encoder
    /// @param rows       the batch's row count
    /// @param pieceSizes the batch's [#sizePieces(Object,long,long)], or `null` to measure it here
    /// @return the chunks to write, possibly none
    List<Object> add(Object data, long rows, long[] pieceSizes) {
        List<Object> out = new ArrayList<>();
        int len = (int) rows;
        int piece = 0;
        int off = 0;
        while (off < len) {
            int length = pieceRows(len - off, this.rows);
            long outOfLine = pieceSizes != null ? pieceSizes[piece] : outOfLineBytes(data, off, length);
            piece++;
            block.add(new Piece(data, off, length, outOfLine));
            blockRows += length;
            this.rows += length;
            off += length;
            if (blockRows == BLOCK_ROWS) {
                pendingBytes += blockBytes();
                pending.addAll(block);
                block.clear();
                blockRows = 0;
                if (pendingBytes >= BLOCK_BYTES) {
                    out.add(drainPending());
                }
            }
        }
        return out;
    }

    /// The rows still pending, as the column's last chunks: the whole blocks, when the partial
    /// block tops them up to [#BLOCK_BYTES], then the partial block, as Rust's `ChunksBuffer`
    /// does when the outer repartition flushes it; otherwise all of them as one chunk.
    List<Object> finish() {
        List<Object> out = new ArrayList<>();
        if (!pending.isEmpty() && pendingBytes + blockBytes() >= BLOCK_BYTES) {
            out.add(drainPending());
        }
        pending.addAll(block);
        if (!pending.isEmpty()) {
            out.add(drainPending());
        }
        block.clear();
        blockRows = 0;
        return out;
    }

    // A piece starting at column row `at` runs to the end of that row's block, or of the batch.
    private static int pieceRows(int remaining, long at) {
        return (int) Math.min(remaining, BLOCK_ROWS - at % BLOCK_ROWS);
    }

    private Object drainPending() {
        Object chunk = concat(pending);
        pending.clear();
        pendingBytes = 0;
        return chunk;
    }

    /// Rust's exact `uncompressed_size_in_bytes` of the block being filled, as one canonical
    /// array (`vortex-array/src/aggregate_fn/fns/uncompressed_size_in_bytes`): fixed-width values
    /// at their width, Utf8/Binary as a 16-byte view per row plus each valid value too long to
    /// inline in its view, and the validity per [#validityBytes(long,long)].
    private long blockBytes() {
        long outOfLine = 0;
        long valid = 0;
        for (Piece p : block) {
            outOfLine += p.outOfLineBytes();
            valid += validRows(p);
        }
        DType storage = dtype instanceof DType.Extension ext ? ext.storageDType() : dtype;
        long values = switch (storage) {
            case DType.Utf8 _, DType.Binary _ -> 16L * blockRows + outOfLine;
            case DType.Bool _ -> (blockRows + 7L) / 8;
            default -> (long) blockRows * elementBytes(storage);
        };
        return values + validityBytes(valid, blockRows);
    }

    /// Rust's `validity_uncompressed_size_in_bytes` of a block's mask: nothing when every row is
    /// valid, a bitmap when some are, and when none are, a `ConstantArray(false)`'s one buffer --
    /// the 2-byte proto `ScalarValue { bool_value: false }`.
    private static long validityBytes(long valid, long rows) {
        if (valid == rows) {
            return 0;
        }
        if (valid == 0) {
            return 2;
        }
        return (rows + 7) / 8;
    }

    private static long validRows(Piece p) {
        if (!(p.data() instanceof NullableData nd)) {
            return p.length();
        }
        long valid = 0;
        for (int i = p.offset(); i < p.offset() + p.length(); i++) {
            if (nd.validity()[i]) {
                valid++;
            }
        }
        return valid;
    }

    // The bytes of the valid values longer than 12, which a VarBinView keeps out of its views
    // (BinaryView::MAX_INLINED_SIZE); zero for any other carrier.
    private static long outOfLineBytes(Object data, int offset, int rows) {
        boolean[] validity = data instanceof NullableData nd ? nd.validity() : null;
        long n = 0;
        switch (values(data)) {
            case String[] a -> {
                for (int i = offset; i < offset + rows; i++) {
                    if (a[i] != null && (validity == null || validity[i])) {
                        // Encoding to count beats a char-by-char count by 2x: the JDK encodes ASCII
                        // with a vectorized copy, and the throwaway array dies young.
                        int length = a[i].getBytes(StandardCharsets.UTF_8).length;
                        n += length > 12 ? length : 0;
                    }
                }
            }
            case byte[][] a -> {
                for (int i = offset; i < offset + rows; i++) {
                    if (a[i] != null && (validity == null || validity[i])) {
                        n += a[i].length > 12 ? a[i].length : 0;
                    }
                }
            }
            default -> {
                // fixed width: blockBytes sizes it from the dtype
            }
        }
        return n;
    }

    private static long elementBytes(DType storage) {
        return switch (storage) {
            case DType.Primitive p -> p.ptype().byteSize();
            case DType.Decimal d -> d.byteWidth();
            default -> 8;
        };
    }

    // ── carriers ───────────────────────────────────────────────────────────

    private static Object values(Object data) {
        Object v = data instanceof NullableData nd ? nd.values() : data;
        return v instanceof ComparableValues cv ? cv.values() : v;
    }

    private static Object concat(List<Piece> parts) {
        Piece first = parts.getFirst();
        if (parts.size() == 1 && first.offset() == 0 && first.length() == Array.getLength(rawValues(first.data()))) {
            return first.data();
        }
        boolean nullable = parts.stream().anyMatch(p -> p.data() instanceof NullableData);
        int total = parts.stream().mapToInt(Piece::length).sum();
        Object out = Array.newInstance(rawValues(first.data()).getClass().getComponentType(), total);
        boolean[] validity = nullable ? new boolean[total] : null;
        int at = 0;
        for (Piece p : parts) {
            System.arraycopy(rawValues(p.data()), p.offset(), out, at, p.length());
            if (validity != null) {
                if (p.data() instanceof NullableData nd) {
                    System.arraycopy(nd.validity(), p.offset(), validity, at, p.length());
                } else {
                    Arrays.fill(validity, at, at + p.length(), true);
                }
            }
            at += p.length();
        }
        return rewrap(first.data(), out, validity);
    }

    /// The plain array behind a carrier: the values of a [NullableData], the raw timestamps of a
    /// [DateTimePartsData].
    private static Object rawValues(Object data) {
        Object v = data instanceof NullableData nd ? nd.values() : data;
        return v instanceof DateTimePartsData dtp ? dtp.timestamps() : v;
    }

    /// Rebuilds `like`'s carrier shape around new values (and validity, for a nullable carrier).
    private static Object rewrap(Object like, Object values, boolean[] validity) {
        Object inner = like instanceof NullableData nd ? nd.values() : like;
        Object wrapped = inner instanceof DateTimePartsData dtp ? new DateTimePartsData((long[]) values, dtp.nullable()) : values;
        return validity == null ? wrapped : new NullableData(wrapped, validity);
    }
}
