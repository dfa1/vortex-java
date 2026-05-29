package io.github.dfa1.vortex.io;

import io.github.dfa1.vortex.scan.ScanOptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;

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
			"constant.vortex",
			"fsst.vortex",
			"null.vortex",
			"runend.vortex",
			"sequence.vortex",
			"varbin.vortex",
			"struct_nested.vortex",
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

	private static void assumeNetworkAvailable() {
		try {
			URI.create("https://vortex-compat-fixtures.s3.amazonaws.com").toURL().openStream().close();
		} catch (Exception e) {
			assumeTrue(false, "network unavailable: " + e.getMessage());
		}
	}
}
