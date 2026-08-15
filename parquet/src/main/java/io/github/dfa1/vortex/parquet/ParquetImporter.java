package io.github.dfa1.vortex.parquet;

import dev.hardwood.InputFile;
import dev.hardwood.metadata.LogicalType;
import dev.hardwood.metadata.PhysicalType;
import dev.hardwood.metadata.RepetitionType;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.reader.RowReader;
import dev.hardwood.schema.ColumnProjection;
import dev.hardwood.schema.ColumnSchema;
import dev.hardwood.schema.FileSchema;
import dev.hardwood.schema.SchemaNode;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.writer.encode.DateTimePartsData;
import io.github.dfa1.vortex.core.model.TimeUnit;
import io.github.dfa1.vortex.core.model.TimestampDtype;
import io.github.dfa1.vortex.writer.VortexWriter;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// Reads a Parquet file and writes a Vortex file.
///
/// Supports flat schemas and nested LIST / STRUCT columns (recursively composable, e.g.
/// `LIST<STRUCT<...>>`); `MAP` and `VARIANT` groups are not yet supported. Nullable columns
/// (OPTIONAL repetition) at any nesting level — leaf, struct field, or list/list-element — carry
/// real null tracking through to the written file, distinguishing a null value from a
/// zero/empty/absent one.
///
/// Supported Parquet physical types:
/// - BOOLEAN → Bool
/// - INT32 (no annotation / IntType 8/16/32, signed or unsigned) → I8/U8/I16/U16/I32/U32
/// - INT64 (no annotation / IntType 64, signed or unsigned) → I64/U64
/// - FLOAT → F32
/// - DOUBLE → F64
/// - BYTE_ARRAY annotated STRING, ENUM, or JSON → Utf8
/// - BYTE_ARRAY with no logical-type annotation → Binary
///
/// All other physical types (INT96, FIXED_LEN_BYTE_ARRAY, annotated-but-unsupported BYTE_ARRAY,
/// DECIMAL, DATE, TIME, TIMESTAMP) throw [UnsupportedOperationException].
public final class ParquetImporter {

    private ParquetImporter() {
    }

    public static void importParquet(Path parquetPath, Path vortexPath) throws IOException {
        importParquet(parquetPath, vortexPath, ImportOptions.defaults());
    }

