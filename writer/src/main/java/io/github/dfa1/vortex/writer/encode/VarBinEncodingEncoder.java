package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.compute.Utf8Order;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.proto.ProtoScalarValue;
import io.github.dfa1.vortex.core.proto.ProtoVarBinMetadata;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/// Write-only encoder for `vortex.varbin`.
public final class VarBinEncodingEncoder implements EncodingEncoder {

    /// Creates the encoder.
    public VarBinEncodingEncoder() {
    }

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_VARBIN;
    }

    @Override
    public boolean accepts(DType dtype) {
        return dtype instanceof DType.Utf8 || dtype instanceof DType.Binary;
    }

    @Override
    public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
        // Binary arrives as raw byte[][] and round-trips byte-for-byte; Utf8 arrives as String[]
        // (UTF-8 encoded here) or as its UTF-8 bytes. Either way a null entry (this encoder is the
        // values child of a masked/nullable layout, where validity carries nullity) contributes a
        // zero-length slot.
        VarBinBytes.Rows rows = VarBinBytes.toContiguous(data);
        if (rows == null) {
            throw new VortexException(EncodingId.VORTEX_VARBIN, "chunk exceeds 2 GB of row bytes");
        }
        int n = rows.count();
        int totalBytes = rows.offsets()[n];

        Arena arena = ctx.arena();
        MemorySegment bytesBuf = arena.allocate(totalBytes > 0 ? totalBytes : 1);
        MemorySegment.copy(rows.bytes(), 0, bytesBuf, ValueLayout.JAVA_BYTE, 0, totalBytes);
        MemorySegment offsetsBuf = arena.allocate((long) (n + 1) * Long.BYTES, Long.BYTES);
        for (int i = 0; i <= n; i++) {
            offsetsBuf.setAtIndex(VortexFormat.LE_LONG, i, rows.offsets()[i]);
        }

        byte[] metaBytes = new ProtoVarBinMetadata(io.github.dfa1.vortex.core.proto.ProtoPType.fromValue(PType.I64.ordinal())).encode();

        MinMax stats = textMinMax(dtype, data, rows);
        ProtoScalarValue statsMin = stats != null ? stats.min() : null;
        ProtoScalarValue statsMax = stats != null ? stats.max() : null;

        EncodeNode offsetsNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 1);
        EncodeNode root = new EncodeNode(EncodingId.VORTEX_VARBIN, MemorySegment.ofArray(metaBytes),
                new EncodeNode[]{offsetsNode}, new int[]{0});
        return new EncodeResult(root, List.of(EncodedBuffer.bytes(bytesBuf), EncodedBuffer.of(offsetsBuf, PType.I64)), statsMin, statsMax);
    }

    /// Computes the serialized min/max string [ProtoScalarValue] pair for a string array, skipping
    /// `null` entries (UTF-8 byte order, see [Utf8Order]). Returns `null` when every entry is
    /// `null`. Shared so the dictionary zone-map path computes per-chunk string min/max identically
    /// to the flat path.
    ///
    /// @param strings the string values (may contain `null`)
    /// @return the [MinMax] of encoded scalars, or `null` if all entries are `null`
    public static MinMax minMaxStats(String[] strings) {
        String minStr = null;
        String maxStr = null;
        for (String s : strings) {
            if (s == null) {
                continue;
            }
            if (minStr == null || Utf8Order.compare(s, minStr) < 0) {
                minStr = s;
            }
            if (maxStr == null || Utf8Order.compare(s, maxStr) > 0) {
                maxStr = s;
            }
        }
        return encodeMinMax(minStr, maxStr);
    }

    /// [#minMaxStats(String[])] over the rows' UTF-8 bytes, for an encoder that already holds them.
    ///
    /// [Arrays#compareUnsigned] orders UTF-8 bytes exactly as [Utf8Order] orders the strings, and it
    /// is vectorized: on rows sharing a long prefix (ids, URLs) it was 4 to 7 times faster, where the
    /// `String` compare was 14% of a write of such columns.
    ///
    /// @param strings the string values (may contain `null`)
    /// @param rows    `strings` as [VarBinBytes#toContiguous(Object)] lays them out
    /// @return the [MinMax] of encoded scalars, or `null` if all entries are `null`
    static MinMax minMaxStats(String[] strings, VarBinBytes.Rows rows) {
        byte[] bytes = rows.bytes();
        int[] offsets = rows.offsets();
        int min = -1;
        int max = -1;
        for (int i = 0; i < strings.length; i++) {
            // a null is an empty row in `rows`, so skip it by the string
            if (strings[i] == null) {
                continue;
            }
            if (min < 0 || Arrays.compareUnsigned(bytes, offsets[i], offsets[i + 1],
                    bytes, offsets[min], offsets[min + 1]) < 0) {
                min = i;
            }
            if (max < 0 || Arrays.compareUnsigned(bytes, offsets[i], offsets[i + 1],
                    bytes, offsets[max], offsets[max + 1]) > 0) {
                max = i;
            }
        }
        return min < 0 ? null : encodeMinMax(strings[min], strings[max]);
    }

    /// The zone-map min/max of a string column, or `null` for binary values or when every value is
    /// `null`. A `String[]` is always text; a `byte[][]` is text (UTF-8) when `dtype`, or an
    /// extension's storage, is Utf8. Binary has no useful ordering: a zone map over arbitrary blobs
    /// prunes nothing.
    ///
    /// @param dtype  the values' dtype
    /// @param values a `String[]` or `byte[][]`, `null` at a null row
    /// @return the [MinMax] of encoded string scalars, or `null`
    public static MinMax textMinMax(DType dtype, Object values) {
        if (values instanceof String[] strings) {
            return minMaxStats(strings);
        }
        if (values instanceof byte[][] utf8 && isUtf8(dtype)) {
            return minMaxStats(utf8);
        }
        return null;
    }

    /// [#textMinMax(DType, Object)] reusing the contiguous rows an encoder already built.
    ///
    /// @param dtype  the values' dtype
    /// @param values a `String[]` or `byte[][]`, `null` at a null row
    /// @param rows   `values` as [VarBinBytes#toContiguous(Object)] lays them out, or `null`
    /// @return the [MinMax] of encoded string scalars, or `null`
    static MinMax textMinMax(DType dtype, Object values, VarBinBytes.Rows rows) {
        if (values instanceof String[] strings && rows != null) {
            return minMaxStats(strings, rows);
        }
        return textMinMax(dtype, values);
    }

    /// Whether `dtype` holds UTF-8 text, directly or as an extension's storage.
    ///
    /// @param dtype the dtype, or `null`
    /// @return `true` for Utf8 and Utf8-backed extensions
    static boolean isUtf8(DType dtype) {
        return dtype instanceof DType.Utf8
                || (dtype instanceof DType.Extension ext && ext.storageDType() instanceof DType.Utf8);
    }

    /// [#minMaxStats(String[])] over UTF-8 rows, comparing their bytes: [Arrays#compareUnsigned]
    /// orders UTF-8 exactly as [Utf8Order] orders the strings. Only the two winners are decoded.
    ///
    /// @param utf8 the rows' UTF-8 bytes, `null` at a null row
    /// @return the [MinMax] of encoded scalars, or `null` if all entries are `null`
    static MinMax minMaxStats(byte[][] utf8) {
        int[] rows = extremeRows(utf8);
        if (rows == null) {
            return null;
        }
        return encodeMinMax(new String(utf8[rows[0]], StandardCharsets.UTF_8),
                new String(utf8[rows[1]], StandardCharsets.UTF_8));
    }

    /// [#minMaxStats(String[])] over each string's UTF-8 bytes, for an encoder that already holds
    /// them one array per row; see [#minMaxStats(String[], VarBinBytes.Rows)] for why bytes.
    ///
    /// @param strings the string values (may contain `null`)
    /// @param utf8    `strings` encoded row for row, `null` where `strings` is
    /// @return the [MinMax] of encoded scalars, or `null` if all entries are `null`
    static MinMax minMaxStats(String[] strings, byte[][] utf8) {
        int[] rows = extremeRows(utf8);
        if (rows == null) {
            return null;
        }
        return encodeMinMax(strings[rows[0]], strings[rows[1]]);
    }

    /// The rows holding the smallest and largest UTF-8 bytes, compared unsigned (Rust's string
    /// order), or `null` when every row is `null`.
    private static int[] extremeRows(byte[][] utf8) {
        int min = -1;
        int max = -1;
        for (int i = 0; i < utf8.length; i++) {
            if (utf8[i] == null) {
                continue;
            }
            if (min < 0 || Arrays.compareUnsigned(utf8[i], utf8[min]) < 0) {
                min = i;
            }
            if (max < 0 || Arrays.compareUnsigned(utf8[i], utf8[max]) > 0) {
                max = i;
            }
        }
        if (min < 0) {
            return null;
        }
        return new int[]{min, max};
    }

    /// Encodes an already-computed string min/max as [#minMaxStats(String[])] would.
    ///
    /// @param minStr the minimum in UTF-8 order, or `null` when there is no non-null value
    /// @param maxStr the maximum in UTF-8 order, or `null` when there is no non-null value
    /// @return the [MinMax] of encoded scalars, or `null` if `minStr` is `null`
    public static MinMax encodeMinMax(String minStr, String maxStr) {
        if (minStr == null) {
            return null;
        }
        return new MinMax(ProtoScalarValue.ofStringValue(minStr), ProtoScalarValue.ofStringValue(maxStr));
    }
}
