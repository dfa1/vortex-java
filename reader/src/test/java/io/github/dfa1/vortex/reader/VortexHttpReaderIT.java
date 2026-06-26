package io.github.dfa1.vortex.reader;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.reader.array.ListArray;
import io.github.dfa1.vortex.reader.array.ListViewArray;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// Integration test: reads real Vortex files from the public S3 compatibility bucket
/// via HTTP Range requests and validates structure + data.
///
/// Skipped automatically when the network is unavailable.
@Tag("integration")
class VortexHttpReaderIT {

    private static final URI BASE = URI.create("https://vortex-compat-fixtures.s3.amazonaws.com/v0.75.0/arrays/");

    private static final URI TPCH_LINEITEM = BASE.resolve("tpch_lineitem.compact.vortex");

    // for.vortex — frame-of-reference encoding, 10 columns in one flat segment
    private static final URI FOR_ARRAY = BASE.resolve("for.vortex");

    @Test
    void open_remoteFile_parsesMetadata() throws Exception {
        // Given

        // When
        try (var sut = VortexHttpReader.open(TPCH_LINEITEM)) {

            // Then
            assertThat(sut.version()).isEqualTo(1);
            assertThat(sut.fileSize()).isGreaterThan(VortexFormat.TRAILER_SIZE);
            assertThat(sut.layout()).isNotNull();
            assertThat(sut.footer()).isNotNull();
            assertThat(sut.dtype()).isNotNull();
        }
    }

    @Test
    void open_remoteFile_layoutRowCountIsPositive() throws Exception {
        // Given

        // When — row count comes from the layout (no data decoding required)
        try (var sut = VortexHttpReader.open(FOR_ARRAY)) {

            // Then
            assertThat(sut.layout().rowCount()).isGreaterThan(0);
            assertThat(sut.footer().segmentSpecs()).isNotEmpty();
        }
    }

    @Test
    void scan_forVortex_decodesAllRows() throws Exception {
        // Given
        long totalRows = 0;

        // When
        try (var sut = VortexHttpReader.open(FOR_ARRAY);
             var iter = sut.scan(ScanOptions.all())) {
            while (iter.hasNext()) {
                try (var c = iter.next()) {
                    totalRows += c.rowCount();
                }
            }
        }

        // Then
        assertThat(totalRows).isGreaterThan(0);
    }

    // Smoke test: every published encoding fixture must open and decode all its rows.
    // Catches upstream wire-format drift across the full encoding set in one place; the
    // structural tests below (for/list/listview) assert shape for the trickier layouts.
    // Excludes the multi-MB clickbench/tpch fixtures (covered by dedicated tests) to keep
    // this fast over HTTP.
    @ParameterizedTest
    @ValueSource(strings = {
            "alp.vortex", "alprd.vortex", "bitpacked.vortex", "booleans.vortex", "bytebool.vortex",
            "chunked.vortex", "constant.vortex", "datetime.vortex", "datetimeparts.vortex",
            "decimal.vortex", "decimal_byte_parts.vortex", "dict.vortex", "fixed_size_list.vortex",
            "fsst.vortex", "null.vortex", "pco.vortex", "primitives.vortex", "rle.vortex",
            "runend.vortex", "sequence.vortex", "sparse.vortex", "struct_nested.vortex",
            "varbin.vortex", "varbinview.vortex", "zigzag.vortex", "zstd.vortex"
            // zstd.vortex is dictionary-compressed; decoded against its shared dictionary via the
            // native libzstd bindings.
    })
    void scan_publishedFixture_decodesAllRows(String fixture) throws Exception {
        // Given
        URI uri = BASE.resolve(fixture);

        // When
        long totalRows = 0;
        try (var sut = VortexHttpReader.open(uri);
             var iter = sut.scan(ScanOptions.all())) {
            while (iter.hasNext()) {
                try (var c = iter.next()) {
                    totalRows += c.rowCount();
                }
            }
        }

        // Then
        assertThat(totalRows).isGreaterThan(0);
    }


