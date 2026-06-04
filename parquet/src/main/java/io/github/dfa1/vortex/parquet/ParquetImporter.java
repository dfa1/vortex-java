package io.github.dfa1.vortex.parquet;

import dev.hardwood.InputFile;
import dev.hardwood.metadata.LogicalType;
import dev.hardwood.metadata.RepetitionType;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.reader.RowReader;
import dev.hardwood.schema.ColumnProjection;
import dev.hardwood.schema.ColumnSchema;
import dev.hardwood.schema.FileSchema;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.writer.VortexWriter;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// Reads a Parquet file and writes a Vortex file.
///
/// Only flat schemas (no nested struct/list/map columns) are supported.
/// Nullable columns (OPTIONAL repetition) produce nulls mapped to type defaults:
/// 0 for numeric types, {@code false} for bool, {@code ""} for strings.
///
/// Supported Parquet physical types:
/// <ul>
///   <li>BOOLEAN → Bool</li>
///   <li>INT32 (no annotation / IntType 8/16/32, signed or unsigned) → I8/U8/I16/U16/I32/U32</li>
///   <li>INT64 (no annotation / IntType 64, signed or unsigned) → I64/U64</li>
///   <li>FLOAT → F32</li>
///   <li>DOUBLE → F64</li>
///   <li>BYTE_ARRAY annotated STRING, ENUM, or JSON → Utf8</li>
/// </ul>
///
/// All other physical types (INT96, FIXED_LEN_BYTE_ARRAY, unannotated BYTE_ARRAY, DECIMAL,
/// DATE, TIME, TIMESTAMP) throw {@link UnsupportedOperationException}.
public final class ParquetImporter {

    private ParquetImporter() {
    }

    public static void importParquet(Path parquetPath, Path vortexPath) throws IOException {
        importParquet(parquetPath, vortexPath, ImportOptions.defaults());
    }

