package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;

import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleConsumer;

/// Lazy ALP-RD-encoded {@link DoubleArray}.
///
/// ALP-RD splits each f64 into a "left" u16 dictionary code and a "right" u64 payload
/// (the low `rightBitWidth` bits of the original bit pattern). Reconstruction is
/// `Double.longBitsToDouble((dict[left[i]] << rightBitWidth) | right[i])`. A small
/// patches table overrides the dict lookup at specific absolute indices for outlier
/// values that don't compress through the dictionary.
///
/// {@code getDouble(i)} resolves on demand: read left/right children, dispatch through
/// dict (or patch if `i + patchOffset` hits a patch index), shift+or, then bits-to-double.
///
/// @param dtype            logical element type
/// @param length           total logical row count
/// @param dict             small u16 dictionary lookup table (length typically ≤ 16)
/// @param rightBitWidth    bit width of the right payload
/// @param leftArr          per-row u16 dict codes
/// @param rightArr         per-row u64 right payloads (raw bits)
/// @param patchIndices     sorted absolute indices of patched rows (or {@code null} when no patches)
/// @param patchLeftValues  actual left u16 values for patched rows; aligned with
///                         {@code patchIndices}
/// @param patchOffset      absolute origin subtracted from row positions before patch lookup
public record LazyAlpRdDoubleArray(
        DType dtype, long length,
        short[] dict, int rightBitWidth,
        ShortArray leftArr, LongArray rightArr,
        Array patchIndices, short[] patchLeftValues, long patchOffset)
        implements DoubleArray {

    @Override
    public double getDouble(long i) {
        int leftU16 = lookupLeft(i);
        long leftBits = ((long) (leftU16 & 0xFFFF)) << rightBitWidth;
        long rightBits = rightArr.getLong(i);
        return Double.longBitsToDouble(leftBits | rightBits);
    }

    @Override
    public void forEachDouble(DoubleConsumer c) {
        long n = length;
        for (long i = 0; i < n; i++) {
            c.accept(getDouble(i));
        }
    }

    @Override
    public double fold(double identity, DoubleBinaryOperator op) {
        double acc = identity;
        long n = length;
        for (long i = 0; i < n; i++) {
            acc = op.applyAsDouble(acc, getDouble(i));
        }
        return acc;
    }

    private int lookupLeft(long i) {
        if (patchIndices != null) {
            int p = SparseArrays.findPatch(patchIndices, patchLeftValues.length, i + patchOffset);
            if (p >= 0) {
                return patchLeftValues[p];
            }
        }
        short code = leftArr.getShort(i);
        return dict[Short.toUnsignedInt(code)];
    }
}