    // vortex.masked / vortex.patched / vortex.variant: decoders implemented, but no S3 fixture
    // is published (still absent at v0.75.0) — enable this test once fixtures exist upstream.

    @Disabled("no S3 fixture through v0.75.0")
    @ParameterizedTest
    @ValueSource(strings = {"masked.vortex", "patched.vortex", "variant.vortex"})
    void scan_unpublishedFixture_decodesAllRows(String fixture) throws Exception {
        // Given
        URI uri = BASE.resolve(fixture);

        // When
        long totalRows = 0;
        try (var sut = VortexHttpReader.open(uri);
             var iter = sut.scan(ScanOptions.all())) {
            while (iter.hasNext()) {
                try (var c = iter.next()) {
                    totalRows += c.rowCount();
                }
            }
        }

        // Then
        assertThat(totalRows).isGreaterThan(0);
    }

    @Test
    void scan_listVortex_decodesListArrayWithCorrectShape() throws Exception {
        // Given
        URI uri = BASE.resolve("list.vortex");

        // When
        try (var sut = VortexHttpReader.open(uri)) {
            DType.Struct schema = (DType.Struct) sut.dtype();
            List<String> names = schema.fieldNames();
            List<DType> types = schema.fieldTypes();
            String listColName = null;
            for (int i = 0; i < types.size(); i++) {
                if (types.get(i) instanceof DType.List) {
                    listColName = names.get(i);
                    break;
                }
            }
            assertThat(listColName).as("list.vortex must have a List-typed column").isNotNull();

            try (var iter = sut.scan(ScanOptions.all())) {
                assertThat(iter.hasNext()).isTrue();
                var chunk = iter.next();

                // Then
                ListArray listArray = chunk.column(listColName);
                assertThat(listArray.elements().length())
                        .as("elements_len must be > 0; 0 indicates silent proto tag mismatch")
                        .isGreaterThan(0);
                assertThat(listArray.offsets().dtype())
                        .as("offset_ptype must be I64; U8 indicates silent proto tag mismatch")
                        .isEqualTo(DType.I64);
                assertThat(listArray.offsets().length())
                        .as("offsets length must be rowCount + 1")
                        .isEqualTo(chunk.rowCount() + 1);
            }
        }
    }

    @Test
    void scan_listviewVortex_decodesListViewArrayWithCorrectShape() throws Exception {
        // Given
        URI uri = BASE.resolve("listview.vortex");

        // When
        try (var sut = VortexHttpReader.open(uri)) {
            DType.Struct schema = (DType.Struct) sut.dtype();
            List<String> names = schema.fieldNames();
            List<DType> types = schema.fieldTypes();
            String listColName = null;
            for (int i = 0; i < types.size(); i++) {
                if (types.get(i) instanceof DType.List) {
                    listColName = names.get(i);
                    break;
                }
            }
            assertThat(listColName).as("listview.vortex must have a List-typed column").isNotNull();

            try (var iter = sut.scan(ScanOptions.all())) {
                assertThat(iter.hasNext()).isTrue();
                var chunk = iter.next();

                // Then
                ListViewArray listViewArray = chunk.column(listColName);
                assertThat(listViewArray.elements().length())
                        .as("elements_len must be > 0; 0 indicates silent proto tag mismatch")
                        .isGreaterThan(0);
                assertThat(listViewArray.offsets().dtype())
                        .as("offset_ptype must be U32; U8 indicates silent proto tag mismatch")
                        .isEqualTo(DType.U32);
                assertThat(listViewArray.sizes().dtype())
                        .as("size_ptype must be U32; U8 indicates silent proto tag mismatch")
                        .isEqualTo(DType.U32);
                assertThat(listViewArray.offsets().length())
                        .as("offsets length must equal rowCount")
                        .isEqualTo(chunk.rowCount());
                assertThat(listViewArray.sizes().length())
                        .as("sizes length must equal rowCount")
                        .isEqualTo(chunk.rowCount());
            }
        }
    }
}
