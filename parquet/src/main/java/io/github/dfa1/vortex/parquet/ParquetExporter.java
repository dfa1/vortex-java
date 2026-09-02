package io.github.dfa1.vortex.parquet;

import dev.hardwood.OutputFile;
import dev.hardwood.metadata.LogicalType;
import dev.hardwood.metadata.PhysicalType;
import dev.hardwood.metadata.RepetitionType;
import dev.hardwood.schema.FileSchema;
import dev.hardwood.writer.ColumnBatch;
import dev.hardwood.writer.ColumnWriter;
import dev.hardwood.writer.ParquetFileWriter;
import io.github.dfa1.vortex.core.io.IoBounds;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.ExtensionId;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.model.TimeUnit;
import io.github.dfa1.vortex.core.model.TimestampDtype;
import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.ScanIterator;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexHandle;
import io.github.dfa1.vortex.reader.VortexReader;
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

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/// Reads a Vortex file and writes a Parquet file.
///
/// Supports flat schemas only: every top-level column must be `Bool`, a non-`F16` `Primitive`,
/// `Utf8`, `Binary`, or a `vortex.timestamp` extension over millisecond/microsecond/nanosecond
/// resolution. `Struct`, `List`, `Map`, `F16`, and any extension other than `vortex.timestamp`
/// throw [UnsupportedOperationException] — the inverse direction ([ParquetImporter]) supports
/// nested `LIST`/`STRUCT`; export does not yet.
///
/// Supported Vortex → Parquet type mapping:
/// - Bool → BOOLEAN
/// - I8/U8, I16/U16 → INT32 (IntType 8/16, signed or unsigned)
/// - I32 → INT32 (no annotation), U32 → INT32 (IntType 32, unsigned)
/// - I64 → INT64 (no annotation), U64 → INT64 (IntType 64, unsigned)
/// - F32 → FLOAT, F64 → DOUBLE
/// - Utf8 → BYTE_ARRAY (STRING), Binary → BYTE_ARRAY (no annotation)
/// - `vortex.timestamp` → INT64 (TIMESTAMP, `isAdjustedToUTC` set from whether the column
///   carries a timezone)
public final class ParquetExporter {

    private ParquetExporter() {
    }

    public static void exportParquet(Path vortexPath, Path parquetPath) throws IOException {
        exportParquet(vortexPath, parquetPath, ExportOptions.defaults());
    }

    public static void exportParquet(Path vortexPath, Path parquetPath, ExportOptions options) throws IOException {
        try (VortexReader reader = VortexReader.open(vortexPath)) {
            exportParquet(reader, parquetPath, options);
        }
    }

    /// Exports an already-open Vortex handle — a local [VortexReader] or a remote
    /// [io.github.dfa1.vortex.reader.VortexHttpReader] — to Parquet. The handle is not closed
    /// here: the caller opened it and keeps ownership of its lifecycle, the same convention the
    /// `csv` module's `CsvExporter` uses for a caller-supplied `Writer`.
    ///
    /// @param vortex      an open handle to the source Vortex data
    /// @param parquetPath destination Parquet file
    /// @throws IOException if reading `vortex` or writing `parquetPath` fails
    public static void exportParquet(VortexHandle vortex, Path parquetPath) throws IOException {
        exportParquet(vortex, parquetPath, ExportOptions.defaults());
    }

