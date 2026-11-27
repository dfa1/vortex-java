package io.github.dfa1.vortex.parquet;

import dev.hardwood.OutputFile;
import dev.hardwood.Validity;
import dev.hardwood.metadata.LogicalType;
import dev.hardwood.metadata.PhysicalType;
import dev.hardwood.metadata.RepetitionType;
import dev.hardwood.schema.FileSchema;
import dev.hardwood.writer.ColumnBatch;
import dev.hardwood.writer.ColumnWriter;
import dev.hardwood.writer.ParquetFileWriter;
import io.github.dfa1.vortex.core.error.VortexException;
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
import io.github.dfa1.vortex.reader.array.DecimalArray;
import io.github.dfa1.vortex.reader.array.DoubleArray;
import io.github.dfa1.vortex.reader.array.FixedSizeListArray;
import io.github.dfa1.vortex.reader.array.Float16Array;
import io.github.dfa1.vortex.reader.array.FloatArray;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.ListArray;
import io.github.dfa1.vortex.reader.array.ListViewArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MapArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.ShortArray;
import io.github.dfa1.vortex.reader.array.StructArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/// Reads a Vortex file and writes a Parquet file.
///
/// Every leaf must be `Bool`, a `Primitive`, `Decimal`, `Utf8`, `Binary`, or a `vortex.timestamp`
/// extension over millisecond/microsecond/nanosecond resolution, at the top level or nested in
/// `Struct`, `List`, `FixedSizeList` and `Map` columns, which export as Parquet groups. Any other
/// type throws [VortexException] naming the column.
///
/// Supported Vortex → Parquet type mapping:
/// - Bool → BOOLEAN
/// - I8/U8, I16/U16 → INT32 (IntType 8/16, signed or unsigned)
/// - I32 → INT32 (no annotation), U32 → INT32 (IntType 32, unsigned)
/// - I64 → INT64 (no annotation), U64 → INT64 (IntType 64, unsigned)
/// - F32 → FLOAT, F64 → DOUBLE, F16 → FIXED_LEN_BYTE_ARRAY(2) (FLOAT16)
/// - Utf8 → BYTE_ARRAY (STRING), Binary → BYTE_ARRAY (no annotation)
/// - Decimal → DECIMAL on INT32 (precision up to 9), INT64 (up to 18) or FIXED_LEN_BYTE_ARRAY
/// - `vortex.timestamp` → INT64 (TIMESTAMP, `isAdjustedToUTC` set from whether the column
///   carries a timezone)
/// - Struct → group, List and FixedSizeList → `LIST`, Map → `MAP` (the key `REQUIRED`, as Parquet
///   requires)
public final class ParquetExporter {

    // the most digits an unscaled decimal holds in a Parquet INT32 / INT64
    private static final int MAX_INT32_DIGITS = 9;
    private static final int MAX_INT64_DIGITS = 18;

    // where Hardwood puts a list element and a map key and value under their group
    private static final String LIST_ELEMENT = ".list.element";
    private static final String MAP_KEY = ".key_value.key";
    private static final String MAP_VALUE = ".key_value.value";

    private ParquetExporter() {
    }

    /// Exports a Vortex file to a Parquet file with the default options.
    ///
    /// @param vortexPath  the Vortex file to read
    /// @param parquetPath the Parquet file to write
    /// @throws IOException if either file cannot be read or written
    public static void exportParquet(Path vortexPath, Path parquetPath) throws IOException {
        exportParquet(vortexPath, parquetPath, ExportOptions.defaults());
    }

    /// Exports a Vortex file to a Parquet file.
    ///
    /// @param vortexPath  the Vortex file to read
    /// @param parquetPath the Parquet file to write
    /// @param options     column projection, progress listener and Parquet writer settings
    /// @throws IOException if either file cannot be read or written
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
        List<ColumnName> names = options.hasProjection() ? options.columns() : allNames;
        List<DType> types = resolveTypes(allNames, allTypes, names);

        FileSchema.Builder builder = FileSchema.builder("schema");
        for (int c = 0; c < names.size(); c++) {
            addColumn(builder, names.get(c).value(), types.get(c));
        }
        FileSchema parquetSchema = builder.build();

