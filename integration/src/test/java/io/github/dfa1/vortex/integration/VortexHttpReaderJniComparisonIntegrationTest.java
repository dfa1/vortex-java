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
import io.github.dfa1.vortex.core.array.ShortArray;
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

/// Cross-decoder correctness: downloads each S3 fixture once, then compares numeric
/// column sums from the Rust JNI reader and the Java {@link VortexReader}.
///
/// Both readers decode the same local bytes — no auth, no network dependency during
/// decode. A mismatch in any column sum points to a decoding bug in the Java reader.
class VortexHttpReaderJniComparisonIntegrationTest {

	private static final URI BASE =
			URI.create("https://vortex-compat-fixtures.s3.amazonaws.com/v0.72.0/arrays/");

	private static final Session SESSION = Session.create();
	private static final BufferAllocator ALLOCATOR = ArrowAllocation.rootAllocator();
	private static final HttpClient HTTP = HttpClient.newHttpClient();

	static {
		NativeLoader.loadJni();
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {
			"alp.vortex",
			"bitpacked.vortex",
			"primitives.vortex",
			"zigzag.vortex",
			"dict.vortex",
	})
	void jni_vs_javaReader_numericColumnSumsMatch(String fixture, @TempDir Path tmp) throws Exception {
		// Given
		Path local = download(BASE.resolve(fixture), tmp);

		// When — Rust (JNI) reference
		Map<String, Double> jniSums = jniColumnSums(local);

		// When — Java reader
		Map<String, Double> javaSums = javaColumnSums(local);

		// Then — same columns, same values
		assertThat(jniSums).as("JNI found no numeric columns in %s", fixture).isNotEmpty();
		assertThat(javaSums.keySet())
				.as("column names in %s", fixture)
				.containsExactlyInAnyOrderElementsOf(jniSums.keySet());
		for (Map.Entry<String, Double> entry : jniSums.entrySet()) {
			assertThat(javaSums.get(entry.getKey()))
					.describedAs("column '%s' sum in %s", entry.getKey(), fixture)
					.isCloseTo(entry.getValue(), withPercentage(0.001));
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

	private static Map<String, Double> jniColumnSums(Path file) throws Exception {
		Map<String, Double> sums = new LinkedHashMap<>();
		String uri = file.toAbsolutePath().toUri().toString();
		DataSource ds = DataSource.open(SESSION, uri);
		Scan scan = ds.scan(ScanOptions.of());
		while (scan.hasNext()) {
			Partition partition = scan.next();
			try (ArrowReader reader = partition.scanArrow(ALLOCATOR)) {
				while (reader.loadNextBatch()) {
					var root = reader.getVectorSchemaRoot();
					for (var field : root.getSchema().getFields()) {
						var vec = root.getVector(field.getName());
						Double colSum = switch (vec) {
							case BigIntVector v -> {
								long s = 0;
								for (int i = 0; i < root.getRowCount(); i++) {
									if (!v.isNull(i)) {
										s += v.get(i);
									}
								}
								yield (double) s;
							}
							case IntVector v -> {
								long s = 0;
								for (int i = 0; i < root.getRowCount(); i++) {
									if (!v.isNull(i)) {
										s += v.get(i);
									}
								}
								yield (double) s;
							}
							case SmallIntVector v -> {
								long s = 0;
								for (int i = 0; i < root.getRowCount(); i++) {
									if (!v.isNull(i)) {
										s += v.get(i);
									}
								}
								yield (double) s;
							}
							case Float8Vector v -> {
								double s = 0;
								for (int i = 0; i < root.getRowCount(); i++) {
									if (!v.isNull(i)) {
										s += v.get(i);
									}
								}
								yield s;
							}
							case Float4Vector v -> {
								double s = 0;
								for (int i = 0; i < root.getRowCount(); i++) {
									if (!v.isNull(i)) {
										s += v.get(i);
									}
								}
								yield s;
							}
							case UInt2Vector v -> {
								long s = 0;
								for (int i = 0; i < root.getRowCount(); i++) {
									if (!v.isNull(i)) {
										s += (short) v.get(i); // cast char→short to match Java ShortArray signed bits
									}
								}
								yield (double) s;
							}
							case UInt4Vector v -> {
								long s = 0;
								for (int i = 0; i < root.getRowCount(); i++) {
									if (!v.isNull(i)) {
										s += v.get(i);
									}
								}
								yield (double) s;
							}
							case UInt8Vector v -> {
								long s = 0;
								for (int i = 0; i < root.getRowCount(); i++) {
									if (!v.isNull(i)) {
										s += v.get(i);
									}
								}
								yield (double) s;
							}
							default -> null;
						};
						if (colSum != null) {
							sums.merge(field.getName(), colSum, Double::sum);
						}
					}
				}
			}
		}
		return sums;
	}

	// ── Java side ─────────────────────────────────────────────────────────────

	private static Map<String, Double> javaColumnSums(Path file) throws Exception {
		Map<String, Double> sums = new LinkedHashMap<>();
		try (VortexReader reader = VortexReader.open(file, EncodingRegistry.loadAll());
		     var iter = reader.scan(io.github.dfa1.vortex.scan.ScanOptions.all())) {
			while (iter.hasNext()) {
				ScanResult chunk = iter.next();
				for (Map.Entry<String, Array> e : chunk.columns().entrySet()) {
					Double colSum = sumArray(e.getValue());
					if (colSum != null) {
						sums.merge(e.getKey(), colSum, Double::sum);
					}
				}
			}
		}
		return sums;
	}

	private static Double sumArray(Array arr) {
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
}
