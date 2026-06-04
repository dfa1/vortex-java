package io.github.dfa1.vortex.encoding;

import com.google.protobuf.InvalidProtocolBufferException;
import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.BoolArray;
import io.github.dfa1.vortex.core.array.DoubleArray;
import io.github.dfa1.vortex.core.array.FloatArray;
import io.github.dfa1.vortex.core.array.IntArray;
import io.github.dfa1.vortex.core.array.LongArray;
import io.github.dfa1.vortex.core.array.MaskedArray;
import io.github.dfa1.vortex.core.array.ShortArray;
import io.github.dfa1.vortex.proto.EncodingProtos;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/// Decoder for {@code vortex.pco} (pcodec numerical compression).
///
/// <p>Wire format (Vortex layer):
/// <ul>
///   <li>Metadata: {@code PcoMetadata} — 2-byte header (major.minor) + repeated {@code PcoChunkInfo}</li>
///   <li>Buffers: {@code chunk_metas[0..N-1]} then {@code pages[0..M-1]}</li>
///   <li>Optional child[0]: validity bitmap (Bool array); pco stores only valid values</li>
/// </ul>
///
/// <p>Wire format (pcodec layer, per chunk/page):
/// <ul>
///   <li>Chunk meta: [4b mode][extra mode bits][4b delta][extra delta bits]
///       [optional delta latent var (U32) for Lookback]
///       [per-latent: 4b ans_size_log, 15b n_bins, per-bin {weight-1, lower, offset_bits}]
///       [0–7b alignment]</li>
///   <li>Classic/Dict page: [deltaOrder×dtypeSize b moments, 4×ansSizeLog b ANS states]
///       [0–7b alignment] [per 256-batch: ANS bits, offset bits]</li>
///   <li>IntMult/FloatMult/FloatQuant page: [primary header][secondary header][0–7b alignment]
///       [per 256-batch: primary ANS+offsets, secondary ANS+offsets]</li>
///   <li>Lookback page: [delta ANS states][stateN×dtypeSize moments][primary ANS states]
///       [0–7b alignment] [per 256-batch: delta ANS+offsets, primary ANS+offsets]</li>
///   <li>All bit packing little-endian (LSB first)</li>
/// </ul>
///
/// <p>Supported: Classic, IntMult, FloatMult, FloatQuant, Dict modes;
/// None+Consecutive+Lookback+Conv1 delta; nullable (validity child[0]);
/// all integer/float ptypes except F16 (Conv1 additionally excludes 64-bit dtypes).
public final class PcoEncoding implements Encoding {

    static final byte PCO_FORMAT_MAJOR = 0x04;
    static final byte PCO_FORMAT_MINOR = 0x01;