    /// Exports an already-open Vortex handle to Parquet, tuned by `options`.
    ///
    /// @param vortex      an open handle to the source Vortex data
    /// @param parquetPath destination Parquet file
    /// @param options     export tuning (column projection, progress, Hardwood writer config)
    /// @throws IOException if reading `vortex` or writing `parquetPath` fails
    public static void exportParquet(VortexHandle vortex, Path parquetPath, ExportOptions options)
            throws IOException {
        if (!(vortex.dtype() instanceof DType.Struct schema)) {
            throw new UnsupportedOperationException("only struct root dtype is supported for Parquet export");
        }
        List<ColumnName> allNames = schema.fieldNames();
        List<DType> allTypes = schema.fieldTypes();
        List<String> names = options.hasProjection() ? options.columns() : namesOf(allNames);
        List<DType> types = resolveTypes(allNames, allTypes, names);

        FileSchema.Builder builder = FileSchema.builder("schema");
        for (int c = 0; c < names.size(); c++) {
            addColumn(builder, names.get(c), types.get(c));
        }
        FileSchema parquetSchema = builder.build();

        long rowsTotal = options.progressListener() != null ? vortex.layout().rowCount() : 0L;
        long rowsDone = 0;

        try (ParquetFileWriter writer = ParquetFileWriter.create(
                OutputFile.of(parquetPath), parquetSchema, options.writerConfig());
             ScanIterator scan = vortex.scan(ScanOptions.columns(names.toArray(String[]::new)))) {

            ColumnWriter columnWriter = writer.columnWriter();
            while (scan.hasNext()) {
                try (Chunk chunk = scan.next()) {
                    int rowCount = IoBounds.checkCount(chunk.rowCount());
                    columnWriter.writeBatch(batch -> {
                        for (int c = 0; c < names.size(); c++) {
                            Array array = chunk.column(names.get(c));
                            writeColumn(batch, c, types.get(c), array, rowCount);
                        }
                    });
                    rowsDone += rowCount;
                    if (options.progressListener() != null) {
                        options.progressListener().onProgress(rowsDone, rowsTotal);
                    }
                }
            }
        }
    }

    private static List<String> namesOf(List<ColumnName> names) {
        List<String> result = new ArrayList<>(names.size());
        for (ColumnName name : names) {
            result.add(name.value());
        }
        return result;
    }

    /// Resolves `names` (either every top-level column, or a caller-requested projection) to
    /// their declared dtypes, in the order given.
    static List<DType> resolveTypes(List<ColumnName> allNames, List<DType> allTypes, List<String> names) {
        List<DType> result = new ArrayList<>(names.size());
        for (String name : names) {
            int idx = allNames.indexOf(ColumnName.of(name));
            if (idx < 0) {
                throw new IllegalArgumentException("column not found in Vortex schema: " + name);
            }
            result.add(allTypes.get(idx));
        }
        return result;
    }

    static void addColumn(FileSchema.Builder builder, String name, DType type) {
        RepetitionType rep = type.nullable() ? RepetitionType.OPTIONAL : RepetitionType.REQUIRED;
        switch (type) {
            case DType.Bool ignored -> builder.addColumn(name, PhysicalType.BOOLEAN, rep);
            case DType.Utf8 ignored ->
                    builder.addColumn(name, PhysicalType.BYTE_ARRAY, rep, new LogicalType.StringType());
            case DType.Binary ignored -> builder.addColumn(name, PhysicalType.BYTE_ARRAY, rep);
            case DType.Primitive p -> addPrimitiveColumn(builder, name, p.ptype(), rep);
            case DType.Extension ext -> addTimestampColumn(builder, name, ext, rep);
            default -> throw new UnsupportedOperationException(
                    "unsupported column type for Parquet export (STRUCT/LIST/MAP not yet supported): " + type
                            + " (column: " + name + ")");
        }
    }

    private static void addPrimitiveColumn(FileSchema.Builder builder, String name, PType ptype, RepetitionType rep) {
        switch (ptype) {
            case I8 -> builder.addColumn(name, PhysicalType.INT32, rep, new LogicalType.IntType(8, true));
            case U8 -> builder.addColumn(name, PhysicalType.INT32, rep, new LogicalType.IntType(8, false));
            case I16 -> builder.addColumn(name, PhysicalType.INT32, rep, new LogicalType.IntType(16, true));
            case U16 -> builder.addColumn(name, PhysicalType.INT32, rep, new LogicalType.IntType(16, false));
            case I32 -> builder.addColumn(name, PhysicalType.INT32, rep);
            case U32 -> builder.addColumn(name, PhysicalType.INT32, rep, new LogicalType.IntType(32, false));
            case I64 -> builder.addColumn(name, PhysicalType.INT64, rep);
            case U64 -> builder.addColumn(name, PhysicalType.INT64, rep, new LogicalType.IntType(64, false));
            case F32 -> builder.addColumn(name, PhysicalType.FLOAT, rep);
            case F64 -> builder.addColumn(name, PhysicalType.DOUBLE, rep);
            case F16 -> throw new UnsupportedOperationException(
                    "F16 columns are not supported for Parquet export (column: " + name + ")");
        }
    }

