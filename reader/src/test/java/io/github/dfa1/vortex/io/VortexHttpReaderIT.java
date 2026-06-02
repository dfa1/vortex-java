package io.github.dfa1.vortex.io;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.array.ListArray;
import io.github.dfa1.vortex.core.array.ListViewArray;
import io.github.dfa1.vortex.scan.ScanOptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Integration test: reads real Vortex files from the public S3 compatibility bucket
/// via HTTP Range requests and validates structure + data.
///
/// Skipped automatically when the network is unavailable.
@Tag("integration")
class VortexHttpReaderIT {

	private static final String BASE = "https://vortex-compat-fixtures.s3.amazonaws.com/v0.72.0/arrays/";

	private static final URI TPCH_LINEITEM = URI.create(BASE + "tpch_lineitem.compact.vortex");

	// for.vortex — frame-of-reference encoding, 10 columns in one flat segment
	private static final URI FOR_ARRAY = URI.create(BASE + "for.vortex");

	@Test
	void open_remoteFile_parsesMetadata() throws Exception {
		// Given
		assumeNetworkAvailable();

		// When
		try (var sut = VortexHttpReader.open(TPCH_LINEITEM)) {

			// Then
			assertThat(sut.version()).isEqualTo(1);
			assertThat(sut.fileSize()).isGreaterThan(VortexReader.TRAILER_SIZE);
			assertThat(sut.layout()).isNotNull();
			assertThat(sut.footer()).isNotNull();
			assertThat(sut.dtype()).isNotNull();
		}
	}

	@Test
	void open_remoteFile_layoutRowCountIsPositive() throws Exception {
		// Given
		assumeNetworkAvailable();

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
		assumeNetworkAvailable();

		// When
		long totalRows = 0;
		try (var sut = VortexHttpReader.open(FOR_ARRAY);
		     var iter = sut.scan(ScanOptions.all())) {
			while (iter.hasNext()) {
				totalRows += iter.next().rowCount();
			}
		}

		// Then
		assertThat(totalRows).isGreaterThan(0);
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {
			"primitives.vortex",
			"alp.vortex",
			"bitpacked.vortex",
			"booleans.vortex",
			"bytebool.vortex",
			"constant.vortex",
			"fsst.vortex",
			"null.vortex",
			"runend.vortex",
			"sequence.vortex",
			"varbin.vortex",
			"zigzag.vortex",
			"struct_nested.vortex",
			"datetime.vortex",
			"varbinview.vortex",
			"dict.vortex",
			"sparse.vortex",
			"decimal.vortex",
			"decimal_byte_parts.vortex",
			"datetimeparts.vortex",
			"zstd.vortex",
			"chunked.vortex",
			"rle.vortex",
			"list.vortex",
			"listview.vortex",
			"fixed_size_list.vortex",
			"alprd.vortex",
	})
	void scan_fixture_decodesAllRows(String fixture) throws Exception {
		// Given
		assumeNetworkAvailable();
		URI uri = URI.create(BASE + fixture);

		// When
		long totalRows = 0;
		try (var sut = VortexHttpReader.open(uri);
		     var iter = sut.scan(ScanOptions.all())) {
			while (iter.hasNext()) {
				totalRows += iter.next().rowCount();
			}
		}

		// Then
		assertThat(totalRows).isGreaterThan(0);
	}

	@Test
	void scan_listVortex_decodesListArrayWithCorrectShape() throws Exception {
		// Given
		assumeNetworkAvailable();
		URI uri = URI.create(BASE + "list.vortex");

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
						.isEqualTo(new DType.Primitive(PType.I64, false));
				assertThat(listArray.offsets().length())
						.as("offsets length must be rowCount + 1")
						.isEqualTo(chunk.rowCount() + 1);
			}
		}
	}

	@Test
	void scan_listviewVortex_decodesListViewArrayWithCorrectShape() throws Exception {
		// Given
		assumeNetworkAvailable();
		URI uri = URI.create(BASE + "listview.vortex");

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
						.isEqualTo(new DType.Primitive(PType.U32, false));
				assertThat(listViewArray.sizes().dtype())
						.as("size_ptype must be U32; U8 indicates silent proto tag mismatch")
						.isEqualTo(new DType.Primitive(PType.U32, false));
				assertThat(listViewArray.offsets().length())
						.as("offsets length must equal rowCount")
						.isEqualTo(chunk.rowCount());
				assertThat(listViewArray.sizes().length())
						.as("sizes length must equal rowCount")
						.isEqualTo(chunk.rowCount());
			}
		}
	}

	private static void assumeNetworkAvailable() {
		try {
			URI.create("https://vortex-compat-fixtures.s3.amazonaws.com").toURL().openStream().close();
		} catch (Exception e) {
			assumeTrue(false, "network unavailable: " + e.getMessage());
		}
	}
}
