package io.github.dfa1.vortex.integration;

import dev.vortex.api.DataSource;
import dev.vortex.api.Session;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.DTypes;
import io.github.dfa1.vortex.core.model.ExtensionId;
import io.github.dfa1.vortex.core.model.ExtensionMetadata;
import io.github.dfa1.vortex.core.model.OpaqueMetadata;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.ScanFilter;
import io.github.dfa1.vortex.reader.ScanLimit;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.FixedSizeListArray;
import io.github.dfa1.vortex.reader.array.FloatArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.extension.ExtensionDecoder;
import io.github.dfa1.vortex.writer.ExtensionEncoder;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;
import io.github.dfa1.vortex.writer.WriteRegistry;
import io.github.dfa1.vortex.writer.encode.FixedSizeListData;
import io.github.dfa1.vortex.writer.encode.NullableData;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Acceptance test for a third-party extension type (#530), end to end through the public API:
/// a `example.vector` type is defined outside the library, registered on both registries, written
/// from domain values and read back as domain values — the Java counterpart of Rust's
/// "adding a new logical type" (an `ExtVTable` registered on the session).
///
/// The type is a fixed-dimension `float[]` embedding over `FixedSizeList<F32, dim>` storage, with
/// the dimension also recorded in a typed `VectorMetadata` (a u32 LE on the wire), so the test covers non-primitive storage,
/// metadata round-trip, nulls and the `validate` hook on both sides. Reads go through a full scan, a
/// filter on a sibling column and a limit: a full scan never prunes or slices, and a sliced
/// fixed-size list must keep each vector's floats with its row.
///
/// The documented counterpart is the "Add a custom extension type" recipe in `docs/how-to.md`,
/// which links here as the complete example: both show the same extension SPI (a typed metadata
/// record, `serializeMetadata`, `parseMetadata`, `validate`), so a change to one changes the other.
class CustomExtensionIntegrationTest {

    static {
        NativeLoader.loadJni();
    }

    private static final Session SESSION = Session.create();
    private static final ExtensionId VECTOR = ExtensionId.parse("example.vector");
    private static final ColumnName ID = ColumnName.of("id");
    private static final ColumnName EMBEDDING = ColumnName.of("embedding");
    private static final int DIM = 3;
    private static final int CHUNKS = 3;
    private static final int CHUNK_ROWS = 10_000;
    private static final int ROWS = CHUNKS * CHUNK_ROWS;

    private static final WriteRegistry WRITE_REGISTRY = WriteRegistry.builder()
            .registerDefaults()
            .register(new VectorEncoder())
            .build();
    private static final ReadRegistry READ_REGISTRY = ReadRegistry.builder()
            .registerDefaults()
            .register(new VectorDecoder())
            .build();

    @Nested
    class RoundTrip {

        @Test
        void fullScan_decodesEveryVectorAndNull(@TempDir Path tmp) throws IOException {
            // Given — three batches of nullable vectors written from float[] values
            Path file = tmp.resolve("vectors.vortex");
            writeVectors(file, vectorDtype(true));

            // When
            SequencedMap<Long, float[]> result = readVectors(file, ScanOptions.all());

            // Then
            assertThat(result.keySet()).containsExactlyElementsOf(ids(0, ROWS));
            assertEachVectorMatchesItsId(result);
        }

        @Test
        void nullRows_readBackAsNull(@TempDir Path tmp) throws IOException {
            // Given — a nullable column written with a null vector at every seventh row (3, 10, 17, …)
            Path file = tmp.resolve("vectors.vortex");
            writeVectors(file, vectorDtype(true));

            // When
            SequencedMap<Long, float[]> result = readVectors(file, ScanOptions.all());

            // Then — the null rows, listed by id rather than compared with vector(): a fixture that
            // stops writing nulls still agrees with vector() and passes assertEachVectorMatchesItsId,
            // so only this catches it; an encoder or decoder losing the validity fails here too
            List<Long> nullRows = result.entrySet().stream()
                    .filter(row -> row.getValue() == null)
                    .map(Map.Entry::getKey)
                    .toList();
            assertThat(nullRows).isEqualTo(LongStream.range(0, ROWS).filter(row -> row % 7 == 3).boxed().toList());
        }

        @Test
        void schema_keepsIdStorageAndMetadata(@TempDir Path tmp) throws IOException {
            // Given
            Path file = tmp.resolve("vectors.vortex");
            DType.Extension written = vectorDtype(true);
            writeVectors(file, written);

            DType result;
            try (VortexReader reader = VortexReader.open(file, READ_REGISTRY)) {
                // When
                result = ((DType.Struct) reader.dtype()).fieldTypes().get(1);
            }

            // Then — the encoder serialized the typed metadata and the decoder parsed it back, so
            // the dimension survived as a VectorMetadata, still readable after the reader closed
            assertThat(result).isEqualTo(written);
            assertThat(((DType.Extension) result).metadata()).isEqualTo(new VectorMetadata(DIM));
        }

        @Test
        void filteredScan_keepsEachVectorWithItsRow(@TempDir Path tmp) throws IOException {
            // Given — a zone-map filter on the sibling id column. It skips only whole chunks that
            // cannot match, so the result is a superset of the matching rows; every vector must
            // still sit next to its own id.
            Path file = tmp.resolve("vectors.vortex");
            writeVectors(file, vectorDtype(true));
            long threshold = CHUNK_ROWS + CHUNK_ROWS / 2;

            // When
            SequencedMap<Long, float[]> result =
                    readVectors(file, ScanOptions.all().withFilter(ScanFilter.gte(ID, threshold)));

            // Then — a hash lookup per id: AssertJ's containsAll scans the whole key set for each of
            // the 15 000 ids, which took 2 s
            assertThat(ids(threshold, ROWS)).allMatch(result::containsKey);
            assertEachVectorMatchesItsId(result);
        }

        @Test
        void limitedScan_returnsTheFirstRows(@TempDir Path tmp) throws IOException {
            // Given
            Path file = tmp.resolve("vectors.vortex");
            writeVectors(file, vectorDtype(true));
            int limit = CHUNK_ROWS + 7;

            // When
            SequencedMap<Long, float[]> result = readVectors(file, ScanOptions.all().withLimit(ScanLimit.of(limit)));

            // Then
            assertThat(result.keySet()).containsExactlyElementsOf(ids(0, limit));
            assertEachVectorMatchesItsId(result);
        }

        @Test
        void storageArray_withoutEncoder_isWrittenAsIs(@TempDir Path tmp) throws IOException {
            // Given — no encoder registered: the caller passes the storage shape itself and the
            // metadata as the bytes a file stores, as for any extension id the writer does not know
            Path file = tmp.resolve("raw.vortex");
            DType.Struct schema = schema(opaqueVectorDtype(2));
            float[] flat = {1f, 2f, 3f, 4f};
            try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var sut = VortexWriter.create(ch, schema, WriteOptions.defaults())) {
                // When
                sut.writeChunk(Map.of(ID, new long[]{0L, 1L}, EMBEDDING, new FixedSizeListData(flat, 2)));
            }

            // Then
            assertThat(readVectors(file, ScanOptions.all()).values())
                    .containsExactly(new float[]{1f, 2f}, new float[]{3f, 4f});
        }
    }

    @Nested
    class UnregisteredOnRead {

        @Test
        void open_withoutDecoder_throws(@TempDir Path tmp) throws IOException {
            // Given — a reader with only the built-ins. Rust refuses an extension id its session
            // has no vtable for ("No such DType extension ID") unless unknown ids are allowed.
            Path file = tmp.resolve("vectors.vortex");
            writeVectors(file, vectorDtype(false));
            ReadRegistry builtInsOnly = ReadRegistry.loadAll();

            // When / Then
            assertThatThrownBy(() -> VortexReader.open(file, builtInsOnly).close())
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("no extension decoder registered for example.vector");
        }

        @Test
        void allowUnknown_readsColumnAsStorageArray(@TempDir Path tmp) throws IOException {
            // Given — with unknown ids allowed the column reads as its fixed-size-list storage,
            // Rust's ForeignExtDType
            Path file = tmp.resolve("vectors.vortex");
            writeVectors(file, vectorDtype(false));
            ReadRegistry allowUnknown = ReadRegistry.builder().registerDefaults().allowUnknown().build();

            // When
            float firstElementOfRow1;
            try (VortexReader reader = VortexReader.open(file, allowUnknown);
                 var iter = reader.scan(ScanOptions.all().withLimit(ScanLimit.of(2)));
                 Chunk chunk = iter.next()) {
                FixedSizeListArray storage = chunk.column(EMBEDDING);
                firstElementOfRow1 = ((FloatArray) storage.elements()).getFloat(DIM);
            }

            // Then
            // row 1's vector is (1, -1.25, 1.5): its first element is the row number
            assertThat(firstElementOfRow1).isEqualTo(1f);
        }

        @Test
        void as_withoutDecoder_throws(@TempDir Path tmp) throws IOException {
            // Given
            Path file = tmp.resolve("vectors.vortex");
            writeVectors(file, vectorDtype(false));
            ReadRegistry allowUnknown = ReadRegistry.builder().registerDefaults().allowUnknown().build();

            try (VortexReader reader = VortexReader.open(file, allowUnknown);
                 var iter = reader.scan(ScanOptions.all().withLimit(ScanLimit.of(2)));
                 Chunk chunk = iter.next()) {
                // When / Then
                assertThatThrownBy(() -> chunk.as(EMBEDDING, float[].class))
                        .isInstanceOf(VortexException.class)
                        .hasMessageContaining("no extension decoder registered for example.vector");
            }
        }

        @Test
        void as_wrongDomainType_throws(@TempDir Path tmp) throws IOException {
            // Given
            Path file = tmp.resolve("vectors.vortex");
            writeVectors(file, vectorDtype(false));

            try (VortexReader reader = VortexReader.open(file, READ_REGISTRY);
                 var iter = reader.scan(ScanOptions.all().withLimit(ScanLimit.of(2)));
                 Chunk chunk = iter.next()) {
                // When / Then
                assertThatThrownBy(() -> chunk.as(EMBEDDING, double[].class))
                        .isInstanceOf(VortexException.class)
                        .hasMessageContaining("decodes to float[], not double[]");
            }
        }

        @Test
        void vortexJni_withoutVtable_refusesTheFile(@TempDir Path tmp) throws IOException {
            // Given — the Rust reference, which has no example.vector vtable and whose JNI session
            // exposes no allow_unknown: the behavior the strict default above mirrors
            Path file = tmp.resolve("vectors.vortex");
            writeVectors(file, vectorDtype(false));
            String uri = file.toAbsolutePath().toUri().toString();

            // When / Then
            assertThatThrownBy(() -> DataSource.open(SESSION, uri).scan(dev.vortex.api.ScanOptions.of()))
                    .hasMessageContaining("No such DType extension ID: example.vector");
        }
    }

    @Nested
    class Validation {

        @Test
        void create_withoutEncoder_rejectsTypedMetadata(@TempDir Path tmp) throws IOException {
            // Given — no encoder to serialize a VectorMetadata: refused when the writer is created,
            // not when it closes after the data is written
            Path file = tmp.resolve("unserializable.vortex");

            try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                // When / Then
                assertThatThrownBy(() -> VortexWriter.create(ch, schema(vectorDtype(false)), WriteOptions.defaults()))
                        .isInstanceOf(VortexException.class)
                        .hasMessageContaining("no extension encoder registered for example.vector");
            }
        }

        @Test
        void create_rejectsDtypeTheEncoderRefuses(@TempDir Path tmp) throws IOException {
            // Given — metadata says 4 dimensions, storage holds 3
            DType.Extension invalid = new DType.Extension(VECTOR.id(),
                    new DType.FixedSizeList(DTypes.F32, DIM, false), new VectorMetadata(4), false);
            Path file = tmp.resolve("invalid.vortex");

            try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                // When / Then
                assertThatThrownBy(() -> VortexWriter.create(ch, schema(invalid), WriteOptions.defaults(), WRITE_REGISTRY))
                        .isInstanceOf(VortexException.class)
                        .hasMessageContaining("example.vector metadata dimension 4 != storage size 3");
            }
        }

        @Test
        void open_rejectsDtypeTheDecoderRefuses(@TempDir Path tmp) throws IOException {
            // Given — a file from a writer that had no encoder, so nothing validated the dtype
            DType.Extension invalid = opaqueVectorDtype(5);
            Path file = tmp.resolve("invalid.vortex");
            try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var writer = VortexWriter.create(ch, schema(invalid), WriteOptions.defaults())) {
                writer.writeChunk(Map.of(ID, new long[]{0L}, EMBEDDING, new FixedSizeListData(new float[]{1f, 2f}, 1)));
            }

            // When / Then — Rust validates an extension dtype when it deserializes it
            assertThatThrownBy(() -> VortexReader.open(file, READ_REGISTRY).close())
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("example.vector metadata dimension 5 != storage size 2");
        }

        @Test
        void writeChunk_nullInNonNullableColumn_throws(@TempDir Path tmp) throws IOException {
            // Given
            Path file = tmp.resolve("nonnull.vortex");
            try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var sut = VortexWriter.create(ch, schema(vectorDtype(false)), WriteOptions.defaults(), WRITE_REGISTRY)) {
                // When / Then
                assertThatThrownBy(() -> sut.writeChunk(Map.of(ID, new long[]{0L},
                        EMBEDDING, Collections.singletonList((float[]) null))))
                        .isInstanceOf(VortexException.class)
                        .hasMessageContaining("null element in non-nullable example.vector column");
            }
        }
    }

    // ── the custom extension type ────────────────────────────────────────────

    /// The `example.vector` metadata: the vector dimension, also the storage's fixed size. Its wire
    /// form is a u32 LE, written by [VectorEncoder] and parsed back by [VectorDecoder].
    ///
    /// @param dimension the number of elements per vector
    record VectorMetadata(int dimension) implements ExtensionMetadata {
    }

    /// The `example.vector` dtype: `FixedSizeList<F32, DIM>` storage, dimension also in metadata.
    static DType.Extension vectorDtype(boolean nullable) {
        return new DType.Extension(VECTOR.id(), new DType.FixedSizeList(DTypes.F32, DIM, nullable),
                new VectorMetadata(DIM), nullable);
    }

    /// The dtype as a writer without the encoder writes it, over 2-element vectors: metadata as the
    /// bytes a file stores, here declaring `metadataDim` dimensions.
    static DType.Extension opaqueVectorDtype(int metadataDim) {
        return new DType.Extension(VECTOR.id(), new DType.FixedSizeList(DTypes.F32, 2, false),
                new OpaqueMetadata(dimensionBytes(metadataDim)), false);
    }

    static MemorySegment dimensionBytes(int dim) {
        MemorySegment metadata = MemorySegment.ofArray(new byte[4]);
        metadata.set(ValueLayout.JAVA_INT_UNALIGNED.withOrder(java.nio.ByteOrder.LITTLE_ENDIAN), 0, dim);
        return metadata;
    }

    /// Shared by encoder and decoder, as one Rust `ExtVTable::validate_dtype` serves both directions.
    static void validateVector(DType.Extension dtype) {
        // the one accepted shape; anything else falls through to the error
        if (dtype.storageDType() instanceof DType.FixedSizeList(DType.Primitive(PType ptype, boolean nullable), int size, var _)
                && ptype == PType.F32 && !nullable
                && dtype.metadata() instanceof VectorMetadata(int dim)) {
            if (dim != size) {
                throw new VortexException("example.vector metadata dimension " + dim + " != storage size " + size);
            }
            return;
        }
        throw new VortexException("example.vector must be FixedSizeList<non-null F32> storage with a VectorMetadata, got "
                + dtype.storageDType() + " and " + dtype.metadata());
    }

    static final class VectorEncoder implements ExtensionEncoder {

        @Override
        public ExtensionId extensionId() {
            return VECTOR;
        }

        @Override
        public DType.Extension dtype(boolean nullable) {
            return vectorDtype(nullable);
        }

        @Override
        public void validate(DType.Extension dtype) {
            validateVector(dtype);
        }

        @Override
        public byte[] serializeMetadata(ExtensionMetadata metadata) {
            return dimensionBytes(((VectorMetadata) metadata).dimension()).toArray(ValueLayout.JAVA_BYTE);
        }

        @Override
        public Object encodeAll(DType.Extension dtype, Collection<?> values) {
            int dim = ((DType.FixedSizeList) dtype.storageDType()).fixedSize();
            int n = values.size();
            float[] flat = new float[n * dim];
            boolean[] validity = new boolean[n];
            boolean anyNull = false;
            int row = 0;
            for (Object value : values) {
                if (value == null) {
                    anyNull = true;
                } else {
                    float[] vector = (float[]) value;
                    if (vector.length != dim) {
                        throw new VortexException("row " + row + " has " + vector.length + " dimensions, not " + dim);
                    }
                    System.arraycopy(vector, 0, flat, row * dim, dim);
                    validity[row] = true;
                }
                row++;
            }
            FixedSizeListData storage = new FixedSizeListData(flat, n);
            if (!anyNull) {
                return storage;
            }
            if (!dtype.nullable()) {
                throw new VortexException("null element in non-nullable example.vector column");
            }
            return new NullableData(storage, validity);
        }
    }

    static final class VectorDecoder implements ExtensionDecoder<float[]> {

        @Override
        public ExtensionId extensionId() {
            return VECTOR;
        }

        @Override
        public DType.Extension dtype(boolean nullable) {
            return vectorDtype(nullable);
        }

        @Override
        public Class<float[]> domainType() {
            return float[].class;
        }

        @Override
        public void validate(DType.Extension dtype) {
            validateVector(dtype);
        }

        @Override
        public ExtensionMetadata parseMetadata(MemorySegment metadata) {
            if (metadata.byteSize() != 4) {
                throw new VortexException("example.vector metadata must be a u32 dimension");
            }
            return new VectorMetadata(metadata.get(ValueLayout.JAVA_INT_UNALIGNED.withOrder(java.nio.ByteOrder.LITTLE_ENDIAN), 0));
        }

        @Override
        public List<float[]> decodeAll(DType.Extension dtype, Array storage) {
            MaskedArray.Unwrapped unwrapped = MaskedArray.unwrap(storage);
            FixedSizeListArray lists = (FixedSizeListArray) unwrapped.inner();
            FloatArray elements = (FloatArray) MaskedArray.innerOrSelf(lists.elements());
            int dim = lists.fixedSize();
            int n = Math.toIntExact(lists.length());
            List<float[]> out = new ArrayList<>(n);
            for (int row = 0; row < n; row++) {
                if (unwrapped.validity() != null && !unwrapped.validity().getBoolean(row)) {
                    out.add(null);
                    continue;
                }
                float[] vector = new float[dim];
                for (int k = 0; k < dim; k++) {
                    vector[k] = elements.getFloat((long) row * dim + k);
                }
                out.add(vector);
            }
            return out;
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static DType.Struct schema(DType.Extension vectorDtype) {
        return new DType.Struct(List.of(ID, EMBEDDING), List.of(DTypes.I64, vectorDtype), false);
    }

    /// Every seventh row is null in a nullable column; values differ per row and per dimension, with
    /// negatives, so a slice off by one row or one element never reads back equal.
    private static float[] vector(long row) {
        if (row % 7 == 3) {
            return null;
        }
        float[] vector = new float[DIM];
        for (int k = 0; k < DIM; k++) {
            vector[k] = (k % 2 == 0 ? 1 : -1) * (row + k * 0.25f);
        }
        return vector;
    }

    private void writeVectors(Path file, DType.Extension vectorDtype) throws IOException {
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var writer = VortexWriter.create(ch, schema(vectorDtype), WriteOptions.defaults(), WRITE_REGISTRY)) {
            for (int chunk = 0; chunk < CHUNKS; chunk++) {
                long[] ids = new long[CHUNK_ROWS];
                List<float[]> vectors = new ArrayList<>(CHUNK_ROWS);
                for (int i = 0; i < CHUNK_ROWS; i++) {
                    long row = (long) chunk * CHUNK_ROWS + i;
                    ids[i] = row;
                    float[] vector = vector(row);
                    // a non-nullable column has no null rows: give them a zero vector
                    vectors.add(vector == null && !vectorDtype.nullable() ? new float[DIM] : vector);
                }
                writer.writeChunk(Map.of(ID, ids, EMBEDDING, vectors));
            }
        }
    }

    /// Reads `id → vector` in scan order; the id column is read alongside so a scan that misaligns
    /// the vector column with its sibling is caught.
    private SequencedMap<Long, float[]> readVectors(Path file, ScanOptions options) throws IOException {
        SequencedMap<Long, float[]> out = new LinkedHashMap<>();
        try (VortexReader reader = VortexReader.open(file, READ_REGISTRY);
             var iter = reader.scan(options)) {
            while (iter.hasNext()) {
                try (Chunk chunk = iter.next()) {
                    List<float[]> vectors = chunk.as(EMBEDDING, float[].class);
                    LongArray ids = chunk.column(ID);
                    for (int i = 0; i < vectors.size(); i++) {
                        out.put(ids.getLong(i), vectors.get(i));
                    }
                }
            }
        }
        return out;
    }

    private static List<Long> ids(long fromInclusive, long toExclusive) {
        return LongStream.range(fromInclusive, toExclusive).boxed().toList();
    }

    private static void assertEachVectorMatchesItsId(Map<Long, float[]> rows) {
        rows.forEach((id, vector) -> assertThat(vector).as("row %d", id).isEqualTo(vector(id)));
    }
}
