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
        // Binary (DType.Binary) arrives as raw byte[][] and must round-trip byte-for-byte —
        // routing it through the Utf8 String[] path would corrupt any byte sequence that isn't
        // valid UTF-8 (e.g. an embedded audio/image blob). Utf8 arrives as String[] and is UTF-8
        // encoded. Either way a null entry (this encoder is the values child of a masked/nullable
        // layout, where validity carries nullity) contributes a zero-length slot.
        String[] strings = data instanceof String[] s ? s : null;
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

        // Zone-map min/max stats are lexicographic-string-only (minMaxStats(String[])); binary
        // blobs (e.g. audio bytes) aren't usefully zone-mapped, so skip stats for that path.
        MinMax stats = strings != null ? minMaxStats(strings, rows) : null;
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
