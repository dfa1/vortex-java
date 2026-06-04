package io.github.dfa1.vortex.io;

import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.EmptyArray;
import io.github.dfa1.vortex.core.array.UnknownArray;
import io.github.dfa1.vortex.encoding.Encoding;
import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.encoding.EncodingRegistry;
import io.github.dfa1.vortex.encoding.DecodeContext;
import io.github.dfa1.vortex.encoding.EncodeResult;
import io.github.dfa1.vortex.scan.ScanOptions;
import io.github.dfa1.vortex.scan.ScanResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VortexReaderTest {

	// --- trailer / magic validation ---

	private static EncodingRegistry buildUniversalStubRegistry() {
		var registry = EncodingRegistry.empty();
		Encoding stub = new Encoding() {
			@Override
			public EncodingId encodingId() {
				return EncodingId.VORTEX_PRIMITIVE;
			}

			@Override
			public EncodeResult encode(DType dtype, Object data) {
				throw new UnsupportedOperationException();
			}

			@Override
			public Array decode(DecodeContext ctx) {
				return EmptyArray.of(ctx.dtype());
			}
		};
		for (EncodingId encodingId : EncodingId.values()) {
			registry.register(new Encoding() {
				@Override
				public EncodingId encodingId() {
					return encodingId;
				}

				@Override
				public EncodeResult encode(DType dtype, Object data) {
					throw new UnsupportedOperationException();
				}

				@Override
				public Array decode(DecodeContext ctx) {
					return EmptyArray.of(ctx.dtype());
				}
			});
		}
		return registry;
	}

	@Test
	void open_fileTooSmall_throwsVortexException(@TempDir Path tmpDir) throws IOException {
		// Given
		Path sut = Files.write(tmpDir.resolve("tiny.vortex"), new byte[4]);

		// When / Then
		assertThatThrownBy(() -> VortexReader.open(sut))
				.isInstanceOf(VortexException.class)
				.hasMessageContaining("file too small");
	}

	// --- real fixtures: full parse ---

	@Test
	void open_wrongMagic_throwsVortexException(@TempDir Path tmpDir) throws IOException {
		// Given
		byte[] bytes = new byte[VortexReader.TRAILER_SIZE]; // exactly 8 bytes
		bytes[4] = 'X';
		bytes[5] = 'X';
		bytes[6] = 'X';
		bytes[7] = 'X';
		Path sut = Files.write(tmpDir.resolve("bad_magic.vortex"), bytes);

		// When / Then
		assertThatThrownBy(() -> VortexReader.open(sut))
				.isInstanceOf(VortexException.class)
				.hasMessageContaining("invalid magic bytes");
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"primitives.vortex",
			"booleans.vortex",
			"null.vortex",
			"varbin.vortex",
			"chunked.vortex"
	})
	void open_fixture_parsesSuccessfully(String name) throws URISyntaxException, IOException {
		// Given
		Path path = fixtureFile(name);

		// When
		try (var sut = VortexReader.open(path)) {

			// Then
			assertThat(sut.version()).isEqualTo(1);
			assertThat(sut.fileSize()).isGreaterThan(VortexReader.TRAILER_SIZE);
			assertThat(sut.layout()).isNotNull();
			assertThat(sut.footer()).isNotNull();
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"primitives.vortex",
			"booleans.vortex",
			"null.vortex",
			"varbin.vortex",
			"chunked.vortex"
	})
	void fixture_hasMagicBytesAtEnd(String name) throws IOException, URISyntaxException {
		// Given
		byte[] bytes = Files.readAllBytes(fixtureFile(name));

		// When
		byte[] trailerMagic = new byte[]{
				bytes[bytes.length - 4],
				bytes[bytes.length - 3],
				bytes[bytes.length - 2],
				bytes[bytes.length - 1]
		};

		// Then
		assertThat(trailerMagic).isEqualTo(VortexReader.MAGIC);
	}

	// --- scan ---

	@ParameterizedTest
	@ValueSource(strings = {
			"primitives.vortex",
			"booleans.vortex",
			"null.vortex",
			"varbin.vortex",
			"chunked.vortex"
	})
	void fixture_trailerHasExpectedVersion(String name) throws IOException, URISyntaxException {
		// Given
		byte[] bytes = Files.readAllBytes(fixtureFile(name));
		int trailerStart = bytes.length - VortexReader.TRAILER_SIZE;

		// When
		int version = java.nio.ByteBuffer.wrap(bytes, trailerStart, 2)
				.order(ByteOrder.LITTLE_ENDIAN)
				.getShort() & 0xFFFF;

		// Then
		assertThat(version).isEqualTo(1);
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"primitives.vortex",
			"booleans.vortex",
			"null.vortex",
			"varbin.vortex",
			"chunked.vortex"
	})
	void scan_withNoDecoders_reachesDecodeStep(String name) throws URISyntaxException, IOException {
		// Given
		Path path = fixtureFile(name);

		// When / Then — layout traversal succeeds; decode fails only on missing decoder
		try (var sut = VortexReader.open(path, EncodingRegistry.empty());
		     var iter = sut.scan(ScanOptions.all())) {
			assertThatThrownBy(iter::hasNext)
					.isInstanceOf(VortexException.class)
					.hasMessageContaining("no encoding registered");
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"primitives.vortex",
			"booleans.vortex",
			"null.vortex",
			"varbin.vortex",
			"chunked.vortex"
	})
	void scan_withNoDecoders_allowUnknown_returnsUnknownArray(String name) throws URISyntaxException, IOException {
		// Given — empty registry + allowUnknown: every leaf decodes to a passthrough UnknownArray
		Path path = fixtureFile(name);
		var registry = EncodingRegistry.empty().allowUnknown();

		// When
		try (var sut = VortexReader.open(path, registry);
		     var iter = sut.scan(ScanOptions.all())) {

			// Then
			assertThat(iter.hasNext()).isTrue();
			ScanResult chunk = iter.next();
			assertThat(chunk.rowCount()).isGreaterThan(0);
			assertThat(chunk.columns()).isNotEmpty();
			for (Array column : chunk.columns().values()) {
				assertThat(column).isInstanceOf(UnknownArray.class);
				UnknownArray foreign = (UnknownArray) column;
				assertThat(foreign.encodingId()).startsWith("vortex.").describedAs(name);
			}
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"primitives.vortex",
			"booleans.vortex",
			"null.vortex",
			"varbin.vortex",
			"chunked.vortex"
	})
	void scan_withStubDecoder_producesAtLeastOneChunk(String name) throws URISyntaxException, IOException {
		// Given
		Path path = fixtureFile(name);
		var registry = buildUniversalStubRegistry();

		// When
		try (var sut = VortexReader.open(path, registry);
		     var iter = sut.scan(ScanOptions.all())) {

			// Then
			assertThat(iter.hasNext()).isTrue();
			ScanResult chunk = iter.next();
			assertThat(chunk.rowCount()).isGreaterThan(0);
			assertThat(chunk.columns()).isNotEmpty();
		}
	}

	@Test
	void scan_chunkedFixture_producesExactlyOneLayoutChunk() throws URISyntaxException, IOException {
		// Given — chunked.vortex uses a ChunkedArray *encoding* inside one flat layout node
		Path path = fixtureFile("chunked.vortex");
		var registry = buildUniversalStubRegistry();
		int chunkCount = 0;

		// When
		try (var sut = VortexReader.open(path, registry);
		     var iter = sut.scan(ScanOptions.all())) {
			while (iter.hasNext()) {
				iter.next();
				chunkCount++;
			}
		}

		// Then
		assertThat(chunkCount).isEqualTo(1);
	}

	@Test
	void scan_withLimit_returnsExactlyNRows() throws URISyntaxException, IOException {
		// Given — primitives.vortex has 3 rows; limit=2 forces truncation of the single chunk
		Path path = fixtureFile("primitives.vortex");
		long limit = 2;

		// When
		long totalRows = 0;
		try (var sut = VortexReader.open(path);
		     var iter = sut.scan(ScanOptions.limit(limit))) {
			while (iter.hasNext()) {
				ScanResult chunk = iter.next();
				totalRows += chunk.rowCount();
				for (Array col : chunk.columns().values()) {
					assertThat(col.length()).isLessThanOrEqualTo(limit);
				}
			}
		}

		// Then
		assertThat(totalRows).isEqualTo(limit);
	}

	@Test
	void scan_withLimitExceedingTotal_returnsAllRows() throws URISyntaxException, IOException {
		// Given
		Path path = fixtureFile("primitives.vortex");
		long totalWithoutLimit;
		try (var sut = VortexReader.open(path);
		     var iter = sut.scan(ScanOptions.all())) {
			totalWithoutLimit = 0;
			while (iter.hasNext()) {
				totalWithoutLimit += iter.next().rowCount();
			}
		}

		// When
		long totalWithLimit = 0;
		try (var sut = VortexReader.open(path);
		     var iter = sut.scan(ScanOptions.limit(Long.MAX_VALUE))) {
			while (iter.hasNext()) {
				totalWithLimit += iter.next().rowCount();
			}
		}

		// Then
		assertThat(totalWithLimit).isEqualTo(totalWithoutLimit);
	}

	@Test
	void scan_withLimitZero_returnsNoRows() throws URISyntaxException, IOException {
		// Given
		Path path = fixtureFile("primitives.vortex");

		// When
		long totalRows = 0;
		try (var sut = VortexReader.open(path);
		     var iter = sut.scan(ScanOptions.limit(0))) {
			while (iter.hasNext()) {
				totalRows += iter.next().rowCount();
			}
		}

		// Then
		assertThat(totalRows).isZero();
	}

	// --- helpers ---

	private Path fixtureFile(String name) throws URISyntaxException {
		var url = getClass().getResource("/fixtures/" + name);
		assertThat(url).as("fixture not found: " + name).isNotNull();
		return Path.of(url.toURI());
	}
}
