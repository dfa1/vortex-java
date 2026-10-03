package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.compute.PrimitiveArrays;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.io.IoBounds;
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.proto.ProtoOnPairMetadata;
import io.github.dfa1.vortex.core.proto.ProtoPType;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.VarBinOffsetArray;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/// Read-only decoder for `vortex.onpair` (unstable edition `unstable2026.06.0`).
///
/// OnPair is a dictionary-based short-string compression: buffer 0 holds the concatenated token
/// bytes and child 0 their `dict_size + 1` offsets (token `t` is `dict[off[t]..off[t+1]]`). Each row
/// is the concatenation of the tokens named by its run of `codes` (child 1); child 2 holds the
/// per-row code boundaries and child 3 the per-row decoded byte lengths, child 4 the optional
/// validity. Rust has no spec section yet; this mirrors `encodings/onpair` at 0.86.1.
///
/// Decodes eagerly into a [VarBinOffsetArray]: codes are walked once in order, so only the first
/// and last code boundary are needed, and row splits come from the decoded lengths.
public final class OnPairEncodingDecoder implements EncodingDecoder {

    private static final EncodingId ID = EncodingId.VORTEX_ONPAIR;

    @Override
    public EncodingId encodingId() {
        return ID;
    }

    @Override
    public Array decode(DecodeContext ctx) {
        return Decoder.decode(ctx);
    }

    private static final class Decoder {

        static Array decode(DecodeContext ctx) {
            int numBufs = ctx.node().bufferIndices().length;
            if (numBufs != 1) {
                throw new VortexException(ID, "expected 1 buffer, got " + numBufs);
            }
            int numChildren = ctx.node().children().length;
            if (numChildren != 4 && numChildren != 5) {
                throw new VortexException(ID, "expected 4 or 5 children, got " + numChildren);
            }
            ProtoOnPairMetadata meta = metadata(ctx);
            long n = ctx.rowCount();
            if (n >= Integer.MAX_VALUE) {
                throw new VortexException(ID, "row count too large: " + n);
            }

            MemorySegment dictBytes = ctx.buffer(0);
            IoBounds.toIntSize(dictBytes.byteSize());
            long[] dictOffsets = childLongs(ctx, 0, meta.dict_offsets_ptype(), Integer.toUnsignedLong(meta.dict_size()) + 1);
            validateDictionary(dictOffsets, dictBytes.byteSize());

            long[] codeBounds = childLongs(ctx, 2, meta.codes_offsets_ptype(), n + 1);
            long codeStart = codeBounds[0];
            long codeEnd = codeBounds[(int) n];
            if (codeStart < 0 || codeStart > codeEnd || codeEnd > meta.codes_len()) {
                throw new VortexException(ID, "codes window [" + codeStart + ", " + codeEnd
                        + ") out of bounds for codes len " + meta.codes_len());
            }
            long[] codes = childLongs(ctx, 1, meta.codes_ptype(), meta.codes_len());

            long[] lengths = childLongs(ctx, 3, meta.uncompressed_lengths_ptype(), n);
            MemorySegment offsets = ctx.arena().allocate((n + 1) * Long.BYTES, Long.BYTES);
            long total = 0;
            for (int i = 0; i < n; i++) {
                if (lengths[i] < 0) {
                    throw new VortexException(ID, "negative uncompressed length at row " + i);
                }
                total += lengths[i];
                offsets.setAtIndex(VortexFormat.LE_LONG, (long) i + 1, total);
            }

            byte[] dict = dictBytes.toArray(ValueLayout.JAVA_BYTE);
            byte[] out = new byte[IoBounds.toIntSize(total)];
            int pos = 0;
            int numTokens = dictOffsets.length - 1;
            for (int c = (int) codeStart; c < codeEnd; c++) {
                long token = codes[c];
                if (token < 0 || token >= numTokens) {
                    throw new VortexException(ID, "code " + token + " out of range for " + numTokens + " tokens");
                }
                int start = (int) dictOffsets[(int) token];
                int len = (int) dictOffsets[(int) token + 1] - start;
                if (len > out.length - pos) {
                    throw new VortexException(ID, "codes decode to more bytes than uncompressed_lengths records");
                }
                System.arraycopy(dict, start, out, pos, len);
                pos += len;
            }
            if (pos != out.length) {
                throw new VortexException(ID, "codes decoded to " + pos
                        + " bytes but uncompressed_lengths records " + out.length);
            }
            MemorySegment bytes = ctx.arena().allocate(Math.max(1, out.length));
            MemorySegment.copy(out, 0, bytes, ValueLayout.JAVA_BYTE, 0, out.length);

            Array values = new VarBinOffsetArray(ctx.dtype(), n, bytes, offsets, PType.I64);
            if (numChildren == 5) {
                Array va = ctx.decodeChild(4, DType.BOOL, n);
                return new MaskedArray(values, MaskedArray.requireBoolArray(va, ID, "validity child"));
            }
            return values;
        }

        /// Proto3 omits zero-valued fields, so an all-default message may be written as no metadata
        /// at all (same convention as `vortex.fsst`).
        private static ProtoOnPairMetadata metadata(DecodeContext ctx) {
            MemorySegment raw = ctx.metadata();
            if (raw == null) {
                return new ProtoOnPairMetadata(ProtoPType.U8, 0, 0, ProtoPType.U8, ProtoPType.U8, ProtoPType.U8);
            }
            try {
                return ProtoOnPairMetadata.decode(raw, 0, raw.byteSize());
            } catch (IOException e) {
                throw new VortexException(ID, "invalid metadata", e);
            }
        }

        /// Offsets must start at 0, be nondecreasing and stay inside the dictionary buffer, so every
        /// token slice read below is in bounds.
        private static void validateDictionary(long[] offsets, long dictSize) {
            if (offsets[0] != 0) {
                throw new VortexException(ID, "dict_offsets must start at 0, got " + offsets[0]);
            }
            for (int i = 1; i < offsets.length; i++) {
                if (offsets[i] < offsets[i - 1]) {
                    throw new VortexException(ID, "dict_offsets must be nondecreasing at " + i);
                }
            }
            if (offsets[offsets.length - 1] > dictSize) {
                throw new VortexException(ID, "dict_offsets end " + offsets[offsets.length - 1]
                        + " exceeds dictionary size " + dictSize);
            }
        }

        /// Decodes integer child `i` (of `count` elements) and widens it to `long[]`, expanding a
        /// constant-encoded child that physically holds a single element.
        private static long[] childLongs(DecodeContext ctx, int i, ProtoPType wirePtype, long count) {
            int n = IoBounds.checkCount(count);
            PType ptype = PType.fromOrdinal(wirePtype.value());
            MemorySegment seg = ctx.decodeChildSegment(i, new DType.Primitive(ptype, false), count);
            int width = ptype.byteSize();
            if (n > 0 && SegmentBroadcast.capacity(seg, width) < n) {
                MemorySegment expanded = ctx.arena().allocate((long) n * width, width);
                SegmentBroadcast.broadcastCopy(seg, expanded, n, width);
                seg = expanded;
            }
            return PrimitiveArrays.toLongs(seg, 0, n, ptype, ID);
        }
    }
}