    /// Maps a `vortex.timestamp` extension to Parquet's `TIMESTAMP` logical type. `Seconds` and
    /// `Days` have no Parquet `TIMESTAMP` equivalent (the format only defines MILLIS/MICROS/NANOS
    /// resolution) and throw; any other extension id throws, since only `vortex.timestamp` is
    /// supported for export.
    private static void addTimestampColumn(FileSchema.Builder builder, String name, DType.Extension ext,
            RepetitionType rep) {
        if (!ExtensionId.VORTEX_TIMESTAMP.id().equals(ext.extensionId())) {
            throw new UnsupportedOperationException(
                    "unsupported extension type for Parquet export: " + ext.extensionId() + " (column: " + name + ")");
        }
        TimeUnit unit = TimestampDtype.readUnit(ext);
        LogicalType.TimeUnit parquetUnit = switch (unit) {
            case Milliseconds -> LogicalType.TimeUnit.MILLIS;
            case Microseconds -> LogicalType.TimeUnit.MICROS;
            case Nanoseconds -> LogicalType.TimeUnit.NANOS;
            case Seconds, Days -> throw new UnsupportedOperationException(
                    "Parquet TIMESTAMP has no " + unit + " resolution (column: " + name + ")");
        };
        boolean isAdjustedToUtc = TimestampDtype.timezone(ext).isPresent();
        builder.addColumn(name, PhysicalType.INT64, rep, new LogicalType.TimestampType(isAdjustedToUtc, parquetUnit));
    }

    /// Writes one column's values for the current batch, addressed by leaf index `idx`
    /// (matching the schema order [#addColumn] built it in). A nullable column decodes as a
    /// [MaskedArray]; its per-row nulls are read once into a `boolean[]` mask and its inner
    /// (unmasked) array supplies the values — the value at a null row is still read rather than
    /// skipped (Hardwood ignores it), keeping the read loop branch-free per row.
    static void writeColumn(ColumnBatch batch, int idx, DType type, Array array, int rowCount) {
        Array target = array;
        boolean[] nulls = null;
        if (array instanceof MaskedArray masked) {
            target = masked.inner();
            nulls = readNulls(masked, rowCount);
        }
        switch (type) {
            case DType.Bool ignored -> writeBooleans(batch, idx, target, rowCount, nulls);
            case DType.Utf8 ignored -> writeBytes(batch, idx, target, rowCount, nulls);
            case DType.Binary ignored -> writeBytes(batch, idx, target, rowCount, nulls);
            case DType.Primitive p -> writePrimitive(batch, idx, p.ptype(), target, rowCount, nulls);
            case DType.Extension ignored -> writeLongs(batch, idx, target, rowCount, nulls);
            default -> throw new UnsupportedOperationException("unsupported column type for Parquet export: " + type);
        }
    }

    private static boolean[] readNulls(MaskedArray masked, int rowCount) {
        boolean[] nulls = new boolean[rowCount];
        for (int i = 0; i < rowCount; i++) {
            nulls[i] = !masked.isValid(i);
        }
        return nulls;
    }

