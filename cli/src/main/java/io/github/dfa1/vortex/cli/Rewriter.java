package io.github.dfa1.vortex.cli;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.csv.ProgressListener;
import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.ScanIterator;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexHandle;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.ByteArray;
import io.github.dfa1.vortex.reader.array.DoubleArray;
import io.github.dfa1.vortex.reader.array.FloatArray;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.ShortArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;
import io.github.dfa1.vortex.writer.encode.NullableData;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongConsumer;

/// Re-encodes a file: scans every chunk of a source and writes it again through the
/// [VortexWriter], so the output is whatever the current writer and `options` pick, not what the
/// source's writer picked.
///
/// The first version covers a flat struct of primitive, bool and utf8 columns, nullable or not.
/// Anything else (nested, decimal, binary, extension, f16) is rejected up front, before the
/// output file is created, rather than written wrongly.
final class Rewriter {

    private Rewriter() {
    }

    /// Rewrites `source` into `output`.
    ///
    /// @param source   the file to read
    /// @param output   the file to create; must not exist
    /// @param options  how to write it
    /// @param progress receives the rows written so far, and the total
    /// @return the number of rows written
    /// @throws IOException      if the output cannot be written
    /// @throws VortexException  if the source has a column type this version cannot rewrite
    static long rewrite(VortexHandle source, Path output, WriteOptions options, ProgressListener progress)
            throws IOException {
        if (!(source.dtype() instanceof DType.Struct schema)) {
            throw new VortexException("rewrite needs a struct file, got " + source.dtype());
        }
        for (int i = 0; i < schema.fieldNames().size(); i++) {
            requireSupported(schema.fieldNames().get(i), schema.fieldTypes().get(i));
        }
        long total = source.layout().rowCount();
        long written = 0;
        try (FileChannel channel = FileChannel.open(output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
             VortexWriter writer = VortexWriter.create(channel, schema, options);
             ScanIterator scan = source.scan(ScanOptions.all())) {
            while (scan.hasNext()) {
                try (Chunk chunk = scan.next()) {
                    // over HTTP a column is fetched when read: start all of them together, merged
                    chunk.prefetch(schema.fieldNames());
                    Map<ColumnName, Object> columns = new LinkedHashMap<>();
                    for (Map.Entry<ColumnName, Chunk.Column> column : chunk.columns().entrySet()) {
                        columns.put(column.getKey(), carrier(column.getKey(), column.getValue()));
                    }
                    writer.writeChunk(columns);
                    written += chunk.rowCount();
                    progress.onProgress(written, total);
                }
            }
        }
        if (written != total) {
            throw new VortexException("rewrote " + written + " rows of " + total + ": the source's layout and chunks disagree");
        }
        return written;
    }

    private static void requireSupported(ColumnName name, DType dtype) {
        boolean supported = switch (dtype) {
            case DType.Primitive p -> p.ptype() != PType.F16;
            case DType.Bool _, DType.Utf8 _ -> true;
            default -> false;
        };
        if (!supported) {
            throw new VortexException("rewrite does not support column '" + name.value() + "' of type " + dtype);
        }
    }

    /// The writer's input for one column: a primitive array when the column is not nullable,
    /// otherwise a [NullableData] of the values (zero at each null, as the writer requires) and a
    /// validity per row. Utf8 is the raw bytes, `null` per null row: going through `String` would
    /// replace any byte sequence that is not valid UTF-8, which the format does not forbid.
    private static Object carrier(ColumnName name, Chunk.Column column) {
        try {
            return carrier(column.array(), column.dtype());
        } catch (ClassCastException e) {
            throw new VortexException("rewrite cannot read column '" + name.value() + "': unexpected array "
                    + column.array().getClass().getSimpleName() + " for " + column.dtype(), e);
        }
    }

    private static Object carrier(Array array, DType dtype) {
        if (array.length() > Integer.MAX_VALUE) {
            throw new VortexException("rewrite cannot hold a chunk of " + array.length() + " rows");
        }
        MaskedArray.Unwrapped unwrapped = MaskedArray.unwrap(array);
        BoolArray validity = unwrapped.validity();
        int rows = (int) array.length();
        Object values = switch (dtype) {
            case DType.Primitive p -> primitive(unwrapped.inner(), p.ptype(), validity, rows);
            case DType.Bool _ -> bools(unwrapped.inner(), validity, rows);
            default -> utf8(unwrapped.inner(), validity, rows);
        };
        if (!dtype.nullable() || dtype instanceof DType.Utf8) {
            return values;
        }
        boolean[] present = new boolean[rows];
        fill(rows, i -> present[(int) i] = valid(validity, i));
        return new NullableData(values, present);
    }

    private static Object primitive(Array values, PType ptype, BoolArray validity, int rows) {
        switch (ptype) {
            case I8, U8 -> {
                byte[] out = new byte[rows];
                fill(rows, i -> out[(int) i] = valid(validity, i) ? ((ByteArray) values).getByte(i) : 0);
                return out;
            }
            case I16, U16 -> {
                short[] out = new short[rows];
                fill(rows, i -> out[(int) i] = valid(validity, i) ? ((ShortArray) values).getShort(i) : 0);
                return out;
            }
            case I32, U32 -> {
                int[] out = new int[rows];
                fill(rows, i -> out[(int) i] = valid(validity, i) ? ((IntArray) values).getInt(i) : 0);
                return out;
            }
            case I64, U64 -> {
                long[] out = new long[rows];
                fill(rows, i -> out[(int) i] = valid(validity, i) ? ((LongArray) values).getLong(i) : 0);
                return out;
            }
            case F32 -> {
                float[] out = new float[rows];
                fill(rows, i -> out[(int) i] = valid(validity, i) ? ((FloatArray) values).getFloat(i) : 0);
                return out;
            }
            case F64 -> {
                double[] out = new double[rows];
                fill(rows, i -> out[(int) i] = valid(validity, i) ? ((DoubleArray) values).getDouble(i) : 0);
                return out;
            }
            default -> throw new VortexException("rewrite does not support " + ptype);
        }
    }

    private static boolean[] bools(Array values, BoolArray validity, int rows) {
        boolean[] out = new boolean[rows];
        fill(rows, i -> out[(int) i] = valid(validity, i) && ((BoolArray) values).getBoolean(i));
        return out;
    }

    private static byte[][] utf8(Array values, BoolArray validity, int rows) {
        byte[][] out = new byte[rows][];
        VarBinArray bytes = (VarBinArray) values;
        fill(rows, i -> out[(int) i] = valid(validity, i) ? bytes.getBytes(i) : null);
        return out;
    }

    private static boolean valid(BoolArray validity, long i) {
        return validity == null || validity.getBoolean(i);
    }

    private static void fill(int rows, LongConsumer body) {
        for (long i = 0; i < rows; i++) {
            body.accept(i);
        }
    }
}
