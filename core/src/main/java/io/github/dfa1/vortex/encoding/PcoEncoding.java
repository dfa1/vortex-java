package io.github.dfa1.vortex.encoding;

import com.google.protobuf.InvalidProtocolBufferException;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.proto.EncodingProtos;

import java.nio.ByteBuffer;

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
///   <li>Chunk meta: mode nibble + extra mode bits + delta nibble + extra delta bits +
///       per-latent: ans_size_log (4b), bin_count (15b), per-bin {weight-1, lower, offset_bits}</li>
///   <li>Page: initial latent state (delta state_n + 4 tANS state indices) → byte align →
///       per 256-batch: tANS-decoded bin indices + offset bits</li>
///   <li>All bit packing little-endian (LSB first)</li>
/// </ul>
///
/// <p>Phase 1: skeleton only — parses metadata, validates header, dispatches on PType.
/// Phase 2 adds Classic/None decode for I64; later phases extend to all ptypes and modes.
public final class PcoEncoding implements Encoding {

    static final byte PCO_FORMAT_MAJOR = 0x04;
    static final byte PCO_FORMAT_MINOR = 0x01;

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

        static Array decode(DecodeContext ctx) {
            EncodingProtos.PcoMetadata meta = parseMeta(ctx);
            validateHeader(meta);
            throw new VortexException(EncodingId.VORTEX_PCO,
                    "pco decode not yet implemented — Phase 2 pending (chunks="
                            + meta.getChunksCount() + ")");
        }

        private static EncodingProtos.PcoMetadata parseMeta(DecodeContext ctx) {
            ByteBuffer raw = ctx.metadata();
            if (raw == null) {
                throw new VortexException(EncodingId.VORTEX_PCO, "missing PcoMetadata");
            }
            try {
                return EncodingProtos.PcoMetadata.parseFrom(raw.duplicate());
            } catch (InvalidProtocolBufferException e) {
                throw new VortexException(EncodingId.VORTEX_PCO, "invalid PcoMetadata: " + e.getMessage());
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
}
