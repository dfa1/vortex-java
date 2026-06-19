package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.encoding.PTypeIO;
import io.github.dfa1.vortex.proto.ScalarValue;
import io.github.dfa1.vortex.proto.VarBinMetadata;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

/// Write-only encoder for `vortex.varbin`.
public final class VarBinEncodingEncoder implements EncodingEncoder {

    /// Public no-arg constructor required by [java.util.ServiceLoader].
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
        String[] strings = (String[]) data;
        int n = strings.length;

        byte[][] byteArrays = new byte[n][];
        int totalBytes = 0;
        for (int i = 0; i < n; i++) {
            byteArrays[i] = strings[i].getBytes(StandardCharsets.UTF_8);
            totalBytes += byteArrays[i].length;
        }

        Arena arena = ctx.arena();
        MemorySegment bytesBuf = arena.allocate(totalBytes > 0 ? totalBytes : 1);
        MemorySegment offsetsBuf = arena.allocate((long) (n + 1) * Long.BYTES, Long.BYTES);

        long pos = 0;
        offsetsBuf.setAtIndex(PTypeIO.LE_LONG, 0, 0L);
        for (int i = 0; i < n; i++) {
            MemorySegment.copy(MemorySegment.ofArray(byteArrays[i]), 0, bytesBuf, pos, byteArrays[i].length);
            pos += byteArrays[i].length;
            offsetsBuf.setAtIndex(PTypeIO.LE_LONG, (long) i + 1, pos);
        }

        byte[] metaBytes = new VarBinMetadata(io.github.dfa1.vortex.proto.PType.fromValue(PType.I64.ordinal())).encode();

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
        byte[] statsMin = minStr != null ? ScalarValue.ofStringValue(minStr).encode() : null;
        byte[] statsMax = maxStr != null ? ScalarValue.ofStringValue(maxStr).encode() : null;

        EncodeNode offsetsNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 1);
        EncodeNode root = new EncodeNode(EncodingId.VORTEX_VARBIN, ByteBuffer.wrap(metaBytes),
                new EncodeNode[]{offsetsNode}, new int[]{0});
        return new EncodeResult(root, List.of(bytesBuf, offsetsBuf), statsMin, statsMax);
    }
}