        long rowsTotal = options.progressListener() != null ? vortex.layout().rowCount() : 0L;
        long rowsDone = 0;

        try (ParquetFileWriter writer = ParquetFileWriter.create(
                OutputFile.of(parquetPath), parquetSchema, options.writerConfig());
             ScanIterator scan = vortex.scan(ScanOptions.columns(names.toArray(ColumnName[]::new)))) {

            ColumnWriter columnWriter = writer.columnWriter();
            while (scan.hasNext()) {
                try (Chunk chunk = scan.next()) {
                    int rowCount = IoBounds.checkCount(chunk.rowCount());
                    int[] rows = new int[rowCount];
                    Arrays.setAll(rows, i -> i);
                    columnWriter.writeBatch(batch -> {
                        int leaf = 0;
                        for (int c = 0; c < names.size(); c++) {
                            Array array = chunk.column(names.get(c));
                            leaf = writeNode(batch, leaf, names.get(c).value(), types.get(c), array, rows, null);
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

    /// Resolves `names` (either every top-level column, or a caller-requested projection) to
    /// their declared dtypes, in the order given.
    static List<DType> resolveTypes(List<ColumnName> allNames, List<DType> allTypes, List<ColumnName> names) {
        List<DType> result = new ArrayList<>(names.size());
        for (ColumnName name : names) {
            int idx = allNames.indexOf(name);
            if (idx < 0) {
                throw new IllegalArgumentException("column not found in Vortex schema: " + name);
            }
            result.add(allTypes.get(idx));
        }
        return result;
    }


    /// Declares top-level column `name` of `type` on `builder`.
    static void addColumn(FileSchema.Builder builder, String name, DType type) {
        requireUndotted(name, type);
        declare(new RootSlot(builder, name), name, type);
    }

    /// Hardwood addresses a struct, list or map by its dotted path, so a `.` in the name of one would
    /// point at another node. A leaf is addressed by index, so its name may hold one.
    private static void requireUndotted(String name, DType type) {
        boolean group = type instanceof DType.Struct || type instanceof DType.List
                || type instanceof DType.FixedSizeList || type instanceof DType.Map;
        if (group && name.contains(".")) {
            throw new VortexException("Parquet export cannot address a nested column whose name holds a '.': " + name);
        }
    }

    /// Declares the Parquet node of `type` in `slot`; `path` is its dotted Parquet path, the address
    /// [#writeNode] fills its groups by, and the column an unsupported type is reported against.
    private static void declare(Slot slot, String path, DType type) {
        RepetitionType rep = type.nullable() ? RepetitionType.OPTIONAL : RepetitionType.REQUIRED;
        switch (type) {
            case DType.Struct s -> slot.struct(rep, fields -> {
                for (int f = 0; f < s.fieldNames().size(); f++) {
                    String name = s.fieldNames().get(f).value();
                    requireUndotted(name, s.fieldTypes().get(f));
                    declare(new FieldSlot(fields, name), path + "." + name, s.fieldTypes().get(f));
                }
            });
            case DType.List l ->
                    slot.list(rep, element -> declare(new ElementSlot(element), path + LIST_ELEMENT, l.elementType()));
            case DType.FixedSizeList l ->
                    slot.list(rep, element -> declare(new ElementSlot(element), path + LIST_ELEMENT, l.elementType()));
            case DType.Map m -> slot.map(leafOf(path + MAP_KEY, m.keyType()), rep,
                    value -> declare(new ElementSlot(value), path + MAP_VALUE, m.valueType()));
            default -> slot.leaf(leafOf(path, type), rep);
        }
    }

    /// A Parquet primitive: its physical type, the length of a `FIXED_LEN_BYTE_ARRAY` (else `0`)
    /// and its annotation (`null` for none).
    private record Leaf(PhysicalType type, int typeLength, LogicalType logical) {

        /// The logical type and fixed length Hardwood takes on a primitive's builder, each only when set.
        void configure(FileSchema.PrimitiveBuilder primitive) {
            if (logical != null) {
                primitive.logicalType(logical);
            }
            if (typeLength != 0) {
                primitive.typeLength(typeLength);
            }
        }
    }

    private static Leaf leafOf(String path, DType type) {
        return switch (type) {
            case DType.Bool _ -> new Leaf(PhysicalType.BOOLEAN, 0, null);
            case DType.Utf8 _ -> new Leaf(PhysicalType.BYTE_ARRAY, 0, new LogicalType.StringType());
            case DType.Binary _ -> new Leaf(PhysicalType.BYTE_ARRAY, 0, null);
            case DType.Primitive p -> primitiveLeaf(p.ptype());
            case DType.Decimal d -> decimalLeaf(d);
            case DType.Extension ext -> timestampLeaf(path, ext);
            default -> throw new VortexException(
                    "unsupported column type for Parquet export: " + type + " (column: " + path + ")");
        };
    }

    /// Maps a decimal to Parquet's `DECIMAL` annotation on the narrowest physical type holding its
    /// unscaled value: `INT32` up to 9 digits, `INT64` up to 18, else a big-endian two's-complement
    /// `FIXED_LEN_BYTE_ARRAY` just wide enough for the precision. The importer reads each of them back
    /// as the same Vortex decimal.
    private static Leaf decimalLeaf(DType.Decimal d) {
        LogicalType.DecimalType logical = new LogicalType.DecimalType(d.precision(), d.scale());
        if (d.precision() <= MAX_INT32_DIGITS) {
            return new Leaf(PhysicalType.INT32, 0, logical);
        }
        if (d.precision() <= MAX_INT64_DIGITS) {
            return new Leaf(PhysicalType.INT64, 0, logical);
        }
        return new Leaf(PhysicalType.FIXED_LEN_BYTE_ARRAY, decimalByteWidth(d.precision()), logical);
    }

    /// The fewest bytes whose two's-complement range holds every `precision`-digit unscaled value.
    private static int decimalByteWidth(int precision) {
        int bits = BigInteger.TEN.pow(precision).subtract(BigInteger.ONE).bitLength() + 1;
        return (bits + 7) / 8;
    }

    private static Leaf primitiveLeaf(PType ptype) {
        return switch (ptype) {
            case I8 -> new Leaf(PhysicalType.INT32, 0, new LogicalType.IntType(8, true));
            case U8 -> new Leaf(PhysicalType.INT32, 0, new LogicalType.IntType(8, false));
            case I16 -> new Leaf(PhysicalType.INT32, 0, new LogicalType.IntType(16, true));
            case U16 -> new Leaf(PhysicalType.INT32, 0, new LogicalType.IntType(16, false));
            case I32 -> new Leaf(PhysicalType.INT32, 0, null);
            case U32 -> new Leaf(PhysicalType.INT32, 0, new LogicalType.IntType(32, false));
            case I64 -> new Leaf(PhysicalType.INT64, 0, null);
            case U64 -> new Leaf(PhysicalType.INT64, 0, new LogicalType.IntType(64, false));
            case F32 -> new Leaf(PhysicalType.FLOAT, 0, null);
            case F64 -> new Leaf(PhysicalType.DOUBLE, 0, null);
            // Parquet's FLOAT16 is a 2-byte FIXED_LEN_BYTE_ARRAY holding the little-endian half
            case F16 -> new Leaf(PhysicalType.FIXED_LEN_BYTE_ARRAY, 2, new LogicalType.Float16Type());
        };
    }

    /// Maps a `vortex.timestamp` extension to Parquet's `TIMESTAMP` logical type. `Seconds` and
    /// `Days` have no Parquet `TIMESTAMP` equivalent (the format only defines MILLIS/MICROS/NANOS
    /// resolution) and throw; any other extension id throws, since only `vortex.timestamp` is
    /// supported for export.
    private static Leaf timestampLeaf(String path, DType.Extension ext) {
        if (ext.extensionId() != ExtensionId.VORTEX_TIMESTAMP) {
            throw new VortexException(
                    "unsupported extension type for Parquet export: " + ext.extensionId() + " (column: " + path + ")");
        }
        TimeUnit unit = TimestampDtype.readUnit(ext);
        LogicalType.TimeUnit parquetUnit = switch (unit) {
            case Milliseconds -> LogicalType.TimeUnit.MILLIS;
            case Microseconds -> LogicalType.TimeUnit.MICROS;
            case Nanoseconds -> LogicalType.TimeUnit.NANOS;
            case Seconds, Days -> throw new VortexException(
                    "Parquet TIMESTAMP has no " + unit + " resolution (column: " + path + ")");
        };
        boolean isAdjustedToUtc = TimestampDtype.timezone(ext).isPresent();
        return new Leaf(PhysicalType.INT64, 0, new LogicalType.TimestampType(isAdjustedToUtc, parquetUnit));
    }

    /// Where [#declare] puts one Parquet node, so one walk serves Hardwood's three builders: a
    /// top-level column, a struct field, and a list element or map value.
    private interface Slot {

        void leaf(Leaf leaf, RepetitionType rep);

        void struct(RepetitionType rep, Consumer<FileSchema.StructBuilder> fields);

        void list(RepetitionType rep, Consumer<FileSchema.ElementBuilder> element);

        void map(Leaf key, RepetitionType rep, Consumer<FileSchema.ElementBuilder> value);
    }

    private record RootSlot(FileSchema.Builder builder, String name) implements Slot {

        @Override
        public void leaf(Leaf leaf, RepetitionType rep) {
            builder.primitive(name, leaf.type(), rep, leaf::configure);
        }

        @Override
        public void struct(RepetitionType rep, Consumer<FileSchema.StructBuilder> fields) {
            builder.struct(name, rep, fields);
        }

        @Override
        public void list(RepetitionType rep, Consumer<FileSchema.ElementBuilder> element) {
            builder.list(name, rep, element);
        }

        @Override
        public void map(Leaf key, RepetitionType rep, Consumer<FileSchema.ElementBuilder> value) {
            builder.map(name, rep, key.type(), key::configure, value);
        }
    }

    private record FieldSlot(FileSchema.StructBuilder builder, String name) implements Slot {

        @Override
        public void leaf(Leaf leaf, RepetitionType rep) {
            builder.primitive(name, leaf.type(), rep, leaf::configure);
        }

        @Override
        public void struct(RepetitionType rep, Consumer<FileSchema.StructBuilder> fields) {
            builder.struct(name, rep, fields);
        }

        @Override
        public void list(RepetitionType rep, Consumer<FileSchema.ElementBuilder> element) {
            builder.list(name, rep, element);
        }

        @Override
        public void map(Leaf key, RepetitionType rep, Consumer<FileSchema.ElementBuilder> value) {
            builder.map(name, rep, key.type(), key::configure, value);
        }
    }

    private record ElementSlot(FileSchema.ElementBuilder builder) implements Slot {

        @Override
        public void leaf(Leaf leaf, RepetitionType rep) {
            builder.primitive(leaf.type(), rep, leaf::configure);
        }

        @Override
        public void struct(RepetitionType rep, Consumer<FileSchema.StructBuilder> fields) {
            builder.struct(rep, fields);
        }

        @Override
        public void list(RepetitionType rep, Consumer<FileSchema.ElementBuilder> element) {
            builder.list(rep, element);
        }

        @Override
        public void map(Leaf key, RepetitionType rep, Consumer<FileSchema.ElementBuilder> value) {
            builder.map(rep, key.type(), key::configure, value);
        }
    }

    /// Writes the columns of the node [#declare] built for `type` at `path`: the values at `rows` of
    /// `array`, its leaves numbered from `leaf` in schema order, plus the nulls of its structs and the
    /// offsets of its lists. `absent` marks the rows under a null struct (`null` for none), where
    /// Parquet allows no list entries.
    ///
    /// Rows are gathered by index, so a list's elements, a list view's out-of-order ranges and the
    /// entries a null list skips need no copy of the arrays.
    ///
    /// @return the index of the leaf after this node's
    private static int writeNode(ColumnBatch batch, int leaf, String path, DType type, Array array, int[] rows,
            boolean[] absent) {
        return switch (type) {
            case DType.Struct s -> writeStruct(batch, leaf, path, s, array, rows, absent);
            case DType.List l -> writeList(batch, leaf, path, l, l.elementType(), array, rows, absent);
            case DType.FixedSizeList l -> writeList(batch, leaf, path, l, l.elementType(), array, rows, absent);
            case DType.Map m -> writeMap(batch, leaf, path, m, array, rows, absent);
            default -> {
                writeLeaf(batch, leaf, type, array, rows);
                yield leaf + 1;
            }
        };
    }

    private static int writeStruct(ColumnBatch batch, int leaf, String path, DType.Struct type, Array array,
            int[] rows, boolean[] absent) {
        StructArray struct = structOf(type, array);
        boolean[] fieldAbsent = absent;
        if (type.nullable()) {
            boolean[] nulls = new boolean[rows.length];
            for (int i = 0; i < rows.length; i++) {
                nulls[i] = !isValid(array, rows[i]) || allFieldsNull(struct, rows[i]);
            }
            batch.struct(path, Validity.ofNulls(nulls));
            // a copy: Hardwood keeps `nulls` as the struct's own validity until the batch is written
            fieldAbsent = nulls.clone();
            if (absent != null) {
                for (int i = 0; i < rows.length; i++) {
                    fieldAbsent[i] |= absent[i];
                }
            }
        }
        int next = leaf;
        for (int f = 0; f < struct.fieldCount(); f++) {
            next = writeNode(batch, next, path + "." + type.fieldNames().get(f).value(), type.fieldTypes().get(f),
                    struct.field(f), rows, fieldAbsent);
        }
        return next;
    }

    /// The fields of a struct column. The `vortex.struct` decoder returns a one-field struct as its
    /// lone field (masked with the struct's validity), so an array of another shape is that field.
    private static StructArray structOf(DType.Struct type, Array array) {
        if (unmasked(array) instanceof StructArray struct
                && ((DType.Struct) struct.dtype()).fieldNames().equals(type.fieldNames())) {
            return struct;
        }
        if (type.fieldTypes().size() != 1) {
            throw new VortexException("struct column decoded to " + array.getClass().getSimpleName());
        }
        return new StructArray(type, array.length(), List.of(array));
    }

    /// Whether struct row `row` is null. A [StructArray] carries no validity of its own: the decoder
    /// masks every field with it, so a row where every field is masked invalid is a null struct. A
    /// row whose fields are each null on their own reads the same; `CsvExporter` takes the same
    /// reading.
    private static boolean allFieldsNull(StructArray struct, int row) {
        for (int f = 0; f < struct.fieldCount(); f++) {
            if (!(struct.field(f) instanceof MaskedArray) || isValid(struct.field(f), row)) {
                return false;
            }
        }
        return struct.fieldCount() > 0;
    }

    private static int writeList(ColumnBatch batch, int leaf, String path, DType type, DType elementType,
            Array array, int[] rows, boolean[] absent) {
        boolean[] nulls = type.nullable() ? nullRows(array, rows) : null;
        Array lists = unmasked(array);
        int[] offsets = new int[rows.length + 1];
        int[] elementRows = entryRows(lists, rows, nulls, absent, offsets);
        if (nulls == null) {
            batch.list(path, offsets);
        } else {
            batch.list(path, offsets, Validity.ofNulls(nulls));
        }
        return writeNode(batch, leaf, path + LIST_ELEMENT, elementType, elementsOf(lists), elementRows, null);
    }

    /// A map is a list of `{key, value}` entry structs ([MapArray]), null where either the map or its
    /// entries list is masked.
    private static int writeMap(ColumnBatch batch, int leaf, String path, DType.Map type, Array array, int[] rows,
            boolean[] absent) {
        if (!(unmasked(array) instanceof MapArray map)) {
            throw new VortexException("map column decoded to " + array.getClass().getSimpleName() + " (column: " + path + ")");
        }
        Array entries = map.entries();
        boolean[] nulls = null;
        if (type.nullable()) {
            nulls = nullRows(array, rows);
            for (int i = 0; i < rows.length; i++) {
                nulls[i] |= !isValid(entries, rows[i]);
            }
        }
        Array lists = unmasked(entries);
        int[] offsets = new int[rows.length + 1];
        int[] entryRows = entryRows(lists, rows, nulls, absent, offsets);
        if (nulls == null) {
            batch.map(path, offsets);
        } else {
            batch.map(path, offsets, Validity.ofNulls(nulls));
        }
        if (!(unmasked(elementsOf(lists)) instanceof StructArray keyValue) || keyValue.fieldCount() != 2) {
            throw new VortexException("map entries are not {key, value} structs (column: " + path + ")");
        }
        int next = writeNode(batch, leaf, path + MAP_KEY, type.keyType(), keyValue.field(0),
                entryRows, null);
        return writeNode(batch, next, path + MAP_VALUE, type.valueType(), keyValue.field(1), entryRows, null);
    }

    /// The element rows of the lists at `rows`, in order, recording in `offsets` where each list's
    /// entries start. A list that is null or `absent` gets none: Parquet stores no entries under a
    /// null, while a Vortex null list may still span some. The ranges come from the file, so one
    /// outside the elements throws here rather than as an index error while reading them.
    private static int[] entryRows(Array lists, int[] rows, boolean[] nulls, boolean[] absent, int[] offsets) {
        long elementCount = elementsOf(lists).length();
        long[] starts = new long[rows.length];
        long total = 0;
        for (int i = 0; i < rows.length; i++) {
            offsets[i] = IoBounds.checkCount(total);
            if ((nulls == null || !nulls[i]) && (absent == null || !absent[i])) {
                long start = listStart(lists, rows[i]);
                long size = listSize(lists, rows[i]);
                if (start < 0 || size < 0 || start + size > elementCount) {
                    throw new VortexException("list row " + rows[i] + " spans elements [" + start + ", "
                            + (start + size) + ") outside the " + elementCount + " it has");
                }
                IoBounds.checkCount(start + size);
                starts[i] = start;
                total += size;
            }
        }
        offsets[rows.length] = IoBounds.checkCount(total);
        int[] elementRows = new int[offsets[rows.length]];
        for (int i = 0; i < rows.length; i++) {
            int base = (int) starts[i] - offsets[i];
            for (int j = offsets[i]; j < offsets[i + 1]; j++) {
                elementRows[j] = base + j;
            }
        }
        return elementRows;
    }

    private static long listStart(Array lists, int row) {
        return switch (lists) {
            case ListArray l -> offsetAt(l.offsets(), row);
            case ListViewArray l -> offsetAt(l.offsets(), row);
            case FixedSizeListArray l -> (long) row * l.fixedSize();
            default -> throw unexpectedList(lists);
        };
    }

    private static long listSize(Array lists, int row) {
        return switch (lists) {
            case ListArray l -> offsetAt(l.offsets(), row + 1L) - offsetAt(l.offsets(), row);
            case ListViewArray l -> offsetAt(l.sizes(), row);
            case FixedSizeListArray l -> l.fixedSize();
            default -> throw unexpectedList(lists);
        };
    }

    private static Array elementsOf(Array lists) {
        return switch (lists) {
            case ListArray l -> l.elements();
            case ListViewArray l -> l.elements();
            case FixedSizeListArray l -> l.elements();
            default -> throw unexpectedList(lists);
        };
    }

    private static VortexException unexpectedList(Array lists) {
        return new VortexException("unexpected list array: " + lists.getClass().getSimpleName());
    }

    /// Reads list offset `idx` as a non-negative `long`. The encoder picks the narrowest ptype that
    /// holds the largest offset, so any integer width can appear; `ByteArray`/`ShortArray#getInt`
    /// already zero-extend U8/U16.
    private static long offsetAt(Array offsets, long idx) {
        return switch (offsets) {
            case LongArray la -> la.getLong(idx);
            case IntArray ia -> Integer.toUnsignedLong(ia.getInt(idx));
            case ShortArray sa -> sa.getInt(idx);
            case ByteArray ba -> ba.getInt(idx);
            default -> throw new VortexException("unexpected list offsets type: " + offsets.getClass().getSimpleName());
        };
    }

    /// Whether row `row` of `array` is valid under every [MaskedArray] wrapping it: a struct field
    /// carries its struct's validity over its own.
    private static boolean isValid(Array array, long row) {
        Array current = array;
        while (current instanceof MaskedArray masked) {
            if (!masked.isValid(row)) {
                return false;
            }
            current = masked.inner();
        }
        return true;
    }

    private static Array unmasked(Array array) {
        Array current = array;
        while (current instanceof MaskedArray masked) {
            current = masked.inner();
        }
        return current;
    }

    private static boolean[] nullRows(Array array, int[] rows) {
        boolean[] nulls = new boolean[rows.length];
        for (int i = 0; i < rows.length; i++) {
            nulls[i] = !isValid(array, rows[i]);
        }
        return nulls;
    }

    /// Writes one leaf column's values at `rows` of `array`, addressed by leaf index `idx` (matching
    /// the schema order [#declare] built it in). A nullable leaf decodes as a [MaskedArray]; its nulls
    /// are read once into a `boolean[]` mask and its inner (unmasked) array supplies the values — the
    /// value at a null row is still read rather than skipped (Hardwood ignores it), keeping the read
    /// loop branch-free per row. A `REQUIRED` leaf may be masked too, by a nullable struct above it,
    /// whose nulls Hardwood takes from the struct instead.
    private static void writeLeaf(ColumnBatch batch, int idx, DType type, Array array, int[] rows) {
        Array target = unmasked(array);
        boolean[] nulls = type.nullable() && array instanceof MaskedArray ? nullRows(array, rows) : null;
        switch (type) {
            case DType.Bool _ -> writeBooleans(batch, idx, target, rows, nulls);
            case DType.Utf8 _ -> writeBytes(batch, idx, target, rows, nulls);
            case DType.Binary _ -> writeBytes(batch, idx, target, rows, nulls);
            case DType.Primitive p -> writePrimitive(batch, idx, p.ptype(), target, rows, nulls);
            case DType.Decimal d -> writeDecimals(batch, idx, d, (DecimalArray) target, rows, nulls);
            case DType.Extension _ -> writeLongs(batch, idx, target, rows, nulls);
            default -> throw new VortexException("unsupported column type for Parquet export: " + type);
        }
    }

    private static void writePrimitive(ColumnBatch batch, int idx, PType ptype, Array target, int[] rows,
            boolean[] nulls) {
        switch (ptype) {
            case I8, U8, I16, U16, I32, U32 -> {
                int[] values = readInts(target, rows);
                if (nulls == null) {
                    batch.ints(idx, values);
                } else {
                    batch.ints(idx, values, nulls);
                }
            }
            case I64, U64 -> writeLongs(batch, idx, target, rows, nulls);
            case F32 -> {
                float[] values = readFloats((FloatArray) target, rows);
                if (nulls == null) {
                    batch.floats(idx, values);
                } else {
                    batch.floats(idx, values, nulls);
                }
            }
            case F64 -> {
                double[] values = readDoubles((DoubleArray) target, rows);
                if (nulls == null) {
                    batch.doubles(idx, values);
                } else {
                    batch.doubles(idx, values, nulls);
                }
            }
            case F16 -> {
                byte[][] values = readHalves((Float16Array) target, rows);
                if (nulls == null) {
                    batch.fixed(idx, values);
                } else {
                    batch.fixed(idx, values, nulls);
                }
            }
        }
    }

    /// Writes a decimal column as its unscaled integer, on the physical type [#decimalLeaf] chose
    /// for the precision. A null row writes a zero instead of reading the masked slot.
    private static void writeDecimals(ColumnBatch batch, int idx, DType.Decimal type, DecimalArray arr,
            int[] rows, boolean[] nulls) {
        int count = rows.length;
        if (type.precision() <= MAX_INT64_DIGITS) {
            long[] unscaled = new long[count];
            for (int i = 0; i < count; i++) {
                if (nulls == null || !nulls[i]) {
                    unscaled[i] = unscaledValue(arr.getDecimal(rows[i]), type).longValueExact();
                }
            }
            if (type.precision() <= MAX_INT32_DIGITS) {
                int[] values = new int[count];
                for (int i = 0; i < count; i++) {
                    values[i] = (int) unscaled[i];
                }
                if (nulls == null) {
                    batch.ints(idx, values);
                } else {
                    batch.ints(idx, values, nulls);
                }
            } else if (nulls == null) {
                batch.longs(idx, unscaled);
            } else {
                batch.longs(idx, unscaled, nulls);
            }
            return;
        }
        int width = decimalByteWidth(type.precision());
        byte[][] values = new byte[count][];
        for (int i = 0; i < count; i++) {
            BigInteger unscaled = BigInteger.ZERO;
            if (nulls == null || !nulls[i]) {
                unscaled = unscaledValue(arr.getDecimal(rows[i]), type);
            }
            values[i] = signExtend(unscaled.toByteArray(), width);
        }
        if (nulls == null) {
            batch.fixed(idx, values);
        } else {
            batch.fixed(idx, values, nulls);
        }
    }

    private static BigInteger unscaledValue(BigDecimal value, DType.Decimal type) {
        if (value.scale() != type.scale()) {
            return value.setScale(type.scale()).unscaledValue();
        }
        return value.unscaledValue();
    }

    /// Left-pads big-endian two's-complement `bytes` to `width` with the sign byte.
    private static byte[] signExtend(byte[] bytes, int width) {
        byte[] result = new byte[width];
        if (bytes[0] < 0) {
            Arrays.fill(result, (byte) -1);
        }
        System.arraycopy(bytes, 0, result, width - bytes.length, bytes.length);
        return result;
    }

    private static void writeBooleans(ColumnBatch batch, int idx, Array target, int[] rows, boolean[] nulls) {
        boolean[] values = readBooleans((BoolArray) target, rows);
        if (nulls == null) {
            batch.booleans(idx, values);
        } else {
            batch.booleans(idx, values, nulls);
        }
    }

    private static void writeBytes(ColumnBatch batch, int idx, Array target, int[] rows, boolean[] nulls) {
        byte[][] values = readBytes((VarBinArray) target, rows);
        if (nulls == null) {
            batch.bytes(idx, values);
        } else {
            batch.bytes(idx, values, nulls);
        }
    }

    private static void writeLongs(ColumnBatch batch, int idx, Array target, int[] rows, boolean[] nulls) {
        long[] values = readLongs((LongArray) target, rows);
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
    private static int[] readInts(Array arr, int[] rows) {
        int[] values = new int[rows.length];
        switch (arr) {
            case IntArray ia -> {
                for (int i = 0; i < rows.length; i++) {
                    values[i] = ia.getInt(rows[i]);
                }
            }
            case ShortArray sa -> {
                for (int i = 0; i < rows.length; i++) {
                    values[i] = sa.getInt(rows[i]);
                }
            }
            case ByteArray ba -> {
                for (int i = 0; i < rows.length; i++) {
                    values[i] = ba.getInt(rows[i]);
                }
            }
            default -> throw new IllegalStateException(
                    "expected an int-widening array, got " + arr.getClass().getSimpleName());
        }
        return values;
    }

    private static long[] readLongs(LongArray arr, int[] rows) {
        long[] values = new long[rows.length];
        for (int i = 0; i < rows.length; i++) {
            values[i] = arr.getLong(rows[i]);
        }
        return values;
    }

    /// One 2-byte little-endian entry per row, copied from the half's raw bits so a NaN payload or
    /// `-0.0` is written as it is held, not rounded through `float`.
    private static byte[][] readHalves(Float16Array arr, int[] rows) {
        byte[][] values = new byte[rows.length][];
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment bits = arr.materialize(arena);
            for (int i = 0; i < rows.length; i++) {
                long at = rows[i] * 2L;
                values[i] = new byte[]{bits.get(ValueLayout.JAVA_BYTE, at), bits.get(ValueLayout.JAVA_BYTE, at + 1)};
            }
        }
        return values;
    }

    private static float[] readFloats(FloatArray arr, int[] rows) {
        float[] values = new float[rows.length];
        for (int i = 0; i < rows.length; i++) {
            values[i] = arr.getFloat(rows[i]);
        }
        return values;
    }

    private static double[] readDoubles(DoubleArray arr, int[] rows) {
        double[] values = new double[rows.length];
        for (int i = 0; i < rows.length; i++) {
            values[i] = arr.getDouble(rows[i]);
        }
        return values;
    }

    private static boolean[] readBooleans(BoolArray arr, int[] rows) {
        boolean[] values = new boolean[rows.length];
        for (int i = 0; i < rows.length; i++) {
            values[i] = arr.getBoolean(rows[i]);
        }
        return values;
    }

    private static byte[][] readBytes(VarBinArray arr, int[] rows) {
        byte[][] values = new byte[rows.length][];
        for (int i = 0; i < rows.length; i++) {
            values[i] = arr.getBytes(rows[i]);
        }
        return values;
    }
}
