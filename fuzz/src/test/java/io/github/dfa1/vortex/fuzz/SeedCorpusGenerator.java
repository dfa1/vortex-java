package io.github.dfa1.vortex.fuzz;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.LongFunction;
import java.util.stream.Stream;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.writer.ColumnEncoding;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/// Writes the per-encoding seed corpus of [VortexReaderFuzzTest#scanMalformedFile(byte[])] (#493),
/// [VortexReaderFuzzTest#filterMalformedFile(byte[])]'s copy of every scan seed behind a
/// filter-choice header, and [VortexReaderFuzzTest#parseMetadataBlobs(byte[])]'s metadata blobs of
/// every scan seed.
///
/// A byte mutator never synthesizes a valid FSST symbol table or Pco chunk header from the
/// hand-made seeds, so each encoding gets a small file of its own that already decodes: the
/// fuzzer then mutates from inside the decoder instead of bouncing off its first check. Each
/// column is forced through one encoding with [ColumnEncoding#candidates(EncodingId...)], and
/// shaped so that encoding wins (a candidate that does not pay is silently left canonical, which
/// the assertion on the written bytes catches). Every 13th row is null so validity is exercised.
///
/// Not part of any build; regenerate (then commit) with
/// `./mvnw test -pl fuzz -am -Dtest=SeedCorpusGenerator -Dsurefire.failIfNoSpecifiedTests=false -Dvortex.fuzz.regenerateSeeds=true`
@EnabledIfSystemProperty(named = "vortex.fuzz.regenerateSeeds", matches = "true")
class SeedCorpusGenerator {

    private static final Path INPUTS = Path.of("src/test/resources/io/github/dfa1/vortex/fuzz/VortexReaderFuzzTestInputs");
    private static final Path OUT = INPUTS.resolve("scanMalformedFile");
    private static final Path FILTER_OUT = INPUTS.resolve("filterMalformedFile");
    private static final Path BLOBS_OUT = INPUTS.resolve("parseMetadataBlobs");
    private static final ColumnName VALUE = ColumnName.of("v");
    // One full 1024-value FastLanes block plus a partial one, still only a few KB per file.
    private static final int ROWS = 1_100;

    static Stream<Arguments> seeds() {
        Random random = new Random(42);
        double[] noise = random.doubles(ROWS).toArray();
        return Stream.of(
                seed(DType.I32, i -> (int) (i % 100), EncodingId.FASTLANES_BITPACKED),
                seed(DType.I64, i -> 1_000_000L + i % 100, EncodingId.FASTLANES_FOR),
                seed(DType.I64, i -> i * 3 + i % 2, EncodingId.FASTLANES_DELTA),
                seed(DType.I32, i -> (int) (i / 50), EncodingId.FASTLANES_RLE),
                seed(DType.I64, i -> i / 100, EncodingId.VORTEX_RUNEND),
                seed(DType.I32, i -> (int) (i % 2 == 0 ? i % 50 : -(i % 50)), EncodingId.VORTEX_ZIGZAG),
                seed(DType.I64, i -> i % 97 == 0 ? i : 0L, EncodingId.VORTEX_SPARSE),
                seed(DType.I64, i -> (i * 2_654_435_761L) % 1_000_003, EncodingId.VORTEX_PCO),
                seed(DType.F64, i -> (i % 1000) / 100.0, EncodingId.VORTEX_ALP),
                seed(DType.F64, i -> noise[(int) i], EncodingId.VORTEX_ALPRD, EncodingId.FASTLANES_BITPACKED),
                seed(DType.UTF8, i -> "category-" + i % 5, EncodingId.VORTEX_DICT),
                seed(DType.UTF8, i -> "https://example.com/items/" + i % 300 + "/detail", EncodingId.VORTEX_FSST),
                seed(DType.UTF8, i -> "https://example.com/items/" + i % 300 + "/detail", EncodingId.VORTEX_ONPAIR),
                seed(DType.UTF8, i -> "row " + i % 40 + " of the zstd seed", EncodingId.VORTEX_ZSTD));
    }

    /// Mirrors every scan seed (not the crash inputs) into the filter target's corpus behind a
    /// header choosing column 0 and a predicate kind that cycles across seeds, against 3.
    @AfterAll
    static void writeFilterSeeds() throws IOException {
        Files.createDirectories(FILTER_OUT);
        List<Path> seeds = scanSeeds();
        for (int i = 0; i < seeds.size(); i++) {
            ByteBuffer header = ByteBuffer.allocate(VortexReaderFuzzTest.FILTER_HEADER_BYTES)
                    .putInt(0).put((byte) i).putLong(3L);
            byte[] file = Files.readAllBytes(seeds.get(i));
            byte[] seed = Arrays.copyOf(header.array(), header.capacity() + file.length);
            System.arraycopy(file, 0, seed, header.capacity(), file.length);
            Files.write(FILTER_OUT.resolve(seeds.get(i).getFileName()), seed);
        }
    }

    /// Mirrors every scan seed's footer, layout and dtype blobs into the metadata-blob target's
    /// corpus, in the flat form it fuzzes.
    @AfterAll
    static void writeBlobSeeds() throws IOException {
        Files.createDirectories(BLOBS_OUT);
        for (Path seed : scanSeeds()) {
            Files.write(BLOBS_OUT.resolve(seed.getFileName()), MetadataBlobs.extract(Files.readAllBytes(seed)));
        }
    }

    private static List<Path> scanSeeds() throws IOException {
        try (Stream<Path> files = Files.list(OUT)) {
            return files.filter(f -> f.getFileName().toString().startsWith("seed-")).sorted().toList();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("seeds")
    void writeSeed(String name, DType dtype, Object values, EncodingId[] candidates) throws IOException {
        // Given
        Path file = OUT.resolve("seed-" + name);
        DType.Struct schema = new DType.Struct(List.of(VALUE), List.of(dtype.asNullable()), false);
        WriteOptions options = WriteOptions.defaults().withoutEditions()
                .withColumnEncoding(VALUE, ColumnEncoding.candidates(candidates));

        // When
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING);
             var writer = VortexWriter.create(ch, schema, options)) {
            writer.writeChunk(Map.of(VALUE, values));
        }

        // Then
        String result = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
        assertTrue(result.contains(candidates[0].toString()), candidates[0] + " lost to the canonical encoding");
    }

    private static Arguments seed(DType dtype, LongFunction<Object> value, EncodingId... candidates) {
        Object[] values = switch (dtype) {
            case DType.Primitive p when p.equals(DType.I32) -> new Integer[ROWS];
            case DType.Primitive p when p.equals(DType.I64) -> new Long[ROWS];
            case DType.Primitive p when p.equals(DType.F64) -> new Double[ROWS];
            default -> new String[ROWS];
        };
        for (int i = 0; i < ROWS; i++) {
            values[i] = i % 13 == 0 ? null : value.apply(i);
        }
        String name = candidates[0].toString().replace('.', '-');
        return Arguments.of(name, dtype, values, candidates);
    }
}