    public static void importParquet(Path parquetPath, Path vortexPath, ImportOptions options) throws IOException {
        try (ParquetFileReader parquet = ParquetFileReader.open(InputFile.of(parquetPath))) {
            FileSchema fileSchema = parquet.getFileSchema();
            if (!fileSchema.isFlatSchema()) {
                throw new UnsupportedOperationException(
                        "nested Parquet schemas not yet supported; schema: " + fileSchema);
            }

            List<ColumnSchema> allColumns = fileSchema.getColumns();
            List<ColumnSchema> columns = options.hasProjection()
                    ? filterColumns(allColumns, options.columns())
                    : allColumns;
            int colCount = columns.size();

            List<String> names = new ArrayList<>(colCount);
            List<DType> types = new ArrayList<>(colCount);
            for (ColumnSchema col : columns) {
                names.add(col.name());
                types.add(mapDType(col));
            }
            DType.Struct schema = new DType.Struct(names, types, false);
            long totalRows = parquet.getFileMetaData().numRows();

            ColumnProjection projection = options.hasProjection()
                    ? ColumnProjection.columns(options.columns().toArray(String[]::new))
                    : ColumnProjection.all();

            try (FileChannel channel = FileChannel.open(
                    vortexPath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
                 VortexWriter writer = VortexWriter.create(channel, schema, options.writeOptions());
                 RowReader rowReader = parquet.buildRowReader().projection(projection).build()) {

                int chunkSize = options.chunkSize();
                Object[] buffers = allocateBuffers(columns, chunkSize);
                int chunkPos = 0;
                long rowsDone = 0;

                while (rowReader.hasNext()) {
                    rowReader.next();
                    fillRow(rowReader, columns, buffers, chunkPos);
                    chunkPos++;
                    rowsDone++;

                    if (chunkPos == chunkSize) {
                        writer.writeChunk(buildChunk(columns, buffers, chunkPos));
                        buffers = allocateBuffers(columns, chunkSize);
                        chunkPos = 0;
                        if (options.progressListener() != null) {
                            options.progressListener().onProgress(rowsDone, totalRows);
                        }
                    }
                }

                if (chunkPos > 0) {
                    writer.writeChunk(buildChunk(columns, buffers, chunkPos));
                    if (options.progressListener() != null) {
                        options.progressListener().onProgress(rowsDone, totalRows);
                    }
                }
            }
        }
    }

    private static DType mapDType(ColumnSchema col) {
        boolean nullable = col.repetitionType() == RepetitionType.OPTIONAL;
        return switch (col.type()) {
            case BOOLEAN -> new DType.Bool(nullable);
            case INT32 -> mapInt32(col.logicalType(), nullable);
            case INT64 -> mapInt64(col.logicalType(), nullable);
            case FLOAT -> new DType.Primitive(PType.F32, nullable);
            case DOUBLE -> new DType.Primitive(PType.F64, nullable);
            case BYTE_ARRAY -> mapByteArray(col.logicalType(), nullable, col.name());
            default -> throw new UnsupportedOperationException(
                    "unsupported Parquet physical type: " + col.type() + " (column: " + col.name() + ")");
        };
    }

    private static DType mapInt32(LogicalType logical, boolean nullable) {
        if (logical instanceof LogicalType.IntType it) {
            PType ptype = switch (it.bitWidth()) {
                case 8 -> it.isSigned() ? PType.I8 : PType.U8;
                case 16 -> it.isSigned() ? PType.I16 : PType.U16;
                default -> it.isSigned() ? PType.I32 : PType.U32;
            };
            return new DType.Primitive(ptype, nullable);
        }
        return new DType.Primitive(PType.I32, nullable);
    }

    private static DType mapInt64(LogicalType logical, boolean nullable) {
        if (logical instanceof LogicalType.IntType it) {
            return new DType.Primitive(it.isSigned() ? PType.I64 : PType.U64, nullable);
        }
        return new DType.Primitive(PType.I64, nullable);
    }

    private static DType mapByteArray(LogicalType logical, boolean nullable, String colName) {
        if (logical instanceof LogicalType.StringType
                || logical instanceof LogicalType.EnumType
                || logical instanceof LogicalType.JsonType) {
            return new DType.Utf8(nullable);
        }
        throw new UnsupportedOperationException(
                "BYTE_ARRAY column '" + colName + "' has no string logical type annotation; "
                        + "only STRING / ENUM / JSON are supported (got: " + logical + ")");
    }

    private static Object[] allocateBuffers(List<ColumnSchema> columns, int chunkSize) {
        Object[] buffers = new Object[columns.size()];
        for (int c = 0; c < columns.size(); c++) {
            buffers[c] = allocateBuffer(columns.get(c), chunkSize);
        }
        return buffers;
    }

    private static Object allocateBuffer(ColumnSchema col, int chunkSize) {
        return switch (col.type()) {
            case BOOLEAN -> new boolean[chunkSize];
            case FLOAT -> new float[chunkSize];
            case DOUBLE -> new double[chunkSize];
            case BYTE_ARRAY -> new String[chunkSize];
            case INT32 -> {
                if (col.logicalType() instanceof LogicalType.IntType it) {
                    yield switch (it.bitWidth()) {
                        case 8 -> new byte[chunkSize];
                        case 16 -> new short[chunkSize];
                        default -> new int[chunkSize];
                    };
                }
                yield new int[chunkSize];
            }
            case INT64 -> new long[chunkSize];
            default -> throw new UnsupportedOperationException("unsupported type: " + col.type());
        };
    }

    private static void fillRow(RowReader reader, List<ColumnSchema> columns, Object[] buffers, int pos) {
        for (int c = 0; c < columns.size(); c++) {
            ColumnSchema col = columns.get(c);
            String name = col.name();
            boolean isNull = col.repetitionType() == RepetitionType.OPTIONAL && reader.isNull(name);
            switch (buffers[c]) {
                case boolean[] arr -> arr[pos] = !isNull && reader.getBoolean(name);
                case float[] arr -> arr[pos] = isNull ? 0f : reader.getFloat(name);
                case double[] arr -> arr[pos] = isNull ? 0.0 : reader.getDouble(name);
                case long[] arr -> arr[pos] = isNull ? 0L : reader.getLong(name);
                case int[] arr -> arr[pos] = isNull ? 0 : reader.getInt(name);
                case short[] arr -> arr[pos] = isNull ? (short) 0 : (short) reader.getInt(name);
                case byte[] arr -> arr[pos] = isNull ? (byte) 0 : (byte) reader.getInt(name);
                case String[] arr -> arr[pos] = isNull ? "" : reader.getString(name);
                default -> throw new UnsupportedOperationException(
                        "unexpected buffer type: " + buffers[c].getClass().getSimpleName());
            }
        }
    }

    private static Map<String, Object> buildChunk(List<ColumnSchema> columns, Object[] buffers, int size) {
        Map<String, Object> chunk = new LinkedHashMap<>();
        for (int c = 0; c < columns.size(); c++) {
            chunk.put(columns.get(c).name(), trimBuffer(buffers[c], size));
        }
        return chunk;
    }

    private static List<ColumnSchema> filterColumns(List<ColumnSchema> all, List<String> names) {
        List<ColumnSchema> result = new ArrayList<>(names.size());
        for (String name : names) {
            boolean found = false;
            for (ColumnSchema col : all) {
                if (col.name().equals(name)) {
                    result.add(col);
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new IllegalArgumentException("column not found in Parquet schema: " + name);
            }
        }
        return result;
    }

    private static Object trimBuffer(Object buffer, int size) {
        return switch (buffer) {
            case boolean[] arr -> size == arr.length ? arr : Arrays.copyOf(arr, size);
            case float[] arr -> size == arr.length ? arr : Arrays.copyOf(arr, size);
            case double[] arr -> size == arr.length ? arr : Arrays.copyOf(arr, size);
            case long[] arr -> size == arr.length ? arr : Arrays.copyOf(arr, size);
            case int[] arr -> size == arr.length ? arr : Arrays.copyOf(arr, size);
            case short[] arr -> size == arr.length ? arr : Arrays.copyOf(arr, size);
            case byte[] arr -> size == arr.length ? arr : Arrays.copyOf(arr, size);
            case String[] arr -> size == arr.length ? arr : Arrays.copyOf(arr, size);
            default -> throw new UnsupportedOperationException(
                    "unexpected buffer type: " + buffer.getClass().getSimpleName());
        };
    }
}
