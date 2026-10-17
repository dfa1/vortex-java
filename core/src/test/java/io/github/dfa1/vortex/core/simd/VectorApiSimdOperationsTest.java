package io.github.dfa1.vortex.core.simd;

import io.github.dfa1.vortex.core.model.PType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// The Vector API implementation: the shared [SimdOperationsTest] contract, plus the Vector-API-specific
/// cases where a lane-wise shortcut would diverge from Rust.
class VectorApiSimdOperationsTest extends SimdOperationsTest {

    private final SimdOperations reference = new AutoVectorizedSimdOperations();

    @Override
    protected SimdOperations createSut() {
        assumeTrue(ModuleLayer.boot().findModule("jdk.incubator.vector").isPresent(), "run with the module");
        assumeTrue(VectorApiSimdOperations.isUsable(), "CPU vectors narrower than 128 bits");
        return new VectorApiSimdOperations();
    }

    @Test
    void sum_signed64_oppositeHugeValuesInOneLane_matchTheSequentialReference() {
        // Given alternating huge values of opposite sign. Every vector length is even, so every positive
        // value lands in the same lane: a lane-wise overflow check would drop the sum although no
        // sequential prefix overflows (Rust: 0). Catches a regression to per-lane detection
        long[] values = new long[64];
        for (int i = 0; i < values.length; i++) {
            values[i] = i % 2 == 0 ? Long.MAX_VALUE / 2 : -(Long.MAX_VALUE / 2);
        }

        // When
        var result = sut.sum(values, PType.I64);

        // Then
        assertThat(result).isEqualTo(reference.sum(values, PType.I64)).hasValue(0L);
    }
}
