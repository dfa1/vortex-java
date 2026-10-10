package io.github.dfa1.vortex.core.simd;

import io.github.dfa1.vortex.core.model.PType;
import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.IntVector;

import java.lang.foreign.MemorySegment;
import java.util.OptionalLong;

/// The [SimdOperations] written with the incubating Vector API, for the kernels where an explicit
/// vector loop is worth having over C2's auto-vectorization. Every other kernel delegates to the
/// [AutoVectorizedSimdOperations] it is built on, so each kernel is switched on independently,
/// by what the benchmarks show.
///
/// Only [SimdOperationsSupport] instantiates it, and only when `jdk.incubator.vector` is on the
/// module graph (`--add-modules jdk.incubator.vector`); without it this class fails to link and the
/// auto-vectorized implementation stays in effect. The contract is identical results for every
/// input, enforced by a differential test against the auto-vectorized implementation.
///
/// The shape follows Hardwood's `VectorOperations` (Apache-2.0, hardwood-hq/hardwood): the
/// preferred species of the CPU, a main loop over whole vectors, and a scalar tail for the rest.
final class VectorApiSimdOperations implements SimdOperations {

    private final SimdOperations fallback;

    VectorApiSimdOperations(SimdOperations fallback) {
        this.fallback = fallback;
    }

    /// Whether the Vector API is worth using on this CPU: a preferred vector of at least 128 bits.
    /// Anything narrower cannot beat the scalar loops.
    ///
    /// @return `true` if the preferred species holds at least four ints and sixteen bytes
    static boolean isUsable() {
        return IntVector.SPECIES_PREFERRED.length() >= 4 && ByteVector.SPECIES_PREFERRED.length() >= 16;
    }

    /// The width of the vectors in use, for diagnostics.
    ///
    /// @return the preferred vector size in bits
    static int vectorBitSize() {
        return IntVector.SPECIES_PREFERRED.vectorBitSize();
    }

    @Override
    public void widenInto(MemorySegment src, long fromElement, int count, PType ptype, long[] out) {
        fallback.widenInto(src, fromElement, count, ptype, out);
    }

    @Override
    public void widenArrayInto(Object values, int from, int count, PType ptype, long[] out) {
        fallback.widenArrayInto(values, from, count, ptype, out);
    }

    @Override
    public void narrowInto(long[] values, PType ptype, MemorySegment dst) {
        fallback.narrowInto(values, ptype, dst);
    }

    @Override
    public void narrowArrayInto(long[] values, PType ptype, Object out) {
        fallback.narrowArrayInto(values, ptype, out);
    }

    @Override
    public long maxUnsigned(MemorySegment src, long count, PType ptype) {
        return fallback.maxUnsigned(src, count, ptype);
    }

    @Override
    public long[] minMax(Object values, PType ptype) {
        return fallback.minMax(values, ptype);
    }

    @Override
    public boolean allEqual(Object values, PType ptype) {
        return fallback.allEqual(values, ptype);
    }

    @Override
    public boolean allEqual(boolean[] values) {
        return fallback.allEqual(values);
    }

    @Override
    public long runs(Object values, PType ptype) {
        return fallback.runs(values, ptype);
    }

    @Override
    public OptionalLong sum(Object values, PType ptype) {
        return fallback.sum(values, ptype);
    }

    @Override
    public double sumFloating(Object values, PType ptype) {
        return fallback.sumFloating(values, ptype);
    }

    @Override
    public void undeltaChunk(long[] deltas, long[] bases, int lanes, int typeBits, long mask, long[] out) {
        fallback.undeltaChunk(deltas, bases, lanes, typeBits, mask, out);
    }

    @Override
    public void deltaChunk(long[] values, long[] bases, int lanes, int typeBits, long mask, long[] out) {
        fallback.deltaChunk(values, bases, lanes, typeBits, mask, out);
    }

    @Override
    public void packBlock(long[] values, int offset, int bitWidth, int typeBits, long[] words) {
        fallback.packBlock(values, offset, bitWidth, typeBits, words);
    }
}
