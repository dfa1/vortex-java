package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.writer.encode.ComparableValues;
import io.github.dfa1.vortex.writer.encode.DateTimePartsData;
import io.github.dfa1.vortex.writer.encode.NullableData;

import java.lang.reflect.Array;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/// Coalesces one column's `writeChunk` batches into the chunks Rust's default write strategy
/// emits (`vortex-layout/src/layouts/repartition.rs`, configured in `vortex-file/src/strategy.rs`):
/// each batch is cut into [#BLOCK_ROWS]-row pieces, and the moment at least [#BLOCK_BYTES] of
/// uncompressed data and [#BLOCK_ROWS] rows are pending, every whole block pending is emitted as
/// one chunk (a piece straddling the last block boundary is split). Whatever is pending at the end
/// becomes the last chunk. So a fixed-width column gets chunks of the fewest whole blocks reaching
/// 1 MB: 131 072 rows of 8-byte values, 524 288 of U16 dict codes.
///
/// Chunk size drives both segment granularity and compression: a ~1 MB chunk gives the cascade's
/// sample enough rows to pick what Rust picks (#458, #470).
final class Repartitioner {

    /// Rust's `row_block_size`.
    static final int BLOCK_ROWS = 8192;

    /// Rust's `data_block_target_bytes` (`ONE_MEG`), the coalescing `block_size_minimum`.
    static final long BLOCK_BYTES = 1L << 20;

    // A row range of one batch, with its Rust nbytes (Rust's ChunksBuffer entry).
    private record Piece(Object data, int offset, int length, long nbytes) {
    }

    private final DType dtype;
    private final ArrayDeque<Piece> pending = new ArrayDeque<>();
    private long pendingRows;
    private long pendingBytes;

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

    /// Adds one batch and returns the chunks now ready, in order.
    ///
    /// @param data the batch, as handed to the column's encoder
    /// @param rows the batch's row count
    /// @return the chunks to write, possibly none
    List<Object> add(Object data, long rows) {
        return add(data, rows, null);
    }

    /// Sizes the [#BLOCK_ROWS]-row pieces of a batch ahead of [#add(Object,long,long[])], so a caller
    /// can measure the columns of a batch in parallel: columns are independent, and measuring a
    /// string column reads every one of its strings.
    ///
    /// @param data the batch, as it will be handed to [#add(Object,long,long[])]
    /// @param rows the batch's row count
    /// @return the byte count of each piece, in order
    long[] sizePieces(Object data, long rows) {
        int len = (int) rows;
        long[] sizes = new long[(len + BLOCK_ROWS - 1) / BLOCK_ROWS];
        for (int i = 0; i < sizes.length; i++) {
            int off = i * BLOCK_ROWS;
            sizes[i] = nbytes(data, off, Math.min(BLOCK_ROWS, len - off));
        }
        return sizes;
    }

    /// Whether sizing a batch of `data` reads its values, as for strings and binaries -- the
    /// only batches worth [#sizePieces(Object,long)] ahead of time.
    ///
    /// @param data the batch
    /// @return `true` for a variable-width batch
    static boolean sizeReadsValues(Object data) {
        return values(data) instanceof Object[];
    }

    /// Adds one batch whose pieces were already sized by [#sizePieces(Object,long)].
    ///
    /// @param data       the batch, as handed to the column's encoder
    /// @param rows       the batch's row count
    /// @param pieceSizes the batch's [#sizePieces(Object,long)], or `null` to size it here
    /// @return the chunks to write, possibly none
    List<Object> add(Object data, long rows, long[] pieceSizes) {
        List<Object> out = new ArrayList<>();
        int len = (int) rows;
        for (int off = 0; off < len; off += BLOCK_ROWS) {
            int length = Math.min(BLOCK_ROWS, len - off);
            pushBack(pieceSizes != null
                    ? new Piece(data, off, length, pieceSizes[off / BLOCK_ROWS])
                    : piece(data, off, length));
            if (pendingBytes >= BLOCK_BYTES && pendingRows >= BLOCK_ROWS) {
                out.add(collectExactBlocks());
            }
        }
        return out;
    }

