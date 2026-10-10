package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.writer.WriteRegistry;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/// #515: F16 support was patchy: constant, dict, rle, sparse and pco each lacked it, found one by
/// one. This pins the set so a new encoder cannot silently skip half-precision floats: every
/// encoder that takes an F32 column takes an F16 one, except ALP and ALP-RD, which Rust's
/// compressor declines for half floats ("We don't support ALP for f16", btrblocks `alp.rs`,
/// `alprd.rs`).
class Float16EncoderSetTest {

    @Test
    void everyEncoderThatTakesF32TakesF16_exceptAlpAndAlpRd() {
        // Given
        Set<String> f32 = accepting(DType.F32);

        // When
        Set<String> result = accepting(DType.F16);

        // Then
        Set<String> expected = new TreeSet<>(f32);
        expected.remove(EncodingId.VORTEX_ALP.toString());
        expected.remove(EncodingId.VORTEX_ALPRD.toString());
        assertThat(result).containsExactlyElementsOf(expected);
        assertThat(result).contains("vortex.constant", "vortex.dict", "fastlanes.rle", "vortex.sparse", "vortex.pco");
    }

    private static Set<String> accepting(DType dtype) {
        Set<String> ids = new TreeSet<>();
        for (EncodingEncoder encoder : WriteRegistry.loadAll().encoderMap().values()) {
            if (encoder.accepts(dtype)) {
                ids.add(encoder.encodingId().toString());
            }
        }
        return ids;
    }
}
