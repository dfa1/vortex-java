package io.github.dfa1.vortex.core.simd;

/// Selects the [SimdOperations] implementation, once per JVM.
///
/// Only the auto-vectorized kernels exist today. The Vector API implementation
/// (ADR 0005, #483, #484) will be chosen here by a guarded static initializer that falls back to them when
/// `jdk.incubator.vector` is absent, so no call site changes when it lands.
public final class SimdOperationsSupport {

    private static final SimdOperations INSTANCE = new AutoVectorizedSimdOperations();

    private SimdOperationsSupport() {
    }

    /// Returns the kernel implementation in effect for this JVM.
    ///
    /// @return the shared, stateless [SimdOperations]
    public static SimdOperations preferred() {
        return INSTANCE;
    }
}
