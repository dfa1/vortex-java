package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PcoEncodingEncoderTest {

    @Test
    void encode_throwsVortexException() {
        var sut = new PcoEncodingEncoder();
        DType dtype = new DType.Primitive(PType.I64, false);

        assertThatThrownBy(() -> sut.encode(dtype, new long[]{1L, 2L, 3L}, EncodeTestHelper.testCtx()))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("not implemented");
    }
}
