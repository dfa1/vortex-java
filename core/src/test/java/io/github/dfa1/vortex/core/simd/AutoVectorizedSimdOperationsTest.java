package io.github.dfa1.vortex.core.simd;

class AutoVectorizedSimdOperationsTest extends SimdOperationsTest {

    @Override
    protected SimdOperations createSut() {
        return new AutoVectorizedSimdOperations();
    }
}
