package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.io.PTypeIO;
import io.github.dfa1.vortex.core.proto.ProtoPcoChunkInfo;
import io.github.dfa1.vortex.core.proto.ProtoPcoMetadata;
import io.github.dfa1.vortex.core.proto.ProtoPcoPageInfo;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.MaterializedDoubleArray;
import io.github.dfa1.vortex.reader.array.MaterializedFloatArray;
import io.github.dfa1.vortex.reader.array.MaterializedIntArray;
import io.github.dfa1.vortex.reader.array.MaterializedLongArray;
import io.github.dfa1.vortex.reader.array.MaterializedShortArray;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;

import static io.github.dfa1.vortex.core.io.VortexFormat.LE_LONG;

/// Read-only decoder for `vortex.pco` — port of pcodec.
public final class PcoEncodingDecoder implements EncodingDecoder {
    static final byte PCO_FORMAT_MAJOR = 0x04;
    static final byte PCO_FORMAT_MINOR = 0x01;
    static final int BITS_TO_ENCODE_OFFSET_BITS_64 = 7;
    static final int BITS_TO_ENCODE_OFFSET_BITS_32 = 6;
    static final int BITS_TO_ENCODE_OFFSET_BITS_16 = 5;

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_PCO;
    }

    @Override
    public Array decode(DecodeContext ctx) {
        ProtoPcoMetadata meta = parseMeta(ctx);
        validateHeader(meta);

        DType dtype = ctx.dtype();
        if (!(dtype instanceof DType.Primitive dt)) {
            throw new VortexException(EncodingId.VORTEX_PCO,
                    "pco decode requires Primitive dtype, got: " + dtype);
        }
        PType ptype = dt.ptype();
        int dtypeSize = dtypeSize(ptype);

        long n = ctx.rowCount();

        BoolArray validity = null;
        long validCount = n;
        if (ctx.node().children().length > 0) {
            Array validityArr = ctx.decodeChild(0, DType.BOOL, n);
            if (!(validityArr instanceof BoolArray ba)) {
                throw new VortexException(EncodingId.VORTEX_PCO,
                        "pco validity child must be Bool, got: " + validityArr.getClass().getSimpleName());
            }
            validity = ba;
            validCount = 0;
            for (long i = 0; i < n; i++) {
                if (validity.getBoolean(i)) {
                    validCount++;
                }
            }
        }

        // Pages declare their own value counts (ProtoPcoPageInfo.n_values), independent of
        // validCount. A crafted file can pair a huge or negative per-page count with a small
        // rowCount: without this check, a negative count silently no-ops its loop while a
        // desynced total either writes past rawLatents/compactOut (raw IndexOutOfBounds) or
        // sizes rawAdjs from an attacker-controlled chunkN unrelated to any real buffer
        // (OutOfMemoryError). Validating the total up front keeps every per-page/per-chunk
        // access below implicitly bounded by validCount.
        long totalPageValues = 0L;
        for (ProtoPcoChunkInfo chunkInfo : meta.chunks()) {
            for (ProtoPcoPageInfo page : chunkInfo.pages()) {
                if (page.n_values() < 0) {
                    throw new VortexException(EncodingId.VORTEX_PCO,
                            "pco page n_values " + page.n_values() + " is negative");
                }
                totalPageValues += page.n_values();
            }
        }
        if (totalPageValues != validCount) {
            throw new VortexException(EncodingId.VORTEX_PCO,
                    "pco total page values " + totalPageValues + " != expected valid row count " + validCount);
        }

        MemorySegment rawLatents = ctx.arena().allocate(validCount * Long.BYTES);

        int nChunks = meta.chunks().size();
        // Buffer layout (matches Rust vortex PcoArray): all chunk metas first, then all pages.
        // buffers[0..nChunks) = chunk metas; buffers[nChunks..) = pages (flattened).
        int pageBufIdx = nChunks;
        long rawByteOffset = 0L;

        long[] batchLowers1 = new long[PcoTansDecoder.BATCH_N];
        int[] batchOffsetBits1 = new int[PcoTansDecoder.BATCH_N];
        long[] batchLowers2 = new long[PcoTansDecoder.BATCH_N];
        int[] batchOffsetBits2 = new int[PcoTansDecoder.BATCH_N];

        for (int c = 0; c < nChunks; c++) {
            ProtoPcoChunkInfo chunkInfo = meta.chunks().get(c);
            MemorySegment chunkMetaBuf = ctx.buffer(c); // chunk metas at indices 0..nChunks-1
            PcoChunkMeta chunkMeta = readChunkMeta(chunkMetaBuf, dtypeSize);

            int mode = chunkMeta.mode();
            int deltaVariant = chunkMeta.deltaVariant();
            long chunkStartOffset = rawByteOffset;

            int chunkN = 0;
            for (int p = 0; p < chunkInfo.pages().size(); p++) {
                chunkN += chunkInfo.pages().get(p).n_values();
            }

            if (deltaVariant == 3) {
                PcoTansDecoder primaryTans = PcoTansDecoder.build(
                        chunkMeta.ansSizeLog(), chunkMeta.bins());
                for (int p = 0; p < chunkInfo.pages().size(); p++) {
                    int pageN = chunkInfo.pages().get(p).n_values();
                    MemorySegment pageBuf = ctx.buffer(pageBufIdx++);
                    rawByteOffset = decodeConv1Page(
                            primaryTans, chunkMeta.ansSizeLog(),
                            chunkMeta.conv1Weights().length,
                            chunkMeta.conv1Quantization(), chunkMeta.conv1Bias(),
                            chunkMeta.conv1Weights(),
                            dtypeSize, pageBuf, pageN,
                            rawLatents, rawByteOffset,
                            batchLowers1, batchOffsetBits1);
                }
            } else if (deltaVariant == 2) {
                if (mode != 0) {
                    throw new VortexException(EncodingId.VORTEX_PCO,
                            "pco Lookback delta with non-Classic mode " + mode + " not yet implemented");
                }
                PcoTansDecoder deltaTans = PcoTansDecoder.build(
                        chunkMeta.deltaAnsSizeLog(), chunkMeta.deltaBins());
                PcoTansDecoder primaryTans = PcoTansDecoder.build(
                        chunkMeta.ansSizeLog(), chunkMeta.bins());
                int stateN = 1 << chunkMeta.stateNLog();
                int windowN = 1 << chunkMeta.windowNLog();
                long mid = typeMid(dtypeSize);
                long mask = typeMask(dtypeSize);
                for (int p = 0; p < chunkInfo.pages().size(); p++) {
                    int pageN = chunkInfo.pages().get(p).n_values();
                    MemorySegment pageBuf = ctx.buffer(pageBufIdx++);
                    rawByteOffset = decodeLookbackPage(
                            deltaTans, chunkMeta.deltaAnsSizeLog(),
                            primaryTans, chunkMeta.ansSizeLog(),
                            stateN, windowN, mid, mask,
                            dtypeSize, pageBuf, pageN,
                            rawLatents, rawByteOffset, ctx.arena(),
                            batchLowers1, batchOffsetBits1,
                            batchLowers2, batchOffsetBits2);
                }
            } else if (mode == 0 || mode == 4) {
                int primaryDtypeSize = (mode == 4) ? 32 : dtypeSize;
                PcoTansDecoder tans = PcoTansDecoder.build(chunkMeta.ansSizeLog(), chunkMeta.bins());
                for (int p = 0; p < chunkInfo.pages().size(); p++) {
                    int pageN = chunkInfo.pages().get(p).n_values();
                    MemorySegment pageBuf = ctx.buffer(pageBufIdx++);
                    rawByteOffset = decodeClassicPage(tans, chunkMeta.ansSizeLog(),
                            chunkMeta.deltaOrder(), primaryDtypeSize,
                            pageBuf, pageN, rawLatents, rawByteOffset,
                            batchLowers1, batchOffsetBits1);
                }
                if (mode == 4) {
                    combineDict(chunkMeta.dict(), chunkN, rawLatents, chunkStartOffset);
                }
            } else {
                long base = chunkMeta.base();
                int primaryAnsSizeLog = chunkMeta.ansSizeLog();
                int secondaryAnsSizeLog = chunkMeta.secondaryAnsSizeLog();
                PcoTansDecoder primaryTans = PcoTansDecoder.build(primaryAnsSizeLog, chunkMeta.bins());
                PcoTansDecoder secondaryTans = PcoTansDecoder.build(secondaryAnsSizeLog, chunkMeta.secondaryBins());
                int deltaOrder = chunkMeta.deltaOrder();
                int secondaryDeltaOrder = chunkMeta.secondaryUsesDelta() ? deltaOrder : 0;

                MemorySegment rawAdjs = ctx.arena().allocate((long) chunkN * Long.BYTES);
                long adjByteOffset = 0L;
                for (int p = 0; p < chunkInfo.pages().size(); p++) {
                    int pageN = chunkInfo.pages().get(p).n_values();
                    MemorySegment pageBuf = ctx.buffer(pageBufIdx++);
                    decodeIntMultPage(primaryTans, primaryAnsSizeLog, deltaOrder,
                            secondaryTans, secondaryAnsSizeLog, secondaryDeltaOrder,
                            dtypeSize, pageBuf, pageN,
                            rawLatents, rawByteOffset,
                            rawAdjs, adjByteOffset,
                            batchLowers1, batchOffsetBits1,
                            batchLowers2, batchOffsetBits2);
                    rawByteOffset += (long) pageN * Long.BYTES;
                    adjByteOffset += (long) pageN * Long.BYTES;
                }

                if (mode == 1) {
                    long mask = typeMask(dtypeSize);
                    for (int i = 0; i < chunkN; i++) {
                        long off = chunkStartOffset + (long) i * Long.BYTES;
                        long mult = rawLatents.get(LE_LONG, off);
                        long adj = rawAdjs.get(LE_LONG, (long) i * Long.BYTES);
                        rawLatents.set(LE_LONG, off, (mult * base + adj) & mask);
                    }
                } else if (mode == 2) {
                    combineFloatMult(ptype, base, chunkN, rawLatents, chunkStartOffset, rawAdjs);
                } else {
                    combineFloatQuant(ptype, chunkMeta.quantizeK(), chunkN, rawLatents, chunkStartOffset, rawAdjs);
                }
            }
        }

        int elemBytes = ptype.byteSize();
        MemorySegment compactOut = ctx.arena().allocate(validCount * elemBytes);
        for (long i = 0; i < validCount; i++) {
            long latent = rawLatents.get(LE_LONG, i * Long.BYTES);
            PTypeIO.set(compactOut, i * elemBytes, ptype, fromLatentOrdered(latent, ptype));
        }

        if (validity == null) {
            return toArray(dtype, n, compactOut);
        }

        MemorySegment fullOut = ctx.arena().allocate(n * elemBytes);
        long srcOff = 0;
        for (long i = 0; i < n; i++) {
            if (validity.getBoolean(i)) {
                MemorySegment.copy(compactOut, srcOff, fullOut, i * elemBytes, elemBytes);
                srcOff += elemBytes;
            }
        }
        DType nonNullDtype = new DType.Primitive(ptype, false);
        return new MaskedArray(toArray(nonNullDtype, n, fullOut), validity);
    }

    private static long decodeClassicPage(PcoTansDecoder tans, int ansSizeLog, int deltaOrder,
            int primaryDtypeSize, MemorySegment pageBuf, int pageN,
            MemorySegment rawLatents, long rawByteOffset,
            long[] batchLowers, int[] batchOffsetBits) {
        LeBitReader pageReader = new LeBitReader(pageBuf);

        long[] moments = new long[deltaOrder];
        for (int m = 0; m < deltaOrder; m++) {
            moments[m] = pageReader.readBits(primaryDtypeSize);
        }

        int[] stateIdxs = new int[PcoTansDecoder.ANS_INTERLEAVING];
        for (int i = 0; i < PcoTansDecoder.ANS_INTERLEAVING; i++) {
            stateIdxs[i] = (int) pageReader.readBits(ansSizeLog);
        }
        pageReader.alignToByte();

        int decodedN = pageN - deltaOrder;
        tans.decodePage(pageReader, stateIdxs, decodedN, rawLatents, rawByteOffset,
                batchLowers, batchOffsetBits);

        if (deltaOrder > 0) {
            applyConsecutiveDelta(rawLatents, rawByteOffset, pageN, moments, primaryDtypeSize);
        }

        return rawByteOffset + (long) pageN * Long.BYTES;
    }

    private static void decodeIntMultPage(
            PcoTansDecoder primaryTans, int primaryAnsSizeLog, int deltaOrder,
            PcoTansDecoder secondaryTans, int secondaryAnsSizeLog, int secondaryDeltaOrder,
            int dtypeSize, MemorySegment pageBuf, int pageN,
            MemorySegment rawMults, long multsOffset,
            MemorySegment rawAdjs, long adjsOffset,
            long[] batchLowersP, int[] batchOffsetBitsP,
            long[] batchLowersS, int[] batchOffsetBitsS) {
        LeBitReader pageReader = new LeBitReader(pageBuf);

        long[] primaryMoments = new long[deltaOrder];
        for (int m = 0; m < deltaOrder; m++) {
            primaryMoments[m] = pageReader.readBits(dtypeSize);
        }
        int[] primaryStateIdxs = new int[PcoTansDecoder.ANS_INTERLEAVING];
        for (int i = 0; i < PcoTansDecoder.ANS_INTERLEAVING; i++) {
            primaryStateIdxs[i] = (int) pageReader.readBits(primaryAnsSizeLog);
        }

        long[] secondaryMoments = new long[secondaryDeltaOrder];
        for (int m = 0; m < secondaryDeltaOrder; m++) {
            secondaryMoments[m] = pageReader.readBits(dtypeSize);
        }
        int[] secondaryStateIdxs = new int[PcoTansDecoder.ANS_INTERLEAVING];
        for (int i = 0; i < PcoTansDecoder.ANS_INTERLEAVING; i++) {
            secondaryStateIdxs[i] = (int) pageReader.readBits(secondaryAnsSizeLog);
        }

        pageReader.alignToByte();

        int nRemaining = pageN;
        long primaryPos = multsOffset;
        long secondaryPos = adjsOffset;

        while (nRemaining > 0) {
            int batchN = Math.min(nRemaining, PcoTansDecoder.BATCH_N);
            int primaryPreDeltaN = Math.clamp((long) nRemaining - deltaOrder, 0, batchN);
            int secondaryPreDeltaN = Math.clamp((long) nRemaining - secondaryDeltaOrder, 0, batchN);

            primaryTans.decodeBatch(pageReader, primaryStateIdxs, primaryPreDeltaN,
                    batchLowersP, batchOffsetBitsP, rawMults, primaryPos);
            secondaryTans.decodeBatch(pageReader, secondaryStateIdxs, secondaryPreDeltaN,
                    batchLowersS, batchOffsetBitsS, rawAdjs, secondaryPos);

            primaryPos += (long) batchN * Long.BYTES;
            secondaryPos += (long) batchN * Long.BYTES;
            nRemaining -= batchN;
        }

        if (deltaOrder > 0) {
            applyConsecutiveDelta(rawMults, multsOffset, pageN, primaryMoments, dtypeSize);
        }
        if (secondaryDeltaOrder > 0) {
            applyConsecutiveDelta(rawAdjs, adjsOffset, pageN, secondaryMoments, dtypeSize);
        }
    }

    private static long decodeLookbackPage(
            PcoTansDecoder deltaTans, int deltaAnsSizeLog,
            PcoTansDecoder primaryTans, int primaryAnsSizeLog,
            int stateN, int windowN, long mid, long mask,
            int dtypeSize, MemorySegment pageBuf, int pageN,
            MemorySegment rawLatents, long latentsOffset,
            SegmentAllocator arena,
            long[] batchLowersD, int[] batchOffsetBitsD,
            long[] batchLowersP, int[] batchOffsetBitsP) {
        if (pageN < stateN) {
            throw new VortexException(EncodingId.VORTEX_PCO,
                    "pco corrupt lookback page: stateN " + stateN + " exceeds pageN " + pageN);
        }
        LeBitReader pageReader = new LeBitReader(pageBuf);

        int[] deltaStateIdxs = new int[PcoTansDecoder.ANS_INTERLEAVING];
        for (int i = 0; i < PcoTansDecoder.ANS_INTERLEAVING; i++) {
            deltaStateIdxs[i] = (int) pageReader.readBits(deltaAnsSizeLog);
        }

        long[] initialState = new long[stateN];
        for (int m = 0; m < stateN; m++) {
            initialState[m] = pageReader.readBits(dtypeSize);
        }
        int[] primaryStateIdxs = new int[PcoTansDecoder.ANS_INTERLEAVING];
        for (int i = 0; i < PcoTansDecoder.ANS_INTERLEAVING; i++) {
            primaryStateIdxs[i] = (int) pageReader.readBits(primaryAnsSizeLog);
        }
        pageReader.alignToByte();

        int decodeN = pageN - stateN;
        if (decodeN > 1 << 23) {
            throw new VortexException(EncodingId.VORTEX_PCO,
                    "pco corrupt lookback page: decodeN " + decodeN + " exceeds max 8388608");
        }
        MemorySegment rawLookbacks = arena.allocate((long) decodeN * Long.BYTES);
        MemorySegment rawResiduals = arena.allocate((long) decodeN * Long.BYTES);

        int remaining = decodeN;
        long dPos = 0L;
        long pPos = 0L;
        while (remaining > 0) {
            int batchN = Math.min(remaining, PcoTansDecoder.BATCH_N);
            deltaTans.decodeBatch(pageReader, deltaStateIdxs, batchN,
                    batchLowersD, batchOffsetBitsD, rawLookbacks, dPos);
            primaryTans.decodeBatch(pageReader, primaryStateIdxs, batchN,
                    batchLowersP, batchOffsetBitsP, rawResiduals, pPos);
            dPos += (long) batchN * Long.BYTES;
            pPos += (long) batchN * Long.BYTES;
            remaining -= batchN;
        }

        for (int i = 0; i < decodeN; i++) {
            long off = (long) i * Long.BYTES;
            rawResiduals.set(LE_LONG, off, (rawResiduals.get(LE_LONG, off) ^ mid) & mask);
        }

        for (int i = 0; i < stateN; i++) {
            rawLatents.set(LE_LONG, latentsOffset + (long) i * Long.BYTES, initialState[i] & mask);
        }

        if (stateN > windowN) {
            throw new VortexException(EncodingId.VORTEX_PCO,
                    "pco corrupt lookback: stateN " + stateN + " exceeds windowN " + windowN);
        }
        long[] window = new long[windowN + decodeN];
        for (int i = 0; i < stateN; i++) {
            window[windowN - stateN + i] = initialState[i] & mask;
        }
        for (int i = 0; i < decodeN; i++) {
            int lb = (int) rawLookbacks.get(LE_LONG, (long) i * Long.BYTES);
            if (lb < 1 || lb > windowN) {
                throw new VortexException(EncodingId.VORTEX_PCO,
                        "pco corrupt lookback index " + lb + " not in [1, " + windowN + "]");
            }
            long decoded = (rawResiduals.get(LE_LONG, (long) i * Long.BYTES) + window[windowN + i - lb]) & mask;
            window[windowN + i] = decoded;
            rawLatents.set(LE_LONG, latentsOffset + (long) (stateN + i) * Long.BYTES, decoded);
        }

        return latentsOffset + (long) pageN * Long.BYTES;
    }

    private static long decodeConv1Page(
            PcoTansDecoder tans, int ansSizeLog,
            int order, int quantization, long bias, long[] weights,
            int dtypeSize, MemorySegment pageBuf, int pageN,
            MemorySegment rawLatents, long latentsOffset,
            long[] batchLowers, int[] batchOffsetBits) {
        LeBitReader pageReader = new LeBitReader(pageBuf);

        long[] state = new long[order];
        for (int i = 0; i < order; i++) {
            state[i] = pageReader.readBits(dtypeSize);
        }
        int[] stateIdxs = new int[PcoTansDecoder.ANS_INTERLEAVING];
        for (int i = 0; i < PcoTansDecoder.ANS_INTERLEAVING; i++) {
            stateIdxs[i] = (int) pageReader.readBits(ansSizeLog);
        }
        pageReader.alignToByte();

        int decodeN = pageN - order;
        long mid = typeMid(dtypeSize);
        long mask = typeMask(dtypeSize);

        for (int i = 0; i < order; i++) {
            rawLatents.set(LE_LONG, latentsOffset + (long) i * Long.BYTES, state[i]);
        }

        tans.decodePage(pageReader, stateIdxs, decodeN, rawLatents,
                latentsOffset + (long) order * Long.BYTES,
                batchLowers, batchOffsetBits);

        for (int i = order; i < pageN; i++) {
            long off = latentsOffset + (long) i * Long.BYTES;
            rawLatents.set(LE_LONG, off, (rawLatents.get(LE_LONG, off) ^ mid) & mask);
        }

        for (int i = order; i < pageN; i++) {
            long pred = predictConv1(rawLatents, latentsOffset, i, order,
                    weights, bias, quantization, mask, dtypeSize);
            long off = latentsOffset + (long) i * Long.BYTES;
            rawLatents.set(LE_LONG, off, (rawLatents.get(LE_LONG, off) + pred) & mask);
        }

        return latentsOffset + (long) pageN * Long.BYTES;
    }

    private static long predictConv1(MemorySegment seg, long baseOff, int pos, int order,
            long[] weights, long bias, int quantization, long mask, int dtypeSize) {
        long s = (dtypeSize == 16) ? (int) bias : bias;
        for (int k = 0; k < order; k++) {
            long w = (dtypeSize == 16) ? (int) weights[k] : weights[k];
            long l = seg.get(LE_LONG, baseOff + (long) (pos - order + k) * Long.BYTES);
            s += w * l;
        }
        if (s < 0) {
            s = 0;
        }
        return (s >> quantization) & mask;
    }

    private static long fromLatentOrdered(long latent, PType ptype) {
        return switch (ptype) {
            case I16 -> latent ^ 0x8000L;
            case I32 -> latent ^ 0x80000000L;
            case I64 -> latent ^ Long.MIN_VALUE;
            case F32 -> {
                long l32 = latent & 0xFFFFFFFFL;
                yield (l32 & 0x80000000L) != 0 ? l32 ^ 0x80000000L : l32 ^ 0xFFFFFFFFL;
            }
            case F64 -> (latent & Long.MIN_VALUE) != 0 ? latent ^ Long.MIN_VALUE : ~latent;
            default -> latent;
        };
    }

    private static void applyConsecutiveDelta(MemorySegment rawLatents, long offset,
            int pageN, long[] moments, int dtypeSize) {
        long mid = typeMid(dtypeSize);
        long mask = typeMask(dtypeSize);

        for (int i = 0; i < pageN; i++) {
            long byteOff = offset + (long) i * Long.BYTES;
            rawLatents.set(LE_LONG, byteOff, (rawLatents.get(LE_LONG, byteOff) ^ mid) & mask);
        }

        for (int m = moments.length - 1; m >= 0; m--) {
            long moment = moments[m] & mask;
            for (int i = 0; i < pageN; i++) {
                long byteOff = offset + (long) i * Long.BYTES;
                long tmp = rawLatents.get(LE_LONG, byteOff);
                rawLatents.set(LE_LONG, byteOff, moment);
                moment = (moment + tmp) & mask;
            }
        }
    }

    private static long typeMid(int dtypeSize) {
        return switch (dtypeSize) {
            case 64 -> Long.MIN_VALUE;
            case 32 -> 0x80000000L;
            case 16 -> 0x8000L;
            default -> throw new VortexException(EncodingId.VORTEX_PCO,
                    "pco: invalid dtypeSize " + dtypeSize);
        };
    }

    private static long typeMask(int dtypeSize) {
        return switch (dtypeSize) {
            case 64 -> -1L;
            case 32 -> 0xFFFFFFFFL;
            case 16 -> 0xFFFFL;
            default -> throw new VortexException(EncodingId.VORTEX_PCO,
                    "pco: invalid dtypeSize " + dtypeSize);
        };
    }

    private static int dtypeSize(PType ptype) {
        return switch (ptype) {
            case I16, U16 -> 16;
            case I32, U32, F32 -> 32;
            case I64, U64, F64 -> 64;
            default -> throw new VortexException(EncodingId.VORTEX_PCO,
                    "pco: unsupported ptype " + ptype);
        };
    }

    private static int bitsToEncodeOffsetBits(int dtypeSize) {
        return switch (dtypeSize) {
            case 64 -> BITS_TO_ENCODE_OFFSET_BITS_64;
            case 32 -> BITS_TO_ENCODE_OFFSET_BITS_32;
            case 16 -> BITS_TO_ENCODE_OFFSET_BITS_16;
            default -> throw new VortexException(EncodingId.VORTEX_PCO,
                    "pco: invalid dtypeSize " + dtypeSize);
        };
    }

    private static Array toArray(DType dtype, long n, MemorySegment out) {
        PType ptype = ((DType.Primitive) dtype).ptype();
        return switch (ptype) {
            case I16, U16 -> new MaterializedShortArray(dtype, n, out);
            case I32, U32 -> new MaterializedIntArray(dtype, n, out);
            case F32 -> new MaterializedFloatArray(dtype, n, out);
            case I64, U64 -> new MaterializedLongArray(dtype, n, out);
            case F64 -> new MaterializedDoubleArray(dtype, n, out);
            default -> throw new VortexException(EncodingId.VORTEX_PCO,
                    "pco: unsupported ptype " + ptype);
        };
    }

    private static float intFloatFromLatentF32(long l) {
        long mid = 0x80000000L;
        boolean negative = (l < mid);
        long absInt = negative ? (0x7FFFFFFFL - l) : (l ^ 0x80000000L);
        long gpi = 1L << 24;
        float absFloat = (absInt < gpi) ? (float) absInt
                                 : Float.intBitsToFloat(0x4B800000 + (int) (absInt - gpi));
        return negative ? -absFloat : absFloat;
    }

    private static double intFloatFromLatentF64(long l) {
        boolean negative = (l >= 0);
        long absInt = negative ? (Long.MAX_VALUE - l) : (l ^ Long.MIN_VALUE);
        long gpi = 1L << 53;
        double absFloat = (absInt < gpi) ? (double) absInt
                                  : Double.longBitsToDouble(0x4340000000000000L + (absInt - gpi));
        return negative ? -absFloat : absFloat;
    }

    private static long toLatentOrderedF32(float f) {
        int bits = Float.floatToRawIntBits(f);
        if ((bits & 0x80000000) != 0) {
            return (~bits) & 0xFFFFFFFFL;
        } else {
            return (bits ^ 0x80000000) & 0xFFFFFFFFL;
        }
    }

    private static long toLatentOrderedF64(double d) {
        long bits = Double.doubleToRawLongBits(d);
        if ((bits & Long.MIN_VALUE) != 0) {
            return ~bits;
        } else {
            return bits ^ Long.MIN_VALUE;
        }
    }

    private static void combineFloatMult(PType ptype, long baseLatent, int chunkN,
            MemorySegment rawLatents, long multsOffset, MemorySegment rawAdjs) {
        if (ptype == PType.F32) {
            float baseFloat = Float.intBitsToFloat((int) fromLatentOrdered(baseLatent, PType.F32));
            for (int i = 0; i < chunkN; i++) {
                long off = multsOffset + (long) i * Long.BYTES;
                long mult = rawLatents.get(LE_LONG, off);
                long adj = rawAdjs.get(LE_LONG, (long) i * Long.BYTES);
                long unadjusted = toLatentOrderedF32(intFloatFromLatentF32(mult) * baseFloat);
                rawLatents.set(LE_LONG, off, (unadjusted + adj) & 0xFFFFFFFFL);
            }
        } else {
            double baseDouble = Double.longBitsToDouble(fromLatentOrdered(baseLatent, PType.F64));
            for (int i = 0; i < chunkN; i++) {
                long off = multsOffset + (long) i * Long.BYTES;
                long mult = rawLatents.get(LE_LONG, off);
                long adj = rawAdjs.get(LE_LONG, (long) i * Long.BYTES);
                long unadjusted = toLatentOrderedF64(intFloatFromLatentF64(mult) * baseDouble);
                rawLatents.set(LE_LONG, off, unadjusted + adj);
            }
        }
    }

    private static void combineFloatQuant(PType ptype, int k, int chunkN,
            MemorySegment rawLatents, long multsOffset, MemorySegment rawAdjs) {
        if (ptype == PType.F32) {
            long signCutoff = 0x80000000L >>> k;
            long lowestKBitsMax = (1L << k) - 1L;
            for (int i = 0; i < chunkN; i++) {
                long off = multsOffset + (long) i * Long.BYTES;
                long quantum = rawLatents.get(LE_LONG, off);
                long adj = rawAdjs.get(LE_LONG, (long) i * Long.BYTES);
                long lowestKBits = (quantum >= signCutoff) ? adj : (lowestKBitsMax - adj);
                rawLatents.set(LE_LONG, off, (quantum << k) + lowestKBits);
            }
        } else {
            long signCutoff = Long.MIN_VALUE >>> k;
            long lowestKBitsMax = (1L << k) - 1L;
            for (int i = 0; i < chunkN; i++) {
                long off = multsOffset + (long) i * Long.BYTES;
                long quantum = rawLatents.get(LE_LONG, off);
                long adj = rawAdjs.get(LE_LONG, (long) i * Long.BYTES);
                boolean isPos = Long.compareUnsigned(quantum, signCutoff) >= 0;
                long lowestKBits = isPos ? adj : (lowestKBitsMax - adj);
                rawLatents.set(LE_LONG, off, (quantum << k) + lowestKBits);
            }
        }
    }

    private static void combineDict(long[] dict, int chunkN,
            MemorySegment rawLatents, long offset) {
        for (int i = 0; i < chunkN; i++) {
            long off = offset + (long) i * Long.BYTES;
            int idx = (int) rawLatents.get(LE_LONG, off);
            if (idx < 0 || idx >= dict.length) {
                throw new VortexException(EncodingId.VORTEX_PCO,
                        "pco dict index " + idx + " out of range [0, " + dict.length + ")");
            }
            rawLatents.set(LE_LONG, off, dict[idx]);
        }
    }

    private static PcoChunkMeta readChunkMeta(MemorySegment buf, int dtypeSize) {
        LeBitReader r = new LeBitReader(buf);

        int modeNibble = (int) r.readBits(4);
        long base = 0L;
        int quantizeK = 0;
        long[] dict = null;
        if (modeNibble == 1 || modeNibble == 2) {
            base = r.readBits(dtypeSize);
        } else if (modeNibble == 3) {
            quantizeK = (int) r.readBits(8);
        } else if (modeNibble == 4) {
            int nUnique = (int) r.readBits(25);
            if (nUnique > 1 << 16) {
                throw new VortexException(EncodingId.VORTEX_PCO,
                        "pco dict nUnique " + nUnique + " exceeds max 65536");
            }
            r.alignToByte();
            dict = new long[nUnique];
            for (int i = 0; i < nUnique; i++) {
                dict[i] = r.readBits(dtypeSize);
            }
        } else if (modeNibble != 0) {
            throw new VortexException(EncodingId.VORTEX_PCO,
                    "pco mode " + modeNibble + " not yet implemented "
                            + "(Classic=0, IntMult=1, FloatMult=2, FloatQuant=3, Dict=4 supported)");
        }

        int deltaVariant = (int) r.readBits(4);
        int deltaOrder = 0;
        boolean secondaryUsesDelta = false;
        int windowNLog = 0;
        int stateNLog = 0;
        int conv1Quantization = 0;
        long conv1Bias = 0L;
        long[] conv1Weights = new long[0];
        if (deltaVariant == 1) {
            deltaOrder = (int) r.readBits(3);
            secondaryUsesDelta = r.readBits(1) != 0;
        } else if (deltaVariant == 2) {
            windowNLog = 1 + (int) r.readBits(5);
            if (windowNLog > 24) {
                throw new VortexException(EncodingId.VORTEX_PCO,
                        "pco lookback windowNLog " + windowNLog + " exceeds max 24");
            }
            stateNLog = (int) r.readBits(4);
            secondaryUsesDelta = r.readBits(1) != 0;
        } else if (deltaVariant == 3) {
            if (dtypeSize == 64) {
                throw new VortexException(EncodingId.VORTEX_PCO,
                        "pco Conv1 delta not supported for 64-bit dtypes (I64/U64/F64)");
            }
            conv1Quantization = (int) r.readBits(5);
            conv1Bias = r.readBits(64) ^ Long.MIN_VALUE;
            int conv1Order = 1 + (int) r.readBits(5);
            conv1Weights = new long[conv1Order];
            for (int i = 0; i < conv1Order; i++) {
                conv1Weights[i] = (int) (r.readBits(32) ^ 0x80000000L);
            }
        } else if (deltaVariant != 0) {
            throw new VortexException(EncodingId.VORTEX_PCO,
                    "pco delta variant " + deltaVariant + " not yet implemented "
                            + "(NoOp=0, Consecutive=1, Lookback=2, Conv1=3 supported)");
        }

        int deltaAnsSizeLog = 0;
        PcoBin[] deltaBins = new PcoBin[0];
        if (deltaVariant == 2) {
            deltaAnsSizeLog = (int) r.readBits(4);
            int nDeltaBins = (int) r.readBits(15);
            deltaBins = readBins(r, nDeltaBins, deltaAnsSizeLog, 32);
        }

        int primaryDtypeSize = (modeNibble == 4) ? 32 : dtypeSize;
        int ansSizeLog = (int) r.readBits(4);
        int nBins = (int) r.readBits(15);
        PcoBin[] bins = readBins(r, nBins, ansSizeLog, primaryDtypeSize);

        int secondaryAnsSizeLog = 0;
        PcoBin[] secondaryBins = new PcoBin[0];
        if (modeNibble == 1 || modeNibble == 2 || modeNibble == 3) {
            secondaryAnsSizeLog = (int) r.readBits(4);
            int nSecondaryBins = (int) r.readBits(15);
            secondaryBins = readBins(r, nSecondaryBins, secondaryAnsSizeLog, dtypeSize);
        }
        r.alignToByte();

        return new PcoChunkMeta(modeNibble, base, quantizeK, dict,
                deltaVariant, deltaOrder, secondaryUsesDelta,
                windowNLog, stateNLog, deltaAnsSizeLog, deltaBins,
                conv1Quantization, conv1Bias, conv1Weights,
                ansSizeLog, bins, secondaryAnsSizeLog, secondaryBins);
    }

    private static PcoBin[] readBins(LeBitReader r, int nBins, int ansSizeLog, int dtypeSize) {
        PcoBin[] bins = new PcoBin[nBins];
        int offsetBitsWidth = bitsToEncodeOffsetBits(dtypeSize);
        for (int b = 0; b < nBins; b++) {
            int weight = (int) r.readBits(ansSizeLog) + 1;
            long lower = r.readBits(dtypeSize);
            int offsetBits = (int) r.readBits(offsetBitsWidth);
            if (offsetBits > 64) {
                // offsetBitsWidth is 5/6/7 bits wide (max value 31/63/127), wider than the
                // 64-bit latent an offset can ever legally span; a page later reads this many
                // bits per value via LeBitReader#readBits(int), whose own <=64 contract this
                // would otherwise violate.
                throw new VortexException(EncodingId.VORTEX_PCO,
                        "pco bin offsetBits " + offsetBits + " exceeds max 64");
            }
            bins[b] = new PcoBin(weight, lower, offsetBits);
        }
        return bins;
    }

    private static ProtoPcoMetadata parseMeta(DecodeContext ctx) {
        MemorySegment raw = ctx.metadata();
        if (raw == null) {
            throw new VortexException(EncodingId.VORTEX_PCO, "missing ProtoPcoMetadata");
        }
        try {
            return ProtoPcoMetadata.decode(raw, 0, raw.byteSize());
        } catch (IOException e) {
            throw new VortexException(EncodingId.VORTEX_PCO,
                    "invalid ProtoPcoMetadata: " + e.getMessage());
        }
    }

    private static void validateHeader(ProtoPcoMetadata meta) {
        byte[] header = meta.header();
        if (header.length < 2) {
            throw new VortexException(EncodingId.VORTEX_PCO,
                    "pco header too short: " + header.length + " bytes");
        }
        if (header[0] != PCO_FORMAT_MAJOR || header[1] != PCO_FORMAT_MINOR) {
            throw new VortexException(EncodingId.VORTEX_PCO,
                    String.format("unsupported pco format version %02x.%02x (expected %02x.%02x)",
                            header[0] & 0xFF, header[1] & 0xFF,
                            PCO_FORMAT_MAJOR & 0xFF, PCO_FORMAT_MINOR & 0xFF));
        }
    }

    @SuppressWarnings("java:S6218") // internal data carrier; record components are arrays of immutable primitives or refs that flow through pipelines without ever being compared.
    private record PcoChunkMeta(int mode, long base, int quantizeK, long[] dict,
                                int deltaVariant, int deltaOrder, boolean secondaryUsesDelta,
                                int windowNLog, int stateNLog, int deltaAnsSizeLog, PcoBin[] deltaBins,
                                int conv1Quantization, long conv1Bias, long[] conv1Weights,
                                int ansSizeLog, PcoBin[] bins,
                                int secondaryAnsSizeLog, PcoBin[] secondaryBins) {
    }
}
