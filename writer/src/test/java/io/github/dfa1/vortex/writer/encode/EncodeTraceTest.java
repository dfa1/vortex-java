package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.EncodingId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EncodeTraceTest {

    @Test
    void root_hasEmptyPathAndDepthZero() {
        // Given
        var sut = EncodeTrace.of("id", 3);

        // When / Then
        assertThat(sut.pathString()).isEmpty();
        assertThat(sut.parentString()).isEmpty();
        assertThat(sut.depth()).isZero();
        assertThat(sut.column()).isEqualTo("id");
        assertThat(sut.segment()).isEqualTo(3);
    }

    @Test
    void child_extendsThePathRootFirstWithoutTheVortexPrefix() {
        // Given
        var root = EncodeTrace.of("ts", 0);

        // When
        var sut = root.child(EncodingId.parse("vortex.datetimeparts"), 0)
                .child(EncodingId.parse("fastlanes.rle"), 1);

        // Then
        assertThat(sut.pathString()).isEqualTo("datetimeparts.0/fastlanes.rle.1");
        assertThat(sut.parentString()).isEqualTo("fastlanes.rle");
        assertThat(sut.depth()).isEqualTo(2);
        assertThat(root.depth()).isZero();
    }
}
