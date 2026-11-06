package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.proto.ProtoScalarValue;

import java.util.Arrays;

/// The stats Rust leaves on the root node of a flat segment, by the column's dtype and the encoding
/// the compressor chose.
///
/// Rust's flat layout does not compute stats; it keeps what the compressor had cached on the array
/// (`flat/writer.rs`). The compressor computes, on the canonical array it is about to compress, the
/// integer min and max, the null count, the exact uncompressed size (`compressor/cascade.rs`) and
/// whether the array is constant. A scheme's output keeps those only when the scheme passes them
/// on: a canonical primitive or bool does, as does `fastlanes.bitpacked`
/// (`bitpacking/array/bitpack_compress`); `vortex.alp`, `vortex.alprd`, `vortex.fsst`,
/// `vortex.onpair`, `vortex.constant` and the rest do not, so their roots carry nothing.
public final class RootNodeStats {

    private RootNodeStats() {
    }

    /// The stats the root node of a segment encoded as `root` carries.
    ///
    /// @param dtype     the column's dtype
    /// @param root      the root encoding the compressor chose
    /// @param min       the exact minimum of the input, or `null` when unknown
    /// @param max       the exact maximum of the input, or `null` when unknown
    /// @param rows      the number of rows
    /// @param nullCount the number of null rows
    /// @return the root's stats, [NodeStats#NONE] when the encoding passes none on
    public static NodeStats of(DType dtype, EncodingId root, ProtoScalarValue min, ProtoScalarValue max, long rows,
                        long nullCount) {
        return switch (dtype) {
            case DType.Primitive p when p.ptype().isFloating() && root.equals(EncodingId.VORTEX_PRIMITIVE) ->
                    new NodeStats(null, null, notConstant(nullCount), null, null, nullCount, canonicalSize(p, rows, nullCount), null);
            case DType.Primitive p when !p.ptype().isFloating() && retainsIntegerStats(root) ->
                    new NodeStats(min, max, isConstant(min, max, nullCount), null, null, nullCount,
                            canonicalSize(p, rows, nullCount), null);
            case DType.Bool _ when root.equals(EncodingId.VORTEX_BOOL) ->
                    new NodeStats(null, null, false, null, null, null, (rows + 7) / 8, null);
            default -> NodeStats.NONE;
        };
    }

    /// The stats of the `vortex.bool` array that holds a nullable array's validity. Rust builds it
    /// from the mask: an array of one value is constant, a mixed one carries both bounds, its
    /// valid-row count as the sum and no nulls.
    ///
    /// @param validity per-row validity
    /// @return the validity child's stats
    public static NodeStats ofValidity(boolean[] validity) {
        long valid = 0;
        for (boolean v : validity) {
            if (v) {
                valid++;
            }
        }
        if (valid == 0 || valid == validity.length) {
            return new NodeStats(null, null, true, null, null, null, null, null);
        }
        return new NodeStats(ProtoScalarValue.ofBoolValue(false), ProtoScalarValue.ofBoolValue(true), null, null, null,
                0L, null, null, ProtoScalarValue.ofUint64Value(valid));
    }

    private static boolean retainsIntegerStats(EncodingId root) {
        return root.equals(EncodingId.VORTEX_PRIMITIVE) || root.equals(EncodingId.FASTLANES_BITPACKED);
    }

    /// Rust records `is_constant` only for an array without nulls: its constant check does not run
    /// on one with invalid rows, so the stat stays absent.
    private static Boolean isConstant(ProtoScalarValue min, ProtoScalarValue max, long nullCount) {
        if (nullCount > 0) {
            return null;
        }
        return min != null && max != null && Arrays.equals(min.encode(), max.encode());
    }

    private static Boolean notConstant(long nullCount) {
        if (nullCount > 0) {
            return null;
        }
        return false;
    }

    /// Rust's `exact_uncompressed_size` of a primitive: the values, plus a packed bitmap when some row is null.
    private static long canonicalSize(DType.Primitive dtype, long rows, long nullCount) {
        long size = rows * dtype.ptype().byteSize();
        if (nullCount > 0) {
            size += (rows + 7) / 8;
        }
        return size;
    }
}
