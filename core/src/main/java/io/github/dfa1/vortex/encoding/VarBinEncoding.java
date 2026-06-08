package io.github.dfa1.vortex.encoding;

import com.google.protobuf.InvalidProtocolBufferException;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.VarBinArray;
import io.github.dfa1.vortex.proto.EncodingProtos;
import io.github.dfa1.vortex.proto.ScalarProtos;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

/// Decoder for {@code vortex.varbin} — variable-length binary / UTF-8 string arrays.
///
/// <p>Metadata: protobuf {@code VarBinMetadata} — {@code offsets_ptype PType} (tag 1).
///
/// <p>Buffer 0: concatenated raw bytes of all strings.
/// Child slot 0: offsets array (length = rowCount + 1, dtype = offsets_ptype).
///   {@code offsets[i]..offsets[i+1]} is the byte range of element {@code i} in the bytes buffer.
/// Child slot 1 (optional): validity array — ignored for non-nullable columns.
///
/// <p>The returned {@code Array} exposes:
/// <ul>
///   <li>{@code buffer(0)} — the raw bytes segment</li>
///   <li>{@code child(0)} — the offsets array (I32 or I64 primitives)</li>
/// </ul>
public final class VarBinEncoding implements Encoding {

    /// Creates a new {@code VarBinEncoding} instance; use via {@link EncodingRegistry}.
    public VarBinEncoding() {
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
        return Encoder.encode(data, ctx);
    }

    @Override
    public Array decode(DecodeContext ctx) {
        return Decoder.decode(ctx);
    }

    private static final class Encoder {

        private static EncodeResult encode(Object data, EncodeContext ctx) {
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
                offsetsBuf.setAtIndex(PTypeIO.LE_LONG, i + 1, pos);
            }

            byte[] metaBytes = EncodingProtos.VarBinMetadata.newBuilder()
                                       .setOffsetsPtype(io.github.dfa1.vortex.proto.DTypeProtos.PType.forNumber(PType.I64.ordinal()))
                                       .build()
                                       .toByteArray();

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
            byte[] statsMin = minStr != null
                                      ? ScalarProtos.ScalarValue.newBuilder().setStringValue(minStr).build().toByteArray() : null;
            byte[] statsMax = maxStr != null
                                      ? ScalarProtos.ScalarValue.newBuilder().setStringValue(maxStr).build().toByteArray() : null;

            EncodeNode offsetsNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 1);
            EncodeNode root = new EncodeNode(EncodingId.VORTEX_VARBIN, ByteBuffer.wrap(metaBytes),
                    new EncodeNode[]{offsetsNode}, new int[]{0});
            return new EncodeResult(root, List.of(bytesBuf, offsetsBuf), statsMin, statsMax);
        }
    }

    private static final class Decoder {

        private static Array decode(DecodeContext ctx) {
            ByteBuffer rawMeta = ctx.metadata();
            if (rawMeta == null) {
                throw new VortexException(EncodingId.VORTEX_VARBIN, "missing metadata");
            }
            EncodingProtos.VarBinMetadata meta;
            try {
                meta = EncodingProtos.VarBinMetadata.parseFrom(rawMeta.duplicate());
            } catch (InvalidProtocolBufferException e) {
                throw new VortexException(EncodingId.VORTEX_VARBIN, "invalid metadata", e);
            }

            PType offsetsPtype = PType.fromOrdinal(meta.getOffsetsPtype().getNumber());
            DType offsetsDtype = new DType.Primitive(offsetsPtype, false);
            long n = ctx.rowCount();

            // Offsets: n+1 elements; bytes: raw string data.
            MemorySegment offsets = ctx.decodeChildSegment(0, offsetsDtype, n + 1);

            // VarBinArray indexes offsets[i]..offsets[i+1] via raw getAtIndex without modulo.
            // If a constant-encoded child returned a 1-element segment, materialize the full
            // n+1 layout here so VarBinArray reads stay in bounds. A truly constant offsets
            // array means every string is empty and starts at the same position — a degenerate
            // but well-defined case.
            int offBytes = offsetsPtype.byteSize();
            long offCap = SegmentBroadcast.capacity(offsets, offBytes);
            if (offCap < n + 1) {
                MemorySegment materialized = ctx.arena().allocate((n + 1) * (long) offBytes, offBytes);
                SegmentBroadcast.broadcastCopy(offsets, materialized, n + 1, offBytes);
                offsets = materialized;
            }

            MemorySegment bytes = ctx.buffer(0);

            return new VarBinArray(ctx.dtype(), n, bytes, offsets, offsetsPtype);
        }
    }
}