    private static void writePrimitive(ColumnBatch batch, int idx, PType ptype, Array target, int rowCount,
            boolean[] nulls) {
        switch (ptype) {
            case I8, U8, I16, U16, I32, U32 -> {
                int[] values = readInts(target, rowCount);
                if (nulls == null) {
                    batch.ints(idx, values);
                } else {
                    batch.ints(idx, values, nulls);
                }
            }
            case I64, U64 -> writeLongs(batch, idx, target, rowCount, nulls);
            case F32 -> {
                float[] values = readFloats((FloatArray) target, rowCount);
                if (nulls == null) {
                    batch.floats(idx, values);
                } else {
                    batch.floats(idx, values, nulls);
                }
            }
            case F64 -> {
                double[] values = readDoubles((DoubleArray) target, rowCount);
                if (nulls == null) {
                    batch.doubles(idx, values);
                } else {
                    batch.doubles(idx, values, nulls);
                }
            }
            case F16 -> throw new UnsupportedOperationException("F16 columns are not supported for Parquet export");
        }
    }

    private static void writeBooleans(ColumnBatch batch, int idx, Array target, int rowCount, boolean[] nulls) {
        boolean[] values = readBooleans((BoolArray) target, rowCount);
        if (nulls == null) {
            batch.booleans(idx, values);
        } else {
            batch.booleans(idx, values, nulls);
        }
    }

    private static void writeBytes(ColumnBatch batch, int idx, Array target, int rowCount, boolean[] nulls) {
        byte[][] values = readBytes((VarBinArray) target, rowCount);
        if (nulls == null) {
            batch.bytes(idx, values);
        } else {
            batch.bytes(idx, values, nulls);
        }
    }

    private static void writeLongs(ColumnBatch batch, int idx, Array target, int rowCount, boolean[] nulls) {
        long[] values = readLongs((LongArray) target, rowCount);
        if (nulls == null) {
            batch.longs(idx, values);
        } else {
            batch.longs(idx, values, nulls);
        }
    }

    /// Reads a fixed-width integer array widened to `int`, dispatching on the array's storage
    /// width. `IntArray`/`ShortArray`/`ByteArray#getInt` each already return the mathematically
    /// correct value for the column's own signedness (sign-extended for a signed narrow type,
    /// zero-extended for unsigned), so the raw widened value is exactly what Parquet's matching
    /// `IntType` annotation expects — no further conversion needed.
    private static int[] readInts(Array arr, int rowCount) {
        int[] values = new int[rowCount];
        switch (arr) {
            case IntArray ia -> {
                for (int i = 0; i < rowCount; i++) {
                    values[i] = ia.getInt(i);
                }
            }
            case ShortArray sa -> {
                for (int i = 0; i < rowCount; i++) {
                    values[i] = sa.getInt(i);
                }
            }
            case ByteArray ba -> {
                for (int i = 0; i < rowCount; i++) {
                    values[i] = ba.getInt(i);
                }
            }
            default -> throw new IllegalStateException(
                    "expected an int-widening array, got " + arr.getClass().getSimpleName());
        }
        return values;
    }

    private static long[] readLongs(LongArray arr, int rowCount) {
        long[] values = new long[rowCount];
        for (int i = 0; i < rowCount; i++) {
            values[i] = arr.getLong(i);
        }
        return values;
    }

    private static float[] readFloats(FloatArray arr, int rowCount) {
        float[] values = new float[rowCount];
        for (int i = 0; i < rowCount; i++) {
            values[i] = arr.getFloat(i);
        }
        return values;
    }

    private static double[] readDoubles(DoubleArray arr, int rowCount) {
        double[] values = new double[rowCount];
        for (int i = 0; i < rowCount; i++) {
            values[i] = arr.getDouble(i);
        }
        return values;
    }

    private static boolean[] readBooleans(BoolArray arr, int rowCount) {
        boolean[] values = new boolean[rowCount];
        for (int i = 0; i < rowCount; i++) {
            values[i] = arr.getBoolean(i);
        }
        return values;
    }

    private static byte[][] readBytes(VarBinArray arr, int rowCount) {
        byte[][] values = new byte[rowCount][];
        for (int i = 0; i < rowCount; i++) {
            values[i] = arr.getBytes(i);
        }
        return values;
    }
}
