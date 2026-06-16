package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;

import java.util.function.DoubleBinaryOperator;

/// Lazy ALP-RD-encoded [FloatArray]. See [LazyAlpRdDoubleArray] for the
/// dict + right + patches reconstruction model. The right payload is u32 instead of
/// u64 and the result is reinterpreted via [Float#intBitsToFloat].
///
/// @param dtype            logical element type
/// @param length           total logical row count
/// @param dict             small u16 dictionary lookup table
/// @param rightBitWidth    bit width of the right payload
/// @param leftArr          per-row u16 dict codes
/// @param rightArr         per-row u32 right payloads (raw bits)
/// @param patchIndices     sorted absolute indices of patched rows (or `null`)
/// @param patchLeftValues  actual left u16 values for patched rows
/// @param patchOffset      absolute origin subtracted from row positions before patch lookup
@SuppressWarnings("java:S6218") // internal data carrier; record components are arrays of immutable primitives or refs that flow through pipelines without ever being compared.
public record LazyAlpRdFloatArray(
        DType dtype, long length,
        short[] dict, int rightBitWidth,
        ShortArray leftArr, IntArray rightArr,
        Array patchIndices, short[] patchLeftValues, long patchOffset)
        implements FloatArray {

    @Override
    public float getFloat(long i) {
        int leftU16 = lookupLeft(i);
        int leftBits = (leftU16 & 0xFFFF) << rightBitWidth;
        int rightBits = rightArr.getInt(i);
        return Float.intBitsToFloat(leftBits | rightBits);
    }

    @Override
    public double fold(double identity, DoubleBinaryOperator op) {
        double acc = identity;
        long n = length;
        for (long i = 0; i < n; i++) {
            acc = op.applyAsDouble(acc, getFloat(i));
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
