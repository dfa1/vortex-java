package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.encoding.PTypeIO;

import java.lang.foreign.MemorySegment;
import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleConsumer;

/// Lazy [DoubleArray] backed by an ALP-encoded {@link MemorySegment} of
/// {@code int64} values and a scale factor.
///
/// {@code value(i) = (double) encoded[i] * scale}. Patches are not supported by
/// this impl; the ALP decoder must fall back to [MaterializedDoubleArray] when
/// the encoded chunk has patches.
public final class AlpDoubleArray implements DoubleArray {

    private final DType dtype;
    private final long length;
    private final MemorySegment encoded;
    private final double scale;

    /// Constructs a lazy ALP-encoded double array.
    ///
    /// @param dtype   F64 primitive dtype
    /// @param length  number of logical rows
    /// @param encoded encoded {@code int64} source segment ({@code length * 8} bytes)
    /// @param scale   pre-computed {@code 10^f * 10^-e} factor
    public AlpDoubleArray(DType dtype, long length, MemorySegment encoded, double scale) {
        this.dtype = dtype;
        this.length = length;
        this.encoded = encoded;
        this.scale = scale;
    }

    @Override
    public DType dtype() {
        return dtype;
    }

    @Override
    public long length() {
        return length;
    }

    /// Returns the encoded source segment.
    ///
    /// @return the {@code int64} segment of encoded ALP values
    public MemorySegment encoded() {
        return encoded;
    }

    /// Returns the ALP scale factor.
    ///
    /// @return {@code 10^f * 10^-e}
    public double scale() {
        return scale;
    }

    @Override
    public double getDouble(long i) {
        return (double) encoded.getAtIndex(PTypeIO.LE_LONG, i) * scale;
    }

    @Override
    public void forEachDouble(DoubleConsumer c) {
        MemorySegment src = encoded;
        double s = scale;
        long n = length;
        for (long i = 0; i < n; i++) {
            c.accept((double) src.getAtIndex(PTypeIO.LE_LONG, i) * s);
        }
    }

    @Override
    public double fold(double identity, DoubleBinaryOperator op) {
        MemorySegment src = encoded;
        double s = scale;
        long n = length;
        double result = identity;
        for (long i = 0; i < n; i++) {
            result = op.applyAsDouble(result, (double) src.getAtIndex(PTypeIO.LE_LONG, i) * s);
        }
        return result;
    }

    /// Materialises the lazy array into a flat double buffer.
    ///
    /// Allocates {@code length * 8} bytes from {@link java.lang.foreign.Arena#ofAuto()}
    /// and writes the decoded values. Intended for fallback paths such as
    /// {@link ArraySegments#of(Array)} that need a primary segment of the
    /// logical dtype; not used on the hot scan path.
    ///
    /// @return a fresh {@link MemorySegment} of decoded doubles
    public MemorySegment materialize() {
        MemorySegment dst = java.lang.foreign.Arena.ofAuto().allocate(length * 8, 8);
        MemorySegment src = encoded;
        double s = scale;
        long n = length;
        for (long i = 0; i < n; i++) {
            dst.setAtIndex(PTypeIO.LE_DOUBLE, i, (double) src.getAtIndex(PTypeIO.LE_LONG, i) * s);
        }
        return dst;
    }

    /// Sum of all values strictly greater than {@code threshold}, computed
    /// without materialising rejected rows.
    ///
    /// Encodes {@code threshold} into the ALP integer domain
    /// ({@code enc = floor(threshold / scale)}) and scans the encoded longs.
    /// Decodes ({@code (double) long * scale}) only the rows whose encoded
    /// value exceeds {@code enc}; a boundary re-check in the double domain
    /// fires only for values whose encoded form rounds back to the threshold.
    ///
    /// @param threshold strict lower bound on the matching values
    /// @return sum of decoded values where {@code value > threshold}
    public double sumWhereGt(double threshold) {
        MemorySegment src = encoded;
        double s = scale;
        long n = length;
        long encLo = (long) Math.floor(threshold / s);
        double result = 0.0;
        for (long i = 0; i < n; i++) {
            long lv = src.getAtIndex(PTypeIO.LE_LONG, i);
            if (lv > encLo) {
                double v = (double) lv * s;
                if (v > threshold) {
                    result += v;
                }
            }
        }
        return result;
    }

}
