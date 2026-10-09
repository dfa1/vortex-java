package io.github.dfa1.vortex.core.simd;

/// Selects the [SimdOperations] implementation, once per JVM.
///
/// Only the scalar kernels exist today. The Vector API implementation (ADR 0005, #483, #484) will be
/// chosen here by a guarded static initializer that falls back to scalar when
/// `jdk.incubator.vector` is absent, so no call site changes when it lands.
public final class VectorSupport {

    private static final SimdOperations INSTANCE = new ScalarOperations();

    private VectorSupport() {
    }

    /// Returns the kernel implementation in effect for this JVM.
    ///
    /// @return the shared, stateless [SimdOperations]
    public static SimdOperations operations() {
        return INSTANCE;
    }
}
