package io.github.dfa1.vortex.integration;

import dev.vortex.api.DataSource;
import dev.vortex.api.Partition;
import dev.vortex.api.Scan;
import dev.vortex.api.ScanOptions;
import dev.vortex.api.Session;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.DoubleArray;
import io.github.dfa1.vortex.core.array.FloatArray;
import io.github.dfa1.vortex.core.array.IntArray;
import io.github.dfa1.vortex.core.array.LongArray;
import io.github.dfa1.vortex.core.array.MaskedArray;
import io.github.dfa1.vortex.core.array.ShortArray;
import io.github.dfa1.vortex.core.array.VarBinArray;
import io.github.dfa1.vortex.encoding.EncodingRegistry;
import io.github.dfa1.vortex.io.VortexReader;
import io.github.dfa1.vortex.scan.ScanResult;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.Float4Vector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.SmallIntVector;
import org.apache.arrow.vector.UInt2Vector;
import org.apache.arrow.vector.UInt4Vector;
import org.apache.arrow.vector.UInt8Vector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.ipc.ArrowReader;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Percentage.withPercentage;

/// Cross-decoder correctness: downloads each S3 fixture once, then compares row counts,
/// numeric column sums, and string byte-length sums from the Rust JNI reader and the
/// Java {@link VortexReader}.
///
/// Both readers decode the same local bytes — no auth, no network dependency during
/// decode. A mismatch in any column value points to a decoding bug in the Java reader.
class VortexHttpReaderJniComparisonIntegrationTest {

	private static final URI BASE =
			URI.create("https://vortex-compat-fixtures.s3.amazonaws.com/v0.72.0/arrays/");

	private static final Session SESSION = Session.create();
	private static final BufferAllocator ALLOCATOR = ArrowAllocation.rootAllocator();
	private static final HttpClient HTTP = HttpClient.newHttpClient();

	static {
		NativeLoader.loadJni();
	}

