package io.github.dfa1.vortex.integration;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.ScanIterator;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.StructArray;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;
import io.github.dfa1.vortex.writer.encode.StructData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Regression cover for a `ScanIterator` bug found while adding nested-schema Parquet import
/// (#nested-parquet-schema-import): scanning a file whose schema has exactly one top-level
/// column, whose own dtype happens to be `DType.Struct`, must expose that column under its own
/// name — not silently expand its fields into top-level columns.
///
/// `ScanIterator#buildColumnMap` has a legitimate reason to unpack a decoded `StructArray` into
/// several named columns: some files' root layout isn't itself a Struct layout (no per-column
/// layout children — e.g. all columns packed into one flat segment, see
/// `VortexHttpReaderIT#scan_forVortex_decodesAllRows`), so the whole file decodes as a single
/// synthetic placeholder whose `StructArray` fields *are* the file's real columns. But that
/// heuristic used to fire on *any* single-column projection whose sole (real, named) column
/// happened to decode to a `StructArray` too — silently dropping the column's actual name and
/// exposing its fields as if they were top-level columns instead.
class SingleStructColumnScanIntegrationTest {

    private static final DType.Struct SCHEMA = new DType.Struct(
            List.of(ColumnName.of("point")),
            List.of(new DType.Struct(
                    List.of(ColumnName.of("x"), ColumnName.of("y")),
                    List.of(DType.I32, DType.I32),
                    false)),
            false);

    @Test
    void singleTopLevelStructColumn_scanExposesItByItsOwnName(@TempDir Path tmp) throws Exception {
        // Given — one top-level column "point", itself STRUCT{x, y}; not the synthetic
        // whole-file placeholder case, so the two struct fields must not surface as if they were
        // the file's own top-level columns "x"/"y".
        Path file = tmp.resolve("single_struct_column.vtx");
        StructData point = new StructData(List.of(new int[]{1, 3, 5}, new int[]{2, 4, 6}));
        // Struct-typed columns only route through CascadingCompressor's dedicated encodeStruct
        // path (writer.encode.StructEncodingEncoder is otherwise used only internally for
        // zone-map stats, never registered in the plain WriteRegistry), so cascading must be on
        // — matching ParquetImporter's own default (ImportOptions.defaults() -> cascading(3)).
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, SCHEMA, WriteOptions.cascading(1))) {
            // When
            sut.writeChunk(Map.of(ColumnName.of("point"), point));
        }

        // Then
        try (VortexReader reader = VortexReader.open(file);
             ScanIterator iter = reader.scan(ScanOptions.all())) {
            assertThat(iter.hasNext()).isTrue();
            try (Chunk chunk = iter.next()) {
                assertThat(chunk.columns().keySet()).containsExactly(ColumnName.of("point"));
                StructArray col = chunk.column("point");
                IntArray x = (IntArray) col.field(0);
                IntArray y = (IntArray) col.field(1);
                assertThat(x.getInt(0)).isEqualTo(1);
                assertThat(y.getInt(0)).isEqualTo(2);
                assertThat(x.length()).isEqualTo(3);
            }
        }
    }
}
