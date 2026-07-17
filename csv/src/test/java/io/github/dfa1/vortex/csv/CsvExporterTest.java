package io.github.dfa1.vortex.csv;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;
import io.github.dfa1.vortex.writer.encode.BoolEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.FixedSizeListData;
import io.github.dfa1.vortex.writer.encode.ListData;
import io.github.dfa1.vortex.writer.encode.ListEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.MaskedEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.NullEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.NullableData;
import io.github.dfa1.vortex.writer.encode.PrimitiveEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.StructData;
import io.github.dfa1.vortex.writer.encode.StructEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.VarBinEncodingEncoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringWriter;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CsvExporterTest {

    @Test
    void exportsToCsvFile(@TempDir Path tmp) throws Exception {
        // Given
        Path vortex = tmp.resolve("data.vortex");
        DType.Struct schema = new DType.Struct(
                List.of(ColumnName.of("id"), ColumnName.of("name")),
                List.of(DType.I64, DType.UTF8),
                false);
        try (FileChannel ch = FileChannel.open(vortex, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             VortexWriter writer = VortexWriter.create(ch, schema, WriteOptions.defaults())) {
            writer.writeChunk(Map.of(ColumnName.of("id"), new long[]{1L, 2L}, ColumnName.of("name"), new String[]{"Alice", "Bob"}));
        }
        Path csv = tmp.resolve("out.csv");

        // When
        CsvExporter.exportCsv(vortex, csv);

        // Then
        List<String> lines = Files.readAllLines(csv);
        assertThat(lines).hasSize(3);
        assertThat(lines.get(0)).isEqualTo("id,name");
        assertThat(lines.get(1)).isEqualTo("1,Alice");
        assertThat(lines.get(2)).isEqualTo("2,Bob");
    }

    @Test
    void exportsToWriter(@TempDir Path tmp) throws Exception {
        // Given
        Path vortex = tmp.resolve("data.vortex");
        DType.Struct schema = new DType.Struct(
                List.of(ColumnName.of("x")),
                List.of(DType.F64),
                false);
        try (FileChannel ch = FileChannel.open(vortex, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             VortexWriter writer = VortexWriter.create(ch, schema, WriteOptions.defaults())) {
            writer.writeChunk(Map.of(ColumnName.of("x"), new double[]{1.5, 2.7}));
        }
        StringWriter out = new StringWriter();

        // When
        CsvExporter.exportCsv(vortex, out, ExportOptions.defaults());

        // Then
        String[] lines = out.toString().split("\r?\n");
        assertThat(lines).hasSize(3);
        assertThat(lines[0]).isEqualTo("x");
        assertThat(lines[1]).isEqualTo("1.5");
        assertThat(lines[2]).isEqualTo("2.7");
    }

    @Test
    void exportsAllNullColumnAsEmptyFields(@TempDir Path tmp) throws Exception {
        // Given a file mixing a value column with an all-null (DType.Null) column —
        // the shape that made real-world exports throw (#211); the long[] carrier for
        // the null column only supplies the row count
        Path vortex = tmp.resolve("data.vortex");
        DType.Struct schema = new DType.Struct(
                List.of(ColumnName.of("id"), ColumnName.of("empty")),
                List.of(DType.I64, DType.NULL),
                false);
        try (FileChannel ch = FileChannel.open(vortex, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             // explicit encoder list: the default cascading set has no DType.Null codec
             VortexWriter writer = VortexWriter.create(ch, schema, WriteOptions.defaults(),
                     List.of(new NullEncodingEncoder(), new PrimitiveEncodingEncoder()))) {
            writer.writeChunk(Map.of(ColumnName.of("id"), new long[]{1L, 2L}, ColumnName.of("empty"), new long[2]));
        }
        Path csv = tmp.resolve("out.csv");

        // When
        CsvExporter.exportCsv(vortex, csv);

        // Then — null cells are empty fields, same as MaskedArray null rows
        List<String> result = Files.readAllLines(csv);
        assertThat(result).containsExactly("id,empty", "1,", "2,");
    }

    @Test
    void suppressesHeaderWhenConfigured(@TempDir Path tmp) throws Exception {
        // Given
        Path vortex = tmp.resolve("data.vortex");
        DType.Struct schema = new DType.Struct(
                List.of(ColumnName.of("id")),
                List.of(DType.I64),
                false);
        try (FileChannel ch = FileChannel.open(vortex, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             VortexWriter writer = VortexWriter.create(ch, schema, WriteOptions.defaults())) {
            writer.writeChunk(Map.of(ColumnName.of("id"), new long[]{7L}));
        }
        Path csv = tmp.resolve("out.csv");

        // When
        CsvExporter.exportCsv(vortex, csv, new ExportOptions(',', false, java.util.List.of(), null));

        // Then
        List<String> lines = Files.readAllLines(csv);
        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst()).isEqualTo("7");
    }

    @Test
    void rendersU8HighHalfAsUnsigned(@TempDir Path tmp) throws Exception {
        // Given a non-nullable U8 column. 132 is 0x84, which is -124 as a signed byte — the
        // uci-wine `magnesium` shape (values 70–162) that regressed to negative numbers.
        Path vortex = tmp.resolve("u8.vortex");
        DType.Struct schema = new DType.Struct(
                List.of(ColumnName.of("magnesium")),
                List.of(new DType.Primitive(PType.U8, false)),
                false);
        try (FileChannel ch = FileChannel.open(vortex, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             VortexWriter writer = VortexWriter.create(ch, schema, WriteOptions.defaults())) {
            writer.writeChunk(Map.of(ColumnName.of("magnesium"), new byte[]{70, (byte) 132, (byte) 255}));
        }
        Path csv = tmp.resolve("out.csv");

        // When
        CsvExporter.exportCsv(vortex, csv);

        // Then
        List<String> result = Files.readAllLines(csv);
        assertThat(result).containsExactly("magnesium", "70", "132", "255");
    }

    @Test
    void rendersNullableU8HighHalfAsUnsigned(@TempDir Path tmp) throws Exception {
        // Given a nullable U8 column (dtype U8?), which decodes as a MaskedArray whose inner
        // values array carries a non-nullable U8 dtype. This is the exact uci-wine layout; the
        // fix must consult the inner array's ptype, not just the class, to widen 132 correctly.
        Path vortex = tmp.resolve("u8n.vortex");
        DType.Struct schema = new DType.Struct(
                List.of(ColumnName.of("magnesium")),
                List.of(new DType.Primitive(PType.U8, true)),
                false);
        try (FileChannel ch = FileChannel.open(vortex, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             VortexWriter writer = VortexWriter.create(ch, schema, WriteOptions.defaults())) {
            writer.writeChunk(Map.of(ColumnName.of("magnesium"),
                    new NullableData(new byte[]{(byte) 132, 0, (byte) 162},
                            new boolean[]{true, false, true})));
        }
        Path csv = tmp.resolve("out.csv");

        // When
        CsvExporter.exportCsv(vortex, csv);

        // Then null rows render as empty; high-half valid rows render unsigned.
        List<String> result = Files.readAllLines(csv);
        assertThat(result).containsExactly("magnesium", "132", "", "162");
    }

    @Test
    void keepsSignedI8Negative(@TempDir Path tmp) throws Exception {
        // Given a SIGNED I8 column with a negative value — pins that the unsigned-rendering
        // fix did not widen signed bytes: -5 must stay "-5", not become "251"
        Path vortex = tmp.resolve("i8.vortex");
        DType.Struct schema = new DType.Struct(
                List.of(ColumnName.of("delta")),
                List.of(new DType.Primitive(PType.I8, false)),
                false);
        try (FileChannel ch = FileChannel.open(vortex, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             VortexWriter writer = VortexWriter.create(ch, schema, WriteOptions.defaults())) {
            writer.writeChunk(Map.of(ColumnName.of("delta"), new byte[]{(byte) -5, (byte) 7}));
        }
        Path csv = tmp.resolve("out.csv");

        // When
        CsvExporter.exportCsv(vortex, csv);

        // Then
        List<String> result = Files.readAllLines(csv);
        assertThat(result).containsExactly("delta", "-5", "7");
    }

    @Test
    void rendersU16HighHalfAsUnsigned(@TempDir Path tmp) throws Exception {
        // Given a non-nullable U16 column. 40000 is 0x9C40, which is -25536 as a signed short.
        Path vortex = tmp.resolve("u16.vortex");
        DType.Struct schema = new DType.Struct(
                List.of(ColumnName.of("v")),
                List.of(new DType.Primitive(PType.U16, false)),
                false);
        try (FileChannel ch = FileChannel.open(vortex, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             VortexWriter writer = VortexWriter.create(ch, schema, WriteOptions.defaults())) {
            writer.writeChunk(Map.of(ColumnName.of("v"), new short[]{1, (short) 40000, (short) 65535}));
        }
        Path csv = tmp.resolve("out.csv");

        // When
        CsvExporter.exportCsv(vortex, csv);

        // Then
        List<String> result = Files.readAllLines(csv);
        assertThat(result).containsExactly("v", "1", "40000", "65535");
    }

    @Test
    void rendersU32AboveIntMaxAsUnsigned(@TempDir Path tmp) throws Exception {
        // Given a non-nullable U32 column. 0x9000_0000 (2415919104) is negative as a signed int.
        Path vortex = tmp.resolve("u32.vortex");
        DType.Struct schema = new DType.Struct(
                List.of(ColumnName.of("v")),
                List.of(new DType.Primitive(PType.U32, false)),
                false);
        try (FileChannel ch = FileChannel.open(vortex, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             VortexWriter writer = VortexWriter.create(ch, schema, WriteOptions.defaults())) {
            writer.writeChunk(Map.of(ColumnName.of("v"), new int[]{1, 0x9000_0000, 0xFFFF_FFFF}));
        }
        Path csv = tmp.resolve("out.csv");

        // When
        CsvExporter.exportCsv(vortex, csv);

        // Then
        List<String> result = Files.readAllLines(csv);
        assertThat(result).containsExactly("v", "1", "2415919104", "4294967295");
    }

    @Test
    void rendersU64AboveLongMaxAsUnsigned(@TempDir Path tmp) throws Exception {
        // Given a non-nullable U64 column. -1L is the all-ones bit pattern: 2^64-1 unsigned.
        Path vortex = tmp.resolve("u64.vortex");
        DType.Struct schema = new DType.Struct(
                List.of(ColumnName.of("v")),
                List.of(new DType.Primitive(PType.U64, false)),
                false);
        try (FileChannel ch = FileChannel.open(vortex, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             VortexWriter writer = VortexWriter.create(ch, schema, WriteOptions.defaults())) {
            writer.writeChunk(Map.of(ColumnName.of("v"), new long[]{1L, Long.MIN_VALUE, -1L}));
        }
        Path csv = tmp.resolve("out.csv");

        // When
        CsvExporter.exportCsv(vortex, csv);

        // Then
        List<String> result = Files.readAllLines(csv);
        assertThat(result).containsExactly("v", "1", "9223372036854775808", "18446744073709551615");
    }

    @Test
    void rendersNestedStructColumnAsJsonObject(@TempDir Path tmp) throws Exception {
        // Given a struct column of mixed field types (i64, utf8, f64), the shape #217 threw on:
        // StructArray had no cellValue arm. The sibling plain columns confirm structs coexist
        // with scalar columns in the same file.
        Path vortex = tmp.resolve("nested.vortex");
        DType.Struct inner = new DType.Struct(
                List.of(ColumnName.of("id"), ColumnName.of("name"), ColumnName.of("score")),
                List.of(DType.I64, DType.UTF8, DType.F64),
                false);
        DType.Struct schema = new DType.Struct(
                List.of(ColumnName.of("region"), ColumnName.of("data")),
                List.of(DType.UTF8, inner),
                false);
        try (FileChannel ch = FileChannel.open(vortex, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             // Struct columns route through the recursive cascade (the flat first-match path has
             // no struct encoder), so enable cascading here and in the other struct cases.
             VortexWriter writer = VortexWriter.create(ch, schema, WriteOptions.cascading(3))) {
            writer.writeChunk(Map.of(
                    ColumnName.of("region"), new String[]{"north", "south"},
                    ColumnName.of("data"), new StructData(List.of(
                            new long[]{1L, 2L},
                            new String[]{"Alice", "Bob"},
                            new double[]{9.5, 3.25}))));
        }
        Path csv = tmp.resolve("out.csv");

        // When
        CsvExporter.exportCsv(vortex, csv);

        // Then — the struct cell is a JSON object in declared field order; numbers/strings quoted
        // per JSON, not CSV.
        List<String> result = Files.readAllLines(csv);
        assertThat(result).containsExactly(
                "region,data",
                "north,\"{\"\"id\"\":1,\"\"name\"\":\"\"Alice\"\",\"\"score\"\":9.5}\"",
                "south,\"{\"\"id\"\":2,\"\"name\"\":\"\"Bob\"\",\"\"score\"\":3.25}\"");
    }

    @Test
    void rendersNullStructFieldAsJsonNull(@TempDir Path tmp) throws Exception {
        // Given a struct whose second field is nullable and null on the first row — the field must
        // become the JSON token null (not an empty field, which is bare CSV's null convention and
        // ambiguous inside a JSON object). Written on the flat path with an explicit struct encoder:
        // StructEncodingEncoder wraps a NullableData field in the masked encoder, whereas the
        // recursive cascade does not yet accept nullable struct fields. A `region` sibling keeps the
        // struct column off the single-column path; `label` needs two struct fields to survive decode
        // (a one-field struct is unwrapped to its bare field by StructEncodingDecoder).
        Path vortex = tmp.resolve("nullfield.vortex");
        DType.Struct inner = new DType.Struct(
                List.of(ColumnName.of("id"), ColumnName.of("label")),
                List.of(DType.I64, new DType.Primitive(PType.I64, true)),
                false);
        DType.Struct schema = new DType.Struct(
                List.of(ColumnName.of("region"), ColumnName.of("data")),
                List.of(DType.UTF8, inner),
                false);
        try (FileChannel ch = FileChannel.open(vortex, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             VortexWriter writer = VortexWriter.create(ch, schema, WriteOptions.defaults(),
                     List.of(new StructEncodingEncoder(), new PrimitiveEncodingEncoder(),
                             new VarBinEncodingEncoder(), new MaskedEncodingEncoder(),
                             new BoolEncodingEncoder()))) {
            writer.writeChunk(Map.of(
                    ColumnName.of("region"), new String[]{"north", "south"},
                    ColumnName.of("data"), new StructData(List.of(
                            new long[]{1L, 2L},
                            new NullableData(new long[]{0L, 42L}, new boolean[]{false, true})))));
        }
        Path csv = tmp.resolve("out.csv");

        // When
        CsvExporter.exportCsv(vortex, csv);

        // Then the null field is the JSON token null; the valid field is the bare number.
        List<String> result = Files.readAllLines(csv);
        assertThat(result).containsExactly(
                "region,data",
                "north,\"{\"\"id\"\":1,\"\"label\"\":null}\"",
                "south,\"{\"\"id\"\":2,\"\"label\"\":42}\"");
    }

    @Test
    void rendersNullStructRowAsEmptyCell(@TempDir Path tmp) throws Exception {
        // Given a nested struct column whose whole row is null (issue #270): the struct decoder
        // represents a null struct row by masking every field invalid, never by wrapping the whole
        // StructArray once. The first row has both fields null (a null struct row); the second has
        // both valid. Regression: cellValue used to route straight to jsonObject, rendering the null
        // row as {"id":null,"label":null} instead of an empty cell like every other nullable column.
        // Two struct fields keep `data` from being unwrapped to its bare field on decode; the
        // `region` sibling keeps it off the single-column expand path.
        Path vortex = tmp.resolve("nullrow.vortex");
        DType.Struct inner = new DType.Struct(
                List.of(ColumnName.of("id"), ColumnName.of("label")),
                List.of(new DType.Primitive(PType.I64, true), new DType.Primitive(PType.I64, true)),
                false);
        DType.Struct schema = new DType.Struct(
                List.of(ColumnName.of("region"), ColumnName.of("data")),
                List.of(DType.UTF8, inner),
                false);
        try (FileChannel ch = FileChannel.open(vortex, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             VortexWriter writer = VortexWriter.create(ch, schema, WriteOptions.defaults(),
                     List.of(new StructEncodingEncoder(), new PrimitiveEncodingEncoder(),
                             new VarBinEncodingEncoder(), new MaskedEncodingEncoder(),
                             new BoolEncodingEncoder()))) {
            writer.writeChunk(Map.of(
                    ColumnName.of("region"), new String[]{"north", "south"},
                    ColumnName.of("data"), new StructData(List.of(
                            new NullableData(new long[]{0L, 1L}, new boolean[]{false, true}),
                            new NullableData(new long[]{0L, 42L}, new boolean[]{false, true})))));
        }
        Path csv = tmp.resolve("out.csv");

        // When
        CsvExporter.exportCsv(vortex, csv);

        // Then the all-fields-null row is an empty cell (not {"id":null,"label":null}); the valid
        // row is the JSON object.
        List<String> result = Files.readAllLines(csv);
        assertThat(result).containsExactly(
                "region,data",
                "north,",
                "south,\"{\"\"id\"\":1,\"\"label\"\":42}\"");
    }

    @Test
    void escapesQuotesAndCommasInsideStructStringField(@TempDir Path tmp) throws Exception {
        // Given a struct string field value containing a JSON-significant quote and a comma: the
        // quote must be JSON-escaped (\") inside the object, and the CSV layer independently doubles
        // the quotes of the whole cell. The comma must NOT split the CSV cell. Two struct fields
        // (note, rank) keep the struct from being unwrapped on decode; the `label` sibling keeps it
        // off the single-column path.
        Path vortex = tmp.resolve("escape.vortex");
        DType.Struct inner = new DType.Struct(
                List.of(ColumnName.of("note"), ColumnName.of("rank")),
                List.of(DType.UTF8, DType.I64),
                false);
        DType.Struct schema = new DType.Struct(
                List.of(ColumnName.of("label"), ColumnName.of("data")),
                List.of(DType.UTF8, inner),
                false);
        try (FileChannel ch = FileChannel.open(vortex, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             VortexWriter writer = VortexWriter.create(ch, schema, WriteOptions.cascading(3))) {
            writer.writeChunk(Map.of(
                    ColumnName.of("label"), new String[]{"x"},
                    ColumnName.of("data"), new StructData(List.of(
                            new String[]{"a\"b,c"},
                            new long[]{7L}))));
        }
        Path csv = tmp.resolve("out.csv");

        // When
        CsvExporter.exportCsv(vortex, csv);

        // Then — JSON escapes the inner quote to \"; the CSV layer then doubles every quote in the
        // whole field. The comma stays inside the single quoted cell.
        List<String> result = Files.readAllLines(csv);
        assertThat(result).containsExactly(
                "label,data",
                "x,\"{\"\"note\"\":\"\"a\\\"\"b,c\"\",\"\"rank\"\":7}\"");
    }

    @Test
    void rendersStructInStructAsNestedJsonObject(@TempDir Path tmp) throws Exception {
        // Given a struct whose field is itself a struct: the inner struct must recurse into a nested
        // JSON object, not throw. A `region` sibling keeps the struct column off the single-column
        // path; every struct here has two fields so none is unwrapped on decode.
        Path vortex = tmp.resolve("deep.vortex");
        DType.Struct innermost = new DType.Struct(
                List.of(ColumnName.of("lat"), ColumnName.of("lon")),
                List.of(DType.F64, DType.F64),
                false);
        DType.Struct inner = new DType.Struct(
                List.of(ColumnName.of("name"), ColumnName.of("coord")),
                List.of(DType.UTF8, innermost),
                false);
        DType.Struct schema = new DType.Struct(
                List.of(ColumnName.of("region"), ColumnName.of("data")),
                List.of(DType.UTF8, inner),
                false);
        try (FileChannel ch = FileChannel.open(vortex, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             VortexWriter writer = VortexWriter.create(ch, schema, WriteOptions.cascading(3))) {
            writer.writeChunk(Map.of(
                    ColumnName.of("region"), new String[]{"north"},
                    ColumnName.of("data"), new StructData(List.of(
                            new String[]{"origin"},
                            new StructData(List.of(new double[]{1.0}, new double[]{2.0}))))));
        }
        Path csv = tmp.resolve("out.csv");

        // When
        CsvExporter.exportCsv(vortex, csv);

        // Then
        List<String> result = Files.readAllLines(csv);
        assertThat(result).containsExactly(
                "region,data",
                "north,\"{\"\"name\"\":\"\"origin\"\",\"\"coord\"\":{\"\"lat\"\":1.0,\"\"lon\"\":2.0}}\"");
    }

    @Test
    void rendersFixedSizeListColumnAsJsonArray(@TempDir Path tmp) throws Exception {
        // Given a FixedSizeList of type F32 and size 3 column — the glove vector shape (issue #257);
        // each row is 3 consecutive floats from the flat elements array.
        Path vortex = tmp.resolve("fsl.vortex");
        DType.FixedSizeList vecDtype = new DType.FixedSizeList(DType.F32, 3, false);
        DType.Struct schema = new DType.Struct(
                List.of(ColumnName.of("label"), ColumnName.of("vec")),
                List.of(DType.UTF8, vecDtype),
                false);
        try (FileChannel ch = FileChannel.open(vortex, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             VortexWriter writer = VortexWriter.create(ch, schema, WriteOptions.defaults())) {
            writer.writeChunk(Map.of(
                    ColumnName.of("label"), new String[]{"a", "b"},
                    ColumnName.of("vec"), new FixedSizeListData(new float[]{1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f}, 2)));
        }
        Path csv = tmp.resolve("out.csv");

        // When
        CsvExporter.exportCsv(vortex, csv);
        List<String> result = Files.readAllLines(csv);

        // Then each row renders as a JSON float array in element order. The CSV layer quotes the
        // whole cell because the JSON array contains commas.
        assertThat(result).containsExactly(
                "label,vec",
                "a,\"[1.0,2.0,3.0]\"",
                "b,\"[4.0,5.0,6.0]\"");
    }

    @Test
    void rendersListColumnAsJsonArray(@TempDir Path tmp) throws Exception {
        // Given a List[I64] column with variable-length rows — tests both the offset
        // reading path and the empty-list edge case (issue #257).
        Path vortex = tmp.resolve("list.vortex");
        DType.List listDtype = new DType.List(DType.I64, false);
        DType.Struct schema = new DType.Struct(
                List.of(ColumnName.of("tag"), ColumnName.of("ids")),
                List.of(DType.UTF8, listDtype),
                false);
        try (FileChannel ch = FileChannel.open(vortex, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             VortexWriter writer = VortexWriter.create(ch, schema, WriteOptions.defaults())) {
            writer.writeChunk(Map.of(
                    ColumnName.of("tag"), new String[]{"a", "b", "c"},
                    ColumnName.of("ids"), new ListData(
                            new long[]{10L, 20L, 30L},
                            new long[]{0, 2, 3, 3},   // row 0 → [10,20], row 1 → [30], row 2 → []
                            3)));
        }
        Path csv = tmp.resolve("out.csv");

        // When
        CsvExporter.exportCsv(vortex, csv);
        List<String> result = Files.readAllLines(csv);

        // Then variable lengths and empty list both render correctly. The CSV layer quotes only the
        // multi-element cell because its JSON array contains a comma.
        assertThat(result).containsExactly(
                "tag,ids",
                "a,\"[10,20]\"",
                "b,[30]",
                "c,[]");
    }

    @Test
    void rendersListOfStructsAsJsonArrayOfObjects(@TempDir Path tmp) throws Exception {
        // Given a List[Struct] column — the osm-germany `members` shape (issue #257);
        // struct elements inside the list must recurse through jsonValue → jsonObject.
        Path vortex = tmp.resolve("listofstruct.vortex");
        DType.Struct memberDtype = new DType.Struct(
                List.of(ColumnName.of("ref"), ColumnName.of("role")),
                List.of(DType.I64, DType.UTF8),
                false);
        DType.List listDtype = new DType.List(memberDtype, false);
        DType.Struct schema = new DType.Struct(
                List.of(ColumnName.of("id"), ColumnName.of("members")),
                List.of(DType.I64, listDtype),
                false);
        try (FileChannel ch = FileChannel.open(vortex, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             VortexWriter writer = VortexWriter.create(ch, schema, WriteOptions.cascading(3))) {
            writer.writeChunk(Map.of(
                    ColumnName.of("id"), new long[]{1L},
                    ColumnName.of("members"), new ListData(
                            new StructData(List.of(new long[]{10L, 20L}, new String[]{"", "outer"})),
                            new long[]{0, 2},
                            1)));
        }
        Path csv = tmp.resolve("out.csv");

        // When
        CsvExporter.exportCsv(vortex, csv);
        List<String> result = Files.readAllLines(csv);

        // Then each list element renders as a JSON object; the array wraps them
        assertThat(result).containsExactly(
                "id,members",
                "1,\"[{\"\"ref\"\":10,\"\"role\"\":\"\"\"\"},{\"\"ref\"\":20,\"\"role\"\":\"\"outer\"\"}]\"");
    }

    @Test
    void rendersNullStructElementInListAsJsonNull(@TempDir Path tmp) throws Exception {
        // Given a List[Struct] whose first element is a null struct row (issue #270, nested case):
        // the struct decoder masks every field invalid, so cellValue never reaches this element —
        // jsonValue's StructArray arm does, and must emit the JSON token null (not
        // {"ref":null,"role":null}) inside the array. This is the only test exercising jsonValue's
        // isNullStructRow branch; the sibling rendersNullStructRowAsEmptyCell only covers the
        // top-level cellValue path. Each field is an independent NullableData both invalid at row 0
        // (the all-fields-invalid shape a genuinely-null struct row decodes to), matching the
        // construction rendersNullStructRowAsEmptyCell uses. The struct needs two fields so it is
        // not unwrapped on decode; the `id` sibling keeps the list column off the single-column path.
        Path vortex = tmp.resolve("nullstructinlist.vortex");
        DType.Struct memberDtype = new DType.Struct(
                List.of(ColumnName.of("ref"), ColumnName.of("role")),
                List.of(new DType.Primitive(PType.I64, true), new DType.Primitive(PType.I64, true)),
                false);
        DType.List listDtype = new DType.List(memberDtype, false);
        DType.Struct schema = new DType.Struct(
                List.of(ColumnName.of("id"), ColumnName.of("members")),
                List.of(DType.I64, listDtype),
                false);
        try (FileChannel ch = FileChannel.open(vortex, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             VortexWriter writer = VortexWriter.create(ch, schema, WriteOptions.defaults(),
                     List.of(new ListEncodingEncoder(), new StructEncodingEncoder(),
                             new PrimitiveEncodingEncoder(), new VarBinEncodingEncoder(),
                             new MaskedEncodingEncoder(), new BoolEncodingEncoder()))) {
            writer.writeChunk(Map.of(
                    ColumnName.of("id"), new long[]{1L},
                    ColumnName.of("members"), new ListData(
                            new StructData(List.of(
                                    new NullableData(new long[]{0L, 20L}, new boolean[]{false, true}),
                                    new NullableData(new long[]{0L, 42L}, new boolean[]{false, true}))),
                            new long[]{0, 2},
                            1)));
        }
        Path csv = tmp.resolve("out.csv");

        // When
        CsvExporter.exportCsv(vortex, csv);
        List<String> result = Files.readAllLines(csv);

        // Then the null struct element is the JSON token null; the valid one is a JSON object.
        assertThat(result).containsExactly(
                "id,members",
                "1,\"[null,{\"\"ref\"\":20,\"\"role\"\":42}]\"");
    }
}
