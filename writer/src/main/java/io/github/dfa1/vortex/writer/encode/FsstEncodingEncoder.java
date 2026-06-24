package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.encoding.PTypeIO;
import io.github.dfa1.vortex.proto.ProtoFSSTMetadata;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/// Write-only encoder for `vortex.fsst`.
public final class FsstEncodingEncoder implements EncodingEncoder {

    private static final int MAX_SYMBOLS = 255;
    private static final int BIGRAM_COUNT = 65536;

    /// Public no-arg constructor required by [java.util.ServiceLoader].
    public FsstEncodingEncoder() {
    }

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_FSST;
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
        for (int i = 0; i < n; i++) {
            byteArrays[i] = strings[i].getBytes(StandardCharsets.UTF_8);
        }

        int[] freq = new int[BIGRAM_COUNT];
        for (byte[] b : byteArrays) {
            for (int i = 0; i + 1 < b.length; i++) {
                freq[(Byte.toUnsignedInt(b[i]) << 8) | Byte.toUnsignedInt(b[i + 1])]++;
            }
        }
        long[] ranked = new long[BIGRAM_COUNT];
        for (int i = 0; i < BIGRAM_COUNT; i++) {
            ranked[i] = ((long) freq[i] << 16) | i;
        }
        Arrays.sort(ranked);

        int numSymbols = 0;
        int[] codeForBigram = new int[BIGRAM_COUNT];
        Arrays.fill(codeForBigram, -1);
        long[] symbolValues = new long[MAX_SYMBOLS];
        for (int rank = BIGRAM_COUNT - 1; rank >= 0 && numSymbols < MAX_SYMBOLS; rank--) {
            int bg = (int) (ranked[rank] & 0xFFFF);
            if (freq[bg] == 0) {
                break;
            }
            codeForBigram[bg] = numSymbols;
            int hi = bg >>> 8;
            int lo = bg & 0xFF;
            symbolValues[numSymbols] = hi | ((long) lo << 8);
            numSymbols++;
        }

        byte[][] compressed = new byte[n][];
        for (int i = 0; i < n; i++) {
            compressed[i] = compressString(byteArrays[i], codeForBigram);
        }

        Arena arena = ctx.arena();

        MemorySegment symBuf = arena.allocate(Math.max(numSymbols * 8L, 1), 8);
        for (int i = 0; i < numSymbols; i++) {
            symBuf.setAtIndex(PTypeIO.LE_LONG, i, symbolValues[i]);
        }

        MemorySegment symLenBuf = arena.allocate(Math.max(numSymbols, 1));
        for (int i = 0; i < numSymbols; i++) {
            symLenBuf.set(ValueLayout.JAVA_BYTE, i, (byte) 2);
        }

        int totalCompressed = 0;
        for (byte[] c : compressed) {
            totalCompressed += c.length;
        }
        MemorySegment compBuf = arena.allocate(Math.max(totalCompressed, 1));
        long pos = 0;
        for (byte[] c : compressed) {
            MemorySegment.copy(MemorySegment.ofArray(c), 0, compBuf, pos, c.length);
            pos += c.length;
        }

        MemorySegment uncompLenBuf = arena.allocate(Math.max(n * 4L, 1), 4);
        for (int i = 0; i < n; i++) {
            uncompLenBuf.setAtIndex(PTypeIO.LE_INT, i, byteArrays[i].length);
        }

        MemorySegment codesOffBuf = arena.allocate((long) (n + 1) * 4, 4);
        long off = 0;
        codesOffBuf.setAtIndex(PTypeIO.LE_INT, 0, 0);
        for (int i = 0; i < n; i++) {
            off += compressed[i].length;
            codesOffBuf.setAtIndex(PTypeIO.LE_INT, (long) i + 1, (int) off);
        }

        byte[] metaBytes = new ProtoFSSTMetadata(
                io.github.dfa1.vortex.proto.ProtoPType.fromValue(PType.I32.ordinal()),
                io.github.dfa1.vortex.proto.ProtoPType.fromValue(PType.I32.ordinal())
        ).encode();

        EncodeNode uncompLensNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 3);
        EncodeNode codesOffNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 4);
        EncodeNode root = new EncodeNode(
                EncodingId.VORTEX_FSST,
                MemorySegment.ofArray(metaBytes),
                new EncodeNode[]{uncompLensNode, codesOffNode},
                new int[]{0, 1, 2});

        return new EncodeResult(root,
                List.of(symBuf, symLenBuf, compBuf, uncompLenBuf, codesOffBuf),
                null, null);
    }

    private static byte[] compressString(byte[] input, int[] codeForBigram) {
        byte[] out = new byte[input.length * 2];
        int outLen = 0;
        int i = 0;
        while (i < input.length) {
            if (i + 1 < input.length) {
                int bg = (Byte.toUnsignedInt(input[i]) << 8) | Byte.toUnsignedInt(input[i + 1]);
                int code = codeForBigram[bg];
                if (code >= 0) {
                    out[outLen++] = (byte) code;
                    i += 2;
                    continue;
                }
            }
            out[outLen++] = (byte) 0xFF;
            out[outLen++] = input[i];
            i++;
        }
        return Arrays.copyOf(out, outLen);
    }
}