	private record Stats(long rowCount, Map<String, Double> numSums, Map<String, Long> strLenSums) {}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {
			"alp.vortex",
			"alprd.vortex",
			"bitpacked.vortex",
			"booleans.vortex",
			"bytebool.vortex",
			"chunked.vortex",
			"constant.vortex",
			"datetime.vortex",
			"datetimeparts.vortex",
			"decimal.vortex",
			"decimal_byte_parts.vortex",
			"dict.vortex",
			"fixed_size_list.vortex",
			"for.vortex",
			"fsst.vortex",
			"null.vortex",
			"primitives.vortex",
			"rle.vortex",
			"runend.vortex",
			"sequence.vortex",
			"sparse.vortex",
			"struct_nested.vortex",
			"varbin.vortex",
			"varbinview.vortex",
			"zigzag.vortex",
			"zstd.vortex",
	})
	void jni_vs_javaReader_statsMatch(String fixture, @TempDir Path tmp) throws Exception {
		// Given
		Path local = download(BASE.resolve(fixture), tmp);

		// When
		Stats jniStats = jniStats(local);
		Stats javaStats = javaStats(local);

		// Then — row counts match
		assertThat(javaStats.rowCount())
				.as("row count in %s", fixture)
				.isEqualTo(jniStats.rowCount());

		// Then — numeric column sums match (skip if no numeric cols in JNI output)
		if (!jniStats.numSums().isEmpty()) {
			assertThat(javaStats.numSums().keySet())
					.as("numeric column names in %s", fixture)
					.containsExactlyInAnyOrderElementsOf(jniStats.numSums().keySet());
			for (Map.Entry<String, Double> entry : jniStats.numSums().entrySet()) {
				assertThat(javaStats.numSums().get(entry.getKey()))
						.describedAs("numeric column '%s' sum in %s", entry.getKey(), fixture)
						.isCloseTo(entry.getValue(), withPercentage(0.001));
			}
		}

		// Then — string byte-length sums match (skip if no string cols in JNI output)
		if (!jniStats.strLenSums().isEmpty()) {
			assertThat(javaStats.strLenSums().keySet())
					.as("string column names in %s", fixture)
					.containsExactlyInAnyOrderElementsOf(jniStats.strLenSums().keySet());
			for (Map.Entry<String, Long> entry : jniStats.strLenSums().entrySet()) {
				assertThat(javaStats.strLenSums().get(entry.getKey()))
						.describedAs("string column '%s' byte-length sum in %s", entry.getKey(), fixture)
						.isEqualTo(entry.getValue());
			}
		}
	}

	// ── download ─────────────────────────────────────────────────────────────

	private static Path download(URI uri, Path dir) throws Exception {
		String name = uri.getPath().substring(uri.getPath().lastIndexOf('/') + 1);
		Path file = dir.resolve(name);
		HTTP.send(
				HttpRequest.newBuilder(uri).GET().build(),
				HttpResponse.BodyHandlers.ofFile(file)
		);
		return file;
	}

	// ── JNI (Rust) side ───────────────────────────────────────────────────────

	private static Stats jniStats(Path file) throws Exception {
		Map<String, Double> numSums = new LinkedHashMap<>();
		Map<String, Long> strLenSums = new LinkedHashMap<>();
		long rowCount = 0;
		String uri = file.toAbsolutePath().toUri().toString();
		DataSource ds = DataSource.open(SESSION, uri);
		Scan scan = ds.scan(ScanOptions.of());
		while (scan.hasNext()) {
			Partition partition = scan.next();
			try (ArrowReader reader = partition.scanArrow(ALLOCATOR)) {
				while (reader.loadNextBatch()) {
					var root = reader.getVectorSchemaRoot();
					rowCount += root.getRowCount();
					for (var field : root.getSchema().getFields()) {
						var vec = root.getVector(field.getName());
						switch (vec) {
							case BigIntVector v -> {
								long s = 0;
								for (int i = 0; i < root.getRowCount(); i++) {
									if (!v.isNull(i)) {
										s += v.get(i);
									}
								}
								numSums.merge(field.getName(), (double) s, Double::sum);
							}
							case IntVector v -> {
								long s = 0;
								for (int i = 0; i < root.getRowCount(); i++) {
									if (!v.isNull(i)) {
										s += v.get(i);
									}
								}
								numSums.merge(field.getName(), (double) s, Double::sum);
							}
							case SmallIntVector v -> {
								long s = 0;
								for (int i = 0; i < root.getRowCount(); i++) {
									if (!v.isNull(i)) {
										s += v.get(i);
									}
								}
								numSums.merge(field.getName(), (double) s, Double::sum);
							}
							case Float8Vector v -> {
								double s = 0;
								for (int i = 0; i < root.getRowCount(); i++) {
									if (!v.isNull(i)) {
										s += v.get(i);
									}
								}
								numSums.merge(field.getName(), s, Double::sum);
							}
							case Float4Vector v -> {
								double s = 0;
								for (int i = 0; i < root.getRowCount(); i++) {
									if (!v.isNull(i)) {
										s += v.get(i);
									}
								}
								numSums.merge(field.getName(), s, Double::sum);
							}
							case UInt2Vector v -> {
								long s = 0;
								for (int i = 0; i < root.getRowCount(); i++) {
									if (!v.isNull(i)) {
										s += (short) v.get(i);
									}
								}
								numSums.merge(field.getName(), (double) s, Double::sum);
							}
							case UInt4Vector v -> {
								long s = 0;
								for (int i = 0; i < root.getRowCount(); i++) {
									if (!v.isNull(i)) {
										s += v.get(i);
									}
								}
								numSums.merge(field.getName(), (double) s, Double::sum);
							}
							case UInt8Vector v -> {
								long s = 0;
								for (int i = 0; i < root.getRowCount(); i++) {
									if (!v.isNull(i)) {
										s += v.get(i);
									}
								}
								numSums.merge(field.getName(), (double) s, Double::sum);
							}
							case VarCharVector v -> {
								long s = 0;
								for (int i = 0; i < root.getRowCount(); i++) {
									if (!v.isNull(i)) {
										s += v.get(i).length;
									}
								}
								if (s > 0) {
									strLenSums.merge(field.getName(), s, Long::sum);
								}
							}
							default -> {
								// skip: bool, null, list, struct, extension, etc.
							}
						}
					}
				}
			}
		}
		return new Stats(rowCount, numSums, strLenSums);
	}

	// ── Java side ─────────────────────────────────────────────────────────────

	private static Stats javaStats(Path file) throws Exception {
		Map<String, Double> numSums = new LinkedHashMap<>();
		Map<String, Long> strLenSums = new LinkedHashMap<>();
		long rowCount = 0;
		try (VortexReader reader = VortexReader.open(file, EncodingRegistry.loadAll());
		     var iter = reader.scan(io.github.dfa1.vortex.scan.ScanOptions.all())) {
			while (iter.hasNext()) {
				ScanResult chunk = iter.next();
				rowCount += chunk.rowCount();
				for (Map.Entry<String, Array> e : chunk.columns().entrySet()) {
					Array arr = unwrapMasked(e.getValue());
					Double numSum = numericSum(arr);
					if (numSum != null) {
						numSums.merge(e.getKey(), numSum, Double::sum);
					}
					Long strLen = stringByteLength(arr);
					if (strLen != null && strLen > 0) {
						strLenSums.merge(e.getKey(), strLen, Long::sum);
					}
				}
			}
		}
		return new Stats(rowCount, numSums, strLenSums);
	}

	private static Array unwrapMasked(Array arr) {
		if (arr instanceof MaskedArray m) {
			return m.child(0);
		}
		return arr;
	}

	private static Double numericSum(Array arr) {
		return switch (arr) {
			case LongArray v -> (double) v.fold(0L, Long::sum);
			case DoubleArray v -> v.fold(0.0, Double::sum);
			case IntArray v -> {
				long s = 0;
				for (long i = 0; i < v.length(); i++) {
					s += v.getInt(i);
				}
				yield (double) s;
			}
			case FloatArray v -> {
				double s = 0;
				for (long i = 0; i < v.length(); i++) {
					s += v.getFloat(i);
				}
				yield s;
			}
			case ShortArray v -> {
				long s = 0;
				for (long i = 0; i < v.length(); i++) {
					s += v.getShort(i);
				}
				yield (double) s;
			}
			default -> null;
		};
	}

	private static Long stringByteLength(Array arr) {
		if (!(arr instanceof VarBinArray v)) {
			return null;
		}
		long[] total = {0L};
		v.forEachByteLength(len -> total[0] += len);
		return total[0];
	}
}
