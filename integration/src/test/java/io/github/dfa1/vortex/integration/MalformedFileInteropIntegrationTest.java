package io.github.dfa1.vortex.integration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vortex.api.DataSource;
import dev.vortex.api.Partition;
import dev.vortex.api.Scan;
import dev.vortex.api.ScanOptions;
import dev.vortex.api.Session;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.ScanIterator;
import io.github.dfa1.vortex.reader.VortexReader;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowReader;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Objects;

/// Files the Jazzer `scanMalformedFile` target produced (#493) that once escaped vortex-java as a
/// raw JDK exception. Both readers must reject them: Rust is the oracle for what is malformed, and
/// vortex-java must throw only [VortexException] doing so (the reader's security contract).
class MalformedFileInteropIntegrationTest {

    static {
        NativeLoader.loadJni();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // A child-node vector offset in a segment's array FlatBuffer points past its end:
            // escaped as IndexOutOfBoundsException from the FlatBuffer runtime.
            "array-flatbuffer-vector-out-of-bounds",
            // A Struct column whose segment is fastlanes.bitpacked: escaped as ClassCastException.
            "bitpacked-struct-dtype",
            // A struct dtype with no names vector, which Rust refuses at open: vortex-java read it
            // as a 0-field struct and failed the scan with ArrayIndexOutOfBoundsException.
            "struct-dtype-missing-names",
            // A vortex.zstd_buffers whose wrapped encoding id holds a control character: escaped as
            // IllegalArgumentException from EncodingId.parse.
            "zstd-buffers-inner-id-control-char"})
    void bothReadersReject(String name) throws URISyntaxException {
        // Given
        Path file = Path.of(Objects.requireNonNull(
                getClass().getResource("/fixtures/malformed/" + name + ".vortex")).toURI());

        // When / Then
        assertThatThrownBy(() -> scanJni(file)).as("vortex-jni").isInstanceOf(Exception.class);
        assertThatThrownBy(() -> scanJava(file)).as("vortex-java").isInstanceOf(VortexException.class);
    }

    private static void scanJava(Path file) throws Exception {
        try (VortexReader reader = VortexReader.open(file);
             ScanIterator scan = reader.scan(io.github.dfa1.vortex.reader.ScanOptions.all())) {
            while (scan.hasNext()) {
                try (Chunk _ = scan.next()) {
                    // decoding the chunk is enough: every file here fails before a value is read
                }
            }
        }
    }

    private static void scanJni(Path file) throws Exception {
        Scan scan = DataSource.open(Session.create(), file.toUri().toString()).scan(ScanOptions.builder().build());
        while (scan.hasNext()) {
            Partition partition = scan.next();
            try (ArrowReader reader = partition.scanArrow(ArrowAllocation.rootAllocator())) {
                while (reader.loadNextBatch()) {
                    VectorSchemaRoot root = reader.getVectorSchemaRoot();
                    for (FieldVector vector : root.getFieldVectors()) {
                        for (int i = 0; i < root.getRowCount(); i++) {
                            vector.getObject(i);
                        }
                    }
                }
            }
        }
    }
}
