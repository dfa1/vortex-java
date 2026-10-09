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
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/// Read-only decoder for `vortex.onpair` (edition `core2026.08.1`).
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
    /// Longest dictionary token, Rust's `onpair::MAX_TOKEN_SIZE`; also the dictionary's read padding.
    private static final int MAX_TOKEN_SIZE = 16;
    private static final VarHandle LE_LONG = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

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
            int numTokens = dictOffsets.length - 1;
            int pos = 0;
            int c = (int) codeStart;
            // Rust's `try_decode_into`: batches of fixed 16-byte over-copies. `batch` is the most
            // tokens provably within `out` from the cursor (each advances it by at most
            // MAX_TOKEN_SIZE), so the inner loop needs no per-token output check; the validated
            // read padding makes the 16-byte source read in bounds for every token.
            while (c < codeEnd) {
                int batch = (out.length - pos) / MAX_TOKEN_SIZE;
                if (batch == 0) {
                    break;
                }
                int end = (int) Math.min(codeEnd, (long) c + batch);
                for (; c < end; c++) {
                    int token = token(codes[c], numTokens);
                    int start = (int) dictOffsets[token];
                    LE_LONG.set(out, pos, (long) LE_LONG.get(dict, start));
                    LE_LONG.set(out, pos + 8, (long) LE_LONG.get(dict, start + 8));
                    pos += (int) dictOffsets[token + 1] - start;
                }
            }
            // Tail: under MAX_TOKEN_SIZE bytes left, exact copies that fail once a token won't fit.
            for (; c < codeEnd; c++) {
                int token = token(codes[c], numTokens);
                int start = (int) dictOffsets[token];
                int len = (int) dictOffsets[token + 1] - start;
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

        /// Rust's `CompactDictionary::validate_safety`: offsets start at 0 and are nondecreasing,
        /// every token is 1 to [#MAX_TOKEN_SIZE] bytes, and the buffer is read-padded so a
        /// MAX_TOKEN_SIZE-byte read from the last token start stays inside it. Together these make
        /// every token's fixed-width over-copy in the decode loop in bounds and exact.
        private static void validateDictionary(long[] offsets, long dictSize) {
            if (offsets[0] != 0) {
                throw new VortexException(ID, "dict_offsets must start at 0, got " + offsets[0]);
            }
            for (int i = 1; i < offsets.length; i++) {
                long len = offsets[i] - offsets[i - 1];
                if (len < 0) {
                    throw new VortexException(ID, "dict_offsets must be nondecreasing at " + i);
                }
                if (len == 0 || len > MAX_TOKEN_SIZE) {
                    throw new VortexException(ID, "dictionary token " + (i - 1) + " is " + len
                            + " bytes, must be 1 to " + MAX_TOKEN_SIZE);
                }
            }
            if (offsets.length > 1 && offsets[offsets.length - 2] + MAX_TOKEN_SIZE > dictSize) {
                throw new VortexException(ID, "dictionary of " + dictSize + " bytes lacks the "
                        + MAX_TOKEN_SIZE + "-byte read padding after its last token");
            }
        }

        private static int token(long code, int numTokens) {
            if (code < 0 || code >= numTokens) {
                throw new VortexException(ID, "code " + code + " out of range for " + numTokens + " tokens");
            }
            return (int) code;
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
