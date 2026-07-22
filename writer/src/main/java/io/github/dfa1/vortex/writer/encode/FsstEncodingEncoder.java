package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.io.PTypeIO;
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.proto.ProtoFSSTMetadata;
import io.github.dfa1.vortex.fsst.Compressor;
import io.github.dfa1.vortex.fsst.CompressorBuilder;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.List;

/// Write-only encoder for `vortex.fsst`.
///
/// This class is a thin wire adapter over the standalone `vortex-fsst` module (issue #287): the
/// FSST compression algorithm — symbol-table training and greedy longest-match compression — lives
/// entirely in [CompressorBuilder]/[Compressor]. This adapter converts the input strings to UTF-8
/// bytes, drives training, compresses each row, and lays the result out in the `vortex.fsst` wire
/// format (symbol table buffers, remapped code stream, per-row uncompressed lengths and code
/// offsets, plus the [ProtoFSSTMetadata] describing the two offset ptypes).
///
/// The wire format packs each symbol's bytes LSB-first into a `long` (first byte in the low byte)
/// alongside a per-symbol length byte, and reserves code `0xFF` as the single-literal-byte escape.
/// Real symbol codes are therefore `0..254` (max 255 symbols), each 1-8 bytes long.
///
/// Symbols are laid out on the wire in length order — all multi-byte symbols (length 2-8) first in
/// non-decreasing length order, then all length-1 symbols — as the `vortex.fsst` wire contract
/// requires (mirroring Rust `FSSTData::validate_symbol_lengths`). The [Compressor] instead numbers
/// its codes in gain-descending order, so this adapter remaps every code the compressor emits into
/// that length-sorted wire order (see [#encode]).
public final class FsstEncodingEncoder implements EncodingEncoder {

    /// Escape opcode: emitted as `0xFF` followed by one literal byte.
    private static final int ESCAPE = 0xFF;

    /// Fixed seed for the training-sample PRNG. Encoding must be reproducible: the same input
    /// always trains the same symbol table and produces byte-identical output.
    private static final long TRAINING_SAMPLE_SEED = 0x5EEDL;

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
        long totalInput = 0;
        int maxUncompLen = 0;
        for (int i = 0; i < n; i++) {
            byteArrays[i] = strings[i].getBytes(StandardCharsets.UTF_8);
            totalInput += byteArrays[i].length;
            maxUncompLen = Math.max(maxUncompLen, byteArrays[i].length);
        }

        Compressor compressor = new CompressorBuilder().seed(TRAINING_SAMPLE_SEED).train(byteArrays);
        int numSymbols = compressor.symbolCount();

        // Wire order: the wire lists symbols in length order (multi-byte length-ascending, then
        // length-1 last), whereas the compressor numbers its codes gain-descending. wireOrder[i] is
        // the compressor's (internal) code that belongs at wire position i; internalToWire is its
        // inverse, mapping every internal code emitted by compress() to its wire code. Both the
        // symbol-table buffers and the code stream must be expressed in wire codes so the file is
        // self-consistent.
        int[] wireOrder = compressor.codesSortedByLength();
        int[] internalToWire = new int[numSymbols];
        for (int i = 0; i < numSymbols; i++) {
            internalToWire[wireOrder[i]] = i;
        }

        Arena arena = ctx.arena();

        MemorySegment symBuf = arena.allocate(Math.max(numSymbols * 8L, 1), 8);
        MemorySegment symLenBuf = arena.allocate(Math.max(numSymbols, 1));
        for (int i = 0; i < numSymbols; i++) {
            int internalCode = wireOrder[i];
            symBuf.setAtIndex(VortexFormat.LE_LONG, i, compressor.packedSymbol(internalCode));
            symLenBuf.set(ValueLayout.JAVA_BYTE, i, (byte) compressor.symbolLength(internalCode));
        }

        // Compress every row back-to-back into one shared scratch buffer (a per-row scratch plus a
        // per-row exact-size copy costs two heap allocations and an extra copy per row — millions
        // per chunk), then remap the code bytes (never the literal byte following an escape) to
        // wire codes in a single pass over the whole stream. Worst case each input byte escapes to
        // 2 output bytes, so 2 * totalInput bounds the entire stream.
        byte[] scratch = new byte[Math.toIntExact(2 * totalInput)];
        int[] rowEnds = new int[n];
        int totalCompressed = 0;
        for (int i = 0; i < n; i++) {
            byte[] row = byteArrays[i];
            totalCompressed = (int) compressor.compress(row, 0, row.length, scratch, totalCompressed);
            rowEnds[i] = totalCompressed;
        }
        remapCodesToWire(scratch, totalCompressed, internalToWire);

        MemorySegment compBuf = arena.allocate(Math.max(totalCompressed, 1));
        MemorySegment.copy(scratch, 0, compBuf, ValueLayout.JAVA_BYTE, 0, totalCompressed);

        // Narrowest ptype that fits every value: row lengths and cumulative offsets are
        // typically far below the 4-byte ceiling this always used to pay (e.g. a 6-byte string
        // column needs only U8 lengths, not I32), and the wire format carries the chosen ptype
        // per FSSTMetadata specifically so a reader never has to guess.
        PType uncompLenPType = PType.narrowestUnsigned(maxUncompLen);
        PType codesOffPType = PType.narrowestUnsigned(totalCompressed);

        long uncompLenBytes = uncompLenPType.byteSize();
        MemorySegment uncompLenBuf = arena.allocate(Math.max((long) n * uncompLenBytes, 1));
        for (int i = 0; i < n; i++) {
            PTypeIO.set(uncompLenBuf, i * uncompLenBytes, uncompLenPType, byteArrays[i].length);
        }

        long codesOffBytes = codesOffPType.byteSize();
        MemorySegment codesOffBuf = arena.allocate((long) (n + 1) * codesOffBytes);
        PTypeIO.set(codesOffBuf, 0, codesOffPType, 0);
        for (int i = 0; i < n; i++) {
            PTypeIO.set(codesOffBuf, (i + 1) * codesOffBytes, codesOffPType, rowEnds[i]);
        }

        byte[] metaBytes = new ProtoFSSTMetadata(
                io.github.dfa1.vortex.core.proto.ProtoPType.fromValue(uncompLenPType.ordinal()),
                io.github.dfa1.vortex.core.proto.ProtoPType.fromValue(codesOffPType.ordinal())
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

    /// Remaps every code byte in `stream[0..length)` from the compressor's internal (gain-descending)
    /// code to its wire (length-sorted) code, in place. The escape byte `0xFF` and the single literal
    /// byte that follows it are copied through unchanged — the literal is raw data, not a code.
    ///
    /// @param stream the freshly compressed code stream, mutated in place
    /// @param length the number of valid bytes in `stream`
    /// @param internalToWire maps an internal code to its wire code, indexed by internal code
    private static void remapCodesToWire(byte[] stream, int length, int[] internalToWire) {
        int j = 0;
        while (j < length) {
            int code = stream[j] & 0xFF;
            if (code == ESCAPE) {
                j += 2;
            } else {
                stream[j] = (byte) internalToWire[code];
                j++;
            }
        }
    }
}
