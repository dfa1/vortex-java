package io.github.dfa1.vortex.encoding;

import com.google.protobuf.InvalidProtocolBufferException;
import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.LongArray;
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
///   <li>Page: [4 × ans_size_log b initial states][0–7b alignment]
///       [per 256-batch: ANS bits for all k, then offset bits for all k]</li>
///   <li>All bit packing little-endian (LSB first)</li>
/// </ul>
///
/// <p>Supported (Phase 2): Classic mode, None delta, non-null, I64.
/// Other modes/deltas/ptypes throw with a clear "not yet implemented" message.
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
            if (ptype != PType.I64) {
                throw new VortexException(EncodingId.VORTEX_PCO,
                        "pco decode Phase 2: only I64 supported, got: " + ptype);
            }

            long n = ctx.rowCount();
            MemorySegment out = ctx.arena().allocate(n * Long.BYTES);

            int nChunks = meta.getChunksCount();
            int bufIdx = 0;
            long outByteOffset = 0L;

            for (int c = 0; c < nChunks; c++) {
                EncodingProtos.PcoChunkInfo chunkInfo = meta.getChunks(c);
                MemorySegment chunkMetaBuf = ctx.buffer(bufIdx++);

                PcoChunkMeta chunkMeta = readChunkMeta(chunkMetaBuf);
                PcoTansDecoder tans = PcoTansDecoder.build(chunkMeta.ansSizeLog(), chunkMeta.bins());

                int nPages = chunkInfo.getPagesCount();
                for (int p = 0; p < nPages; p++) {
                    int pageN = chunkInfo.getPages(p).getNValues();
                    MemorySegment pageBuf = ctx.buffer(bufIdx++);

                    LeBitReader pageReader = new LeBitReader(pageBuf);
                    int[] stateIdxs = new int[PcoTansDecoder.ANS_INTERLEAVING];
                    for (int i = 0; i < PcoTansDecoder.ANS_INTERLEAVING; i++) {
                        stateIdxs[i] = (int) pageReader.readBits(chunkMeta.ansSizeLog());
                    }
                    pageReader.alignToByte();

                    tans.decodePage(pageReader, stateIdxs, pageN, out, outByteOffset);
                    outByteOffset += (long) pageN * Long.BYTES;
                }
            }

            // Convert U64 latents → I64: flip sign bit (from_latent_ordered for signed types)
            for (long i = 0; i < n; i++) {
                long byteOff = i * Long.BYTES;
                out.set(LE_LONG, byteOff, out.get(LE_LONG, byteOff) ^ Long.MIN_VALUE);
            }

            return new LongArray(dtype, n, out, ArrayStats.empty());
        }

        private static PcoChunkMeta readChunkMeta(MemorySegment buf) {
            LeBitReader r = new LeBitReader(buf);

            int modeNibble = (int) r.readBits(4);
            if (modeNibble != 0) {
                throw new VortexException(EncodingId.VORTEX_PCO,
                        "pco mode " + modeNibble + " not yet implemented (only Classic=0)");
            }
            int deltaNibble = (int) r.readBits(4);
            if (deltaNibble != 0) {
                throw new VortexException(EncodingId.VORTEX_PCO,
                        "pco delta " + deltaNibble + " not yet implemented (only None=0)");
            }

            // One primary latent variable for Classic + None delta.
            int ansSizeLog = (int) r.readBits(4);
            int nBins = (int) r.readBits(15);

            PcoBin[] bins = new PcoBin[nBins];
            for (int b = 0; b < nBins; b++) {
                int weight = (int) r.readBits(ansSizeLog) + 1;
                long lower = r.readBits(64);  // dtype_size = 64 for I64/U64
                int offsetBits = (int) r.readBits(BITS_TO_ENCODE_OFFSET_BITS_64);
                bins[b] = new PcoBin(weight, lower, offsetBits);
            }
            r.alignToByte(); // drain padding at end of chunk meta

            return new PcoChunkMeta(ansSizeLog, bins);
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

    private record PcoChunkMeta(int ansSizeLog, PcoBin[] bins) {
    }
}
