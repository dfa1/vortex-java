package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.proto.ProtoScalarValue;
import io.github.dfa1.vortex.core.proto.ProtoVarBinMetadata;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.List;

/// Write-only encoder for `vortex.varbin`.
public final class VarBinEncodingEncoder implements EncodingEncoder {

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
        byte[][] byteArrays = VarBinBytes.toByteArrays(data);
        int n = byteArrays.length;
        int totalBytes = 0;
        for (byte[] b : byteArrays) {
            totalBytes += b.length;
        }

        Arena arena = ctx.arena();
        MemorySegment bytesBuf = arena.allocate(totalBytes > 0 ? totalBytes : 1);
        MemorySegment offsetsBuf = arena.allocate((long) (n + 1) * Long.BYTES, Long.BYTES);

        long pos = 0;
        offsetsBuf.setAtIndex(VortexFormat.LE_LONG, 0, 0L);
        for (int i = 0; i < n; i++) {
            MemorySegment.copy(MemorySegment.ofArray(byteArrays[i]), 0, bytesBuf, pos, byteArrays[i].length);
            pos += byteArrays[i].length;
            offsetsBuf.setAtIndex(VortexFormat.LE_LONG, (long) i + 1, pos);
        }

        byte[] metaBytes = new ProtoVarBinMetadata(io.github.dfa1.vortex.core.proto.ProtoPType.fromValue(PType.I64.ordinal())).encode();

        // Zone-map min/max stats are lexicographic-string-only (minMaxStats(String[])); binary
        // blobs (e.g. audio bytes) aren't usefully zone-mapped, so skip stats for that path.
        byte[][] stats = strings != null ? minMaxStats(strings) : null;
        byte[] statsMin = stats != null ? stats[0] : null;
        byte[] statsMax = stats != null ? stats[1] : null;

        EncodeNode offsetsNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 1);
        EncodeNode root = new EncodeNode(EncodingId.VORTEX_VARBIN, MemorySegment.ofArray(metaBytes),
                new EncodeNode[]{offsetsNode}, new int[]{0});
        return new EncodeResult(root, List.of(bytesBuf, offsetsBuf), statsMin, statsMax);
    }

    /// Computes the serialized min/max string [ProtoScalarValue] pair for a string array, skipping
    /// `null` entries (lexicographic by [String#compareTo]). Returns `null` when every entry is
    /// `null`. Shared so the dictionary zone-map path computes per-chunk string min/max identically
    /// to the flat path.
    ///
    /// @param strings the string values (may contain `null`)
    /// @return a two-element `{min, max}` array of encoded scalars, or `null` if all entries are `null`
    public static byte[][] minMaxStats(String[] strings) {
        String minStr = null;
        String maxStr = null;
        for (String s : strings) {
            if (s == null) {
                continue;
            }
            if (minStr == null || s.compareTo(minStr) < 0) {
                minStr = s;
            }
            if (maxStr == null || s.compareTo(maxStr) > 0) {
                maxStr = s;
            }
        }
        if (minStr == null) {
            return null;
        }
        return new byte[][]{ProtoScalarValue.ofStringValue(minStr).encode(), ProtoScalarValue.ofStringValue(maxStr).encode()};
    }
}