    public static void importParquet(Path parquetPath, Path vortexPath, ImportOptions options) throws IOException {
        try (ParquetFileReader parquet = ParquetFileReader.open(InputFile.of(parquetPath))) {
            FileSchema fileSchema = parquet.getFileSchema();
            List<SchemaNode> allTopLevel = fileSchema.getRootNode().children();
            List<SchemaNode> topLevel = options.hasProjection()
                                                ? filterTopLevel(allTopLevel, options.columns())
                                                : allTopLevel;
            int colCount = topLevel.size();

            List<ColumnName> names = new ArrayList<>(colCount);
            List<DType> types = new ArrayList<>(colCount);
            for (SchemaNode node : topLevel) {
                checkNotBareRepeated(node);
                names.add(ColumnName.of(node.name()));
                types.add(mapDType(node));
            }
            checkNoDuplicateNames(names, parquetPath);
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
                Object[] buffers = allocateBuffers(types, chunkSize);
                ColumnBuilder[] nestedBuilders = allocateNestedBuilders(types);
                int chunkPos = 0;
                long rowsDone = 0;

                while (rowReader.hasNext()) {
                    rowReader.next();
                    fillRow(rowReader, names, types, buffers, nestedBuilders, chunkPos);
                    chunkPos++;
                    rowsDone++;

                    if (chunkPos == chunkSize) {
                        writer.writeChunk(buildChunk(names, types, buffers, nestedBuilders, chunkPos));
                        buffers = allocateBuffers(types, chunkSize);
                        nestedBuilders = allocateNestedBuilders(types);
                        chunkPos = 0;
                        if (options.progressListener() != null) {
                            options.progressListener().onProgress(rowsDone, totalRows);
                        }
                    }
                }

                if (chunkPos > 0) {
                    writer.writeChunk(buildChunk(names, types, buffers, nestedBuilders, chunkPos));
                    if (options.progressListener() != null) {
                        options.progressListener().onProgress(rowsDone, totalRows);
                    }
                }
            }
        }
    }

    static void checkNoDuplicateNames(List<ColumnName> names, Path parquetPath) {
        Set<ColumnName> seen = new HashSet<>();
        Set<ColumnName> duplicates = new LinkedHashSet<>();
        for (ColumnName name : names) {
            if (!seen.add(name)) {
                duplicates.add(name);
            }
        }
        if (!duplicates.isEmpty()) {
            throw new IllegalArgumentException(
                    "Parquet schema has duplicate column name(s): " + duplicates + "; source file: " + parquetPath);
        }
    }

    /// Maps a top-level or nested schema node to its Vortex dtype, recursing through
    /// LIST/STRUCT groups. Leaf mapping is shared with [#mapDType(ColumnSchema)] via
    /// [#mapLeafDType(PhysicalType, LogicalType, boolean, String)].
    static DType mapDType(SchemaNode node) {
        if (node instanceof SchemaNode.GroupNode group) {
            return mapGroupDType(group);
        }
        SchemaNode.PrimitiveNode prim = (SchemaNode.PrimitiveNode) node;
        boolean nullable = prim.repetitionType() == RepetitionType.OPTIONAL;
        return mapLeafDType(prim.type(), prim.logicalType(), nullable, prim.name());
    }

    /// Rejects a bare `REPEATED` field reached as an ordinary struct/top-level field (not
    /// resolved through [SchemaNode.GroupNode#getListElement()]) — the legacy encoding where a
    /// field repeats without an enclosing `LIST` group (parquet-testing's
    /// `repeated_primitive_no_list.parquet`). Silently mapping it as a scalar would keep only
    /// whatever a single-value getter happens to return per row instead of every repetition.
    private static void checkNotBareRepeated(SchemaNode node) {
        if (node.repetitionType() == RepetitionType.REPEATED) {
            throw new UnsupportedOperationException(
                    "repeated field without an enclosing LIST group is not supported: " + node.name());
        }
    }

    private static DType mapGroupDType(SchemaNode.GroupNode group) {
        boolean nullable = group.repetitionType() == RepetitionType.OPTIONAL;
        if (group.isStruct()) {
            List<SchemaNode> children = group.children();
            List<ColumnName> fieldNames = new ArrayList<>(children.size());
            List<DType> fieldTypes = new ArrayList<>(children.size());
            for (SchemaNode child : children) {
                checkNotBareRepeated(child);
                fieldNames.add(ColumnName.of(child.name()));
                fieldTypes.add(mapDType(child));
            }
            return new DType.Struct(fieldNames, fieldTypes, nullable);
        }
        if (group.isList()) {
            SchemaNode element = group.getListElement();
            if (element == null) {
                throw new UnsupportedOperationException("malformed LIST schema: " + group.name());
            }
            return new DType.List(mapDType(element), nullable);
        }
        throw new UnsupportedOperationException(
                "unsupported nested Parquet group (MAP / VARIANT not yet supported): " + group.name());
    }

    static DType mapDType(ColumnSchema col) {
        boolean nullable = col.repetitionType() == RepetitionType.OPTIONAL;
        return mapLeafDType(col.type(), col.logicalType(), nullable, col.name());
    }

    private static DType mapLeafDType(PhysicalType type, LogicalType logical, boolean nullable, String name) {
        return switch (type) {
            case BOOLEAN -> new DType.Bool(nullable);
            case INT32 -> mapInt32(logical, nullable);
            case INT64 -> mapInt64(logical, nullable);
            case FLOAT -> new DType.Primitive(PType.F32, nullable);
            case DOUBLE -> new DType.Primitive(PType.F64, nullable);
            case BYTE_ARRAY -> mapByteArray(logical, nullable, name);
            default -> throw new UnsupportedOperationException(
                    "unsupported Parquet physical type: " + type + " (column: " + name + ")");
        };
    }

    private static DType mapInt32(LogicalType logical, boolean nullable) {
        if (logical instanceof LogicalType.IntType(int bitWidth, boolean isSigned)) {
            PType ptype = switch (bitWidth) {
                case 8 -> isSigned ? PType.I8 : PType.U8;
                case 16 -> isSigned ? PType.I16 : PType.U16;
                default -> isSigned ? PType.I32 : PType.U32;
            };
            return new DType.Primitive(ptype, nullable);
        }
        return new DType.Primitive(PType.I32, nullable);
    }

    private static DType mapInt64(LogicalType logical, boolean nullable) {
        if (logical instanceof LogicalType.IntType it) {
            return new DType.Primitive(it.isSigned() ? PType.I64 : PType.U64, nullable);
        }
        if (logical instanceof LogicalType.TimestampType ts) {
            return timestampExtension(ts.unit(), nullable);
        }
        return new DType.Primitive(PType.I64, nullable);
    }

    private static DType.Extension timestampExtension(LogicalType.TimeUnit parquetUnit, boolean nullable) {
        TimeUnit unit = switch (parquetUnit) {
            case MILLIS -> TimeUnit.Milliseconds;
            case MICROS -> TimeUnit.Microseconds;
            case NANOS -> TimeUnit.Nanoseconds;
        };
        // The wire layout (TimeUnit tag + tz_len + tz UTF-8) lives in TimestampDtype — reuse it.
        return TimestampDtype.of(unit, null, nullable);
    }

    /// Un-annotated `BYTE_ARRAY` maps to [DType.Binary] (e.g. an embedded audio/image blob);
    /// any other, unrecognized annotation (DECIMAL, UUID, ...) still throws rather than
    /// silently dropping its semantic into a plain byte string.
    private static DType mapByteArray(LogicalType logical, boolean nullable, String colName) {
        if (logical instanceof LogicalType.StringType
                    || logical instanceof LogicalType.EnumType
                    || logical instanceof LogicalType.JsonType) {
            return new DType.Utf8(nullable);
        }
        if (logical == null) {
            return new DType.Binary(nullable);
        }
        throw new UnsupportedOperationException(
                "BYTE_ARRAY column '" + colName + "' has an unsupported logical type annotation; "
                        + "only STRING / ENUM / JSON / none (binary) are supported (got: " + logical + ")");
    }

    private static Object[] allocateBuffers(List<DType> types, int chunkSize) {
        Object[] buffers = new Object[types.size()];
        for (int c = 0; c < types.size(); c++) {
            DType type = types.get(c);
            if (!(type instanceof DType.Struct) && !(type instanceof DType.List)) {
                buffers[c] = allocateBuffer(type, chunkSize);
            }
        }
        return buffers;
    }

    private static ColumnBuilder[] allocateNestedBuilders(List<DType> types) {
        ColumnBuilder[] builders = new ColumnBuilder[types.size()];
        for (int c = 0; c < types.size(); c++) {
            DType type = types.get(c);
            if (type instanceof DType.Struct || type instanceof DType.List) {
                builders[c] = ColumnBuilder.forType(type);
            }
        }
        return builders;
    }

    private static Object allocateBuffer(DType type, int chunkSize) {
        return switch (type) {
            case DType.Bool ignored -> new boolean[chunkSize];
            case DType.Utf8 ignored -> new String[chunkSize];
            case DType.Binary ignored -> new byte[chunkSize][];
            case DType.Extension ignored -> new long[chunkSize]; // timestamp storage
            case DType.Primitive p -> switch (p.ptype()) {
                case I8, U8 -> new byte[chunkSize];
                case I16, U16 -> new short[chunkSize];
                case I32, U32 -> new int[chunkSize];
                case I64, U64 -> new long[chunkSize];
                case F32 -> new float[chunkSize];
                case F64 -> new double[chunkSize];
                case F16 -> throw new UnsupportedOperationException("F16 columns are not supported");
            };
            default -> throw new UnsupportedOperationException("unsupported type: " + type);
        };
    }

    private static void fillRow(RowReader reader, List<ColumnName> names, List<DType> types,
            Object[] buffers, ColumnBuilder[] nestedBuilders, int pos) {
        for (int c = 0; c < names.size(); c++) {
            String name = names.get(c).value();
            if (nestedBuilders[c] != null) {
                nestedBuilders[c].append(reader.isNull(name) ? null : reader.getValue(name));
                continue;
            }
            boolean isNull = types.get(c).nullable() && reader.isNull(name);
            switch (buffers[c]) {
                case boolean[] arr -> arr[pos] = !isNull && reader.getBoolean(name);
                case float[] arr -> arr[pos] = isNull ? 0f : reader.getFloat(name);
                case double[] arr -> arr[pos] = isNull ? 0.0 : reader.getDouble(name);
                case long[] arr -> arr[pos] = isNull ? 0L : reader.getLong(name);
                case int[] arr -> arr[pos] = isNull ? 0 : reader.getInt(name);
                case short[] arr -> arr[pos] = isNull ? (short) 0 : (short) reader.getInt(name);
                case byte[] arr -> arr[pos] = isNull ? (byte) 0 : (byte) reader.getInt(name);
                case String[] arr -> arr[pos] = isNull ? null : reader.getString(name);
                case byte[][] arr -> arr[pos] = isNull ? null : reader.getBinary(name);
                default -> throw new UnsupportedOperationException(
                        "unexpected buffer type: " + buffers[c].getClass().getSimpleName());
            }
        }
    }

    private static Map<ColumnName, Object> buildChunk(List<ColumnName> names, List<DType> types,
            Object[] buffers, ColumnBuilder[] nestedBuilders, int size) {
        Map<ColumnName, Object> chunk = new LinkedHashMap<>();
        for (int c = 0; c < names.size(); c++) {
            Object value;
            if (nestedBuilders[c] != null) {
                value = nestedBuilders[c].build();
            } else {
                value = trimBuffer(buffers[c], size);
                if (types.get(c) instanceof DType.Extension) {
                    boolean nullable = types.get(c).nullable();
                    value = new DateTimePartsData((long[]) value, nullable);
                }
            }
            chunk.put(names.get(c), value);
        }
        return chunk;
    }

    /// Filters the top-level schema nodes to those named in `names`, in the requested order.
    ///
    /// @param all   every top-level schema node, in file order
    /// @param names requested top-level field names, in the desired output order
    /// @return the matching nodes, reordered to match `names`
    /// @throws IllegalArgumentException if a requested name has no matching top-level field
    static List<SchemaNode> filterTopLevel(List<SchemaNode> all, List<String> names) {
        List<SchemaNode> result = new ArrayList<>(names.size());
        for (String name : names) {
            boolean found = false;
            for (SchemaNode node : all) {
                if (node.name().equals(name)) {
                    result.add(node);
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
            case byte[][] arr -> size == arr.length ? arr : Arrays.copyOf(arr, size);
            default -> throw new UnsupportedOperationException(
                    "unexpected buffer type: " + buffer.getClass().getSimpleName());
        };
    }
}
