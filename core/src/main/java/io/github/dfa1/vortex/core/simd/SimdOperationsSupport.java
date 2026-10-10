package io.github.dfa1.vortex.core.simd;

/// Selects the [SimdOperations] implementation, once per JVM.
///
/// The Vector API implementation is chosen only when `jdk.incubator.vector` is on the module graph
/// (`--add-modules jdk.incubator.vector`) and the CPU has vectors of at least 128 bits; otherwise the
/// auto-vectorized implementation stays in effect. That launch flag is the opt-in, and omitting it
/// is the opt-out, as in Hardwood: ADR 0005 rules out a per-scan flag.
public final class SimdOperationsSupport {

    private static final System.Logger LOG = System.getLogger(SimdOperationsSupport.class.getName());

    private static final SimdOperations INSTANCE = select();

    private SimdOperationsSupport() {
    }

    /// Returns the kernel implementation in effect for this JVM.
    ///
    /// @return the shared, stateless [SimdOperations]
    public static SimdOperations preferred() {
        return INSTANCE;
    }

    private static SimdOperations select() {
        SimdOperations autoVectorized = new AutoVectorizedSimdOperations();
        if (ModuleLayer.boot().findModule("jdk.incubator.vector").isEmpty()) {
            LOG.log(System.Logger.Level.DEBUG, "SIMD: auto-vectorized (jdk.incubator.vector not on the module graph)");
            return autoVectorized;
        }
        try {
            if (!VectorApiSimdOperations.isUsable()) {
                LOG.log(System.Logger.Level.DEBUG, "SIMD: auto-vectorized (preferred vectors narrower than 128 bits)");
                return autoVectorized;
            }
            SimdOperations vectorApi = new VectorApiSimdOperations();
            LOG.log(System.Logger.Level.DEBUG, "SIMD: Vector API, {0}-bit vectors", VectorApiSimdOperations.vectorBitSize());
            return vectorApi;
        } catch (LinkageError e) {
            // The module is present but the Vector API could not be linked or initialized
            LOG.log(System.Logger.Level.DEBUG, "SIMD: auto-vectorized (Vector API unavailable: {0})", e.toString());
            return autoVectorized;
        }
    }
}
