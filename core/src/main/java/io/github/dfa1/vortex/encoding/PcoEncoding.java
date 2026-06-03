package io.github.dfa1.vortex.encoding;

import com.google.protobuf.InvalidProtocolBufferException;
import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.DoubleArray;
import io.github.dfa1.vortex.core.array.FloatArray;
import io.github.dfa1.vortex.core.array.IntArray;
import io.github.dfa1.vortex.core.array.LongArray;
import io.github.dfa1.vortex.core.array.ShortArray;
import io.github.dfa1.vortex.proto.EncodingProtos;

import java.lang.foreign.MemorySegment;
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
///       [per-latent: 4b ans_size_log, 15b n_bins, per-bin {weight-1, lower, offset_bits}]
///       [0–7b alignment]</li>
///   <li>Classic page: [primary: deltaOrder×dtypeSize b moments, 4×ansSizeLog b ANS states]
///       [0–7b alignment] [per 256-batch: ANS bits, offset bits]</li>
///   <li>IntMult page: [primary header][secondary header][0–7b alignment]
///       [per 256-batch: primary ANS+offsets, secondary ANS+offsets]</li>
///   <li>All bit packing little-endian (LSB first)</li>
/// </ul>
///
/// <p>Supported: Classic and IntMult modes, None+Consecutive delta (order ≤ 7), non-null,
/// all integer/float ptypes except F16.  Other modes (FloatMult, FloatQuant, Dict) throw
/// "not implemented".
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

            // decodePage always writes U64 latents (8 bytes per element).
            MemorySegment rawLatents = ctx.arena().allocate(n * Long.BYTES);

            int nChunks = meta.getChunksCount();
            int bufIdx = 0;
            long rawByteOffset = 0L;

            for (int c = 0; c < nChunks; c++) {
                EncodingProtos.PcoChunkInfo chunkInfo = meta.getChunks(c);
                MemorySegment chunkMetaBuf = ctx.buffer(bufIdx++);

                PcoChunkMeta chunkMeta = readChunkMeta(chunkMetaBuf, dtypeSize);

                if (chunkMeta.mode() == 0) {
                    // Classic mode: one latent variable, decode directly into rawLatents.
                    PcoTansDecoder tans = PcoTansDecoder.build(chunkMeta.ansSizeLog(), chunkMeta.bins());
                    int deltaOrder = chunkMeta.deltaOrder();
                    int ansSizeLog = chunkMeta.ansSizeLog();
                    int nPages = chunkInfo.getPagesCount();
                    for (int p = 0; p < nPages; p++) {
                        int pageN = chunkInfo.getPages(p).getNValues();
                        MemorySegment pageBuf = ctx.buffer(bufIdx++);
                        rawByteOffset = decodeClassicPage(tans, ansSizeLog, deltaOrder, dtypeSize,
                                pageBuf, pageN, rawLatents, rawByteOffset);
                    }
                } else {
                    // IntMult mode: two latent variables (mult, adj); num = base * mult + adj.
                    long base = chunkMeta.base();
                    int primaryAnsSizeLog = chunkMeta.ansSizeLog();
                    int secondaryAnsSizeLog = chunkMeta.secondaryAnsSizeLog();
                    PcoTansDecoder primaryTans = PcoTansDecoder.build(primaryAnsSizeLog, chunkMeta.bins());
                    PcoTansDecoder secondaryTans = PcoTansDecoder.build(secondaryAnsSizeLog, chunkMeta.secondaryBins());
                    int deltaOrder = chunkMeta.deltaOrder();
                    int secondaryDeltaOrder = chunkMeta.secondaryUsesDelta() ? deltaOrder : 0;

                    // Compute total values in this chunk to allocate adj buffer.
                    int chunkN = 0;
                    for (int p = 0; p < chunkInfo.getPagesCount(); p++) {
                        chunkN += chunkInfo.getPages(p).getNValues();
                    }
                    MemorySegment rawAdjs = ctx.arena().allocate((long) chunkN * Long.BYTES);

                    long chunkMultsOffset = rawByteOffset;
                    long adjByteOffset = 0L;
                    int nPages = chunkInfo.getPagesCount();
                    for (int p = 0; p < nPages; p++) {
                        int pageN = chunkInfo.getPages(p).getNValues();
                        MemorySegment pageBuf = ctx.buffer(bufIdx++);
                        decodeIntMultPage(primaryTans, primaryAnsSizeLog, deltaOrder,
                                secondaryTans, secondaryAnsSizeLog, secondaryDeltaOrder,
                                dtypeSize, pageBuf, pageN,
                                rawLatents, rawByteOffset,
                                rawAdjs, adjByteOffset);
                        rawByteOffset += (long) pageN * Long.BYTES;
                        adjByteOffset += (long) pageN * Long.BYTES;
                    }

                    // Combine: rawLatents[i] = (mult[i] * base + adj[i]) & mask
                    long mask = typeMask(dtypeSize);
                    for (int i = 0; i < chunkN; i++) {
                        long off = chunkMultsOffset + (long) i * Long.BYTES;
                        long mult = rawLatents.get(LE_LONG, off);
                        long adj = rawAdjs.get(LE_LONG, (long) i * Long.BYTES);
                        rawLatents.set(LE_LONG, off, (mult * base + adj) & mask);
                    }
                }
            }

            // Convert raw U-latents → typed values; resize from 8-byte slots to elemBytes.
            int elemBytes = ptype.byteSize();
            MemorySegment out = ctx.arena().allocate(n * elemBytes);
            for (long i = 0; i < n; i++) {
                long latent = rawLatents.get(LE_LONG, i * Long.BYTES);
                PTypeIO.set(out, i * elemBytes, ptype, fromLatentOrdered(latent, ptype));
            }

            return toArray(dtype, n, out);
        }

        /// Decode one Classic-mode page into rawLatents and return the updated byte offset.
        private static long decodeClassicPage(PcoTansDecoder tans, int ansSizeLog, int deltaOrder,
                int dtypeSize, MemorySegment pageBuf, int pageN,
                MemorySegment rawLatents, long rawByteOffset) {
            LeBitReader pageReader = new LeBitReader(pageBuf);

            long[] moments = new long[deltaOrder];
            for (int m = 0; m < deltaOrder; m++) {
                moments[m] = pageReader.readBits(dtypeSize);
            }

            int[] stateIdxs = new int[PcoTansDecoder.ANS_INTERLEAVING];
            for (int i = 0; i < PcoTansDecoder.ANS_INTERLEAVING; i++) {
                stateIdxs[i] = (int) pageReader.readBits(ansSizeLog);
            }
            pageReader.alignToByte();

            int decodedN = pageN - deltaOrder;
            tans.decodePage(pageReader, stateIdxs, decodedN, rawLatents, rawByteOffset);

            if (deltaOrder > 0) {
                applyConsecutiveDelta(rawLatents, rawByteOffset, pageN, moments, dtypeSize);
            }

            return rawByteOffset + (long) pageN * Long.BYTES;
        }

        /// Decode one IntMult-mode page: primary latents into rawMults, secondary into rawAdjs.
        ///
        /// Page body is interleaved per 256-value batch:
        /// [primary ANS+offsets for preDeltaN] [secondary ANS+offsets for secondaryPreDeltaN] …
        private static void decodeIntMultPage(
                PcoTansDecoder primaryTans, int primaryAnsSizeLog, int deltaOrder,
                PcoTansDecoder secondaryTans, int secondaryAnsSizeLog, int secondaryDeltaOrder,
                int dtypeSize, MemorySegment pageBuf, int pageN,
                MemorySegment rawMults, long multsOffset,
                MemorySegment rawAdjs, long adjsOffset) {
            LeBitReader pageReader = new LeBitReader(pageBuf);

            // Primary page header: moments + ANS states.
            long[] primaryMoments = new long[deltaOrder];
            for (int m = 0; m < deltaOrder; m++) {
                primaryMoments[m] = pageReader.readBits(dtypeSize);
            }
            int[] primaryStateIdxs = new int[PcoTansDecoder.ANS_INTERLEAVING];
            for (int i = 0; i < PcoTansDecoder.ANS_INTERLEAVING; i++) {
                primaryStateIdxs[i] = (int) pageReader.readBits(primaryAnsSizeLog);
            }

            // Secondary page header: moments + ANS states.
            long[] secondaryMoments = new long[secondaryDeltaOrder];
            for (int m = 0; m < secondaryDeltaOrder; m++) {
                secondaryMoments[m] = pageReader.readBits(dtypeSize);
            }
            int[] secondaryStateIdxs = new int[PcoTansDecoder.ANS_INTERLEAVING];
            for (int i = 0; i < PcoTansDecoder.ANS_INTERLEAVING; i++) {
                secondaryStateIdxs[i] = (int) pageReader.readBits(secondaryAnsSizeLog);
            }

            pageReader.alignToByte();

            long[] batchLowersP = new long[PcoTansDecoder.BATCH_N];
            int[] batchOffsetBitsP = new int[PcoTansDecoder.BATCH_N];
            long[] batchLowersS = new long[PcoTansDecoder.BATCH_N];
            int[] batchOffsetBitsS = new int[PcoTansDecoder.BATCH_N];

            int nRemaining = pageN;
            long primaryPos = multsOffset;
            long secondaryPos = adjsOffset;

            while (nRemaining > 0) {
                int batchN = Math.min(nRemaining, PcoTansDecoder.BATCH_N);
                // pre_delta_len = min(batchN, max(0, nRemaining - deltaOrder))
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

        /// Inverse of pcodec {@code to_latent_ordered}: maps raw U-latent back to typed bits.
        ///
        /// <ul>
        ///   <li>Unsigned ints (U16/U32/U64): identity — already correct bit pattern.</li>
        ///   <li>Signed ints (I16/I32/I64): XOR with the type's MIN_VALUE (flip sign bit).</li>
        ///   <li>Floats: if sign bit of latent is set → was positive, XOR sign-bit mask;
        ///       else → was negative, bitwise NOT (= XOR all-ones mask).</li>
        /// </ul>
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
        /// <p>Pcodec encodes deltas with a sign-centering XOR (toggle_center = XOR dtype_mid),
        /// then removes it on decode.  After toggle_center, the cumulative sum restores the
        /// original U-latents.  The buffer beyond the {@code decodedN} positions is zero
        /// (arena-initialized); toggle_center(0) = dtype_mid, which is treated as junk and
        /// harmlessly overwritten during the reconstruction pass.
        private static void applyConsecutiveDelta(MemorySegment rawLatents, long offset,
                int pageN, long[] moments, int dtypeSize) {
            long mid = typeMid(dtypeSize);
            long mask = typeMask(dtypeSize);

            // toggle_center on all pageN slots (decodedN real + junk zeros at end).
            for (int i = 0; i < pageN; i++) {
                long byteOff = offset + (long) i * Long.BYTES;
                rawLatents.set(LE_LONG, byteOff, (rawLatents.get(LE_LONG, byteOff) ^ mid) & mask);
            }

            // Apply first-order cumulative-sum for each delta moment, last moment first.
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
                case 64 -> Long.MIN_VALUE;   // 1L << 63
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

        private static PcoChunkMeta readChunkMeta(MemorySegment buf, int dtypeSize) {
            LeBitReader r = new LeBitReader(buf);

            int modeNibble = (int) r.readBits(4);
            long base = 0L;
            if (modeNibble == 1) {
                // IntMult: read base value (dtypeSize bits, uncompressed).
                base = r.readBits(dtypeSize);
            } else if (modeNibble != 0) {
                throw new VortexException(EncodingId.VORTEX_PCO,
                        "pco mode " + modeNibble + " not yet implemented (Classic=0, IntMult=1 supported)");
            }

            // delta_encoding_variant: 4 bits. 0=NoOp, 1=Consecutive (3b order + 1b secondary_uses_delta).
            int deltaVariant = (int) r.readBits(4);
            int deltaOrder = 0;
            boolean secondaryUsesDelta = false;
            if (deltaVariant == 0) {
                // NoOp — no extra bits.
            } else if (deltaVariant == 1) {
                deltaOrder = (int) r.readBits(3); // BITS_TO_ENCODE_DELTA_ENCODING_ORDER
                secondaryUsesDelta = r.readBits(1) != 0;
            } else {
                throw new VortexException(EncodingId.VORTEX_PCO,
                        "pco delta variant " + deltaVariant + " not yet implemented");
            }

            // Primary latent variable.
            int ansSizeLog = (int) r.readBits(4);
            int nBins = (int) r.readBits(15);
            PcoBin[] bins = readBins(r, nBins, ansSizeLog, dtypeSize);

            // Secondary latent variable (IntMult only).
            int secondaryAnsSizeLog = 0;
            PcoBin[] secondaryBins = new PcoBin[0];
            if (modeNibble == 1) {
                secondaryAnsSizeLog = (int) r.readBits(4);
                int nSecondaryBins = (int) r.readBits(15);
                secondaryBins = readBins(r, nSecondaryBins, secondaryAnsSizeLog, dtypeSize);
            }
            r.alignToByte();

            return new PcoChunkMeta(modeNibble, base, ansSizeLog, deltaOrder, bins,
                    secondaryUsesDelta, secondaryAnsSizeLog, secondaryBins);
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

    private record PcoChunkMeta(int mode, long base,
                                 int ansSizeLog, int deltaOrder, PcoBin[] bins,
                                 boolean secondaryUsesDelta, int secondaryAnsSizeLog, PcoBin[] secondaryBins) {
    }
}