    // bits needed to encode offset_bits field per latent type
    static final int BITS_TO_ENCODE_OFFSET_BITS_64 = 7; // log2(64) + 1
    static final int BITS_TO_ENCODE_OFFSET_BITS_32 = 6; // log2(32) + 1
    static final int BITS_TO_ENCODE_OFFSET_BITS_16 = 5; // log2(16) + 1

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_PCO;
    }

    @Override
    public EncodeResult encode(DType dtype, Object data) {
        return Encoder.encode(dtype, data);
    }

    @Override
    public Array decode(DecodeContext ctx) {
        return Decoder.decode(ctx);
    }

    private static final class Encoder {

        static EncodeResult encode(DType dtype, Object data) {
            throw new VortexException(EncodingId.VORTEX_PCO,
                    "encode not implemented — pco encode port pending");
        }
    }

    static final class Decoder {

        private static final ValueLayout.OfLong LE_LONG =
                ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

        static Array decode(DecodeContext ctx) {
            EncodingProtos.PcoMetadata meta = parseMeta(ctx);
            validateHeader(meta);

            DType dtype = ctx.dtype();
            if (!(dtype instanceof DType.Primitive dt)) {
                throw new VortexException(EncodingId.VORTEX_PCO,
                        "pco decode requires Primitive dtype, got: " + dtype);
            }
            PType ptype = dt.ptype();
            int dtypeSize = dtypeSize(ptype);

            long n = ctx.rowCount();

            // Nullable: child[0] is a validity bitmap; pco encodes only valid values.
            BoolArray validity = null;
            long validCount = n;
            if (ctx.node().children().length > 0) {
                Array validityArr = decodeChild(ctx, 0, new DType.Bool(false), n);
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

            // decodePage always writes U64 latents (8 bytes per element).
            MemorySegment rawLatents = ctx.arena().allocate(validCount * Long.BYTES);

            int nChunks = meta.getChunksCount();
            int bufIdx = 0;
            long rawByteOffset = 0L;

            long[] batchLowers1 = new long[PcoTansDecoder.BATCH_N];
            int[] batchOffsetBits1 = new int[PcoTansDecoder.BATCH_N];
            long[] batchLowers2 = new long[PcoTansDecoder.BATCH_N];
            int[] batchOffsetBits2 = new int[PcoTansDecoder.BATCH_N];

            for (int c = 0; c < nChunks; c++) {
                EncodingProtos.PcoChunkInfo chunkInfo = meta.getChunks(c);
                MemorySegment chunkMetaBuf = ctx.buffer(bufIdx++);
                PcoChunkMeta chunkMeta = readChunkMeta(chunkMetaBuf, dtypeSize);

                int mode = chunkMeta.mode();
                int deltaVariant = chunkMeta.deltaVariant();
                long chunkStartOffset = rawByteOffset;

                // Compute total values in this chunk.
                int chunkN = 0;
                for (int p = 0; p < chunkInfo.getPagesCount(); p++) {
                    chunkN += chunkInfo.getPages(p).getNValues();
                }

                if (deltaVariant == 3) {
                    // Conv1 delta: 64-bit check is in readChunkMeta; 64-bit case never reaches here.
                    PcoTansDecoder primaryTans = PcoTansDecoder.build(
                            chunkMeta.ansSizeLog(), chunkMeta.bins());
                    for (int p = 0; p < chunkInfo.getPagesCount(); p++) {
                        int pageN = chunkInfo.getPages(p).getNValues();
                        MemorySegment pageBuf = ctx.buffer(bufIdx++);
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
                    // Lookback delta: currently only Classic mode supported.
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
                    for (int p = 0; p < chunkInfo.getPagesCount(); p++) {
                        int pageN = chunkInfo.getPages(p).getNValues();
                        MemorySegment pageBuf = ctx.buffer(bufIdx++);
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
                    // Single-latent var: Classic or Dict.
                    int primaryDtypeSize = (mode == 4) ? 32 : dtypeSize;
                    PcoTansDecoder tans = PcoTansDecoder.build(chunkMeta.ansSizeLog(), chunkMeta.bins());
                    for (int p = 0; p < chunkInfo.getPagesCount(); p++) {
                        int pageN = chunkInfo.getPages(p).getNValues();
                        MemorySegment pageBuf = ctx.buffer(bufIdx++);
                        rawByteOffset = decodeClassicPage(tans, chunkMeta.ansSizeLog(),
                                chunkMeta.deltaOrder(), primaryDtypeSize,
                                pageBuf, pageN, rawLatents, rawByteOffset,
                                batchLowers1, batchOffsetBits1);
                    }
                    if (mode == 4) {
                        combineDict(chunkMeta.dict(), chunkN, rawLatents, chunkStartOffset);
                    }
                } else {
                    // Two-latent var: IntMult (1), FloatMult (2), FloatQuant (3).
                    long base = chunkMeta.base();
                    int primaryAnsSizeLog = chunkMeta.ansSizeLog();
                    int secondaryAnsSizeLog = chunkMeta.secondaryAnsSizeLog();
                    PcoTansDecoder primaryTans = PcoTansDecoder.build(primaryAnsSizeLog, chunkMeta.bins());
                    PcoTansDecoder secondaryTans = PcoTansDecoder.build(secondaryAnsSizeLog, chunkMeta.secondaryBins());
                    int deltaOrder = chunkMeta.deltaOrder();
                    int secondaryDeltaOrder = chunkMeta.secondaryUsesDelta() ? deltaOrder : 0;

                    MemorySegment rawAdjs = ctx.arena().allocate((long) chunkN * Long.BYTES);
                    long adjByteOffset = 0L;
                    for (int p = 0; p < chunkInfo.getPagesCount(); p++) {
                        int pageN = chunkInfo.getPages(p).getNValues();
                        MemorySegment pageBuf = ctx.buffer(bufIdx++);
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

            // Convert raw U-latents → typed values; resize from 8-byte slots to elemBytes.
            int elemBytes = ptype.byteSize();
            MemorySegment compactOut = ctx.arena().allocate(validCount * elemBytes);
            for (long i = 0; i < validCount; i++) {
                long latent = rawLatents.get(LE_LONG, i * Long.BYTES);
                PTypeIO.set(compactOut, i * elemBytes, ptype, fromLatentOrdered(latent, ptype));
            }

            if (validity == null) {
                return toArray(dtype, n, compactOut);
            }

            // Scatter validCount compact values into full-length output; null slots stay zeroed.
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

        private static Array decodeChild(DecodeContext parent, int idx, DType dtype, long rowCount) {
            ArrayNode childNode = parent.node().children()[idx];
            DecodeContext childCtx = new DecodeContext(
                    childNode, dtype, rowCount, parent.segmentBuffers(), parent.registry(), parent.arena());
            return parent.registry().decode(childCtx);
        }

        /// Decode one Classic-mode page into rawLatents and return the updated byte offset.
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

        /// Decode one IntMult/FloatMult/FloatQuant-mode page: primary latents into rawMults,
        /// secondary into rawAdjs. Page body interleaved per 256-value batch.
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
                int primaryPreDeltaN = Math.min(batchN, Math.max(0, nRemaining - deltaOrder));
                int secondaryPreDeltaN = Math.min(batchN, Math.max(0, nRemaining - secondaryDeltaOrder));

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

        /// Decode one Lookback-delta page into rawLatents. Per-page window seeded from page header.
        ///
        /// Page layout: [delta ANS states (U32)][stateN×dtypeSize initial values][primary ANS states]
        /// [0–7b align] [per 256-batch: delta ANS+offsets (lookback idx), primary ANS+offsets (residuals)]
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

            // Delta page header: 0 moments (NoOp delta-of-delta) + 4 × deltaAnsSizeLog ANS states.
            int[] deltaStateIdxs = new int[PcoTansDecoder.ANS_INTERLEAVING];
            for (int i = 0; i < PcoTansDecoder.ANS_INTERLEAVING; i++) {
                deltaStateIdxs[i] = (int) pageReader.readBits(deltaAnsSizeLog);
            }

            // Primary page header: stateN × dtypeSize initial values + 4 × primaryAnsSizeLog ANS states.
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

            // Toggle-center undo (XOR mid) on residuals.
            for (int i = 0; i < decodeN; i++) {
                long off = (long) i * Long.BYTES;
                rawResiduals.set(LE_LONG, off, (rawResiduals.get(LE_LONG, off) ^ mid) & mask);
            }

            // Write initial state to output.
            for (int i = 0; i < stateN; i++) {
                rawLatents.set(LE_LONG, latentsOffset + (long) i * Long.BYTES, initialState[i] & mask);
            }

            if (stateN > windowN) {
                throw new VortexException(EncodingId.VORTEX_PCO,
                        "pco corrupt lookback: stateN " + stateN + " exceeds windowN " + windowN);
            }
            // Lookback reconstruction: window[0..windowN] seeded from initialState.
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

        /// Decode one Conv1-delta page into rawLatents and return the updated byte offset.
        ///
        /// Page layout: [order×dtypeSize initial state][4×ansSizeLog ANS states][0–7b align]
        /// [per 256-batch: ANS bits + offset bits for (pageN-order) residuals]
        ///
        /// Port of {@code conv1::decode_in_place} from pcodec.
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

            // Write initial state directly into rawLatents.
            for (int i = 0; i < order; i++) {
                rawLatents.set(LE_LONG, latentsOffset + (long) i * Long.BYTES, state[i]);
            }

            // Decode residuals directly into rawLatents[latentsOffset + order*8..].
            tans.decodePage(pageReader, stateIdxs, decodeN, rawLatents,
                    latentsOffset + (long) order * Long.BYTES,
                    batchLowers, batchOffsetBits);

            // Toggle-center decoded residuals in-place.
            for (int i = order; i < pageN; i++) {
                long off = latentsOffset + (long) i * Long.BYTES;
                rawLatents.set(LE_LONG, off, (rawLatents.get(LE_LONG, off) ^ mid) & mask);
            }

            // Reconstruct in-place: rawLatents[i] += predict(rawLatents[i-order..i]).
            for (int i = order; i < pageN; i++) {
                long pred = predictConv1(rawLatents, latentsOffset, i, order,
                        weights, bias, quantization, mask, dtypeSize);
                long off = latentsOffset + (long) i * Long.BYTES;
                rawLatents.set(LE_LONG, off, (rawLatents.get(LE_LONG, off) + pred) & mask);
            }

            return latentsOffset + (long) pageN * Long.BYTES;
        }

        /// Compute Conv1 prediction for position {@code pos} in {@code seg[baseOff..]}.
        ///
        /// Accumulator is i64 for dtypeSize=32, or i32 (bias/weights truncated) for dtypeSize=16.
        /// {@code mask} is pre-computed by the caller to avoid a switch per call.
        private static long predictConv1(MemorySegment seg, long baseOff, int pos, int order,
                long[] weights, long bias, int quantization, long mask, int dtypeSize) {
            // For dtypeSize=16, accumulator is i32 (truncate bias/weights from i64).
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

        /// Inverse of pcodec {@code to_latent_ordered}: maps raw U-latent back to typed bits.
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
                default -> latent; // U16, U32, U64: identity
            };
        }

        /// Undo pcodec consecutive delta encoding for one page.
        ///
        /// toggle_center (XOR dtype_mid) + cumulative sum restores original U-latents.
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
                case I16, U16 -> new ShortArray(dtype, n, out, ArrayStats.empty());
                case I32, U32 -> new IntArray(dtype, n, out, ArrayStats.empty());
                case F32 -> new FloatArray(dtype, n, out, ArrayStats.empty());
                case I64, U64 -> new LongArray(dtype, n, out, ArrayStats.empty());
                case F64 -> new DoubleArray(dtype, n, out, ArrayStats.empty());
                default -> throw new VortexException(EncodingId.VORTEX_PCO,
                        "pco: unsupported ptype " + ptype);
            };
        }

        /// Int-float latent → integer-valued float for F32.
        ///
        /// mid = 2^31; negative when l &lt; mid; GPI = 2^24 (f32 mantissa bits).
        private static float intFloatFromLatentF32(long l) {
            long mid = 0x80000000L;
            boolean negative = (l < mid);
            long absInt = negative ? (0x7FFFFFFFL - l) : (l ^ 0x80000000L);
            long gpi = 1L << 24;
            float absFloat = (absInt < gpi) ? (float) absInt
                    : Float.intBitsToFloat(0x4B800000 + (int) (absInt - gpi));
            return negative ? -absFloat : absFloat;
        }

        /// Int-float latent → integer-valued float for F64.
        ///
        /// mid = 2^63; l &lt; 2^63 unsigned (l ≥ 0 signed) → negative float; GPI = 2^53.
        private static double intFloatFromLatentF64(long l) {
            boolean negative = (l >= 0);
            long absInt = negative ? (Long.MAX_VALUE - l) : (l ^ Long.MIN_VALUE);
            long gpi = 1L << 53;
            double absFloat = (absInt < gpi) ? (double) absInt
                    : Double.longBitsToDouble(0x4340000000000000L + (absInt - gpi));
            return negative ? -absFloat : absFloat;
        }

        /// Inverse of {@link #fromLatentOrdered} for F32: float → ordered latent.
        private static long toLatentOrderedF32(float f) {
            int bits = Float.floatToRawIntBits(f);
            if ((bits & 0x80000000) != 0) {
                return (~bits) & 0xFFFFFFFFL;
            } else {
                return (bits ^ 0x80000000) & 0xFFFFFFFFL;
            }
        }

        /// Inverse of {@link #fromLatentOrdered} for F64: float → ordered latent.
        private static long toLatentOrderedF64(double d) {
            long bits = Double.doubleToRawLongBits(d);
            if ((bits & Long.MIN_VALUE) != 0) {
                return ~bits;
            } else {
                return bits ^ Long.MIN_VALUE;
            }
        }

        /// FloatMult combine: rawLatents[i] = toLatentOrdered(intFloatFromLatent(mult) * baseFloat) + adj.
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

        /// FloatQuant combine: num_latent = (quantum << k) + lowest_k_bits.
        ///
        /// sign_cutoff = MID >> k; is_positive = quantum >= sign_cutoff (unsigned).
        /// lowest_k_bits = adj if positive, else (lowestKBitsMax - adj).
        /// Port of pcodec float_quant.rs join_latents.
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
                // F64: unsigned comparison via Long.compareUnsigned.
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

        /// Dict combine: rawLatents[i] = dict[index], where dict entries are ordered latents.
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

            // Mode nibble + mode-specific extra bits.
            int modeNibble = (int) r.readBits(4);
            long base = 0L;
            int quantizeK = 0;
            long[] dict = null;
            if (modeNibble == 1 || modeNibble == 2) {
                base = r.readBits(dtypeSize);
            } else if (modeNibble == 3) {
                quantizeK = (int) r.readBits(8); // BITS_TO_ENCODE_QUANTIZE_K
            } else if (modeNibble == 4) {
                int nUnique = (int) r.readBits(25); // BITS_TO_ENCODE_DICT_LEN
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

            // Delta encoding variant + extra bits.
            int deltaVariant = (int) r.readBits(4);
            int deltaOrder = 0;
            boolean secondaryUsesDelta = false;
            int windowNLog = 0;
            int stateNLog = 0;
            int conv1Quantization = 0;
            long conv1Bias = 0L;
            long[] conv1Weights = new long[0];
            if (deltaVariant == 0) {
                // NoOp
            } else if (deltaVariant == 1) {
                deltaOrder = (int) r.readBits(3); // BITS_TO_ENCODE_DELTA_ENCODING_ORDER
                secondaryUsesDelta = r.readBits(1) != 0;
            } else if (deltaVariant == 2) {
                windowNLog = 1 + (int) r.readBits(5); // BITS_TO_ENCODE_DELTA_LOOKBACK_WINDOW_N_LOG
                if (windowNLog > 24) {
                    throw new VortexException(EncodingId.VORTEX_PCO,
                            "pco lookback windowNLog " + windowNLog + " exceeds max 24");
                }
                stateNLog = (int) r.readBits(4);       // BITS_TO_ENCODE_DELTA_LOOKBACK_STATE_N_LOG
                secondaryUsesDelta = r.readBits(1) != 0;
            } else if (deltaVariant == 3) {
                // Conv1: 64-bit dtypes unsupported — fail before reading Conv1 fields.
                if (dtypeSize == 64) {
                    throw new VortexException(EncodingId.VORTEX_PCO,
                            "pco Conv1 delta not supported for 64-bit dtypes (I64/U64/F64)");
                }
                // Conv1: quantization(5b) + bias(64b latent-ordered i64) + (order-1)(5b) + weights(order×32b)
                conv1Quantization = (int) r.readBits(5);
                conv1Bias = r.readBits(64) ^ Long.MIN_VALUE; // from_latent_ordered for i64
                int conv1Order = 1 + (int) r.readBits(5);
                conv1Weights = new long[conv1Order];
                for (int i = 0; i < conv1Order; i++) {
                    // from_latent_ordered for i32: XOR with 0x80000000, then sign-extend to i64
                    conv1Weights[i] = (int) (r.readBits(32) ^ 0x80000000L);
                }
            } else {
                throw new VortexException(EncodingId.VORTEX_PCO,
                        "pco delta variant " + deltaVariant + " not yet implemented "
                        + "(NoOp=0, Consecutive=1, Lookback=2, Conv1=3 supported)");
            }

            // Delta latent var (U32 lookback indices) — only present for Lookback delta.
            int deltaAnsSizeLog = 0;
            PcoBin[] deltaBins = new PcoBin[0];
            if (deltaVariant == 2) {
                deltaAnsSizeLog = (int) r.readBits(4);
                int nDeltaBins = (int) r.readBits(15);
                deltaBins = readBins(r, nDeltaBins, deltaAnsSizeLog, 32); // U32 indices
            }

            // Primary latent var. Dict uses U32 indices (32-bit); all others use dtypeSize.
            int primaryDtypeSize = (modeNibble == 4) ? 32 : dtypeSize;
            int ansSizeLog = (int) r.readBits(4);
            int nBins = (int) r.readBits(15);
            PcoBin[] bins = readBins(r, nBins, ansSizeLog, primaryDtypeSize);

            // Secondary latent var — IntMult (1), FloatMult (2), FloatQuant (3) only.
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
                bins[b] = new PcoBin(weight, lower, offsetBits);
            }
            return bins;
        }

        private static EncodingProtos.PcoMetadata parseMeta(DecodeContext ctx) {
            ByteBuffer raw = ctx.metadata();
            if (raw == null) {
                throw new VortexException(EncodingId.VORTEX_PCO, "missing PcoMetadata");
            }
            try {
                return EncodingProtos.PcoMetadata.parseFrom(raw.duplicate());
            } catch (InvalidProtocolBufferException e) {
                throw new VortexException(EncodingId.VORTEX_PCO,
                        "invalid PcoMetadata: " + e.getMessage());
            }
        }

        private static void validateHeader(EncodingProtos.PcoMetadata meta) {
            byte[] header = meta.getHeader().toByteArray();
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
    }

    private record PcoChunkMeta(int mode, long base, int quantizeK, long[] dict,
                                 int deltaVariant, int deltaOrder, boolean secondaryUsesDelta,
                                 int windowNLog, int stateNLog, int deltaAnsSizeLog, PcoBin[] deltaBins,
                                 int conv1Quantization, long conv1Bias, long[] conv1Weights,
                                 int ansSizeLog, PcoBin[] bins,
                                 int secondaryAnsSizeLog, PcoBin[] secondaryBins) {
    }
}