    /// The rows still pending, as the column's last chunk, or `null` when none are.
    Object finish() {
        if (pending.isEmpty()) {
            return null;
        }
        List<Piece> rest = new ArrayList<>(pending);
        pending.clear();
        pendingRows = 0;
        pendingBytes = 0;
        return concat(rest);
    }

    // Rust's ChunksBuffer::collect_exact_blocks: pops whole blocks' worth of rows, splitting the
    // piece that straddles the boundary and keeping its tail pending.
    private Object collectExactBlocks() {
        long remaining = pendingRows / BLOCK_ROWS * BLOCK_ROWS;
        List<Piece> taken = new ArrayList<>();
        while (remaining > 0) {
            Piece p = popFront();
            if (p.length() > remaining) {
                int head = (int) remaining;
                taken.add(piece(p.data(), p.offset(), head));
                pushFront(piece(p.data(), p.offset() + head, p.length() - head));
                remaining = 0;
            } else {
                taken.add(p);
                remaining -= p.length();
            }
        }
        return concat(taken);
    }

    private Piece piece(Object data, int offset, int length) {
        return new Piece(data, offset, length, nbytes(data, offset, length));
    }

    private void pushBack(Piece p) {
        pending.addLast(p);
        pendingRows += p.length();
        pendingBytes += p.nbytes();
    }

    private void pushFront(Piece p) {
        pending.addFirst(p);
        pendingRows += p.length();
        pendingBytes += p.nbytes();
    }

    private Piece popFront() {
        Piece p = pending.removeFirst();
        pendingRows -= p.length();
        pendingBytes -= p.nbytes();
        return p;
    }

    /// Rust's `ArrayRef::nbytes` of the canonical array: fixed-width values at their width plus a
    /// validity bitmap when nullable, and Utf8/Binary as Rust's canonical `VarBinView` -- a 16-byte
    /// view per row plus its bytes.
    // ponytail: a sliced Rust VarBinView counts its whole shared data buffers, so string chunk
    // boundaries can differ from Rust's; exact only for fixed-width columns.
    private long nbytes(Object data, int offset, int rows) {
        long validity = data instanceof NullableData ? (rows + 7L) / 8 : 0;
        Object values = values(data);
        return validity + switch (values) {
            case String[] a -> {
                long n = 16L * rows;
                for (int i = offset; i < offset + rows; i++) {
                    n += a[i] == null ? 0 : utf8Length(a[i]);
                }
                yield n;
            }
            case byte[][] a -> {
                long n = 16L * rows;
                for (int i = offset; i < offset + rows; i++) {
                    n += a[i] == null ? 0 : a[i].length;
                }
                yield n;
            }
            case boolean[] _ -> (rows + 7L) / 8;
            default -> (long) rows * elementBytes();
        };
    }

    /// The length of `s` in UTF-8, counted without encoding it: sizing a batch must not cost a copy
    /// of every string, as it runs on the caller's thread, ahead of the parallel compression.
    /// An unpaired surrogate is one byte, as `getBytes` encodes it (`?`).
    static int utf8Length(String s) {
        int n = s.length();
        int bytes = n;
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c >= 0x800) {
                if (Character.isHighSurrogate(c) && i + 1 < n && Character.isLowSurrogate(s.charAt(i + 1))) {
                    bytes += 2;
                    i++;
                } else if (Character.isSurrogate(c)) {
                    continue;
                } else {
                    bytes += 2;
                }
            } else if (c >= 0x80) {
                bytes++;
            }
        }
        return bytes;
    }

    private long elementBytes() {
        DType storage = dtype instanceof DType.Extension ext ? ext.storageDType() : dtype;
        return switch (storage) {
            case DType.Primitive p -> p.ptype().byteSize();
            case DType.Decimal d -> decimalBytes(d.precision());
            default -> 8;
        };
    }

    private static int decimalBytes(int precision) {
        if (precision <= 2) {
            return 1;
        }
        if (precision <= 4) {
            return 2;
        }
        if (precision <= 9) {
            return 4;
        }
        if (precision <= 18) {
            return 8;
        }
        return precision <= 38 ? 16 : 32;
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
